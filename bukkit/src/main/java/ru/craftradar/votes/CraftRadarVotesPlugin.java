package ru.craftradar.votes;

import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VotifierEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import ru.craftradar.votes.rewards.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;

/**
 * Клей Bukkit/Votifier. Компилируется против самого старого поддерживаемого API (см. bukkit/build.gradle.kts),
 * в Java 8 bytecode; методы новых API — только через рефлексию с fallback (ItemGranter, EconomyBridge, additionalData).
 */
public final class CraftRadarVotesPlugin extends JavaPlugin implements Listener {
    private final Object lock = new Object();   // защищает fwd/queue при reload/disable
    private DiskQueue queue;                    // ОДИН экземпляр на всё время жизни плагина
    private volatile VoteForwarder fwd;
    private boolean registered;
    // награды: движок работает только на главном потоке; пересылка голосов от него не зависит
    private volatile RewardsEngine rewards;
    private RewardsSettings rewardsSettings = RewardsSettings.disabled();   // последние валидные настройки
    private RewardJournal rewardsJournal;
    private boolean rewardsRecovered, journalLogging;
    private EconomyBridge economy;
    // скины: модуль есть, только если skins.enabled и SkinsRestorer 15.x включён (SkinsModule ссылается на его API)
    private volatile SkinsModule skins;
    private volatile String skinsState = "выключены";

    @Override public void onEnable() {
        saveDefaultConfig();
        load();
        getCommand("crvotes").setExecutor(this);
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler public void onJoin(PlayerJoinEvent e) {
                RewardsEngine r = rewards;
                SkinsModule s = skins;
                if (s != null) s.onJoin(e.getPlayer());   // асинхронно, не мешает наградам
                if (r == null) return;
                try { r.onJoin(e.getPlayer().getName()); }
                catch (Exception ex) { getLogger().log(Level.SEVERE, "Награды: ошибка выдачи очереди при входе: " + ex); }
            }
        }, this);
    }

    // ---- локальные награды (контракт docs/REWARDS.md) ----

    /** Section -> вложенные Map/List: ConfigValidator работает с обычными структурами, getValues(false) неглубокий. */
    private static Object plain(Object o) {
        if (o instanceof ConfigurationSection) {
            ConfigurationSection cs = (ConfigurationSection) o;
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : cs.getKeys(false)) m.put(k, plain(cs.get(k)));
            return m;
        }
        if (o instanceof Map<?, ?>) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) m.put(String.valueOf(e.getKey()), plain(e.getValue()));
            return m;
        }
        if (o instanceof List<?>) {
            List<Object> out = new java.util.ArrayList<>();
            for (Object x : (List<?>) o) out.add(plain(x));
            return java.util.Collections.unmodifiableList(out);
        }
        return o;
    }

    @SuppressWarnings("unchecked")
    private void loadRewards() {
        ConfigurationSection sec = getConfig().getConfigurationSection("rewards");
        ValidationResult vr = ConfigValidator.validate(sec == null ? null : (Map<String, Object>) plain(sec), ItemGranter::materialExists);
        Clock clock = System::currentTimeMillis;
        if (!vr.ok()) {
            // невалидный reload: прежний валидный движок остаётся, ошибки уходят в журнал и в status
            for (String e : vr.errors()) getLogger().severe("Награды (CONFIG_INVALID): " + e);
            getLogger().severe("Награды не запущены/не изменены до исправления config.yml; пересылка голосов работает.");
            if (rewards == null) { rewards = buildEngine(clock, true); }
            rewards.reload(vr);
            return;
        }
        RewardsSettings st = vr.settings();
        RewardsEngine e = buildEngine(clock, st.logging());
        e.configure(st);
        rewardsSettings = st;
        if (st.enabled() && !rewardsRecovered) { e.recoverOnStart(); rewardsRecovered = true; }
        rewards = e;
        if (st.enabled()) {
            org.bukkit.plugin.PluginManager pm = getServer().getPluginManager();
            for (String other : new String[]{"VotingPlugin", "SuperbVote"})
                if (pm.getPlugin(other) != null)
                    getLogger().warning("Награды: rewards.enabled=true, но найден " + other + ": при выдаче наград обоими плагинами за один голос награды будут двойными. "
                            + "Оставьте rewards.enabled: false, если награды выдаёт " + other + ".");
            getLogger().info("Награды включены" + (st.dryRun() ? " (dry-run: только журнал)" : "") + ".");
        }
    }

    private RewardsEngine buildEngine(Clock clock, boolean logging) {
        if (rewardsJournal == null || journalLogging != logging) {
            journalLogging = logging;
            rewardsJournal = new RewardJournal(getDataFolder().toPath(), clock, logging);
        }
        if (economy == null) economy = new EconomyBridge(getServer());
        PlayerGateway gw = new BukkitGateway(economy);
        ActionExecutor ex = new CommandExecutor(gw, cmd -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd),
                mainThread());
        return new RewardsEngine(clock, gw, ex, new JsonStateStore(getDataFolder().toPath()), rewardsJournal, new Random());
    }

    /** На главном потоке — сразу, иначе callSyncMethod с ожиданием до 10 с (перенесено из core: там нет Bukkit). */
    private CommandExecutor.MainThread mainThread() {
        final JavaPlugin plugin = this;
        return new CommandExecutor.MainThread() {
            @Override public <T> T call(Supplier<T> task) throws Exception {
                if (Bukkit.isPrimaryThread()) return task.get();
                Future<T> f = Bukkit.getScheduler().callSyncMethod(plugin, task::get);
                try {
                    return f.get(10, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    f.cancel(false); // не начатая задача не выполнится позже (иначе at-most-once нарушился бы)
                    throw e;
                }
            }
        };
    }

    /** Votifier classic (1.9) не знает additionalData (это NuVotifier v2): тогда null, как у голоса v1. */
    private static byte[] additionalData(Vote v) {
        try { return v.getAdditionalData(); } catch (NoSuchMethodError | AbstractMethodError e) { return null; }
    }

    /** Тонкий Bukkit-клей для движка; вызывается на главном потоке. */
    private static final class BukkitGateway implements PlayerGateway {
        private final EconomyBridge eco;
        BukkitGateway(EconomyBridge eco) { this.eco = eco; }
        @Override public boolean isOnline(String p) { return Bukkit.getPlayerExact(p) != null; }
        @Override public int giveItems(String p, String material, int amount) {
            Player pl = Bukkit.getPlayerExact(p);
            return pl == null ? amount : ItemGranter.give(pl, material, amount);
        }
        @Override public boolean hasEconomy() { return eco.available(); }
        @Override public double balance(String p) { return eco.balance(p); }
        @Override public boolean deposit(String p, double amount) { return eco.deposit(p, amount); }
        @Override public void sendMessage(String p, String text) {
            Player pl = Bukkit.getPlayerExact(p);
            if (pl != null) pl.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
        }
        @Override public void broadcast(String text) { Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', text)); }
    }

    /** Голос -> движок. Вызывается ПОСЛЕ пересылки; любой сбой наград не влияет на мост. */
    private void rewardsVote(Vote v) {
        RewardsEngine r = rewards;
        if (r == null) return;
        long ts;
        try { ts = Long.parseLong(v.getTimeStamp().trim()); } catch (Exception ex) { ts = System.currentTimeMillis(); }
        if (ts < 100_000_000_000L) ts *= 1000;   // секунды -> мс
        VoteInput in = new VoteInput(v.getServiceName(), v.getUsername(), ts, v.getAddress(), correlationOf(v));
        Runnable job = () -> {
            try { r.handleVote(in); }
            catch (Throwable t) { getLogger().log(Level.SEVERE, "Награды: ошибка обработки голоса: " + t); }
        };
        if (Bukkit.isPrimaryThread()) job.run(); else getServer().getScheduler().runTask(this, job);
    }

    private static String correlationOf(Vote v) {
        return Correlation.decode(additionalData(v));
    }

    private void registerListener() {
        if (registered) return;
        org.bukkit.plugin.PluginManager pm = getServer().getPluginManager();
        if (!pm.isPluginEnabled("Votifier") && !pm.isPluginEnabled("NuVotifier")) {
            getLogger().warning("NuVotifier/Votifier не найден или не включён: голоса получать неоткуда, мост не будет принимать события. "
                    + "Установите NuVotifier и перезапустите сервер.");
            return;
        }
        pm.registerEvents(this, this);
        registered = true;
    }

    private void load() {
        synchronized (lock) {
            stopForwarder();
            reloadConfig();
            loadRewards();
            loadSkins();
            registerListener();   // награды получают голос и при выключенном/ненастроенном мосте (fwd тогда null)
            FileConfiguration c = getConfig();
            Settings s = new Settings(c.getBoolean("enabled", true), c.getString("api_url", ""),
                    c.getString("key_id", ""), c.getString("secret", ""),
                    c.getBoolean("allow_insecure_http", false), c.getInt("max_queue_size", 5000), c.getString("log_level", "info"));
            String ll = s.logLevel() == null ? "" : s.logLevel().toLowerCase();
            Level lvl = ll.equals("debug") ? Level.FINE : ll.equals("warn") ? Level.WARNING : ll.equals("error") ? Level.SEVERE : Level.INFO;
            getLogger().setLevel(lvl);
            for (Handler h : getLogger().getHandlers()) h.setLevel(lvl);
            if (!s.enabled()) { getLogger().info("Отключён в config.yml (enabled: false)."); return; }
            if (s.apiUrlHadSuffix())
                getLogger().warning("api_url содержит /api/vote_ingest.php — это лишнее, хвост убран автоматически: " + s.baseUrl());
            List<String> errs = s.validate();
            if (!errs.isEmpty()) {
                for (String e : errs) getLogger().severe("Конфигурация: " + e);
                getLogger().severe("Мост выключен до исправления config.yml и /crvotes reload.");
                return;
            }
            try {
                if (queue == null) queue = new DiskQueue(getDataFolder().toPath());
                registerListener();
                VoteForwarder f = new VoteForwarder(s, queue, getLogger(), System::currentTimeMillis);
                f.start();
                fwd = f;
                getLogger().info("Готово: " + s.endpoint() + ", в очереди с прошлого запуска: " + f.queueSize());
            } catch (Exception e) {
                getLogger().severe("Не удалось инициализировать очередь: " + e);
            }
        }
    }

    /** Модуль skins (README, раздел «Скины CraftRadar»): без SkinsRestorer или с невалидным адресом — спит с понятным логом. */
    private void loadSkins() {
        skins = null;
        FileConfiguration c = getConfig();
        if (!c.getBoolean("skins.enabled", false)) { skinsState = "выключены (skins.enabled: false)"; return; }
        String base = String.valueOf(c.getString("skins.api_url", "https://craftradar.ru")).trim().replaceAll("/+$", "");
        if (!base.startsWith("https://") && !(base.startsWith("http://") && c.getBoolean("allow_insecure_http", false))) {
            skinsState = "выключены: skins.api_url должен начинаться с https://";
            getLogger().severe("Скины: " + skinsState);
            return;
        }
        if (!getServer().getPluginManager().isPluginEnabled("SkinsRestorer")) {
            skinsState = "спят: SkinsRestorer не установлен";
            getLogger().info("Скины: skins.enabled=true, но SkinsRestorer не найден — модуль спит. Установите SkinsRestorer 15.x и перезапустите сервер.");
            return;
        }
        int timeout = Math.max(500, Math.min(10_000, c.getInt("skins.timeout_ms", 3000)));
        int minutes = Math.max(1, Math.min(1440, c.getInt("skins.cache_minutes", 10)));
        try {
            skins = new SkinsModule(this, new SkinLookup(base, timeout, minutes * 60_000L, System::currentTimeMillis));
            skinsState = "включены: " + base + ", кэш " + minutes + " мин";
            getLogger().info("Скины CraftRadar " + skinsState + " (через SkinsRestorer; выбранный игроком /skin не трогаем).");
        } catch (IllegalStateException | LinkageError e) {   // LinkageError: SkinsRestorer 14 и старше — другое API
            skinsState = "спят: API SkinsRestorer 15.x недоступно";
            getLogger().warning("Скины: " + skinsState + " (" + e + "). Нужен SkinsRestorer 15.x; в режиме прокси включите API на бэкенде по инструкции SkinsRestorer.");
        }
    }

    private void stopForwarder() {
        VoteForwarder old = fwd;
        fwd = null;
        if (old != null) old.close(); // ждёт до 5 с, недоотправленное остаётся на диске
    }

    @Override public void onDisable() { synchronized (lock) { stopForwarder(); } }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onVote(VotifierEvent e) {
        Vote v = e.getVote();
        VoteForwarder f = fwd;
        byte[] extra = additionalData(v);
        if (Correlation.isDiagnostic(v.getServiceName(), extra)) {
            // диагностика: ни наград, ни пересылки как голоса
            getLogger().info("Диагностика CraftRadar: получена проверка, награды не выдаются");
            String did = Correlation.decodeDiagnostic(extra);
            if (f == null) getLogger().warning("Диагностика CraftRadar: мост не настроен, подтверждение не отправлено");
            else if (did != null) f.submitDiagnostic(did);
            return;
        }
        if (f != null) f.submit(v.getServiceName(), v.getUsername(), v.getAddress(), v.getTimeStamp(), null, extra);
        try { rewardsVote(v); }
        catch (Throwable t) { getLogger().log(Level.SEVERE, "Награды: " + t); }
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("craftradar.votes.admin")) { sender.sendMessage("Нет прав."); return true; }
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        VoteForwarder f = fwd;
        if (sub.equals("reload")) {
            load(); sender.sendMessage("CraftRadarVotes: конфигурация перезагружена" + (fwd == null ? " (мост выключен, см. консоль)." : "."));
        } else if (sub.equals("flush")) {
            if (f == null) { sender.sendMessage("Мост выключен."); return true; }
            int n = f.flush();
            sender.sendMessage("CraftRadarVotes: запущено отправок: " + n + ", в очереди: " + f.queueSize());
        } else if (sub.equals("requeue")) {
            if (f == null) { sender.sendMessage("Мост выключен."); return true; }
            final String which = args.length > 1 ? args[1] : "all";
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                try { sender.sendMessage("CraftRadarVotes: возвращено в очередь: " + f.requeue(which)); }
                catch (Exception ex) { sender.sendMessage("CraftRadarVotes: ошибка: " + ex.getMessage()); }
            });
        } else if (sub.equals("rewards")) {
            rewardsCmd(sender, args);
        } else if (sub.equals("status")) {
            sender.sendMessage("Скины: " + skinsState);
            if (f == null) { sender.sendMessage("CraftRadarVotes: мост выключен или не настроен."); return true; }
            sender.sendMessage("Очередь: " + f.queueSize() + ", в rejected/: " + f.rejectedCount() + " (макс. " + DiskQueue.REJECTED_CAP + ")");
            sender.sendMessage("Последний успех: " + (f.lastSuccess() == 0 ? "нет" : Instant.ofEpochMilli(f.lastSuccess())));
            sender.sendMessage("Последняя ошибка: " + (f.lastError() == null ? "нет" : f.lastError()));
        } else {
            sender.sendMessage("/crvotes status|flush|reload|requeue [all|<id>]|rewards <status|test <ник> [dry]|reload>");
        }
        return true;
    }

    private void rewardsCmd(CommandSender sender, String[] args) {
        String a = args.length > 1 ? args[1].toLowerCase() : "status";
        RewardsEngine r = rewards;
        if (a.equals("reload")) {
            synchronized (lock) { reloadConfig(); loadRewards(); }
            sender.sendMessage("CraftRadarVotes: награды перезагружены.");
            for (String l : rewards.status().split("\n")) sender.sendMessage(l);
        } else if (a.equals("test")) {
            if (args.length < 3) { sender.sendMessage("/crvotes rewards test <ник> [dry]"); return; }
            boolean dry = args.length > 3 && args[3].equalsIgnoreCase("dry");
            RewardsEngine e = r;
            if (dry) {   // отдельный движок с dry-run: основной не меняется, состояние теста не трогает
                e = buildEngine(System::currentTimeMillis, rewardsSettings.logging());
                RewardsSettings s0 = rewardsSettings;
                e.configure(new RewardsSettings(s0.enabled(), true, s0.services(), s0.timezone(), s0.dailyLimit(),
                        s0.duplicateProtection(), s0.offlineQueue(), s0.defaults(), s0.weightedPool(), s0.streaks(), s0.logging()));
            }
            RewardResult res = e.test(args[2]);
            sender.sendMessage("Тест наград: " + res.status() + (res.detail() == null || res.detail().isEmpty() ? "" : " — " + res.detail()));
        } else {
            for (String l : r.status().split("\n")) sender.sendMessage(l);
            if (rewardsJournal != null && rewardsJournal.lastError() != null) sender.sendMessage("Ошибка журнала: " + rewardsJournal.lastError());
        }
    }
}
