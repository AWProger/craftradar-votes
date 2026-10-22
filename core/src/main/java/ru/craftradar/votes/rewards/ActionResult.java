package ru.craftradar.votes.rewards;

import java.util.Objects;

/**
 * Итог исполнения действия. RETRY_LATER — обратимая причина (оффлайн, нет Vault, полный инвентарь):
 * действие можно повторить. remaining — сколько предметов не поместилось (только ITEM).
 */
public final class ActionResult {
    public enum Outcome { DONE, FAILED, RETRY_LATER }

    private final Outcome outcome;
    private final String detail;
    private final int remaining;

    public ActionResult(Outcome outcome, String detail, int remaining) { this.outcome = outcome; this.detail = detail; this.remaining = remaining; }
    public Outcome outcome() { return outcome; }
    public String detail() { return detail; }
    public int remaining() { return remaining; }

    public static ActionResult done() { return new ActionResult(Outcome.DONE, "", 0); }
    public static ActionResult failed(String why) { return new ActionResult(Outcome.FAILED, why, 0); }
    public static ActionResult retryLater(String why, int remaining) { return new ActionResult(Outcome.RETRY_LATER, why, remaining); }

    @Override public boolean equals(Object o) {
        if (!(o instanceof ActionResult)) return false;
        ActionResult r = (ActionResult) o;
        return outcome == r.outcome && remaining == r.remaining && Objects.equals(detail, r.detail);
    }
    @Override public int hashCode() { return Objects.hash(outcome, detail, remaining); }
    @Override public String toString() { return "ActionResult[outcome=" + outcome + ", detail=" + detail + ", remaining=" + remaining + "]"; }
}
