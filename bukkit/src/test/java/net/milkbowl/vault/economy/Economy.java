package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

/** Тестовый двойник Vault API (подпись как в Vault 1.7). */
public interface Economy {
    double getBalance(OfflinePlayer player);
    EconomyResponse depositPlayer(OfflinePlayer player, double amount);
}
