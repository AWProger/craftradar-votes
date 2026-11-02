package ru.craftradar.votes.rewards;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** {service}: только message/broadcast, имя мониторинга санитизируется; в командах — ошибка конфигурации. */
class ServicePlaceholderTest {
    private static Map<String, Object> cfg(Map<String, Object> def) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.put("default", def);
        return m;
    }

    @Test void allowedInTextsNotInCommands() {
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("message", "Спасибо за голос на {service}, {player}!");
        ok.put("broadcast", "{player} проголосовал на {service}");
        assertTrue(ConfigValidator.validate(cfg(ok), m -> true).ok());
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("commands", Collections.singletonList("say {service}"));
        ValidationResult vr = ConfigValidator.validate(cfg(bad), m -> true);
        assertFalse(vr.ok());
        assertTrue(vr.errors().toString().contains("{service}"));
    }

    @Test void substituteSanitizes() {
        assertEquals("на TopCraft", PlaceholderGuard.substituteService("на {service}", "TopCraft"));
        assertEquals("на Мониторинг Майнкрафт", PlaceholderGuard.substituteService("на {service}", " Мониторинг Майнкрафт "));
        assertEquals("на мониторинг", PlaceholderGuard.substituteService("на {service}", "x{player}§c"));
        assertEquals("на мониторинг", PlaceholderGuard.substituteService("на {service}", null));
    }
}
