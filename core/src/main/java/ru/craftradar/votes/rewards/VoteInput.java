package ru.craftradar.votes.rewards;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;

/** Нормализованный голос для движка. correlationId может быть null. */
public final class VoteInput {
    private final String service, username, address, correlationId;
    private final long timestampMs;

    public VoteInput(String service, String username, long timestampMs, String address, String correlationId) {
        this.service = service; this.username = username; this.timestampMs = timestampMs; this.address = address; this.correlationId = correlationId;
    }
    public String service() { return service; }
    public String username() { return username; }
    public long timestampMs() { return timestampMs; }
    public String address() { return address; }
    public String correlationId() { return correlationId; }

    /** Ключ события (§4): crdv1:<correlation_id> или sha256(service|lower(username)|timestampMs|address). */
    public String eventKey() {
        if (correlationId != null && !ru.craftradar.votes.J8.blank(correlationId)) return "crdv1:" + correlationId;
        String raw = nz(service) + "|" + nz(username).toLowerCase(Locale.ROOT) + "|" + timestampMs + "|" + nz(address);
        try {
            return ru.craftradar.votes.J8.hex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 есть в любой JVM
        }
    }
    private static String nz(String s) { return s == null ? "" : s; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof VoteInput)) return false;
        VoteInput v = (VoteInput) o;
        return timestampMs == v.timestampMs && Objects.equals(service, v.service) && Objects.equals(username, v.username)
                && Objects.equals(address, v.address) && Objects.equals(correlationId, v.correlationId);
    }
    @Override public int hashCode() { return Objects.hash(service, username, timestampMs, address, correlationId); }
    @Override public String toString() {
        return "VoteInput[service=" + service + ", username=" + username + ", timestampMs=" + timestampMs + ", address=" + address + ", correlationId=" + correlationId + "]";
    }
}
