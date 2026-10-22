package ru.craftradar.votes.rewards;

import static org.junit.jupiter.api.Assertions.*;
import static ru.craftradar.votes.rewards.EndToEndHarness.vote;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Votifier может отдать событие с любого потока: плагин переносит его на главный (runTask), а движок
 * однопоточный. Модель главного потока здесь — одиночный executor; проверяем, что гонка потоков-источников
 * не даёт двойной награды.
 */
class ConcurrencyRewardsTest {
    @TempDir Path dir;

    @Test void параллельные_события_одного_ключа_дают_одну_награду() throws Exception {
        var h = new EndToEndHarness(dir);
        h.boot();
        h.configure("enabled: true\ndefault:\n  commands: [\"give {player} apple\"]\n  money: 5");
        ExecutorService mainThread = Executors.newSingleThreadExecutor();
        ExecutorService sources = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<RewardStatus>> res = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            res.add(sources.submit(() -> {
                go.await();
                return mainThread.submit(() -> h.engine.handleVote(vote("Steve", "same")).status()).get();
            }));
        }
        go.countDown();
        int granted = 0, dup = 0;
        for (var f : res) {
            RewardStatus s = f.get(30, TimeUnit.SECONDS);
            if (s == RewardStatus.GRANTED) granted++; else if (s == RewardStatus.REJECTED_DUPLICATE) dup++;
        }
        sources.shutdown(); mainThread.shutdown();
        assertEquals(1, granted);
        assertEquals(63, dup);
        assertEquals(List.of("give Steve apple"), h.console);
        assertEquals(List.of("Steve:5.0"), h.deposits);
    }

    @Test void параллельные_разные_голоса_и_лимит_не_превышаются() throws Exception {
        var h = new EndToEndHarness(dir);
        h.boot();
        h.configure("enabled: true\ndaily-limit: 3\ndefault:\n  commands: [\"a {player}\"]");
        ExecutorService mainThread = Executors.newSingleThreadExecutor();
        ExecutorService sources = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            final int n = i;
            fs.add(sources.submit(() -> mainThread.submit(() -> h.engine.handleVote(vote("Steve", "k" + n))).get()));
        }
        for (var f : fs) f.get(30, TimeUnit.SECONDS);
        sources.shutdown(); mainThread.shutdown();
        assertEquals(3, h.console.size());
    }

    @Test void вход_игрока_параллельно_с_голосом_не_дублирует_очередь() throws Exception {
        var h = new EndToEndHarness(dir);
        h.boot();
        h.configure("enabled: true\ndefault:\n  commands: [\"a {player}\"]");
        h.engine.handleVote(vote("Alex", "q1")); // оффлайн -> очередь
        h.online.add("Alex");
        ExecutorService mainThread = Executors.newSingleThreadExecutor();
        ExecutorService sources = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 20; i++) fs.add(sources.submit(() -> mainThread.submit(() -> h.engine.onJoin("Alex")).get()));
        for (var f : fs) f.get(30, TimeUnit.SECONDS);
        sources.shutdown(); mainThread.shutdown();
        assertEquals(List.of("a Alex"), h.console);
    }
}
