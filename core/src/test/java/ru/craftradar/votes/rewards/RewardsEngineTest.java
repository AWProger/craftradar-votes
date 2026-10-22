package ru.craftradar.votes.rewards;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.craftradar.votes.rewards.RewardsSettings.*;

/** Движок на фейках: без Bukkit, без диска. */
class RewardsEngineTest {
    static final long DAY = 86_400_000L;
    static final long T0 = 1_767_268_800_000L; // 2026-01-01T12:00:00Z

    long now;
    final Set<String> online = new java.util.HashSet<>(Set.of("Steve"));
    final List<Action> executed = new ArrayList<>();
    final List<String> log = new ArrayList<>();
    Function<Action, ActionResult> behavior = a -> ActionResult.done();
    RewardsState state = new RewardsState();
    int saves;
    boolean failSave;
    /** Статус действия в сохранённом состоянии на момент исполнения (проверка at-most-once). */
    final List<ActionStatus> statusAtExec = new ArrayList<>();
    RewardsEngine engine;

    @BeforeEach
    void setUp() {
        now = T0;
        engine = new RewardsEngine(() -> now, gateway(), action -> {
            var rec = state.actions.get(action.id());
            statusAtExec.add(rec == null ? null : rec.status());
            executed.add(action);
            return behavior.apply(action);
        }, new StateStore() {
            public RewardsState load() { return state; }
            public void save(RewardsState s) throws IOException { if (failSave) throw new IOException("диск"); saves++; }
        }, (st, key, player, detail) -> log.add(st + " " + player + " " + detail), new Random(1));
    }

    PlayerGateway gateway() {
        return new PlayerGateway() {
            public boolean isOnline(String p) { return online.contains(p); }
            public int giveItems(String p, String m, int n) { return 0; }
            public boolean hasEconomy() { return true; }
            public double balance(String p) { return 0; }
            public boolean deposit(String p, double a) { return true; }
            public void sendMessage(String p, String t) {}
            public void broadcast(String t) {}
        };
    }

    static RewardBlock block(List<String> cmds, List<ItemSpec> items, double money, String msg) { return new RewardBlock(cmds, items, money, msg, ""); }
    static RewardBlock cmd(String c) { return block(List.of(c), List.of(), 0, ""); }

    RewardsSettings settings(RewardBlock def) { return build(def, Set.of(), false, 0, true, WP_OFF, ST_OFF); }
    static final WeightedPool WP_OFF = new WeightedPool(false, List.of());
    static final Streaks ST_OFF = new Streaks(false, List.of());

    RewardsSettings build(RewardBlock def, Set<String> services, boolean dry, int limit, boolean queue, WeightedPool wp, Streaks st) {
        return new RewardsSettings(true, dry, services, ZoneId.of("UTC"), limit, new DuplicateProtection(true, 30),
                new OfflineQueueSettings(queue, 50, 7), def, wp, st, true);
    }

    static VoteInput vote(String user, String corr) { return new VoteInput("TopSite", user, 1L, "1.2.3.4", corr); }
    List<String> texts() { return executed.stream().map(a -> a.text() != null ? a.text() : a.type() + ":" + a.material() + ":" + a.amount() + ":" + a.money()).toList(); }

    @Test void выключенные_награды_ничего_не_делают() {
        engine.configure(RewardsSettings.disabled());
        assertNull(engine.handleVote(vote("Steve", "a")));
        assertTrue(executed.isEmpty());
        assertEquals(0, saves);
    }

    @Test void награда_по_умолчанию_суммирует_всё_и_подставляет_ник() {
        engine.configure(settings(new RewardBlock(List.of("give {player} apple", "say hi"), List.of(new ItemSpec("DIAMOND", 3)), 50, "Спасибо, {player}", "{player} проголосовал")));
        RewardResult r = engine.handleVote(vote("Steve", "c1"));
        assertEquals(RewardStatus.GRANTED, r.status());
        assertEquals("crdv1:c1", r.eventKey());
        assertEquals(List.of("give Steve apple", "say hi", "ITEM:DIAMOND:3:0.0", "MONEY:null:0:50.0", "Спасибо, Steve", "Steve проголосовал"), texts());
        assertEquals(List.of("crdv1:c1#1", "crdv1:c1#2", "crdv1:c1#3", "crdv1:c1#4", "crdv1:c1#5", "crdv1:c1#6"), executed.stream().map(Action::id).toList());
        assertTrue(state.actions.values().stream().allMatch(x -> x.status() == ActionStatus.DONE));
    }

    @Test void фильтр_сервиса_без_регистра() {
        engine.configure(build(cmd("a {player}"), Set.of("topsite"), false, 0, true, WP_OFF, ST_OFF));
        assertEquals(RewardStatus.GRANTED, engine.handleVote(vote("Steve", "1")).status());
        var other = new VoteInput("Other", "Steve", 1L, "x", "2");
        assertEquals(RewardStatus.REJECTED_SERVICE, engine.handleVote(other).status());
        assertEquals(1, executed.size());
        assertFalse(state.processed.containsKey("crdv1:2"));
    }

    @Test void невалидный_ник_не_исполняет_команд() {
        engine.configure(settings(cmd("a {player}")));
        for (String bad : new String[]{"St eve; op", "Steve\nop", "", "ThisNameIsWayTooLong1", null}) {
            assertEquals(RewardStatus.REJECTED_PLAYER, engine.handleVote(vote(bad, "k" + bad)).status(), String.valueOf(bad));
        }
        assertTrue(executed.isEmpty());
    }

    @Test void дубликат_отклоняется_и_не_выдаёт_дважды() {
        engine.configure(settings(cmd("a {player}")));
        assertEquals(RewardStatus.GRANTED, engine.handleVote(vote("Steve", "dup")).status());
        assertEquals(RewardStatus.REJECTED_DUPLICATE, engine.handleVote(vote("Steve", "dup")).status());
        // без correlation_id ключ — хэш; тот же голос = дубликат, другое время = новый
        assertEquals(RewardStatus.GRANTED, engine.handleVote(vote("Steve", null)).status());
        assertEquals(RewardStatus.REJECTED_DUPLICATE, engine.handleVote(vote("Steve", null)).status());
        assertEquals(RewardStatus.GRANTED, engine.handleVote(new VoteInput("TopSite", "Steve", 2L, "1.2.3.4", null)).status());
        assertEquals(3, executed.size());
    }

    @Test void суточный_лимит_блокирует_награду_но_серия_обновляется() {
        var ms = new Milestone(2, false, cmd("streak2 {player}"));
        engine.configure(build(cmd("a {player}"), Set.of(), false, 1, true, WP_OFF, new Streaks(true, List.of(ms))));
        assertEquals(RewardStatus.GRANTED, engine.handleVote(vote("Steve", "1")).status());
        assertEquals(RewardStatus.LIMIT_REACHED, engine.handleVote(vote("Steve", "2")).status());
        assertEquals(1, executed.size());
        now += DAY; // новые сутки: лимит снова свободен, серия продолжилась (=2), милестоун сработал
        assertEquals(RewardStatus.GRANTED, engine.handleVote(vote("Steve", "3")).status());
        assertEquals(List.of("a Steve", "a Steve", "streak2 Steve"), texts());
    }

    @Test void милестоуны_по_дням_и_повтор() {
        engine.configure(build(RewardBlock.empty(), Set.of(), false, 0, true, WP_OFF,
                new Streaks(true, List.of(new Milestone(2, true, cmd("rep {player}")), new Milestone(3, false, cmd("once {player}"))))));
        for (int d = 1; d <= 4; d++) { engine.handleVote(vote("Steve", "d" + d)); now += DAY; }
        assertEquals(List.of("rep Steve", "once Steve", "rep Steve"), texts()); // серия 2: rep; 3: once; 4: rep
        now += 3 * DAY; // пропуск -> серия 1, ничего
        engine.handleVote(vote("Steve", "d9"));
        assertEquals(3, executed.size());
    }

    @Test void пул_выдаёт_ровно_одну_запись() {
        var pool = new WeightedPool(true, List.of(new PoolEntry(1, cmd("A {player}")), new PoolEntry(1, cmd("B {player}"))));
        engine.configure(build(cmd("base {player}"), Set.of(), false, 0, true, pool, ST_OFF));
        for (int i = 0; i < 20; i++) engine.handleVote(vote("Steve", "p" + i));
        for (int i = 0; i < 20; i++) {
            var chunk = texts().subList(i * 2, i * 2 + 2);
            assertEquals("base Steve", chunk.get(0));
            assertTrue(chunk.get(1).equals("A Steve") || chunk.get(1).equals("B Steve"));
        }
        assertEquals(40, executed.size());
    }

    @Test void оффлайн_ставит_в_очередь_и_выдаёт_при_входе() {
        engine.configure(settings(cmd("a {player}")));
        assertEquals(RewardStatus.QUEUED, engine.handleVote(vote("Alex", "q1")).status());
        assertTrue(executed.isEmpty());
        online.add("Alex");
        engine.onJoin("Alex");
        assertEquals(List.of("a Alex"), texts());
        engine.onJoin("Alex"); // очередь пуста: повторно не выдаём
        assertEquals(1, executed.size());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("GRANTED Alex")));
    }

    @Test void оффлайн_без_очереди_пропускается() {
        engine.configure(build(cmd("a {player}"), Set.of(), false, 0, false, WP_OFF, ST_OFF));
        assertEquals(RewardStatus.SKIPPED_OFFLINE, engine.handleVote(vote("Alex", "o1")).status());
        online.add("Alex");
        engine.onJoin("Alex");
        assertTrue(executed.isEmpty());
    }

    @Test void переполнение_очереди_логируется() {
        engine.configure(new RewardsSettings(true, false, Set.of(), ZoneId.of("UTC"), 0, new DuplicateProtection(true, 30),
                new OfflineQueueSettings(true, 1, 7), cmd("a {player}"), WP_OFF, ST_OFF, true));
        assertEquals(RewardStatus.QUEUED, engine.handleVote(vote("Alex", "1")).status());
        assertEquals(RewardStatus.SKIPPED_OFFLINE, engine.handleVote(vote("Alex", "2")).status());
        assertTrue(log.stream().anyMatch(l -> l.contains("SKIPPED_OFFLINE") && l.contains("переполнена")));
    }

    @Test void предметы_не_поместились_остаток_остаётся_в_очереди() {
        engine.configure(settings(block(List.of(), List.of(new ItemSpec("DIAMOND", 10)), 0, "")));
        behavior = a -> ActionResult.retryLater("инвентарь полон", 4);
        assertEquals(RewardStatus.QUEUED, engine.handleVote(vote("Steve", "i1")).status());
        assertEquals(1, state.queue.get("steve").size());
        assertEquals(4, state.queue.get("steve").get(0).actions().get(0).amount());
        behavior = a -> ActionResult.done();
        engine.onJoin("Steve");
        assertEquals(4, executed.get(executed.size() - 1).amount());
        assertTrue(state.queue.getOrDefault("steve", List.of()).isEmpty());
    }

    @Test void сбой_действия_это_не_GRANTED() {
        engine.configure(settings(block(List.of("a {player}"), List.of(), 10, "")));
        behavior = a -> a.type() == Action.Type.MONEY ? ActionResult.failed("нет Vault") : ActionResult.done();
        assertEquals(RewardStatus.ACTION_FAILED, engine.handleVote(vote("Steve", "f1")).status());
        assertEquals(ActionStatus.FAILED, state.actions.get("crdv1:f1#2").status());
        assertFalse(log.stream().anyMatch(l -> l.startsWith("GRANTED")));
    }

    @Test void исключение_исполнителя_становится_FAILED() {
        engine.configure(settings(cmd("a {player}")));
        behavior = a -> { throw new IllegalStateException("boom"); };
        assertEquals(RewardStatus.ACTION_FAILED, engine.handleVote(vote("Steve", "e1")).status());
    }

    @Test void команда_помечается_ATTEMPTED_на_диске_до_исполнения() {
        engine.configure(settings(cmd("a {player}")));
        engine.handleVote(vote("Steve", "m1"));
        assertEquals(List.of(ActionStatus.ATTEMPTED), statusAtExec);
    }

    @Test void если_ATTEMPTED_не_сохранился_команда_не_исполняется() {
        engine.configure(settings(cmd("a {player}")));
        failSave = true;
        assertEquals(RewardStatus.ACTION_FAILED, engine.handleVote(vote("Steve", "s1")).status());
        assertTrue(executed.isEmpty());
    }

    @Test void dry_run_ничего_не_исполняет_и_не_меняет_состояние() {
        engine.configure(build(cmd("a {player}"), Set.of(), true, 1, true, WP_OFF, new Streaks(true, List.of())));
        RewardResult r = engine.handleVote(vote("Steve", "dr"));
        assertEquals(RewardStatus.DRY_RUN, r.status());
        assertTrue(r.detail().contains("a Steve"));
        // тот же голос снова: дедуп/лимит не накопились
        assertEquals(RewardStatus.DRY_RUN, engine.handleVote(vote("Steve", "dr")).status());
        assertTrue(executed.isEmpty());
        assertTrue(state.processed.isEmpty() && state.streaks.isEmpty() && state.daily.isEmpty() && state.queue.isEmpty());
        assertEquals(0, saves);
        assertEquals(RewardStatus.DRY_RUN, engine.handleVote(vote("Alex", "dr2")).status()); // оффлайн тоже без очереди
        assertTrue(state.queue.isEmpty());
    }

    @Test void невалидный_reload_оставляет_прежние_настройки() {
        engine.configure(settings(cmd("old {player}")));
        var bad = new ValidationResult(null, List.of("rewards.default.items[0].material: неизвестный материал"));
        assertFalse(engine.reload(bad));
        assertTrue(log.stream().anyMatch(l -> l.startsWith("CONFIG_INVALID") && l.contains("items[0].material")));
        assertTrue(engine.status().contains("items[0].material"));
        assertEquals(RewardStatus.GRANTED, engine.handleVote(vote("Steve", "r1")).status());
        assertEquals(List.of("old Steve"), texts());
        assertTrue(engine.reload(new ValidationResult(settings(cmd("new {player}")), List.of())));
        engine.handleVote(vote("Steve", "r2"));
        assertEquals("new Steve", texts().get(1));
        assertFalse(engine.status().contains("items[0].material"));
    }

    @Test void рестарт_ATTEMPTED_становится_UNCERTAIN_осиротевшие_PENDING_уходят_в_очередь() {
        Action inFlight = Action.command("k#1", "Steve", "x Steve");
        Action orphan = Action.command("k#2", "Steve", "y Steve");
        Action queued = Action.command("q#1", "Steve", "z Steve");
        Action dead = Action.command("q#2", "Steve", "w Steve");
        state.actions.put("k#1", new RewardsState.ActionRecord(inFlight, ActionStatus.ATTEMPTED, "", T0));
        state.actions.put("k#2", new RewardsState.ActionRecord(orphan, ActionStatus.PENDING, "", T0));
        state.actions.put("q#1", new RewardsState.ActionRecord(queued, ActionStatus.PENDING, "", T0));
        state.actions.put("q#2", new RewardsState.ActionRecord(dead, ActionStatus.ATTEMPTED, "", T0));
        state.queue.put("steve", new ArrayList<>(List.of(new RewardsState.QueuedReward("q", "steve", T0, List.of(queued, dead)))));
        engine.configure(settings(RewardBlock.empty()));
        engine.recoverOnStart();
        assertEquals(ActionStatus.UNCERTAIN, state.actions.get("k#1").status());
        assertEquals(ActionStatus.PENDING, state.actions.get("k#2").status(), "не начиналась: ждёт в очереди");
        assertEquals(ActionStatus.PENDING, state.actions.get("q#1").status());
        assertEquals(ActionStatus.UNCERTAIN, state.actions.get("q#2").status());
        assertEquals(List.of(queued), state.queue.get("steve").get(0).actions());
        assertEquals(List.of(orphan), state.queue.get("steve").get(1).actions());
        engine.onJoin("Steve");
        assertEquals(List.of("z Steve", "y Steve"), texts());
        assertEquals(2, log.stream().filter(l -> l.startsWith("ACTION_UNCERTAIN")).count());
    }

    @Test void test_исполняет_для_онлайн_и_не_трогает_состояние() {
        engine.configure(build(cmd("t {player}"), Set.of(), false, 1, true, WP_OFF, new Streaks(true, List.of(new Milestone(1, false, cmd("ms {player}"))))));
        assertEquals(RewardStatus.GRANTED, engine.test("Steve").status());
        assertEquals(RewardStatus.GRANTED, engine.test("Steve").status()); // лимит 1 не мешает
        assertEquals(List.of("t Steve", "t Steve"), texts()); // без милестоунов
        assertTrue(state.processed.isEmpty() && state.streaks.isEmpty() && state.daily.isEmpty() && state.actions.isEmpty());
        assertEquals(0, saves);
        assertEquals(RewardStatus.SKIPPED_OFFLINE, engine.test("Alex").status());
        assertEquals(RewardStatus.REJECTED_PLAYER, engine.test("a b").status());
    }

    @Test void состояние_не_читается_награды_не_выдаются() throws Exception {
        var broken = new RewardsEngine(() -> now, gateway(), a -> { executed.add(a); return ActionResult.done(); },
                new StateStore() {
                    public RewardsState load() throws IOException { throw new IOException("битый json"); }
                    public void save(RewardsState s) {}
                }, (st, k, p, d) -> log.add(st + " " + d), new Random(1));
        broken.configure(settings(cmd("a {player}")));
        assertEquals(RewardStatus.CONFIG_INVALID, broken.handleVote(vote("Steve", "b1")).status());
        assertTrue(executed.isEmpty());
        assertTrue(broken.status().contains("не загружено"));
    }
}
