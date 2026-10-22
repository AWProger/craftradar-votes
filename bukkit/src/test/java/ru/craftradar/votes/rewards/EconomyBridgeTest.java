package ru.craftradar.votes.rewards;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

class EconomyBridgeTest {
    /** Заглушка Economy: хранит балансы, может отказывать. */
    static class FakeEco implements Economy {
        final Map<String, Double> bal = new HashMap<>();
        boolean refuse = false, boom = false;
        public double getBalance(OfflinePlayer p) { return bal.getOrDefault(p.getName(), 0.0); }
        public EconomyResponse depositPlayer(OfflinePlayer p, double a) {
            if (boom) throw new IllegalStateException("provider down");
            if (refuse) return new EconomyResponse(false);
            bal.merge(p.getName(), a, Double::sum);
            return new EconomyResponse(true);
        }
    }

    static OfflinePlayer player(String name) {
        return (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class},
                (pr, m, a) -> m.getName().equals("getName") ? name : null);
    }

    /** Server: известны только игроки из known; провайдер Economy — eco (null = не зарегистрирован). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Server server(Economy eco, String... known) {
        ServicesManager sm = (ServicesManager) Proxy.newProxyInstance(ServicesManager.class.getClassLoader(),
                new Class<?>[]{ServicesManager.class}, (pr, m, a) -> {
                    if (m.getName().equals("getRegistration") && eco != null && a[0] == Economy.class)
                        return new RegisteredServiceProvider(Economy.class, eco, org.bukkit.plugin.ServicePriority.Normal, null);
                    return null;
                });
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class}, (pr, m, a) -> {
            switch (m.getName()) {
                case "getServicesManager": return sm;
                case "getOfflinePlayerIfCached": // getPlayerExact -> null (никто не онлайн)
                    for (String k : known) if (k.equals(a[0])) return player(k);
                    return null;
                default: return null;
            }
        });
    }

    @Test void noProviderMeansUnavailableAndDepositFails() {
        EconomyBridge b = new EconomyBridge(server(null, "Steve"));
        assertFalse(b.available());
        assertFalse(b.deposit("Steve", 10));
        assertEquals(0, b.balance("Steve"));
    }

    @Test void depositGoesThroughProvider() {
        FakeEco eco = new FakeEco();
        EconomyBridge b = new EconomyBridge(server(eco, "Steve"));
        assertTrue(b.available());
        assertTrue(b.deposit("Steve", 12.5));
        assertTrue(b.deposit("Steve", 7.5));
        assertEquals(20.0, eco.bal.get("Steve"));
        assertEquals(20.0, b.balance("Steve"));
    }

    @Test void providerRefusalAndExceptionAreFalse() {
        FakeEco eco = new FakeEco();
        EconomyBridge b = new EconomyBridge(server(eco, "Steve"));
        eco.refuse = true;
        assertFalse(b.deposit("Steve", 5));
        eco.refuse = false; eco.boom = true;
        assertFalse(b.deposit("Steve", 5)); // исключение провайдера не пробрасывается
        assertTrue(eco.bal.isEmpty());
    }

    @Test void unknownPlayerIsNotDeposited() {
        FakeEco eco = new FakeEco();
        EconomyBridge b = new EconomyBridge(server(eco, "Steve"));
        assertFalse(b.deposit("Ghost", 5));
        assertTrue(eco.bal.isEmpty());
    }

    @Test void badAmountsRejected() {
        FakeEco eco = new FakeEco();
        EconomyBridge b = new EconomyBridge(server(eco, "Steve"));
        assertFalse(b.deposit("Steve", 0));
        assertFalse(b.deposit("Steve", -1));
        assertFalse(b.deposit("Steve", Double.NaN));
        assertFalse(b.deposit("Steve", Double.POSITIVE_INFINITY));
        assertTrue(eco.bal.isEmpty());
    }
}
