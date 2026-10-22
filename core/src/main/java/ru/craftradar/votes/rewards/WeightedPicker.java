package ru.craftradar.votes.rewards;

import java.util.List;
import java.util.Random;

/** Выбор одной записи пула пропорционально весу (§3). Владелец: A8. */
public final class WeightedPicker {
    private WeightedPicker() {}

    /**
     * Ровно одна запись пропорционально weight; пустой список -> null (движок пропускает пул).
     * Сумма в long: 50 x 1_000_000 не переполняет int. Вес <= 0 -> IllegalArgumentException
     * (валидатор такое не пропускает; тихо игнорировать нельзя, исказило бы шансы).
     */
    public static RewardsSettings.PoolEntry pick(List<RewardsSettings.PoolEntry> entries, Random random) {
        if (entries == null || entries.isEmpty()) return null;
        long total = 0;
        for (RewardsSettings.PoolEntry e : entries) {
            if (e.weight() <= 0) throw new IllegalArgumentException("weight должен быть > 0: " + e.weight());
            total += e.weight();
        }
        long r = boundedNextLong(random, total);
        for (RewardsSettings.PoolEntry e : entries) {
            r -= e.weight();
            if (r < 0) return e;
        }
        throw new IllegalStateException("недостижимо: r < total");
    }

    /**
     * = Random.nextLong(bound) из Java 17 (RandomSupport.boundedNextLong), байт в байт: та же последовательность
     * при том же seed, что и в 1.0.0 (Java 21), но работает на Java 8.
     */
    private static final java.lang.reflect.Method NEXT_LONG_BOUND = findNextLongBound();

    private static java.lang.reflect.Method findNextLongBound() {
        try { return Random.class.getMethod("nextLong", long.class); } catch (NoSuchMethodException e) { return null; }
    }

    static long boundedNextLong(Random rng, long bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
        if (NEXT_LONG_BOUND != null) {   // Java 17+: настоящий (в т.ч. переопределённый) nextLong(bound)
            try { return (Long) NEXT_LONG_BOUND.invoke(rng, bound); }
            catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
                throw new IllegalStateException(e.getCause());
            } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
        return java8BoundedNextLong(rng, bound);
    }

    /** Java 8: тот же алгоритм, что RandomSupport.boundedNextLong (проверяется тестом против Random.nextLong(bound) Java 17+). */
    static long java8BoundedNextLong(Random rng, long bound) {
        final long m = bound - 1;
        long r = rng.nextLong();
        if ((bound & m) == 0L) {
            r &= m;
        } else {
            for (long u = r >>> 1; u + m - (r = u % bound) < 0L; u = rng.nextLong() >>> 1) { }
        }
        return r;
    }
}
