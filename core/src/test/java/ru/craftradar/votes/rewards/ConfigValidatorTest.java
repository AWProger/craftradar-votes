package ru.craftradar.votes.rewards;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ConfigValidatorTest {
    private static final Predicate<String> MATERIALS = Set.of("DIAMOND", "EMERALD")::contains;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String yaml) {
        Object root = new Yaml().load(yaml);
        return root == null ? null : (Map<String, Object>) ((Map<String, Object>) root).get("rewards");
    }

    private static ValidationResult file(String name) throws Exception {
        try (InputStream in = ConfigValidatorTest.class.getResourceAsStream("/rewards/" + name)) {
            assertNotNull(in, name);
            return ConfigValidator.validate(parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)), MATERIALS);
        }
    }

    private static ValidationResult v(String rewardsBody) {
        return ConfigValidator.validate(parse("rewards:\n" + rewardsBody.indent(2)), MATERIALS);
    }

    private static void assertError(ValidationResult r, String pathPrefix) {
        assertFalse(r.ok());
        assertNull(r.settings(), "частичное применение недопустимо");
        assertTrue(r.errors().stream().anyMatch(e -> e.startsWith(pathPrefix + ":")), pathPrefix + " нет в " + r.errors());
    }

    @Test void missingSectionIsDisabled() {
        ValidationResult r = ConfigValidator.validate(null, MATERIALS);
        assertTrue(r.ok());
        assertFalse(r.settings().enabled());
    }

    @Test void emptySectionGivesContractDefaults() {
        ValidationResult r = v("enabled: false");
        assertTrue(r.ok(), r.errors().toString());
        assertEquals(RewardsSettings.disabled(), r.settings());
    }

    @Test void fullValidFile() throws Exception {
        ValidationResult r = file("valid-full.yml");
        assertTrue(r.ok(), r.errors().toString());
        RewardsSettings s = r.settings();
        assertTrue(s.enabled() && s.dryRun() && !s.logging());
        assertEquals(Set.of("topcraft", "mineserv"), s.services());
        assertEquals(ZoneId.of("Europe/Moscow"), s.timezone());
        assertEquals(3, s.dailyLimit());
        assertEquals(14, s.duplicateProtection().retentionDays());
        assertEquals(20, s.offlineQueue().maxPerPlayer());
        assertEquals(List.of("give {player} apple 1", "eco give {player} 5"), s.defaults().commands());
        assertEquals(new RewardsSettings.ItemSpec("DIAMOND", 3), s.defaults().items().get(0));
        assertEquals(10.5, s.defaults().money());
        assertEquals("{player} проголосовал", s.defaults().broadcast());
        assertEquals(2, s.weightedPool().entries().size());
        assertEquals(70, s.weightedPool().entries().get(0).weight());
        assertEquals(1.0, s.weightedPool().entries().get(0).reward().money());
        assertEquals(2, s.streaks().milestones().size());
        assertTrue(s.streaks().milestones().get(1).repeat());
        assertFalse(s.streaks().milestones().get(0).repeat());
    }

    @Test void minimalFile() throws Exception {
        assertTrue(file("valid-minimal.yml").ok());
    }

    @Test void manyErrorsReportedWithPathsAndNothingApplied() throws Exception {
        ValidationResult r = file("invalid-many.yml");
        for (String p : List.of("rewards.enabled", "rewards.bogus", "rewards.timezone", "rewards.daily-limit",
                "rewards.duplicate-protection.retention-days", "rewards.duplicate-protection.extra",
                "rewards.default.commands[0]", "rewards.default.commands[1]", "rewards.default.commands[2]",
                "rewards.default.items[0].material", "rewards.default.items[1].amount", "rewards.default.items[2].amount",
                "rewards.default.items[3].material", "rewards.default.money", "rewards.default.message",
                "rewards.default.broadcast", "rewards.weighted-pool.entries[0].weight",
                "rewards.weighted-pool.entries[1].broadcast", "rewards.streaks.milestones[0].days",
                "rewards.streaks.milestones[1].days"))
            assertError(r, p);
        // валидная команда ok {player} не даёт ошибки
        assertTrue(r.errors().stream().noneMatch(e -> e.startsWith("rewards.default.commands[3]")));
    }

    @Test void unknownTopLevelKey() { assertError(v("enabld: true"), "rewards.enabld"); }
    @Test void sectionWrongType() { assertError(v("default: [1]"), "rewards.default"); assertError(v("streaks: 5"), "rewards.streaks"); }
    @Test void nullValueRejected() { assertError(v("timezone:"), "rewards.timezone"); }
    @Test void booleanStrict() { assertError(v("dry-run: 1"), "rewards.dry-run"); }

    @Test void timezone() {
        assertTrue(v("timezone: UTC").ok());
        assertTrue(v("timezone: America/New_York").ok());
        assertError(v("timezone: Nowhere/City"), "rewards.timezone");
        assertError(v("timezone: 5"), "rewards.timezone");
    }

    @Test void dailyLimitBounds() {
        assertTrue(v("daily-limit: 0").ok());
        assertTrue(v("daily-limit: 1000").ok());
        assertError(v("daily-limit: -1"), "rewards.daily-limit");
        assertError(v("daily-limit: 1001"), "rewards.daily-limit");
        assertError(v("daily-limit: 2.0"), "rewards.daily-limit");
        assertError(v("daily-limit: '5'"), "rewards.daily-limit");
    }

    @Test void servicesMustBeStrings() {
        assertError(v("services: TopCraft"), "rewards.services");
        assertError(v("services: [1]"), "rewards.services[0]");
        assertError(v("services: ['']"), "rewards.services[0]");
    }

    @Test void placeholders() {
        assertTrue(v("default: { commands: ['say {player} {player}'], message: '{player}', broadcast: '{player}' }").ok());
        assertError(v("default: { commands: ['say {uuid}'] }"), "rewards.default.commands[0]");
        assertError(v("default: { message: '{}' }"), "rewards.default.message");
        assertError(v("default: { broadcast: '%player% {PLAYER}' }"), "rewards.default.broadcast");
        assertError(v("streaks: { milestones: [ { days: 1, message: '{x}' } ] }"), "rewards.streaks.milestones[0].message");
    }

    @Test void commandRules() {
        assertTrue(v("default: { commands: ['" + "a".repeat(256) + "'] }").ok());
        assertError(v("default: { commands: ['" + "a".repeat(257) + "'] }"), "rewards.default.commands[0]");
        assertError(v("default: { commands: ['/say hi'] }"), "rewards.default.commands[0]");
        assertError(v("default: { commands: [\"say a\\nsay b\"] }"), "rewards.default.commands[0]");
        assertError(v("default: { commands: ['  '] }"), "rewards.default.commands[0]");
        assertError(v("default: { commands: [5] }"), "rewards.default.commands[0]");
        assertError(v("default: { commands: 'say hi' }"), "rewards.default.commands");
        String ok20 = "default: { commands: [" + "'x', ".repeat(20) + "] }";
        assertTrue(v(ok20).ok());
        assertError(v("default: { commands: [" + "'x', ".repeat(21) + "] }"), "rewards.default.commands");
    }

    @Test void messageLength() {
        assertTrue(v("default: { message: '" + "m".repeat(256) + "' }").ok());
        assertError(v("default: { message: '" + "m".repeat(257) + "' }"), "rewards.default.message");
    }

    @Test void itemRules() {
        assertTrue(v("default: { items: [ { material: DIAMOND, amount: 2304 } ] }").ok());
        assertError(v("default: { items: [ { material: DIAMOND, amount: 0 } ] }"), "rewards.default.items[0].amount");
        assertError(v("default: { items: [ { material: DIAMOND } ] }"), "rewards.default.items[0].amount");
        assertError(v("default: { items: [ { material: diamond, amount: 1 } ] }"), "rewards.default.items[0].material");
        assertError(v("default: { items: [ { material: 5, amount: 1 } ] }"), "rewards.default.items[0].material");
        assertError(v("default: { items: [ { material: DIAMOND, amount: 1, nbt: x } ] }"), "rewards.default.items[0].nbt");
        assertError(v("default: { items: [ DIAMOND ] }"), "rewards.default.items[0]");
    }

    @Test void moneyRules() {
        assertTrue(v("default: { money: 0 }").ok());
        assertTrue(v("default: { money: 1000000000 }").ok());
        assertError(v("default: { money: -0.01 }"), "rewards.default.money");
        assertError(v("default: { money: 1000000001 }"), "rewards.default.money");
        assertError(v("default: { money: .nan }"), "rewards.default.money");
        assertError(v("default: { money: .inf }"), "rewards.default.money");
        assertError(v("default: { money: '5' }"), "rewards.default.money");
    }

    @Test void poolRules() {
        assertTrue(v("weighted-pool: { enabled: false, entries: [] }").ok());
        assertError(v("weighted-pool: { enabled: true, entries: [] }"), "rewards.weighted-pool.entries");
        assertError(v("weighted-pool: { entries: [ { commands: [] } ] }"), "rewards.weighted-pool.entries[0].weight");
        assertError(v("weighted-pool: { entries: [ { weight: 1000001 } ] }"), "rewards.weighted-pool.entries[0].weight");
        assertTrue(v("weighted-pool: { entries: [ { weight: 1000000 } ] }").ok());
        assertError(v("weighted-pool: { entries: [ { weight: 1, days: 2 } ] }"), "rewards.weighted-pool.entries[0].days");
        assertError(v("weighted-pool: { entries: [" + "{ weight: 1 }, ".repeat(51) + "] }"), "rewards.weighted-pool.entries");
        assertError(v("weighted-pool: { entries: x }"), "rewards.weighted-pool.entries");
    }

    @Test void milestoneRules() {
        assertTrue(v("streaks: { enabled: true, milestones: [ { days: 3650, repeat: true } ] }").ok());
        assertError(v("streaks: { milestones: [ { days: 0 } ] }"), "rewards.streaks.milestones[0].days");
        assertError(v("streaks: { milestones: [ { repeat: true } ] }"), "rewards.streaks.milestones[0].days");
        assertError(v("streaks: { milestones: [ { days: 1, repeat: maybe } ] }"), "rewards.streaks.milestones[0].repeat");
        assertError(v("streaks: { milestones: [" + "{ days: 1 }, ".repeat(51) + "] }"), "rewards.streaks.milestones");
    }

    @Test void queueAndDedupeRanges() {
        assertError(v("offline-queue: { max-per-player: 0 }"), "rewards.offline-queue.max-per-player");
        assertError(v("offline-queue: { expire-days: 0 }"), "rewards.offline-queue.expire-days");
        assertError(v("duplicate-protection: { retention-days: 3651 }"), "rewards.duplicate-protection.retention-days");
        assertError(v("logging: { enabled: 1 }"), "rewards.logging.enabled");
        assertError(v("logging: { level: x }"), "rewards.logging.level");
    }
}
