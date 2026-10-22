package ru.craftradar.votes;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static java.nio.file.StandardOpenOption.*;

/**
 * Очередь: один JSON-файл на событие (queue/&lt;sha256&gt;.json). Имя файла = дедуп-ключ.
 * Метаданные (attempts, next, created) держатся в памяти и загружаются один раз при старте;
 * все файловые операции по-идентификаторные, глобального замка нет.
 * Файлы в rejected/ имеют тот же формат + reason/http/response, поэтому их можно вернуть (requeue).
 */
public final class DiskQueue {
    public static final int REJECTED_CAP = 1000;
    private static final Pattern ID = Pattern.compile("[0-9a-f]{64}");
    private static final Gson GSON = new Gson();

    public static final class Item {
        public final String id;
        public final String body;      // тело запроса, байты неизменны между попытками
        public int attempts;
        public long nextAttemptAt;     // epoch ms
        public final long created;     // epoch ms, для истечения срока
        Item(String id, String body, int attempts, long next, long created) {
            this.id = id; this.body = body; this.attempts = attempts; this.nextAttemptAt = next; this.created = created;
        }
    }

    private static final class Meta {
        final String id; final long created; volatile int attempts; volatile long next;
        Meta(String id, int attempts, long next, long created) { this.id = id; this.attempts = attempts; this.next = next; this.created = created; }
    }

    private final Path dir, rejected;
    private final int rejectedCap;
    private final Map<String, Meta> index = new ConcurrentHashMap<>();
    private final Object rejLock = new Object();

    public DiskQueue(Path root) throws IOException { this(root, REJECTED_CAP); }

    public DiskQueue(Path root, int rejectedCap) throws IOException {
        this.rejectedCap = rejectedCap;
        dir = root.resolve("queue");
        rejected = root.resolve("rejected");
        Files.createDirectories(dir);
        Files.createDirectories(rejected);
        for (Path d : Arrays.asList(dir, rejected)) {
            try (java.util.stream.Stream<Path> s = Files.list(d)) {
                for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".tmp"))::iterator) Files.deleteIfExists(p);
            }
        }
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".json"))::iterator) {
                try {
                    Item it = parse(J8.readString(p), Files.getLastModifiedTime(p).toMillis());
                    index.put(it.id, new Meta(it.id, it.attempts, it.nextAttemptAt, it.created));
                } catch (Exception bad) { quarantine(p); }
            }
        }
    }

    public static String key(String service, String username, String timestamp) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest((service + "\n" + username + "\n" + timestamp).getBytes(StandardCharsets.UTF_8));
            return J8.hex(h);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Размер из памяти, без диска. */
    public int size() { return index.size(); }

    /** @return false, если такое событие уже в очереди. Одна атомарная запись маленького файла, без общего замка. */
    public boolean add(String id, String body, long now) throws IOException {
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("bad id");
        // Пока файл пишется, запись в индексе «не созрела» (next = MAX): иначе параллельный опрос очереди прочитает несуществующий
        // файл, удалит запись из индекса, а файл останется сиротой до перезапуска (DiskQueueAddRaceTest).
        Meta m = new Meta(id, 0, Long.MAX_VALUE, now);
        if (index.putIfAbsent(id, m) != null) return false;
        try { write(dir, id, toJson(new Item(id, body, 0, 0, now)), true); }
        catch (IOException | RuntimeException e) { index.remove(id); throw e; }
        m.next = 0;
        return true;
    }

    /** Идентификаторы созревших элементов в порядке очереди (next, created). */
    public List<String> dueIds(long now) {
        List<Meta> l = new ArrayList<>();
        for (Meta m : index.values()) if (m.next <= now) l.add(m);
        l.sort(Comparator.<Meta>comparingLong(m -> m.next).thenComparingLong(m -> m.created));
        List<String> out = new ArrayList<>(l.size());
        for (Meta m : l) out.add(m.id);
        return out;
    }

    /** Читает элемент с диска; null, если файла нет или он повреждён (тогда уходит в rejected/*.corrupt). */
    public Item read(String id) throws IOException {
        Path p = dir.resolve(id + ".json");
        try {
            return parse(J8.readString(p), Files.getLastModifiedTime(p).toMillis());
        } catch (NoSuchFileException e) {
            index.remove(id); return null;
        } catch (RuntimeException bad) {
            index.remove(id); quarantine(p); return null;
        }
    }

    public void update(Item it) throws IOException {
        Meta m = index.get(it.id);
        if (m != null) { m.attempts = it.attempts; m.next = it.nextAttemptAt; } // память первой: даже при сбое диска не крутимся
        write(dir, it.id, toJson(it), false); // без fsync: потеря счётчика попыток при сбое питания безвредна
    }

    public void postpone(String id, long next) { Meta m = index.get(id); if (m != null) m.next = next; }

    public void resetNext() { for (Meta m : index.values()) m.next = 0; }

    public void delete(String id) throws IOException { index.remove(id); Files.deleteIfExists(dir.resolve(id + ".json")); }

    /** Диагностика: читает все файлы (не для горячего пути). */
    public List<Item> list() {
        List<Item> out = new ArrayList<>();
        for (String id : dueIds(Long.MAX_VALUE)) {
            try { Item it = read(id); if (it != null) out.add(it); } catch (IOException ignored) {}
        }
        return out;
    }

    /** Переносит в rejected/ в том же формате + reason/http/response (секрета в файле нет). */
    public void reject(Item it, int http, String response, String reason) throws IOException {
        JsonObject o = toJson(it);
        o.addProperty("reason", reason); o.addProperty("http", http);
        o.addProperty("response", response == null ? "" : response);
        o.addProperty("rejected_at", System.currentTimeMillis());
        write(rejected, it.id, o, true);
        delete(it.id);
        capRejected();
    }

    /** Возвращает из rejected/ в очередь с обнулёнными попытками. which = "all" или id. */
    public int requeue(String which, long now) throws IOException {
        List<Path> files = new ArrayList<>();
        if (which == null || which.equalsIgnoreCase("all")) {
            try (java.util.stream.Stream<Path> s = Files.list(rejected)) { s.filter(x -> x.toString().endsWith(".json")).forEach(files::add); }
        } else if (ID.matcher(which).matches() && Files.exists(rejected.resolve(which + ".json"))) {
            files.add(rejected.resolve(which + ".json"));
        }
        int n = 0;
        for (Path p : files) {
            String id = p.getFileName().toString().replaceFirst("\\.json$", "");
            if (!ID.matcher(id).matches()) continue;
            try {
                String body = JsonParser.parseString(J8.readString(p)).getAsJsonObject().get("body").getAsString();
                add(id, body, now); // уже в очереди — просто убираем копию из rejected
                Files.deleteIfExists(p);
                n++;
            } catch (RuntimeException bad) { /* нечитаемый файл остаётся на месте */ }
        }
        return n;
    }

    /** Все файлы в rejected/ (включая *.corrupt). */
    public int rejectedCount() {
        try (java.util.stream.Stream<Path> s = Files.list(rejected)) { return (int) s.count(); } catch (IOException e) { return 0; }
    }

    private void capRejected() {
        synchronized (rejLock) {
            try (java.util.stream.Stream<Path> s = Files.list(rejected)) {
                List<Path> all = s.collect(java.util.stream.Collectors.toList());
                if (all.size() <= rejectedCap) return;
                final Map<Path, Long> mtime = new HashMap<>();
                List<Path> fs = new ArrayList<>();
                for (Path p : all) { try { mtime.put(p, Files.getLastModifiedTime(p).toMillis()); fs.add(p); } catch (IOException ignored) {} }
                fs.sort(Comparator.comparingLong(mtime::get));
                for (int i = 0; i < fs.size() - rejectedCap; i++) Files.deleteIfExists(fs.get(i));
            } catch (IOException ignored) {}
        }
    }

    private void quarantine(Path p) {
        try { Files.move(p, rejected.resolve(p.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
    }

    private static Item parse(String json, long fallbackCreated) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String id = o.get("id").getAsString();
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("bad id");
        return new Item(id, o.get("body").getAsString(),
                o.has("attempts") ? o.get("attempts").getAsInt() : 0,
                o.has("next") ? o.get("next").getAsLong() : 0,
                o.has("created") ? o.get("created").getAsLong() : fallbackCreated);
    }

    private static JsonObject toJson(Item it) {
        JsonObject o = new JsonObject();
        o.addProperty("id", it.id); o.addProperty("body", it.body);
        o.addProperty("attempts", it.attempts); o.addProperty("next", it.nextAttemptAt);
        o.addProperty("created", it.created);
        return o;
    }

    /** tmp + (fsync для новых событий) + atomic rename. */
    private static void write(Path d, String id, JsonObject o, boolean sync) throws IOException {
        Path f = d.resolve(id + ".json"), tmp = d.resolve(id + ".tmp");
        ByteBuffer bb = ByteBuffer.wrap(GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
        try (FileChannel ch = FileChannel.open(tmp, CREATE, TRUNCATE_EXISTING, WRITE)) {
            while (bb.hasRemaining()) ch.write(bb);
            if (sync) ch.force(true);
        }
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
