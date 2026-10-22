package ru.craftradar.votes.rewards;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class WeightedPickerTest {
    private static RewardsSettings.PoolEntry e(int w) {
        return new RewardsSettings.PoolEntry(w, new RewardsSettings.RewardBlock(List.of(), List.of(), 0, "m" + w, ""));
    }

    @Test void singleEntryAlways() {
        var only = e(5);
        for (int i = 0; i < 100; i++) assertSame(only, WeightedPicker.pick(List.of(only), new Random(i)));
    }

    @Test void emptyOrNullGivesNull() {
        assertNull(WeightedPicker.pick(List.of(), new Random(1)));
        assertNull(WeightedPicker.pick(null, new Random(1)));
    }

    @Test void nonPositiveWeightRejected() {
        assertThrows(IllegalArgumentException.class, () -> WeightedPicker.pick(List.of(e(1), e(0)), new Random(1)));
        assertThrows(IllegalArgumentException.class, () -> WeightedPicker.pick(List.of(e(-3)), new Random(1)));
    }

    @Test void proportionalWithinTolerance() {
        var a = e(70); var b = e(20); var c = e(10);
        int[] n = new int[3];
        var rnd = new Random(12345);
        int N = 100_000;
        for (int i = 0; i < N; i++) {
            var p = WeightedPicker.pick(List.of(a, b, c), rnd);
            n[p == a ? 0 : p == b ? 1 : 2]++;
        }
        assertEquals(0.70, n[0] / (double) N, 0.01);
        assertEquals(0.20, n[1] / (double) N, 0.01);
        assertEquals(0.10, n[2] / (double) N, 0.01);
    }

    @Test void hugeWeightsNoOverflow() {
        // 50 x 1_000_000 = 5e7 влезает и в int, но проверяем и крайний случай: один тяжёлый и 49 лёгких
        var heavy = e(1_000_000);
        var list = new java.util.ArrayList<RewardsSettings.PoolEntry>();
        list.add(heavy);
        for (int i = 0; i < 49; i++) list.add(e(1));
        var rnd = new Random(7);
        int hits = 0, N = 20_000;
        for (int i = 0; i < N; i++) if (WeightedPicker.pick(list, rnd) == heavy) hits++;
        assertTrue(hits > N * 0.999, "heavy=" + hits);
        // все веса максимальны: равномерно, каждый индекс достижим
        var maxed = new java.util.ArrayList<RewardsSettings.PoolEntry>();
        for (int i = 0; i < 50; i++) maxed.add(new RewardsSettings.PoolEntry(1_000_000, e(1).reward()));
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<RewardsSettings.PoolEntry, Boolean>());
        for (int i = 0; i < 20_000; i++) seen.add(WeightedPicker.pick(maxed, rnd));
        assertEquals(50, seen.size());
    }

    @Test void injectedRandomBoundaries() {
        var a = e(1); var b = e(3);
        // детерминированный Random: nextLong(4) -> граница между записями
        assertSame(a, WeightedPicker.pick(List.of(a, b), fixed(0)));
        assertSame(b, WeightedPicker.pick(List.of(a, b), fixed(1)));
        assertSame(b, WeightedPicker.pick(List.of(a, b), fixed(3)));
    }

    /** Random, у которого nextLong(bound) возвращает заданное значение. */
    private static Random fixed(long v) {
        return new Random() {
            @Override public long nextLong(long bound) { return v; }
        };
    }
}
