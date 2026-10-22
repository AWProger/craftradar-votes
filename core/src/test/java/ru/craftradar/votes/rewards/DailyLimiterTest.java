package ru.craftradar.votes.rewards;

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class DailyLimiterTest {
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private long now;
    private final Clock clock = () -> now;
    private final RewardsState state = new RewardsState();

    private long at(String iso) { return ZonedDateTime.parse(iso + "[Europe/Moscow]").toInstant().toEpochMilli(); }
    private DailyLimiter limiter(int limit) { return new DailyLimiter(state, clock, MSK, limit); }

    @Test
    void limitIsEnforcedPerPlayerAndService() {
        now = at("2026-03-10T12:00:00+03:00");
        var l = limiter(2);
        assertTrue(l.tryAcquire("top", "Steve"));
        assertTrue(l.tryAcquire("top", "Steve"));
        assertFalse(l.tryAcquire("top", "Steve"));
        assertTrue(l.tryAcquire("top", "Alex"), "другой игрок");
        assertTrue(l.tryAcquire("other", "Steve"), "другой сервис");
    }

    @Test
    void caseVariationDoesNotBypass() {
        now = at("2026-03-10T12:00:00+03:00");
        var l = limiter(1);
        assertTrue(l.tryAcquire("Top", "Steve"));
        assertFalse(l.tryAcquire("top", "STEVE"));
        assertFalse(l.tryAcquire("TOP", "steve"));
    }

    @Test
    void resetsAtLocalMidnightNotUtc() {
        var l = limiter(1);
        now = at("2026-03-10T23:59:59+03:00");
        assertTrue(l.tryAcquire("s", "p"));
        now = at("2026-03-10T23:59:59.9+03:00");
        assertFalse(l.tryAcquire("s", "p"));
        now = at("2026-03-11T00:00:00+03:00");
        assertTrue(l.tryAcquire("s", "p"), "новые сутки в timezone");
        assertFalse(l.tryAcquire("s", "p"));
    }

    @Test
    void rejectedAttemptsDoNotIncrementAndSurviveRestart() {
        now = at("2026-03-10T10:00:00+03:00");
        assertTrue(limiter(1).tryAcquire("s", "p"));
        assertFalse(limiter(1).tryAcquire("s", "p")); // новый экземпляр на том же state = рестарт
        assertEquals(1, state.daily.get("s|p").count());
    }

    @Test
    void clockGoingBackDoesNotResetCounter() {
        now = at("2026-03-10T10:00:00+03:00");
        var l = limiter(1);
        assertTrue(l.tryAcquire("s", "p"));
        now = at("2026-03-09T10:00:00+03:00");
        assertFalse(l.tryAcquire("s", "p"));
    }

    @Test
    void zeroMeansUnlimitedAndLeavesStateUntouched() {
        now = at("2026-03-10T10:00:00+03:00");
        var l = limiter(0);
        for (int i = 0; i < 100; i++) assertTrue(l.tryAcquire("s", "p"));
        assertTrue(state.daily.isEmpty());
    }

    @Test
    void corruptStoredDayIsTreatedAsStale() {
        now = at("2026-03-10T10:00:00+03:00");
        state.daily.put("s|p", new RewardsState.DailyCounter("мусор", 99));
        assertTrue(limiter(1).tryAcquire("s", "p"));
    }
}
