package ru.craftradar.votes.rewards;

import java.util.Random;

/** Доступ тестов другого пакета к package-private алгоритму Java 8. */
public final class WeightedPickerAccess {
    private WeightedPickerAccess() {}
    public static long java8(Random r, long bound) { return WeightedPicker.java8BoundedNextLong(r, bound); }
}
