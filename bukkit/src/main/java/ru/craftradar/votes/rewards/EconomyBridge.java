package ru.craftradar.votes.rewards;

import java.lang.reflect.Method;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Vault без compile-зависимости (артефакт Vault недоступен offline): провайдер экономики берётся из
 * ServicesManager по имени класса, вызовы — рефлексией. Нет Vault/провайдера — available()=false,
 * deposit()=false (вызывающий превращает это в RETRY_LATER «нет экономики», а не в успех).
 * Провайдер запрашивается при каждом вызове: Vault и экономика могут подняться позже старта плагина.
 */
public final class EconomyBridge {
    static final String ECONOMY_CLASS = "net.milkbowl.vault.economy.Economy";

    private final Server server;

    public EconomyBridge(Server server) { this.server = server; }

    public boolean available() { return provider() != null; }

    /** 0, если экономики нет или игрок неизвестен серверу. */
    public double balance(String player) {
        Object eco = provider();
        OfflinePlayer p = resolve(player);
        if (eco == null || p == null) return 0;
        try {
            Object r;
            try { r = ecoClass().getMethod("getBalance", OfflinePlayer.class).invoke(eco, p); }
            catch (NoSuchMethodException old) { r = ecoClass().getMethod("getBalance", String.class).invoke(eco, p.getName()); } // Vault < 1.4
            return r instanceof Number ? ((Number) r).doubleValue() : 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    /** false — нет экономики, игрок неизвестен, сумма некорректна или провайдер отказал. */
    public boolean deposit(String player, double amount) {
        if (!(amount > 0) || Double.isInfinite(amount)) return false;
        Object eco = provider();
        OfflinePlayer p = resolve(player);
        if (eco == null || p == null) return false;
        try {
            Object resp;
            try { resp = ecoClass().getMethod("depositPlayer", OfflinePlayer.class, double.class).invoke(eco, p, amount); }
            catch (NoSuchMethodException old) { resp = ecoClass().getMethod("depositPlayer", String.class, double.class).invoke(eco, p.getName(), amount); } // Vault < 1.4
            if (resp == null) return false;
            Method ok = resp.getClass().getMethod("transactionSuccess");
            ok.setAccessible(true);
            return Boolean.TRUE.equals(ok.invoke(resp));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private Class<?> ecoClass() throws ClassNotFoundException {
        return Class.forName(ECONOMY_CLASS, false, EconomyBridge.class.getClassLoader());
    }

    private Object provider() {
        try {
            RegisteredServiceProvider<?> reg = server.getServicesManager().getRegistration(ecoClass());
            return reg == null ? null : reg.getProvider();
        } catch (ClassNotFoundException | RuntimeException | LinkageError e) {
            return null; // Vault не установлен
        }
    }

    /**
     * Без сетевых запросов к Mojang: онлайн-игрок или уже известный серверу (кэш, getOfflinePlayerIfCached — только Paper 1.16+).
     * На Spigot/старых версиях — только онлайн-игрок (награды и так выдаются только онлайн-игроку, см. RewardsEngine).
     */
    private OfflinePlayer resolve(String name) {
        OfflinePlayer p = server.getPlayerExact(name);
        if (p != null || IF_CACHED == null) return p;
        try { return (OfflinePlayer) IF_CACHED.invoke(server, name); } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }

    private static final Method IF_CACHED = ifCached();
    private static Method ifCached() {
        try { return Server.class.getMethod("getOfflinePlayerIfCached", String.class); } catch (NoSuchMethodException | RuntimeException e) { return null; }
    }
}
