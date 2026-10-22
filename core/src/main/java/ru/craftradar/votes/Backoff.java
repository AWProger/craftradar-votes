package ru.craftradar.votes;

import java.util.Random;

public final class Backoff {
    public static final long BASE_MS = 5_000;
    public static final long CAP_MS = 15 * 60_000;

    private Backoff() {}

    /** attempt = число уже сделанных неудачных попыток (>=1). 5с*2^(n-1), jitter ±20%, потолок 15 мин. */
    public static long delayMs(int attempt, Random rnd) {
        int shift = Math.min(Math.max(attempt - 1, 0), 20);
        long d = Math.min(CAP_MS, BASE_MS << shift);
        double j = 0.8 + 0.4 * rnd.nextDouble();
        return Math.min(CAP_MS, (long) (d * j));
    }

    public static final long LONG_BASE_MS = 5 * 60_000;
    public static final long LONG_CAP_MS = 60 * 60_000;

    /** Для 401/403/404 и config_error: 5 мин * 2^(n-1), jitter ±20%, потолок 1 ч. */
    public static long longDelayMs(int attempt, Random rnd) {
        int shift = Math.min(Math.max(attempt - 1, 0), 20);
        long d = Math.min(LONG_CAP_MS, LONG_BASE_MS << shift);
        return Math.min(LONG_CAP_MS, (long) (d * (0.8 + 0.4 * rnd.nextDouble())));
    }
}
