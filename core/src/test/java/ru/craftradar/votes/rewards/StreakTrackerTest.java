package ru.craftradar.votes.rewards;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreakTrackerTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final ZoneId NY = ZoneId.of("America/New_York");

    private static long ms(String isoLocal, ZoneId z) { return LocalDateTime.parse(isoLocal).atZone(z).toInstant().toEpochMilli(); }

    private static RewardsSettings.Milestone ms(int days, boolean repeat) {
        return new RewardsSettings.Milestone(days, repeat, RewardsSettings.RewardBlock.empty());
    }

    @Test void consecutiveDaysGrow_sameDayUnchanged_gapResets() {
        var st = new RewardsState();
        assertEquals(1, StreakTracker.record(st, "Steve", ms("2026-03-01T10:00", UTC), UTC));
        assertEquals(1, StreakTracker.record(st, "Steve", ms("2026-03-01T23:59", UTC), UTC));
        assertEquals(2, StreakTracker.record(st, "Steve", ms("2026-03-02T00:00", UTC), UTC));
        assertEquals(3, StreakTracker.record(st, "steve", ms("2026-03-03T12:00", UTC), UTC));
        assertEquals(1, StreakTracker.record(st, "STEVE", ms("2026-03-05T12:00", UTC), UTC));
        assertEquals(1, st.streaks.size());
    }

    @Test void midnightBoundaryUsesZoneNotUtc() {
        var st = new RewardsState();
        // 21:30 UTC = 00:30 МСК следующего дня
        StreakTracker.record(st, "a", ms("2026-03-01T20:00", UTC), MSK);
        assertEquals(2, StreakTracker.record(st, "a", ms("2026-03-01T21:30", UTC), MSK));
        assertEquals("2026-03-02", st.streaks.get("a").lastDay());
    }

    @Test void dstSpringForwardAndFallBackKeepStreak() {
        var st = new RewardsState();
        StreakTracker.record(st, "a", ms("2026-03-07T23:30", NY), NY);
        assertEquals(2, StreakTracker.record(st, "a", ms("2026-03-08T00:10", NY), NY)); // сутки 23 ч
        assertEquals(3, StreakTracker.record(st, "a", ms("2026-03-09T23:50", NY), NY));
        var st2 = new RewardsState();
        StreakTracker.record(st2, "b", ms("2026-10-31T00:30", NY), NY);
        assertEquals(2, StreakTracker.record(st2, "b", ms("2026-11-01T23:30", NY), NY)); // сутки 25 ч
        assertEquals(3, StreakTracker.record(st2, "b", ms("2026-11-02T00:05", NY), NY));
    }

    @Test void clockGoingBackwardsDoesNotBreakOrChangeStreak() {
        var st = new RewardsState();
        StreakTracker.record(st, "a", ms("2026-03-01T10:00", UTC), UTC);
        StreakTracker.record(st, "a", ms("2026-03-02T10:00", UTC), UTC);
        assertEquals(2, StreakTracker.record(st, "a", ms("2026-02-20T10:00", UTC), UTC));
        assertEquals("2026-03-02", st.streaks.get("a").lastDay());
    }

    @Test void corruptedStateRestartsStreak() {
        var st = new RewardsState();
        st.streaks.put("a", new RewardsState.StreakEntry("мусор", 5));
        assertEquals(1, StreakTracker.record(st, "a", ms("2026-03-01T10:00", UTC), UTC));
        st.streaks.put("b", new RewardsState.StreakEntry(null, 5));
        assertEquals(1, StreakTracker.record(st, "b", ms("2026-03-01T10:00", UTC), UTC));
    }

    @Test void restartContinuesFromPersistedEntry() {
        var st = new RewardsState();
        st.streaks.put("a", new RewardsState.StreakEntry("2026-03-01", 6)); // как после load() из rewards-state.json
        assertEquals(7, StreakTracker.record(st, "a", ms("2026-03-02T08:00", UTC), UTC));
    }

    @Test void milestonesExactAndRepeat() {
        var once7 = ms(7, false);
        var every3 = ms(3, true);
        var all = List.of(once7, every3);
        assertEquals(List.of(), StreakTracker.triggered(all, 2));
        assertEquals(List.of(every3), StreakTracker.triggered(all, 3));
        assertEquals(List.of(every3), StreakTracker.triggered(all, 6));
        assertEquals(List.of(once7), StreakTracker.triggered(all, 7)); // 7 % 3 != 0, repeat=false не срабатывает на 14
        assertEquals(List.of(), StreakTracker.triggered(List.of(once7), 14));
        assertEquals(List.of(ms(7, true), every3), StreakTracker.triggered(List.of(once7, ms(7, true), every3), 21));
        assertEquals(List.of(once7, ms(7, true)), StreakTracker.triggered(List.of(once7, ms(7, true), every3), 7));
    }

    @Test void milestoneEdgeInputs() {
        assertEquals(List.of(), StreakTracker.triggered(null, 5));
        assertEquals(List.of(), StreakTracker.triggered(List.of(ms(1, true)), 0));
        assertEquals(List.of(ms(1, true)), StreakTracker.triggered(List.of(ms(1, true)), 4));
    }
}
