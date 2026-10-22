package ru.craftradar.votes.rewards;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Всё персистентное состояние (§4). Изменяемое, не потокобезопасное: движок работает только на главном потоке.
 * Ключи player-карт — ник в нижнем регистре. Дни — ISO yyyy-MM-dd в timezone настроек.
 */
public final class RewardsState {
    public int version = 1;
    /** eventKey -> время принятия (мс): дедуп. */
    public Map<String, Long> processed = new LinkedHashMap<>();
    /** player -> серия. */
    public Map<String, StreakEntry> streaks = new LinkedHashMap<>();
    /** "service|player" -> счётчик за сутки. */
    public Map<String, DailyCounter> daily = new LinkedHashMap<>();
    /** player -> очередь невыданных наград (оффлайн/полный инвентарь). */
    public Map<String, List<QueuedReward>> queue = new LinkedHashMap<>();
    /** actionId -> запись о действии. */
    public Map<String, ActionRecord> actions = new LinkedHashMap<>();

    // Java 8: бывшие record. Имена полей = ключи JSON (совместимость rewards-state.json с 1.0.0).
    public static final class StreakEntry {
        private final String lastDay; private final int streak;
        public StreakEntry(String lastDay, int streak) { this.lastDay = lastDay; this.streak = streak; }
        public String lastDay() { return lastDay; }
        public int streak() { return streak; }
        @Override public boolean equals(Object o) {
            return o instanceof StreakEntry && streak == ((StreakEntry) o).streak && Objects.equals(lastDay, ((StreakEntry) o).lastDay);
        }
        @Override public int hashCode() { return Objects.hash(lastDay, streak); }
        @Override public String toString() { return "StreakEntry[lastDay=" + lastDay + ", streak=" + streak + "]"; }
    }
    public static final class DailyCounter {
        private final String day; private final int count;
        public DailyCounter(String day, int count) { this.day = day; this.count = count; }
        public String day() { return day; }
        public int count() { return count; }
        @Override public boolean equals(Object o) {
            return o instanceof DailyCounter && count == ((DailyCounter) o).count && Objects.equals(day, ((DailyCounter) o).day);
        }
        @Override public int hashCode() { return Objects.hash(day, count); }
        @Override public String toString() { return "DailyCounter[day=" + day + ", count=" + count + "]"; }
    }
    public static final class QueuedReward {
        private final String eventKey, player; private final long queuedAtMs; private final List<Action> actions;
        public QueuedReward(String eventKey, String player, long queuedAtMs, List<Action> actions) {
            this.eventKey = eventKey; this.player = player; this.queuedAtMs = queuedAtMs; this.actions = new ArrayList<>(actions);
        }
        public String eventKey() { return eventKey; }
        public String player() { return player; }
        public long queuedAtMs() { return queuedAtMs; }
        public List<Action> actions() { return actions; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof QueuedReward)) return false;
            QueuedReward q = (QueuedReward) o;
            return queuedAtMs == q.queuedAtMs && Objects.equals(eventKey, q.eventKey) && Objects.equals(player, q.player) && Objects.equals(actions, q.actions);
        }
        @Override public int hashCode() { return Objects.hash(eventKey, player, queuedAtMs, actions); }
        @Override public String toString() { return "QueuedReward[eventKey=" + eventKey + ", player=" + player + ", queuedAtMs=" + queuedAtMs + ", actions=" + actions + "]"; }
    }
    public static final class ActionRecord {
        private final Action action; private final ActionStatus status; private final String detail; private final long updatedMs;
        public ActionRecord(Action action, ActionStatus status, String detail, long updatedMs) {
            this.action = action; this.status = status; this.detail = detail; this.updatedMs = updatedMs;
        }
        public Action action() { return action; }
        public ActionStatus status() { return status; }
        public String detail() { return detail; }
        public long updatedMs() { return updatedMs; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof ActionRecord)) return false;
            ActionRecord r = (ActionRecord) o;
            return updatedMs == r.updatedMs && status == r.status && Objects.equals(action, r.action) && Objects.equals(detail, r.detail);
        }
        @Override public int hashCode() { return Objects.hash(action, status, detail, updatedMs); }
        @Override public String toString() { return "ActionRecord[action=" + action + ", status=" + status + ", detail=" + detail + ", updatedMs=" + updatedMs + "]"; }
    }
}
