package ru.craftradar.votes.rewards;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Владелец: A6. Выдача предметов с учётом места; остаток возвращается вызывающему (очередь), на землю ничего не падает. */
public final class ItemGranter {
    private ItemGranter() {}

    /**
     * Выдать amount предметов материала material (стеки по maxStackSize); возвращает остаток, не поместившийся (0 = всё).
     * Неизвестный/AIR/legacy материал или amount <= 0 с неположительным maxStackSize: ничего не выдаём, возвращаем amount
     * (для amount <= 0 возвращается 0). Вызывать на главном потоке.
     */
    public static int give(Player player, String material, int amount) {
        if (amount <= 0) return 0;
        Material m = material == null ? null : Material.matchMaterial(material);
        if (m == null || !usable(m)) return amount;
        List<ItemStack> stacks = new ArrayList<>();
        for (int n : split(amount, m.getMaxStackSize())) stacks.add(new ItemStack(m, n));
        // addItem сам доливает неполные стеки и возвращает то, что не влезло
        Map<Integer, ItemStack> left = player.getInventory().addItem(stacks.toArray(new ItemStack[0]));
        List<Integer> notFitted = new ArrayList<>();
        for (ItemStack s : left.values()) notFitted.add(s.getAmount());
        return sum(notFitted);
    }

    // Методы Material, которых нет в старых API: isLegacy (1.13+), isAir (1.14+), isItem (1.12+). Нет метода — fallback.
    private static final Method IS_LEGACY = method("isLegacy"), IS_AIR = method("isAir"), IS_ITEM = method("isItem");

    private static Method method(String name) {
        try { return Material.class.getMethod(name); } catch (NoSuchMethodException | RuntimeException e) { return null; }
    }

    private static boolean call(Method m, Material mat, boolean fallback) {
        if (m == null) return fallback;
        try { return (Boolean) m.invoke(mat); } catch (ReflectiveOperationException | RuntimeException e) { return fallback; }
    }

    /**
     * Материал годится для награды: не legacy (1.13+), не воздух, является предметом (1.12+).
     * До 1.13 legacy-материалов как отдельного понятия нет: годится любое имя из Material этого сервера, кроме AIR.
     */
    public static boolean usable(Material m) {
        return notLegacyNotAir(m) && call(IS_ITEM, m, true);
    }

    private static boolean notLegacyNotAir(Material m) {
        if (m == null) return false;
        boolean air = IS_AIR != null ? call(IS_AIR, m, false) : m.name().equals("AIR") || m.name().endsWith("_AIR");
        return !call(IS_LEGACY, m, false) && !air;
    }

    /** Проверка имени из config.yml — как в 1.0.0: точное имя (Material.getMaterial), не legacy, не воздух. isItem не проверяется. */
    public static boolean materialExists(String name) {
        return name != null && notLegacyNotAir(Material.getMaterial(name));
    }

    /** Чистая логика: разбить amount на стеки не больше maxStack (maxStack < 1 трактуем как 1). */
    static List<Integer> split(int amount, int maxStack) {
        int max = Math.max(1, maxStack);
        List<Integer> out = new ArrayList<>();
        for (int rest = Math.max(0, amount); rest > 0; rest -= max) out.add(Math.min(rest, max));
        return out;
    }

    /** Чистая логика: суммарный остаток по не поместившимся стекам. */
    static int sum(Collection<Integer> parts) {
        int t = 0;
        for (int p : parts) t += p;
        return t;
    }
}
