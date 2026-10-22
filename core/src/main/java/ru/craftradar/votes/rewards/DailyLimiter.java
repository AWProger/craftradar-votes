package ru.craftradar.votes.rewards;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Владелец: A12. Суточный лимит наград по паре сервис+игрок (§3). Счётчик живёт в RewardsState.daily
 * ("service|player" в нижнем регистре -> день ISO + число), поэтому переживает рестарт и не обходится
 * сменой регистра ника/сервиса. Сутки — календарные в timezone (по LocalDate, переход на летнее время не ломает границу).
 */
public final class DailyLimiter {
    private final RewardsState state;
    private final Clock clock;
    private final ZoneId zone;
    private final int limit;

    public DailyLimiter(RewardsState state, Clock clock, ZoneId zone, int limit) {
        this.state = state;
        this.clock = clock;
        this.zone = zone;
        this.limit = limit;
    }

    /** true — награда в пределах лимита, счётчик увеличен; false — LIMIT_REACHED. limit 0 = без лимита (всегда true). */
    public boolean tryAcquire(String service, String player) {
        if (limit <= 0) return true;
        String key = service.toLowerCase(Locale.ROOT) + "|" + player.toLowerCase(Locale.ROOT);
        String today = Instant.ofEpochMilli(clock.nowMs()).atZone(zone).toLocalDate().toString();
        RewardsState.DailyCounter c = state.daily.get(key);
        int count = 0;
        if (c != null && c.day() != null) {
            // Новый день — сброс. День в записи позже текущего (часы сдвинули назад) — счёт сохраняем, не даём обойти лимит.
            if (c.day().equals(today) || laterThan(c.day(), today)) count = c.count();
        }
        if (count >= limit) return false;
        String day = c != null && c.day() != null && laterThan(c.day(), today) ? c.day() : today;
        state.daily.put(key, new RewardsState.DailyCounter(day, count + 1));
        return true;
    }

    private static boolean laterThan(String stored, String today) {
        try {
            return LocalDate.parse(stored).isAfter(LocalDate.parse(today));
        } catch (RuntimeException e) {
            return false; // повреждённая запись: считаем устаревшей
        }
    }
}
