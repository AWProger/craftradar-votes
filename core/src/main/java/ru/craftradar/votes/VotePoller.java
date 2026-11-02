package ru.craftradar.votes;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Поток C (PROTOCOL.md §10): плагин сам забирает голоса CraftRadar по HTTPS и подтверждает обработанные id.
 * At-least-once: id подтверждается только после того, как {@link Sink} принял голос; повтор того же id (потерянный ack)
 * отсекается дедупом по id (pull-state.json, хранится 8 дней). Не зависит от Bukkit.
 */
public final class VotePoller {
    /** Голос из ответа сайта. timestamp — Unix мс строкой. */
    public static final class PulledVote {
        public final String id, service, username, address, timestamp;
        PulledVote(String id, String service, String username, String address, String timestamp) {
            this.id = id; this.service = service; this.username = username; this.address = address; this.timestamp = timestamp;
        }
        public long timestampMs() {
            try { long t = Long.parseLong(timestamp.trim()); return t < 100_000_000_000L ? t * 1000 : t; } catch (Exception e) { return 0; }
        }
    }
    /** true — голос надёжно передан обработчику (событие + награды), можно подтверждать. false/исключение — повторить позже. */
    public interface Sink { boolean accept(PulledVote v) throws Exception; }
    /** Проверка «Проверить подключение» из кабинета: ответить diagnostic_ack (§5). */
    public interface DiagSink { void diagnostic(String id); }

    static final Pattern ID = Pattern.compile("^[0-9a-f]{16}$");
    static final String STATE_FILE = "pull-state.json";
    static final long SEEN_TTL_MS = 8L * 24 * 3600_000;
    static final int MAX_SEEN = 20_000, MAX_ACK = 100, LIMIT = 50;
    static final long WARN_EVERY_MS = 10 * 60_000;

    private final Settings cfg;
    private final Path stateFile;
    private final Logger log;
    private final LongSupplier clock;
    private final Sink sink;
    private final DiagSink diag;
    private final Random rnd = new Random();
    private final Map<String, Long> seen = new LinkedHashMap<>();       // id -> когда обработан
    private final Set<String> pendingAck = new LinkedHashSet<>();        // обработаны, сайт ещё не подтвердил
    private final Set<String> diagSeen = new LinkedHashSet<>();
    private final Map<String, Long> warned = new LinkedHashMap<>();
    private volatile ScheduledExecutorService sched;
    private int failures;
    private volatile String mode = null, lastError = null;
    private volatile long lastOkAt = 0, received = 0;

    public VotePoller(Settings cfg, Path dataDir, Logger log, LongSupplier clock, Sink sink, DiagSink diag) {
        this.cfg = cfg; this.stateFile = dataDir.resolve(STATE_FILE); this.log = log; this.clock = clock; this.sink = sink; this.diag = diag;
        load();
    }

    public void start() {
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "CraftRadarVotes-pull"); t.setDaemon(true); return t; });
        sched = s;
        s.schedule(this::tick, 1, TimeUnit.SECONDS);
    }

    /** Останавливает опрос (ждёт до 5 с). Необработанное остаётся на сайте, обработанное — в pull-state.json для ack при следующем старте. */
    public void close() {
        ScheduledExecutorService s = sched;
        sched = null;
        if (s == null) return;
        s.shutdownNow();
        try { s.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** /crvotes flush: опросить сейчас. */
    public void pollSoon() {
        ScheduledExecutorService s = sched;
        if (s != null) try { s.execute(() -> { failures = 0; }); } catch (RejectedExecutionException ignored) { }
    }

    private void tick() {
        long d;
        try { d = pollOnce(); } catch (Throwable t) { lastError = "внутренняя ошибка: " + t; d = Backoff.delayMs(++failures, rnd); }
        ScheduledExecutorService s = sched;
        if (s != null) try { s.schedule(this::tick, d, TimeUnit.MILLISECONDS); } catch (RejectedExecutionException ignored) { }
    }

    /** Один запрос: подтвердить обработанное, забрать новое. Возвращает паузу до следующего опроса, мс. */
    public synchronized long pollOnce() {
        List<String> ack = new ArrayList<>();
        for (String id : pendingAck) { if (ack.size() >= MAX_ACK) break; ack.add(id); }
        JsonObject req = new JsonObject();
        JsonArray a = new JsonArray();
        for (String id : ack) a.add(id);
        req.add("ack", a);
        req.addProperty("limit", LIMIT);
        byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
        int code; String resp;
        HttpURLConnection c = null;
        try {
            long ts = clock.getAsLong() / 1000;
            String sig = Signer.sign(cfg.secret(), ts, cfg.keyId(), body);
            c = (HttpURLConnection) cfg.pullEndpoint().toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(VoteForwarder.CONNECT_TIMEOUT_MS);
            c.setReadTimeout(VoteForwarder.READ_TIMEOUT_MS);
            c.setUseCaches(false);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-CR-Key", cfg.keyId());
            c.setRequestProperty("X-CR-Timestamp", Long.toString(ts));
            c.setRequestProperty("X-CR-Signature", sig);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            code = c.getResponseCode();
            resp = VoteForwarder.readBody(code >= 400 ? c.getErrorStream() : c.getInputStream());
        } catch (IOException e) {
            return fail("нет связи с CraftRadar: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage()), false);
        } finally {
            if (c != null) c.disconnect();
        }
        return handle(code, resp, ack);
    }

    long handle(int code, String resp, List<String> sentAck) {
        JsonObject j = null;
        try { JsonElement e = JsonParser.parseString(resp == null ? "" : resp); if (e.isJsonObject()) j = e.getAsJsonObject(); } catch (Exception ignored) { }
        if (code == 200 && j != null) return ok(j, sentAck);
        String st = j != null && j.has("status") && j.get("status").isJsonPrimitive() ? j.get("status").getAsString() : "";
        if (code == 429) {
            long ra = 1;
            try { ra = j.get("retry_after").getAsLong(); } catch (Exception ignored) { }
            lastError = "CraftRadar просит опрашивать реже (HTTP 429), пауза " + Math.max(1, ra) + " с";
            return Math.min(Backoff.CAP_MS, Math.max(1000, ra * 1000));
        }
        if (code == 401 || code == 403) return fail("ключ не принят (HTTP " + code + "): проверьте key_id и secret, включён ли приём от плагина в кабинете и время сервера (NTP)", true);
        if (code == 404) return fail("сервер не опубликован на CraftRadar или снят с каталога (HTTP 404)", true);
        if (code >= 300 && code < 400) return fail("редирект HTTP " + code + ": укажите в api_url точный адрес https://craftradar.ru", true);
        if (code == 400) { pendingAck.clear(); save(); return fail("сайт отклонил запрос (HTTP 400 " + st + ")", false); }
        if (code == 503 && "pull_not_configured".equals(st)) return fail("на CraftRadar ещё не включён режим «Наш плагин» (обновление сайта)", true);
        if (code == 503) return fail("приём голосов на CraftRadar временно выключен (" + st + ")", false);
        return fail("CraftRadar ответил HTTP " + code, false);
    }

    private long ok(JsonObject j, List<String> sentAck) {
        long now = clock.getAsLong();
        boolean dirty = false;
        for (String id : sentAck) dirty |= pendingAck.remove(id); // подтверждены (или сайт их уже не знает: TTL) — больше не шлём
        String m = str(j, "mode");
        if (m != null && !m.equals(mode)) {
            if ("votifier".equals(m)) log.info("CraftRadarVotes: в кабинете CraftRadar выбран способ «Без нашего плагина» (Votifier) — голоса CraftRadar придут через Votifier, а не опросом.");
            else if ("pull".equals(m)) log.info("CraftRadarVotes: способ «Наш плагин» — голоса CraftRadar забираются с сайта.");
        }
        mode = m;
        boolean stopped = false;
        if (j.has("votes") && j.get("votes").isJsonArray()) {
            for (JsonElement e : j.getAsJsonArray("votes")) {
                if (!e.isJsonObject()) continue;
                JsonObject v = e.getAsJsonObject();
                String id = str(v, "id");
                if (id == null || !ID.matcher(id).matches()) continue;
                if (seen.containsKey(id)) { dirty |= pendingAck.add(id); continue; } // уже обработан, ack потерялся
                PulledVote pv = new PulledVote(id, nz(str(v, "service_name"), "CraftRadar"), str(v, "username"), nz(str(v, "address"), ""), nz(str(v, "timestamp"), Long.toString(now)));
                boolean accepted;
                try { accepted = pv.username != null && sink.accept(pv); }
                catch (Exception ex) { accepted = false; lastError = "обработчик голоса: " + ex; }
                if (!accepted) { stopped = true; break; } // порядок сохраняем: остальное — следующим опросом
                seen.put(id, now);
                pendingAck.add(id);
                received++;
                dirty = true;
            }
        }
        if (j.has("diagnostics") && j.get("diagnostics").isJsonArray()) {
            for (JsonElement e : j.getAsJsonArray("diagnostics")) {
                String id = e.isJsonPrimitive() ? e.getAsString() : null;
                if (id == null || !ID.matcher(id).matches() || !diagSeen.add(id)) continue;
                log.info("Диагностика CraftRadar: получена проверка подключения (режим «Наш плагин»), награды не выдаются");
                try { diag.diagnostic(id); } catch (Exception ex) { lastError = "диагностика: " + ex; }
                while (diagSeen.size() > 100) { Iterator<String> it = diagSeen.iterator(); it.next(); it.remove(); }
            }
        }
        if (dirty) { purge(now); save(); }
        failures = 0;
        lastOkAt = now;
        if (!stopped) lastError = null;
        boolean more = j.has("more") && j.get("more").isJsonPrimitive() && j.get("more").getAsBoolean();
        if (stopped) return Backoff.delayMs(1, rnd);
        if (!pendingAck.isEmpty() || more) return 1000; // подтвердить сразу (и забрать остаток)
        long after = 0;
        try { after = j.get("poll_after").getAsLong(); } catch (Exception ignored) { }
        return Math.max(cfg.pullIntervalSec(), Math.min(300, after)) * 1000L;
    }

    private long fail(String why, boolean longBackoff) {
        failures++;
        long d = longBackoff ? Backoff.longDelayMs(failures, rnd) : Backoff.delayMs(failures, rnd);
        lastError = why + " (повтор через " + d / 1000 + " с)";
        long now = clock.getAsLong();
        Long w = warned.get(why);
        if (w == null || now - w >= WARN_EVERY_MS) { warned.put(why, now); log.warning("CraftRadarVotes: " + lastError); }
        return d;
    }

    private void purge(long now) {
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) { Map.Entry<String, Long> e = it.next(); if (now - e.getValue() > SEEN_TTL_MS || seen.size() > MAX_SEEN) it.remove(); }
    }

    private void load() {
        try {
            if (!Files.exists(stateFile)) return;
            JsonObject o = JsonParser.parseString(J8.readString(stateFile)).getAsJsonObject();
            if (o.has("seen")) for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("seen").entrySet()) if (ID.matcher(e.getKey()).matches()) seen.put(e.getKey(), e.getValue().getAsLong());
            if (o.has("ack")) for (JsonElement e : o.getAsJsonArray("ack")) if (ID.matcher(e.getAsString()).matches()) pendingAck.add(e.getAsString());
        } catch (Exception e) {
            log.warning("CraftRadarVotes: " + STATE_FILE + " повреждён (" + e.getClass().getSimpleName() + "), начинаю с пустого; повторные голоса отсечёт дедуп наград");
        }
    }

    private void save() {
        JsonObject o = new JsonObject();
        JsonObject s = new JsonObject();
        for (Map.Entry<String, Long> e : seen.entrySet()) s.addProperty(e.getKey(), e.getValue());
        o.add("seen", s);
        JsonArray a = new JsonArray();
        for (String id : pendingAck) a.add(id);
        o.add("ack", a);
        try {
            Files.createDirectories(stateFile.getParent());
            Path tmp = stateFile.resolveSibling(STATE_FILE + ".tmp");
            J8.writeString(tmp, new Gson().toJson(o));
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            lastError = "не удалось записать " + STATE_FILE + ": " + e.getMessage();
            log.severe("CraftRadarVotes: " + lastError);
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
    private static String nz(String s, String d) { return s == null ? d : s; }

    public String mode() { return mode; }
    public String lastError() { return lastError; }
    public long lastOkAt() { return lastOkAt; }
    public long received() { return received; }
    public synchronized int pendingAcks() { return pendingAck.size(); }
}
