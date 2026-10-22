package ru.craftradar.votes.rewards;

import java.util.Iterator;
import java.util.Map;

/**
 * Владелец: A11. Дедуп событий (§4) поверх RewardsState.processed (eventKey -> время принятия, мс).
 * Потокобезопасен: проверка и запись ключа атомарны под одним замком, поэтому из параллельных вызовов
 * с одним ключом true получит ровно один. Размер ограничен retention-days и жёстким потолком MAX_KEYS.
 */
public final class DedupeStore {
    /** Жёсткий потолок записей: защита файла состояния от раздувания при всплеске голосов. */
    public static final int MAX_KEYS = 100_000;
    private static final long DAY_MS = 86_400_000L;

    private final RewardsState state;
    private final RewardsSettings.DuplicateProtection settings;
    private final Clock clock;

    public DedupeStore(RewardsState state, RewardsSettings.DuplicateProtection settings, Clock clock) {
        this.state = state;
        this.settings = settings;
        this.clock = clock;
    }

    /** true — ключ новый и записан; false — дубликат. При выключенной защите всегда true. Чистит записи старше retention-days. */
    public boolean markIfNew(String eventKey) {
        if (!settings.enabled()) return true;
        if (eventKey == null || eventKey.isEmpty()) throw new IllegalArgumentException("пустой ключ события");
        synchronized (state.processed) {
            long now = clock.nowMs();
            purge(now);
            if (state.processed.containsKey(eventKey)) return false;
            state.processed.put(eventKey, now);
            // ponytail: потолок срезает самые старые по порядку вставки; при реальном всплеске >100k/срок нужен LRU по времени
            Iterator<Map.Entry<String, Long>> it = state.processed.entrySet().iterator();
            while (state.processed.size() > MAX_KEYS && it.hasNext()) { it.next(); it.remove(); }
            return true;
        }
    }

    /** Удаляет просроченные. Порядок в карте не считаем отсортированным (файл могли править), поэтому полный проход. */
    private void purge(long now) {
        long cutoff = now - (long) settings.retentionDays() * DAY_MS;
        state.processed.values().removeIf(t -> t == null || t < cutoff);
    }
}
