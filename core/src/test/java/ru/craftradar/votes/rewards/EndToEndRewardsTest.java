package ru.craftradar.votes.rewards;

import static org.junit.jupiter.api.Assertions.*;
import static ru.craftradar.votes.rewards.EndToEndHarness.DAY;
import static ru.craftradar.votes.rewards.EndToEndHarness.vote;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Сквозные сценарии наград: движок + настоящие валидатор, исполнитель, состояние и журнал на диске. */
class EndToEndRewardsTest {
    @TempDir Path dir;
    EndToEndHarness h;

    static final String BASIC = """
            enabled: true
            default:
              commands: ["give {player} apple 1", "say привет {player}"]
            """;

    @BeforeEach void setUp() { h = new EndToEndHarness(dir); h.boot(); }

    @Test void выключено_ничего_не_происходит_и_диск_не_трогается() throws Exception {
        h.configure("enabled: false\ndefault:\n  commands: [\"say x\"]");
        assertNull(h.engine.handleVote(vote("Steve", "a")));
        assertTrue(h.console.isEmpty());
        assertNull(h.stateJson());
        assertEquals("", h.log());
    }

    @Test void валидный_голос_исполняет_команды_и_пишет_состояние_и_журнал() throws Exception {
        h.configure(BASIC);
        RewardResult r = h.engine.handleVote(vote("Steve", "v1"));
        assertEquals(RewardStatus.GRANTED, r.status());
        assertEquals(List.of("give Steve apple 1", "say привет Steve"), h.console);
        assertTrue(h.stateJson().contains("crdv1:v1"));
        assertTrue(h.log().contains("GRANTED"));
    }

    @Test void дубликат_не_выдаёт_повторно_в_том_числе_после_рестарта() {
        h.configure(BASIC);
        h.engine.handleVote(vote("Steve", "v1"));
        assertEquals(RewardStatus.REJECTED_DUPLICATE, h.engine.handleVote(vote("Steve", "v1")).status());
        h.boot();
        h.configure(BASIC);
        assertEquals(RewardStatus.REJECTED_DUPLICATE, h.engine.handleVote(vote("Steve", "v1")).status());
        assertEquals(2, h.console.size());
    }

    @Test void инъекция_через_ник_отклоняется_и_консоль_не_получает_ничего() throws Exception {
        h.configure(BASIC);
        String[] bad = {"x; op x", "a b", "x\nop x", "Steve\n", "x\r\nop x", "Ник", "Ｓteve", "x op x", "x\u0000", "{player}", "../x", "a".repeat(17)};
        for (String n : bad) {
            assertEquals(RewardStatus.REJECTED_PLAYER, h.engine.handleVote(vote(n, "k" + n.hashCode())).status(), n);
        }
        assertTrue(h.console.isEmpty());
        assertTrue(h.messages.isEmpty());
        assertTrue(h.log().contains("REJECTED_PLAYER"));
    }

    @Test void серия_через_границу_суток_в_часовом_поясе_пропуск_и_рестарт() {
        String cfg = """
                enabled: true
                timezone: "Europe/Moscow"
                streaks:
                  enabled: true
                  milestones:
                    - {days: 3, commands: ["say серия3 {player}"]}
                """;
        h.configure(cfg);
        h.now = 1_767_299_400_000L; // 2026-01-01T20:30Z = 23:30 MSK, день 1
        h.engine.handleVote(vote("Steve", "d1"));
        h.now += 3_600_000L;        // 00:30 MSK 2 января: другой календарный день, хотя прошёл час
        h.engine.handleVote(vote("Steve", "d2"));
        h.boot(); h.configure(cfg); // рестарт: серия на диске
        h.now += DAY;
        h.engine.handleVote(vote("Steve", "d3"));
        assertEquals(List.of("say серия3 Steve"), h.console);
        h.now += 3 * DAY;           // пропуск: серия 1
        h.engine.handleVote(vote("Steve", "d6"));
        h.now += DAY; h.engine.handleVote(vote("Steve", "d7"));
        assertEquals(1, h.console.size(), "после пропуска серия началась заново");
        h.now += DAY; h.engine.handleVote(vote("Steve", "d8"));
        assertEquals(2, h.console.size(), "новая серия дошла до 3");
    }

    @Test void тот_же_день_серию_не_увеличивает() {
        h.configure("enabled: true\nstreaks:\n  enabled: true\n  milestones:\n    - {days: 2, commands: [\"say s2\"]}");
        h.engine.handleVote(vote("Steve", "a"));
        h.engine.handleVote(vote("Steve", "b"));
        assertTrue(h.console.isEmpty());
    }

    @Test void оффлайн_голос_в_очереди_и_выдаётся_при_входе_даже_после_рестарта() {
        h.configure(BASIC);
        assertEquals(RewardStatus.QUEUED, h.engine.handleVote(vote("Alex", "o1")).status());
        assertTrue(h.console.isEmpty());
        h.boot(); h.configure(BASIC);
        h.online.add("Alex");
        h.engine.onJoin("Alex");
        assertEquals(List.of("give Alex apple 1", "say привет Alex"), h.console);
        h.engine.onJoin("Alex");
        assertEquals(2, h.console.size(), "очередь выдана один раз");
    }

    @Test void оффлайн_при_выключенной_очереди_пропуск_и_нет_выдачи_на_входе() {
        h.configure(BASIC + "offline-queue: {enabled: false}\n");
        assertEquals(RewardStatus.SKIPPED_OFFLINE, h.engine.handleVote(vote("Alex", "o1")).status());
        h.online.add("Alex");
        h.engine.onJoin("Alex");
        assertTrue(h.console.isEmpty());
    }

    @Test void истёкшая_очередь_не_выдаётся() {
        h.configure(BASIC + "offline-queue: {enabled: true, max-per-player: 5, expire-days: 7}\n");
        h.engine.handleVote(vote("Alex", "o1"));
        h.now += 8 * DAY;
        h.online.add("Alex");
        h.engine.onJoin("Alex");
        assertTrue(h.console.isEmpty());
    }

    @Test void сбой_одной_команды_не_повторяет_остальные_at_most_once() {
        h.configure("enabled: true\ndefault:\n  commands: [\"a {player}\", \"b {player}\", \"c {player}\"]\n  money: 10");
        h.dispatchResult = c -> !c.startsWith("b");
        assertEquals(RewardStatus.ACTION_FAILED, h.engine.handleVote(vote("Steve", "f1")).status());
        assertEquals(List.of("a Steve", "b Steve", "c Steve"), h.console);
        assertEquals(List.of("Steve:10.0"), h.deposits);
        h.boot(); h.configure("enabled: true\ndefault:\n  commands: [\"a {player}\", \"b {player}\", \"c {player}\"]\n  money: 10");
        h.engine.onJoin("Steve");
        h.engine.handleVote(vote("Steve", "f1"));
        assertEquals(3, h.console.size());
        assertEquals(1, h.deposits.size());
    }

    @Test void падение_посреди_награды_начатое_не_повторяется_не_начатое_выдаётся_при_входе() {
        String cfg = "enabled: true\ndefault:\n  commands: [\"a {player}\", \"b {player}\", \"c {player}\"]";
        h.configure(cfg);
        // «крах процесса» на второй команде: Error проходит мимо обработчика исключений движка
        h.boot(a -> {
            h.console.add(a.text());
            if (a.text().startsWith("b")) throw new OutOfMemoryError("краш");
            return ActionResult.done();
        });
        h.configure(cfg);
        assertThrows(OutOfMemoryError.class, () -> h.engine.handleVote(vote("Steve", "c1")));
        assertEquals(List.of("a Steve", "b Steve"), h.console);

        h.boot(); h.configure(cfg); // рестарт: b (ATTEMPTED) -> UNCERTAIN; c не начиналась -> очередь
        assertTrue(h.engine.status().contains("неопределённых действий: 1"), h.engine.status());
        assertTrue(h.engine.status().contains("в очереди: 1"), h.engine.status());
        h.boot(); h.configure(cfg); // повторный рестарт до входа игрока: очередь на диске, ничего не потеряно и не удвоено
        assertTrue(h.engine.status().contains("в очереди: 1"), h.engine.status());
        h.engine.onJoin("Steve");
        assertEquals(List.of("a Steve", "b Steve", "c Steve"), h.console, "a и b не повторены, c выдана ровно один раз");
        h.engine.onJoin("Steve");
        assertEquals(RewardStatus.REJECTED_DUPLICATE, h.engine.handleVote(vote("Steve", "c1")).status());
        assertEquals(3, h.console.size());
    }

    @Test void нет_места_в_инвентаре_остаток_предметов_ждёт_входа_команды_не_повторяются() {
        String cfg = "enabled: true\ndefault:\n  commands: [\"a {player}\"]\n  items: [{material: DIAMOND, amount: 5}]";
        h.configure(cfg);
        h.inventoryRest = 5; // совсем нет места
        assertEquals(RewardStatus.QUEUED, h.engine.handleVote(vote("Steve", "i1")).status());
        assertEquals(List.of("a Steve"), h.console);
        h.inventoryRest = 0;
        h.boot(); h.configure(cfg);
        h.engine.onJoin("Steve");
        assertEquals(List.of("Steve:DIAMOND:5"), h.given);
        assertEquals(1, h.console.size(), "команда не повторена");
    }

    @Test void dry_run_ничего_не_исполняет_не_пишет_состояние_и_не_ест_дедуп_и_серию() throws Exception {
        String dry = "enabled: true\ndry-run: true\ndefault:\n  commands: [\"a {player}\"]\n"
                + "streaks:\n  enabled: true\n  milestones:\n    - {days: 2, commands: [\"s2 {player}\"]}";
        h.configure(dry);
        assertEquals(RewardStatus.DRY_RUN, h.engine.handleVote(vote("Steve", "d1")).status());
        h.now += DAY;
        assertEquals(RewardStatus.DRY_RUN, h.engine.handleVote(vote("Steve", "d2")).status());
        assertTrue(h.console.isEmpty());
        assertNull(h.stateJson());
        assertTrue(h.log().contains("DRY_RUN"));
        // боевой режим: тот же ключ принимается, серия началась с 1 (милестоун дня 2 не сработал)
        h.configure(dry.replace("dry-run: true", "dry-run: false"));
        assertEquals(RewardStatus.GRANTED, h.engine.handleVote(vote("Steve", "d2")).status());
        assertEquals(List.of("a Steve"), h.console);
    }

    @Test void невалидный_reload_оставляет_прежний_конфиг_и_пишет_ошибки_с_путём() throws Exception {
        h.configure(BASIC);
        ValidationResult bad = EndToEndHarness.validate("rewards:\n  enabled: true\n  default:\n    items: [{material: NOPE, amount: 1}]\n  неизвестный: 1\n");
        assertFalse(h.engine.reload(bad));
        assertTrue(h.engine.status().contains("CONFIG_INVALID"));
        assertTrue(h.engine.status().contains("rewards.default.items[0].material"), h.engine.status());
        assertTrue(h.log().contains("CONFIG_INVALID"));
        assertEquals(RewardStatus.GRANTED, h.engine.handleVote(vote("Steve", "r1")).status());
        assertEquals(List.of("give Steve apple 1", "say привет Steve"), h.console);
    }

    @Test void тестовое_событие_исполняет_действия_но_не_меняет_состояние() throws Exception {
        String cfg = "enabled: true\ndaily-limit: 1\ndefault:\n  commands: [\"a {player}\"]\n"
                + "streaks:\n  enabled: true\n  milestones:\n    - {days: 2, commands: [\"s2 {player}\"]}";
        h.configure(cfg);
        for (int i = 0; i < 3; i++) { h.now += 1000; assertEquals(RewardStatus.GRANTED, h.engine.test("Steve").status()); }
        assertEquals(3, h.console.size());
        assertNull(h.stateJson(), "тест не пишет состояние");
        // лимит, дедуп и серия нетронуты: первый реальный голос выдаётся, второй упирается в лимит
        assertEquals(RewardStatus.GRANTED, h.engine.handleVote(vote("Steve", "real1")).status());
        assertEquals(RewardStatus.LIMIT_REACHED, h.engine.handleVote(vote("Steve", "real2")).status());
        assertEquals(4, h.console.size());
        // серия = 1 (а не 4): следующий день даёт 2 и милестоун, не раньше
        h.now += DAY;
        h.engine.handleVote(vote("Steve", "real3"));
        assertTrue(h.console.contains("s2 Steve"));
    }

    @Test void тест_для_оффлайн_и_невалидного_ника_ничего_не_ставит_в_очередь() {
        h.configure(BASIC);
        assertEquals(RewardStatus.SKIPPED_OFFLINE, h.engine.test("Alex").status());
        assertEquals(RewardStatus.REJECTED_PLAYER, h.engine.test("x; op x").status());
        h.online.add("Alex");
        h.engine.onJoin("Alex");
        assertTrue(h.console.isEmpty());
    }
}
