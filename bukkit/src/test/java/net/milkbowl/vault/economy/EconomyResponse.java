package net.milkbowl.vault.economy;

public class EconomyResponse {
    private final boolean ok;
    public EconomyResponse(boolean ok) { this.ok = ok; }
    public boolean transactionSuccess() { return ok; }
}
