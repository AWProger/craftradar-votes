package ru.craftradar.votes.rewards;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;

import static java.nio.file.StandardOpenOption.*;

/**
 * Владелец: A11. Файл фиксированного имени rewards-state.json в dataDir (никаких путей извне);
 * запись атомарная: tmp + fsync + rename. Битый файл не сбрасывается молча: он переименовывается в
 * rewards-state.json.corrupt (бэкап), а load бросает IOException — решение (старт с пустым состоянием)
 * за вызывающим; следующий load уже вернёт пустое состояние.
 */
public final class JsonStateStore implements StateStore {
    public static final String FILE = "rewards-state.json";
    private static final Gson GSON = new Gson();

    private final Path file, tmp, corrupt;
    private final Object lock = new Object();

    public JsonStateStore(Path dataDir) {
        file = dataDir.resolve(FILE);
        tmp = dataDir.resolve(FILE + ".tmp");
        corrupt = dataDir.resolve(FILE + ".corrupt");
    }

    @Override public RewardsState load() throws IOException {
        synchronized (lock) {
            if (!Files.exists(file)) return new RewardsState();
            RewardsState s;
            try {
                s = GSON.fromJson(ru.craftradar.votes.J8.readString(file), RewardsState.class);
                if (s == null) throw new JsonParseException("пустой файл");
                normalize(s);
            } catch (JsonParseException | IllegalStateException | java.nio.charset.CharacterCodingException e) {
                Files.move(file, corrupt, StandardCopyOption.REPLACE_EXISTING);
                throw new IOException(FILE + " повреждён, сохранён как " + FILE + ".corrupt: " + e.getMessage(), e);
            }
            return s;
        }
    }

    @Override public void save(RewardsState state) throws IOException {
        synchronized (lock) {
            byte[] data = GSON.toJson(state).getBytes(StandardCharsets.UTF_8);
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (FileChannel ch = FileChannel.open(tmp, CREATE, TRUNCATE_EXISTING, WRITE)) {
                ByteBuffer b = ByteBuffer.wrap(data);
                while (b.hasRemaining()) ch.write(b);
                ch.force(true);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Gson оставляет null для отсутствующих полей и свои типы карт: приводим к ожидаемым. */
    private static void normalize(RewardsState s) {
        s.processed = s.processed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(s.processed);
        s.streaks = s.streaks == null ? new LinkedHashMap<>() : new LinkedHashMap<>(s.streaks);
        s.daily = s.daily == null ? new LinkedHashMap<>() : new LinkedHashMap<>(s.daily);
        s.queue = s.queue == null ? new LinkedHashMap<>() : new LinkedHashMap<>(s.queue);
        s.actions = s.actions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(s.actions);
    }
}
