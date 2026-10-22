package ru.craftradar.votes.rewards;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Неизменяемые настройки секции rewards: (контракт §2). Создаются только ConfigValidator. Java 8: бывшие record — final-классы. */
public final class RewardsSettings {
    private final boolean enabled, dryRun, logging;
    private final Set<String> services;
    private final ZoneId timezone;
    private final int dailyLimit;
    private final DuplicateProtection duplicateProtection;
    private final OfflineQueueSettings offlineQueue;
    private final RewardBlock defaults;
    private final WeightedPool weightedPool;
    private final Streaks streaks;

    public RewardsSettings(boolean enabled, boolean dryRun, Set<String> services, ZoneId timezone, int dailyLimit,
                           DuplicateProtection duplicateProtection, OfflineQueueSettings offlineQueue,
                           RewardBlock defaults, WeightedPool weightedPool, Streaks streaks, boolean logging) {
        this.enabled = enabled; this.dryRun = dryRun; this.services = copy(services); this.timezone = timezone; this.dailyLimit = dailyLimit;
        this.duplicateProtection = duplicateProtection; this.offlineQueue = offlineQueue; this.defaults = defaults;
        this.weightedPool = weightedPool; this.streaks = streaks; this.logging = logging;
    }
    public boolean enabled() { return enabled; }
    public boolean dryRun() { return dryRun; }
    public Set<String> services() { return services; }
    public ZoneId timezone() { return timezone; }
    public int dailyLimit() { return dailyLimit; }
    public DuplicateProtection duplicateProtection() { return duplicateProtection; }
    public OfflineQueueSettings offlineQueue() { return offlineQueue; }
    public RewardBlock defaults() { return defaults; }
    public WeightedPool weightedPool() { return weightedPool; }
    public Streaks streaks() { return streaks; }
    public boolean logging() { return logging; }

    static <T> List<T> copy(List<T> l) {
        for (T t : l) Objects.requireNonNull(t);   // как List.copyOf
        return Collections.unmodifiableList(new ArrayList<>(l));
    }
    static <T> Set<T> copy(Set<T> s) {
        for (T t : s) Objects.requireNonNull(t);
        return Collections.unmodifiableSet(new LinkedHashSet<>(s));
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof RewardsSettings)) return false;
        RewardsSettings s = (RewardsSettings) o;
        return enabled == s.enabled && dryRun == s.dryRun && logging == s.logging && dailyLimit == s.dailyLimit
                && services.equals(s.services) && Objects.equals(timezone, s.timezone)
                && Objects.equals(duplicateProtection, s.duplicateProtection) && Objects.equals(offlineQueue, s.offlineQueue)
                && Objects.equals(defaults, s.defaults) && Objects.equals(weightedPool, s.weightedPool) && Objects.equals(streaks, s.streaks);
    }
    @Override public int hashCode() {
        return Objects.hash(enabled, dryRun, services, timezone, dailyLimit, duplicateProtection, offlineQueue, defaults, weightedPool, streaks, logging);
    }
    @Override public String toString() {
        return "RewardsSettings[enabled=" + enabled + ", dryRun=" + dryRun + ", services=" + services + ", timezone=" + timezone
                + ", dailyLimit=" + dailyLimit + ", duplicateProtection=" + duplicateProtection + ", offlineQueue=" + offlineQueue
                + ", defaults=" + defaults + ", weightedPool=" + weightedPool + ", streaks=" + streaks + ", logging=" + logging + "]";
    }

    public static final class DuplicateProtection {
        private final boolean enabled; private final int retentionDays;
        public DuplicateProtection(boolean enabled, int retentionDays) { this.enabled = enabled; this.retentionDays = retentionDays; }
        public boolean enabled() { return enabled; }
        public int retentionDays() { return retentionDays; }
        @Override public boolean equals(Object o) {
            return o instanceof DuplicateProtection && enabled == ((DuplicateProtection) o).enabled && retentionDays == ((DuplicateProtection) o).retentionDays;
        }
        @Override public int hashCode() { return Objects.hash(enabled, retentionDays); }
        @Override public String toString() { return "DuplicateProtection[enabled=" + enabled + ", retentionDays=" + retentionDays + "]"; }
    }

    public static final class OfflineQueueSettings {
        private final boolean enabled; private final int maxPerPlayer, expireDays;
        public OfflineQueueSettings(boolean enabled, int maxPerPlayer, int expireDays) { this.enabled = enabled; this.maxPerPlayer = maxPerPlayer; this.expireDays = expireDays; }
        public boolean enabled() { return enabled; }
        public int maxPerPlayer() { return maxPerPlayer; }
        public int expireDays() { return expireDays; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof OfflineQueueSettings)) return false;
            OfflineQueueSettings q = (OfflineQueueSettings) o;
            return enabled == q.enabled && maxPerPlayer == q.maxPerPlayer && expireDays == q.expireDays;
        }
        @Override public int hashCode() { return Objects.hash(enabled, maxPerPlayer, expireDays); }
        @Override public String toString() { return "OfflineQueueSettings[enabled=" + enabled + ", maxPerPlayer=" + maxPerPlayer + ", expireDays=" + expireDays + "]"; }
    }

    public static final class ItemSpec {
        private final String material; private final int amount;
        public ItemSpec(String material, int amount) { this.material = material; this.amount = amount; }
        public String material() { return material; }
        public int amount() { return amount; }
        @Override public boolean equals(Object o) {
            return o instanceof ItemSpec && amount == ((ItemSpec) o).amount && Objects.equals(material, ((ItemSpec) o).material);
        }
        @Override public int hashCode() { return Objects.hash(material, amount); }
        @Override public String toString() { return "ItemSpec[material=" + material + ", amount=" + amount + "]"; }
    }

    /** Блок награды; broadcast используется только в default. */
    public static final class RewardBlock {
        private final List<String> commands; private final List<ItemSpec> items;
        private final double money; private final String message, broadcast;
        public RewardBlock(List<String> commands, List<ItemSpec> items, double money, String message, String broadcast) {
            this.commands = copy(commands); this.items = copy(items); this.money = money; this.message = message; this.broadcast = broadcast;
        }
        public static RewardBlock empty() { return new RewardBlock(Collections.<String>emptyList(), Collections.<ItemSpec>emptyList(), 0, "", ""); }
        public List<String> commands() { return commands; }
        public List<ItemSpec> items() { return items; }
        public double money() { return money; }
        public String message() { return message; }
        public String broadcast() { return broadcast; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof RewardBlock)) return false;
            RewardBlock b = (RewardBlock) o;
            return Double.compare(money, b.money) == 0 && commands.equals(b.commands) && items.equals(b.items)
                    && Objects.equals(message, b.message) && Objects.equals(broadcast, b.broadcast);
        }
        @Override public int hashCode() { return Objects.hash(commands, items, money, message, broadcast); }
        @Override public String toString() {
            return "RewardBlock[commands=" + commands + ", items=" + items + ", money=" + money + ", message=" + message + ", broadcast=" + broadcast + "]";
        }
    }

    public static final class PoolEntry {
        private final int weight; private final RewardBlock reward;
        public PoolEntry(int weight, RewardBlock reward) { this.weight = weight; this.reward = reward; }
        public int weight() { return weight; }
        public RewardBlock reward() { return reward; }
        @Override public boolean equals(Object o) {
            return o instanceof PoolEntry && weight == ((PoolEntry) o).weight && Objects.equals(reward, ((PoolEntry) o).reward);
        }
        @Override public int hashCode() { return Objects.hash(weight, reward); }
        @Override public String toString() { return "PoolEntry[weight=" + weight + ", reward=" + reward + "]"; }
    }

    public static final class WeightedPool {
        private final boolean enabled; private final List<PoolEntry> entries;
        public WeightedPool(boolean enabled, List<PoolEntry> entries) { this.enabled = enabled; this.entries = copy(entries); }
        public boolean enabled() { return enabled; }
        public List<PoolEntry> entries() { return entries; }
        @Override public boolean equals(Object o) {
            return o instanceof WeightedPool && enabled == ((WeightedPool) o).enabled && entries.equals(((WeightedPool) o).entries);
        }
        @Override public int hashCode() { return Objects.hash(enabled, entries); }
        @Override public String toString() { return "WeightedPool[enabled=" + enabled + ", entries=" + entries + "]"; }
    }

    public static final class Milestone {
        private final int days; private final boolean repeat; private final RewardBlock reward;
        public Milestone(int days, boolean repeat, RewardBlock reward) { this.days = days; this.repeat = repeat; this.reward = reward; }
        public int days() { return days; }
        public boolean repeat() { return repeat; }
        public RewardBlock reward() { return reward; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Milestone)) return false;
            Milestone m = (Milestone) o;
            return days == m.days && repeat == m.repeat && Objects.equals(reward, m.reward);
        }
        @Override public int hashCode() { return Objects.hash(days, repeat, reward); }
        @Override public String toString() { return "Milestone[days=" + days + ", repeat=" + repeat + ", reward=" + reward + "]"; }
    }

    public static final class Streaks {
        private final boolean enabled; private final List<Milestone> milestones;
        public Streaks(boolean enabled, List<Milestone> milestones) { this.enabled = enabled; this.milestones = copy(milestones); }
        public boolean enabled() { return enabled; }
        public List<Milestone> milestones() { return milestones; }
        @Override public boolean equals(Object o) {
            return o instanceof Streaks && enabled == ((Streaks) o).enabled && milestones.equals(((Streaks) o).milestones);
        }
        @Override public int hashCode() { return Objects.hash(enabled, milestones); }
        @Override public String toString() { return "Streaks[enabled=" + enabled + ", milestones=" + milestones + "]"; }
    }

    /** Выключенные награды (значения по умолчанию §2). */
    public static RewardsSettings disabled() {
        return new RewardsSettings(false, false, Collections.<String>emptySet(), ZoneId.of("UTC"), 0,
                new DuplicateProtection(true, 30), new OfflineQueueSettings(true, 50, 7),
                RewardBlock.empty(), new WeightedPool(false, Collections.<PoolEntry>emptyList()),
                new Streaks(false, Collections.<Milestone>emptyList()), true);
    }
}
