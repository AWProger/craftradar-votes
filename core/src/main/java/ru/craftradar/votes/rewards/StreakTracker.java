package ru.craftradar.votes.rewards;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Владелец: A9. Серии по календарным дням в timezone (§3). Состояние живёт в RewardsState.streaks
 * (lastDay ISO + streak), сериализует его JsonStateStore, поэтому серия переживает рестарт.
 * Считаем по LocalDate, а не по 24-часовым интервалам: переход на летнее время (23/25-часовые сутки) не ломает серию.
 */
public final class StreakTracker {
    private StreakTracker() {}

    /** Учесть голос: +1 за следующий день, тот же день без изменения, пропуск >=2 дней = 1. Пишет в state.streaks; возвращает текущую серию. */
    public static int record(RewardsState state, String player, long nowMs, ZoneId zone) {
        String key = player.toLowerCase(Locale.ROOT);
        LocalDate today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        RewardsState.StreakEntry prev = state.streaks.get(key);
        int streak = 1;
        if (prev != null && prev.streak() >= 1) {
            try {
                long gap = ChronoUnit.DAYS.between(LocalDate.parse(prev.lastDay()), today);
                // gap <= 0: тот же день или часы сдвинули назад — серию и день не трогаем
                if (gap <= 0) return prev.streak();
                if (gap == 1) streak = prev.streak() == Integer.MAX_VALUE ? prev.streak() : prev.streak() + 1;
            } catch (DateTimeParseException | NullPointerException e) {
                // повреждённая запись состояния: начинаем серию заново
            }
        }
        state.streaks.put(key, new RewardsState.StreakEntry(today.toString(), streak));
        return streak;
    }

    /** Сработавшие милестоуны: days==streak или (repeat && streak % days == 0). */
    public static List<RewardsSettings.Milestone> triggered(List<RewardsSettings.Milestone> milestones, int streak) {
        List<RewardsSettings.Milestone> out = new ArrayList<>();
        if (milestones == null || streak < 1) return out;
        for (RewardsSettings.Milestone m : milestones) {
            if (m.days() < 1) continue;
            if (m.days() == streak || (m.repeat() && streak % m.days() == 0)) out.add(m);
        }
        return out;
    }
}
