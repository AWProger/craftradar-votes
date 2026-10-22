package ru.craftradar.votes.rewards;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class StateStoreTest {
    private static final long DAY = 86_400_000L;
    @TempDir Path dir;

    private static DedupeStore dedupe(RewardsState s, boolean on, int days, AtomicLong now) {
        return new DedupeStore(s, new RewardsSettings.DuplicateProtection(on, days), now::get);
    }

    @Test void duplicateRejectedAndRetentionExpires() {
        var s = new RewardsState(); var now = new AtomicLong(1_000 * DAY);
        var d = dedupe(s, true, 30, now);
        assertTrue(d.markIfNew("crdv1:a"));
        assertFalse(d.markIfNew("crdv1:a"));
        now.addAndGet(29 * DAY);
        assertFalse(d.markIfNew("crdv1:a"));
        now.addAndGet(2 * DAY);
        assertTrue(d.markIfNew("crdv1:a"), "после retention ключ снова новый");
        assertEquals(1, s.processed.size(), "старые записи вычищены");
    }

    @Test void disabledProtectionAlwaysTrueAndStoresNothing() {
        var s = new RewardsState(); var d = dedupe(s, false, 30, new AtomicLong(5));
        assertTrue(d.markIfNew("k")); assertTrue(d.markIfNew("k"));
        assertTrue(s.processed.isEmpty());
    }

    @Test void parallelSameKeyHasSingleWinner() throws Exception {
        var s = new RewardsState(); var d = dedupe(s, true, 30, new AtomicLong(DAY));
        int n = 32; var pool = Executors.newFixedThreadPool(n); var gate = new CountDownLatch(1);
        var wins = new AtomicInteger(); List<Future<?>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) fs.add(pool.submit(() -> { gate.await(); if (d.markIfNew("crdv1:same")) wins.incrementAndGet(); return null; }));
        gate.countDown();
        for (var f : fs) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(1, wins.get());
    }

    @Test void sizeIsCapped() {
        var s = new RewardsState(); var d = dedupe(s, true, 3650, new AtomicLong(DAY));
        for (int i = 0; i < DedupeStore.MAX_KEYS + 10; i++) s.processed.put("old" + i, DAY);
        assertTrue(d.markIfNew("new"));
        assertEquals(DedupeStore.MAX_KEYS, s.processed.size());
        assertTrue(s.processed.containsKey("new"));
        assertFalse(s.processed.containsKey("old0"), "вытеснены самые старые");
    }

    @Test void roundTripSurvivesRestartAndKeepsDedupe() throws Exception {
        var s = new RewardsState(); var now = new AtomicLong(DAY);
        dedupe(s, true, 30, now).markIfNew("crdv1:x");
        s.streaks.put("steve", new RewardsState.StreakEntry("2026-01-02", 3));
        var a = Action.command("crdv1:x#0", "steve", "give steve diamond 1");
        s.actions.put(a.id(), new RewardsState.ActionRecord(a, ActionStatus.ATTEMPTED, "d", 7));
        s.queue.put("steve", List.of(new RewardsState.QueuedReward("crdv1:x", "steve", 5, List.of(a))));
        new JsonStateStore(dir).save(s);

        var loaded = new JsonStateStore(dir).load();
        assertEquals(3, loaded.streaks.get("steve").streak());
        assertEquals(a, loaded.actions.get("crdv1:x#0").action());
        assertEquals(ActionStatus.ATTEMPTED, loaded.actions.get("crdv1:x#0").status());
        assertEquals(a, loaded.queue.get("steve").get(0).actions().get(0));
        assertFalse(dedupe(loaded, true, 30, now).markIfNew("crdv1:x"), "дедуп пережил перезапуск");
    }

    @Test void missingFileGivesEmptyState() throws Exception {
        var s = new JsonStateStore(dir).load();
        assertTrue(s.processed.isEmpty());
    }

    @Test void saveLeavesOnlyFixedNameAndOverwrites() throws Exception {
        var st = new JsonStateStore(dir); var s = new RewardsState();
        st.save(s); s.processed.put("k", 1L); st.save(s);
        try (var f = Files.list(dir)) { assertEquals(List.of("rewards-state.json"), f.map(p -> p.getFileName().toString()).toList()); }
        assertEquals(1L, st.load().processed.get("k"));
    }

    @Test void corruptFileIsBackedUpNotSilentlyReset() throws Exception {
        Files.writeString(dir.resolve("rewards-state.json"), "{\"processed\": {broken");
        var st = new JsonStateStore(dir);
        var e = assertThrows(java.io.IOException.class, st::load);
        assertTrue(e.getMessage().contains(".corrupt"));
        assertEquals("{\"processed\": {broken", Files.readString(dir.resolve("rewards-state.json.corrupt")));
        assertFalse(Files.exists(dir.resolve("rewards-state.json")));
        assertTrue(st.load().processed.isEmpty(), "после бэкапа старт с чистого состояния");
    }

    @Test void emptyFileTreatedAsCorrupt() throws Exception {
        Files.writeString(dir.resolve("rewards-state.json"), "");
        assertThrows(java.io.IOException.class, () -> new JsonStateStore(dir).load());
        assertTrue(Files.exists(dir.resolve("rewards-state.json.corrupt")));
    }

    @Test void partialJsonWithMissingSectionsGetsDefaults() throws Exception {
        Files.writeString(dir.resolve("rewards-state.json"), "{\"version\":1,\"processed\":{\"a\":5}}");
        var s = new JsonStateStore(dir).load();
        assertEquals(5L, s.processed.get("a"));
        assertNotNull(s.queue); assertNotNull(s.actions);
    }
}
