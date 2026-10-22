package ru.craftradar.votes.rewards;

import java.util.Objects;

/** Итог обработки голоса движком (для /crvotes rewards test и логов). */
public final class RewardResult {
    private final RewardStatus status;
    private final String eventKey, detail;

    public RewardResult(RewardStatus status, String eventKey, String detail) { this.status = status; this.eventKey = eventKey; this.detail = detail; }
    public RewardStatus status() { return status; }
    public String eventKey() { return eventKey; }
    public String detail() { return detail; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof RewardResult)) return false;
        RewardResult r = (RewardResult) o;
        return status == r.status && Objects.equals(eventKey, r.eventKey) && Objects.equals(detail, r.detail);
    }
    @Override public int hashCode() { return Objects.hash(status, eventKey, detail); }
    @Override public String toString() { return "RewardResult[status=" + status + ", eventKey=" + eventKey + ", detail=" + detail + "]"; }
}
