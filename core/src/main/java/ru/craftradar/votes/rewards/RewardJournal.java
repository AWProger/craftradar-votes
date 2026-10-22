package ru.craftradar.votes.rewards;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Владелец: A13. Журнал rewards.log в папке плагина (фиксированное имя, §4).
 * Строка: {@code <ISO-8601 UTC>\t<СТАТУС>\t<ключ события>\t<ник>\t<детали>}; пустое поле = "-",
 * табы и переводы строк внутри полей заменяются пробелом, так что строка всегда одна и делится по \t.
 * Ротация по размеру: rewards.log -> rewards.log.1 (старый .1 затирается). Ошибка записи журнал не роняет:
 * голос важнее лога, причина доступна в {@link #lastError()}. Секреты (token/secret/password/key=...) вырезаются.
 */
public final class RewardJournal implements Journal {
    public static final String FILE_NAME = "rewards.log";
    public static final long DEFAULT_MAX_BYTES = 1_048_576L;
    private static final int MAX_FIELD = 512;
    private static final int MAX_UNCERTAIN_KEYS = 10_000;
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(token|secret|password|passwd|api[-_]?key|private[-_]?key|key)(\\s*[=:]\\s*)\\S+");

    private final Path file;
    private final Path rotated;
    private final Clock clock;
    private final boolean enabled;
    private final long maxBytes;
    private final Map<RewardStatus, Long> counts = new EnumMap<>(RewardStatus.class);
    /** Ключи с ACTION_UNCERTAIN: по ним GRANTED недопустим (§4). ponytail: потолок 10k, дальше сброс. */
    private final Set<String> uncertainKeys = new HashSet<>();
    private String lastError;

    public RewardJournal(Path dataDir, Clock clock, boolean enabled) { this(dataDir, clock, enabled, DEFAULT_MAX_BYTES); }

    RewardJournal(Path dataDir, Clock clock, boolean enabled, long maxBytes) {
        this.file = dataDir.resolve(FILE_NAME);
        this.rotated = dataDir.resolve(FILE_NAME + ".1");
        this.clock = clock;
        this.enabled = enabled;
        this.maxBytes = maxBytes;
    }

    /** @throws IllegalStateException GRANTED по событию, у которого есть ACTION_UNCERTAIN (баг движка, строка не пишется). */
    @Override public synchronized void log(RewardStatus status, String eventKey, String player, String detail) {
        if (status == null) throw new IllegalArgumentException("статус не задан");
        if (status == RewardStatus.ACTION_UNCERTAIN && eventKey != null) {
            if (uncertainKeys.size() >= MAX_UNCERTAIN_KEYS) uncertainKeys.clear();
            uncertainKeys.add(eventKey);
        } else if (status == RewardStatus.GRANTED && eventKey != null && uncertainKeys.contains(eventKey)) {
            throw new IllegalStateException("GRANTED недопустим: у события " + eventKey + " есть ACTION_UNCERTAIN");
        }
        counts.merge(status, 1L, Long::sum);
        if (!enabled) return;
        String line = Instant.ofEpochMilli(clock.nowMs()) + "\t" + status + "\t" + field(eventKey) + "\t"
                + field(player) + "\t" + field(detail) + "\n";
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) + line.length() > maxBytes) {
                Files.move(file, rotated, StandardCopyOption.REPLACE_EXISTING);
            }
            ru.craftradar.votes.J8.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            lastError = null;
        } catch (IOException | RuntimeException e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** Счётчики по статусам с момента запуска (для /crvotes rewards status; все статусы присутствуют, 0 если не было). */
    public synchronized Map<RewardStatus, Long> counts() {
        Map<RewardStatus, Long> m = new EnumMap<>(RewardStatus.class);
        for (RewardStatus s : RewardStatus.values()) m.put(s, counts.getOrDefault(s, 0L));
        return m;
    }

    /** Последняя ошибка записи в rewards.log или null, если последняя запись удалась. */
    public synchronized String lastError() { return lastError; }

    private static String field(String s) {
        if (s == null || s.isEmpty()) return "-";
        String r = SECRET.matcher(s).replaceAll("$1$2***").replaceAll("[\\t\\r\\n]+", " ");
        return r.length() > MAX_FIELD ? r.substring(0, MAX_FIELD) + "…" : r;
    }
}
