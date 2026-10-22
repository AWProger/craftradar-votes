package ru.craftradar.votes.rewards;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Очередь невыданных наград по игроку (лежит в RewardsState.queue, ключ — ник в нижнем регистре).
 * Лимит считается в записях (QueuedReward) на игрока. Истечение и переполнение пишутся в журнал.
 */
public final class OfflineQueue {
    private static final long DAY_MS = 86_400_000L;

    private final RewardsState state;
    private final RewardsSettings.OfflineQueueSettings settings;
    private final Clock clock;
    private final Journal journal;

    public OfflineQueue(RewardsState state, RewardsSettings.OfflineQueueSettings settings, Clock clock, Journal journal) {
        this.state = state;
        this.settings = settings;
        this.clock = clock;
        this.journal = journal;
    }

    private static String key(String player) { return player.toLowerCase(Locale.ROOT); }

    /** false — переполнено (max-per-player) или очередь выключена, событие логируется, не молча. */
    public boolean enqueue(RewardsState.QueuedReward reward) {
        String p = key(reward.player());
        if (!settings.enabled()) return false;
        List<RewardsState.QueuedReward> list = state.queue.computeIfAbsent(p, k -> new ArrayList<>());
        if (list.size() >= settings.maxPerPlayer()) {
            if (list.isEmpty()) state.queue.remove(p);
            journal.log(RewardStatus.SKIPPED_OFFLINE, reward.eventKey(), p,
                    "очередь переполнена (max-per-player=" + settings.maxPerPlayer() + "), награда отброшена");
            return false;
        }
        list.add(reward);
        return true;
    }

    /** Забрать все неистёкшие награды игрока (истёкшие удаляются с записью в журнал). */
    public List<RewardsState.QueuedReward> drain(String player) {
        String p = key(player);
        List<RewardsState.QueuedReward> list = state.queue.remove(p);
        List<RewardsState.QueuedReward> fresh = new ArrayList<>();
        if (list == null) return fresh;
        long ttl = settings.expireDays() * DAY_MS;
        long now = clock.nowMs();
        for (RewardsState.QueuedReward q : list) {
            if (now - q.queuedAtMs() >= ttl) {
                journal.log(RewardStatus.SKIPPED_OFFLINE, q.eventKey(), p,
                        "награда в очереди истекла (expire-days=" + settings.expireDays() + "), удалена");
            } else {
                fresh.add(q);
            }
        }
        return fresh;
    }

    /** Вернуть остаток обратно (полный инвентарь): лимит не применяется, queuedAtMs сохраняется. Пустой остаток игнорируется. */
    public void putBack(RewardsState.QueuedReward reward) {
        if (reward.actions().isEmpty()) return;
        state.queue.computeIfAbsent(key(reward.player()), k -> new ArrayList<>()).add(reward);
    }

    public int size(String player) {
        List<RewardsState.QueuedReward> list = state.queue.get(key(player));
        return list == null ? 0 : list.size();
    }
}
