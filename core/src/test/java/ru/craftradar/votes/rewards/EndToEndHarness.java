package ru.craftradar.votes.rewards;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import org.yaml.snakeyaml.Yaml;

/**
 * Сквозной стенд: настоящие ConfigValidator, CommandExecutor, JsonStateStore и RewardJournal на временной
 * папке; фейки только на границах (время, игроки, консоль, Vault). {@link #boot()} = рестарт сервера:
 * новый движок над теми же файлами на диске.
 */
final class EndToEndHarness {
    static final long DAY = 86_400_000L;
    static final long T0 = 1_767_268_800_000L; // 2026-01-01T12:00:00Z

    final Path dir;
    volatile long now = T0;
    final Set<String> online = Collections.synchronizedSet(new HashSet<>(Set.of("Steve")));
    /** Всё, что дошло до консоли сервера (после подстановки ника). */
    final List<String> console = Collections.synchronizedList(new ArrayList<>());
    final List<String> messages = Collections.synchronizedList(new ArrayList<>());
    /** Решение консоли по тексту команды: true = dispatchCommand вернул true. */
    volatile Function<String, Boolean> dispatchResult = c -> true;
    /** Сколько предметов не помещается у игрока (остаток). */
    volatile int inventoryRest = 0;
    final List<String> given = Collections.synchronizedList(new ArrayList<>());
    final List<String> deposits = Collections.synchronizedList(new ArrayList<>());
    RewardsEngine engine;

    EndToEndHarness(Path dir) { this.dir = dir; }

    Clock clock() { return () -> now; }

    PlayerGateway gateway() {
        return new PlayerGateway() {
            public boolean isOnline(String p) { return online.contains(p); }
            public int giveItems(String p, String m, int n) {
                if (inventoryRest >= n) return n;
                given.add(p + ":" + m + ":" + (n - inventoryRest));
                return inventoryRest;
            }
            public boolean hasEconomy() { return true; }
            public double balance(String p) { return 0; }
            public boolean deposit(String p, double a) { deposits.add(p + ":" + a); return true; }
            public void sendMessage(String p, String t) { messages.add(p + ":" + t); }
            public void broadcast(String t) { messages.add("*:" + t); }
        };
    }

    /** Старт сервера: движок над файлами папки; recoverOnStart как в плагине. */
    RewardsEngine boot() { return boot(new CommandExecutor(gateway(), c -> { console.add(c); return dispatchResult.apply(c); })); }

    RewardsEngine boot(ActionExecutor executor) {
        engine = new RewardsEngine(clock(), gateway(), executor, new JsonStateStore(dir),
                new RewardJournal(dir, clock(), true), new Random(7));
        engine.recoverOnStart();
        return engine;
    }

    @SuppressWarnings("unchecked")
    static ValidationResult validate(String yaml) {
        Map<String, Object> root = new Yaml().load(yaml);
        return ConfigValidator.validate(root == null ? null : (Map<String, Object>) root.get("rewards"),
                Set.of("DIAMOND", "BREAD")::contains);
    }

    /** Применить YAML как настоящую конфигурацию; невалидный YAML тест ловит сам. */
    RewardsEngine configure(String rewardsBody) {
        ValidationResult vr = validate("rewards:\n" + rewardsBody.indent(2));
        if (!vr.ok()) throw new AssertionError(vr.errors().toString());
        engine.configure(vr.settings());
        return engine;
    }

    static VoteInput vote(String user, String corr) { return new VoteInput("TopSite", user, 1L, "1.2.3.4", corr); }

    String stateJson() throws IOException {
        Path f = dir.resolve(JsonStateStore.FILE);
        return Files.exists(f) ? Files.readString(f) : null;
    }

    String log() throws IOException {
        Path f = dir.resolve(RewardJournal.FILE_NAME);
        return Files.exists(f) ? Files.readString(f) : "";
    }
}
