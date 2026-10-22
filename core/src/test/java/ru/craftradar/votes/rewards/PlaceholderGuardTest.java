package ru.craftradar.votes.rewards;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlaceholderGuardTest {
    @Test void names() {
        for (String ok : new String[]{"a", "Steve_1", "a.b", "A234567890123456"}) assertTrue(PlaceholderGuard.validPlayerName(ok), ok);
        for (String bad : new String[]{null, "", "a b", "Steve\n", "\nSteve", "a-b", "A2345678901234567", "ñ", "a;b", "a/b"})
            assertFalse(PlaceholderGuard.validPlayerName(bad), String.valueOf(bad));
    }

    @Test void unknown() {
        assertEquals(List.of(), PlaceholderGuard.unknownPlaceholders("give {player} x {player}"));
        assertEquals(List.of(), PlaceholderGuard.unknownPlaceholders(null));
        assertEquals(List.of("{uuid}", "{Player}", "{}"), PlaceholderGuard.unknownPlaceholders("{uuid} {player} {Player} {}"));
    }

    @Test void substitute() {
        assertEquals("a Steve b Steve", PlaceholderGuard.substitute("a {player} b {player}", "Steve"));
        assertThrows(IllegalArgumentException.class, () -> PlaceholderGuard.substitute("{player}", "a b"));
        assertThrows(IllegalArgumentException.class, () -> PlaceholderGuard.substitute("{player}", "{player}"));
    }

    @Test void controlChars() {
        assertTrue(PlaceholderGuard.hasLineBreakOrControl("a\nb"));
        assertFalse(PlaceholderGuard.hasLineBreakOrControl("&aПривет"));
    }
}
