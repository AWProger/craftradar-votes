package ru.craftradar.votes.rewards;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OfflineQueueTest {
    static final long DAY = 86_400_000L;
    long now = 1_000_000_000L;
    final List<String> log = new ArrayList<>();
    final RewardsState state = new RewardsState();

    OfflineQueue q(boolean enabled, int max, int days) {
        return new OfflineQueue(state, new RewardsSettings.OfflineQueueSettings(enabled, max, days), () -> now,
                (st, key, pl, d) -> log.add(st + "|" + key + "|" + pl + "|" + d));
    }

    RewardsState.QueuedReward r(String key, String player, long at) {
        return new RewardsState.QueuedReward(key, player, at, List.of(Action.item(key + "#0", player, "DIAMOND", 3)));
    }

    @Test void enqueueDrainIsCaseInsensitiveAndEmptiesQueue() {
        OfflineQueue q = q(true, 5, 7);
        assertTrue(q.enqueue(r("a", "Steve", now)));
        assertEquals(1, q.size("STEVE"));
        List<RewardsState.QueuedReward> got = q.drain("sTeVe");
        assertEquals(1, got.size());
        assertEquals(0, q.size("steve"));
        assertFalse(state.queue.containsKey("steve"));
        assertTrue(q.drain("steve").isEmpty());
    }

    @Test void maxPerPlayerRejectsAndLogsPerPlayerOnly() {
        OfflineQueue q = q(true, 2, 7);
        assertTrue(q.enqueue(r("a", "steve", now)));
        assertTrue(q.enqueue(r("b", "steve", now)));
        assertFalse(q.enqueue(r("c", "steve", now)));
        assertEquals(2, q.size("steve"));
        assertEquals(1, log.size());
        assertTrue(log.get(0).startsWith("SKIPPED_OFFLINE|c|steve|"));
        assertTrue(q.enqueue(r("d", "alex", now)));
    }

    @Test void disabledQueueRejects() {
        assertFalse(q(false, 5, 7).enqueue(r("a", "steve", now)));
        assertTrue(state.queue.isEmpty());
    }

    @Test void expiredDroppedWithLogFreshKept() {
        OfflineQueue q = q(true, 5, 7);
        q.enqueue(r("old", "steve", now - 7 * DAY));
        q.enqueue(r("new", "steve", now - 6 * DAY));
        List<RewardsState.QueuedReward> got = q.drain("steve");
        assertEquals(List.of("new"), got.stream().map(RewardsState.QueuedReward::eventKey).toList());
        assertEquals(1, log.size());
        assertTrue(log.get(0).contains("|old|steve|") && log.get(0).contains("истекла"));
    }

    @Test void putBackKeepsRemainderAndOriginalTimeAndIgnoresLimit() {
        OfflineQueue q = q(true, 1, 7);
        long t = now - 3 * DAY;
        q.enqueue(r("a", "steve", t));
        var drained = q.drain("steve").get(0);
        q.enqueue(r("b", "steve", now));                 // пока игрок онлайн пришла новая
        var rest = new RewardsState.QueuedReward("a", "steve", t, List.of(drained.actions().get(0).withAmount(2)));
        q.putBack(rest);                                  // остаток не теряется даже при max=1
        assertEquals(2, q.size("steve"));
        now += 4 * DAY;                                   // 7 дней от исходного времени, не от возврата
        assertEquals(List.of("b"), q.drain("steve").stream().map(RewardsState.QueuedReward::eventKey).toList());
        assertEquals(1, log.size());
    }

    @Test void putBackEmptyRemainderIsNoop() {
        OfflineQueue q = q(true, 5, 7);
        q.putBack(new RewardsState.QueuedReward("a", "steve", now, List.of()));
        assertEquals(0, q.size("steve"));
    }

    @Test void survivesJsonRoundTrip() {
        OfflineQueue q = q(true, 5, 7);
        q.enqueue(r("a", "steve", now));
        Gson g = new Gson();
        RewardsState copy = g.fromJson(g.toJson(state), RewardsState.class);
        OfflineQueue q2 = new OfflineQueue(copy, new RewardsSettings.OfflineQueueSettings(true, 5, 7), () -> now, (s, k, p, d) -> {});
        var got = q2.drain("steve");
        assertEquals(1, got.size());
        assertEquals(Action.item("a#0", "steve", "DIAMOND", 3), got.get(0).actions().get(0));
    }
}
