package ru.craftradar.votes;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import ru.craftradar.votes.api.CraftRadarVoteEvent;
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
 * Клей Bukkit. Голоса CraftRadar — опросом сайта (VotePoller, способ «Наш плагин», NuVotifier не нужен);
 * голоса других мониторингов — через NuVotifier, если он стоит (VotifierListener, только чтение события). Компилируется против самого старого поддерживаемого API (см. bukkit/build.gradle.kts),
 * в Java 8 bytecode; методы новых API — только через рефлексию с fallback (ItemGranter, EconomyBridge, additionalData).
 */
public final class CraftRadarVotesPlugin extends JavaPlugin {
    private final Object lock = new Object();   // защищает fwd/queue при reload/disable
    private DiskQueue queue;                    // ОДИН экземпляр на всё время жизни плагина
    private volatile VoteForwarder fwd;
    private volatile VotePoller poller;
    private boolean registered;
    // награды за голоса из NuVotifier (другие мониторинги): auto = да, если нет стороннего плагина наград
    private volatile boolean votifierRewards = true;
    private volatile String votifierRewardsWhy = "";
    static final String[] OTHER_REWARD_PLUGINS = {"VotingPlugin", "SuperbVote", "GAListener", "VoteRoulette"};
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
        Map<String, Object> raw = sec == null ? null : new LinkedHashMap<>((Map<String, Object>) plain(sec));
        Object hv = raw == null ? null : raw.remove("handle_votifier_votes"); // ключ клея, не движка (ConfigValidator его не знает)
        resolveVotifierRewards(hv);
        ValidationResult vr = ConfigValidator.validate(raw, ItemGranter::materialExists);
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
        if (st.enabled()) getLogger().info("Награды включены" + (st.dryRun() ? " (dry-run: только журнал)" : "") + ". Голоса через NuVotifier: " + votifierRewardsWhy + ".");
        else getLogger().info("Награды выключены (rewards.enabled: false): голоса принимаются, но встроенные награды не выдаются.");
    }

    /** rewards.handle_votifier_votes: auto (по умолчанию) | true | false. auto = false, если стоит сторонний плагин наград. */
    private void resolveVotifierRewards(Object v) {
        String other = null;
        for (String n : OTHER_REWARD_PLUGINS) if (getServer().getPluginManager().getPlugin(n) != null) { other = n; break; }
        String s = v == null ? "auto" : String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.equals("true")) {
            votifierRewards = true;
            votifierRewardsWhy = "награждаются (handle_votifier_votes: true)";
            if (other != null) getLogger().warning("Награды: handle_votifier_votes: true, но найден " + other + " — за голос через NuVotifier награду выдадут оба плагина (двойные награды). Поставьте auto или false.");
        } else if (s.equals("false")) {
            votifierRewards = false;
            votifierRewardsWhy = "не награждаются (handle_votifier_votes: false)";
        } else {
            if (!s.equals("auto")) getLogger().warning("Награды: rewards.handle_votifier_votes='" + s + "' — допустимо auto, true или false; принято auto.");
            votifierRewards = other == null;
            votifierRewardsWhy = other == null ? "награждаются (auto: стороннего плагина наград нет)"
                    : "не награждаются (auto: найден " + other + ", награды за них выдаёт он; голоса CraftRadar по-прежнему награждает CraftRadar Votes)";
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

    /**
     * Голос -> событие CraftRadarVoteEvent -> движок наград. Только главный поток. Сбой наград не влияет на мост.
     * @return true, если голос передан (событие вызвано, движок отработал или выключен)
     */
    private boolean deliverVote(String service, String user, String address, long tsMs, String voteId, CraftRadarVoteEvent.Source src) {
        CraftRadarVoteEvent ev = new CraftRadarVoteEvent(user, service, address, tsMs, voteId, src);
        try { getServer().getPluginManager().callEvent(ev); }
        catch (Throwable t) { getLogger().log(Level.WARNING, "Событие CraftRadarVoteEvent: ошибка в стороннем обработчике: " + t); }
        if (ev.isCancelled()) { getLogger().info("Голос " + user + " (" + service + "): награда отменена другим плагином через CraftRadarVoteEvent."); return true; }
        RewardsEngine r = rewards;
        if (r == null) return true;
        try { r.handleVote(new VoteInput(service, user, tsMs, address, voteId)); }
        catch (Throwable t) { getLogger().log(Level.SEVERE, "Награды: ошибка обработки голоса: " + t); }
        return true;
    }

    private static long toMs(String raw) {
        long ts;
        try { ts = Long.parseLong(raw.trim()); } catch (Exception ex) { ts = System.currentTimeMillis(); }
        return ts < 100_000_000_000L ? ts * 1000 : ts;   // секунды -> мс
    }

    /** Голос из пула CraftRadar (поток опроса): исполнить на главном потоке и дождаться; false — повторить позже (не подтверждаем). */
    private boolean acceptPulled(VotePoller.PulledVote v) throws Exception {
        if (Bukkit.isPrimaryThread()) return deliverVote(v.service, v.username, v.address, v.timestampMs(), v.id, CraftRadarVoteEvent.Source.CRAFTRADAR);
        Future<Boolean> f = Bukkit.getScheduler().callSyncMethod(this, () -> deliverVote(v.service, v.username, v.address, v.timestampMs(), v.id, CraftRadarVoteEvent.Source.CRAFTRADAR));
        try { return f.get(10, TimeUnit.SECONDS); }
        catch (TimeoutException e) { f.cancel(false); return false; }   // сервер завис: голос останется на сайте и придёт снова
    }

    /** Из VotifierListener: голос через NuVotifier (другой мониторинг). Событие не меняем и не отменяем. */
    void onVotifierVote(String service, String user, String address, String timestamp, byte[] extra) {
        VoteForwarder f = fwd;
        if (Correlation.isDiagnostic(service, extra)) {
            // диагностика: ни наград, ни пересылки как голоса
            getLogger().info("Диагностика CraftRadar: получена проверка, награды не выдаются");
            String did = Correlation.decodeDiagnostic(extra);
            if (f == null) getLogger().warning("Диагностика CraftRadar: мост не настроен, подтверждение не отправлено");
            else if (did != null) f.submitDiagnostic(did);
            return;
        }
        if (f != null) f.submit(service, user, address, timestamp, null, extra);   // статистика на CraftRadar
        if (!votifierRewards) return;                                            // награды выдаёт сторонний плагин
        final String corr = Correlation.decode(extra);
        final long ts = toMs(timestamp == null ? "" : timestamp);
        Runnable job = () -> deliverVote(service, user, address, ts, corr, CraftRadarVoteEvent.Source.VOTIFIER);
        try { if (Bukkit.isPrimaryThread()) job.run(); else getServer().getScheduler().runTask(this, job); }
        catch (Throwable t) { getLogger().log(Level.SEVERE, "Награды: " + t); }
    }

    private void registerListener() {
        if (registered) return;
        org.bukkit.plugin.PluginManager pm = getServer().getPluginManager();
        if (!pm.isPluginEnabled("Votifier") && !pm.isPluginEnabled("NuVotifier")) {
            getLogger().info("NuVotifier не установлен — это нормально: голоса CraftRadar плагин забирает с сайта сам. "
                    + "NuVotifier нужен, только если хотите получать голоса с других мониторингов.");
            return;
        }
        pm.registerEvents(new VotifierListener(this), this);   // порт NuVotifier не трогаем, событие только читаем
        registered = true;
        getLogger().info("NuVotifier найден: голоса других мониторингов пересылаются в статистику CraftRadar и здесь " + votifierRewardsWhy + ".");
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
                    c.getBoolean("allow_insecure_http", false), c.getInt("max_queue_size", 5000), c.getString("log_level", "info"),
                    c.getBoolean("pull.enabled", true), c.getInt("pull.interval_sec", 5));
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
                getLogger().severe("Связь с CraftRadar выключена до исправления config.yml и /crvotes reload: голоса CraftRadar не забираются.");
                return;
            }
            try {
                if (queue == null) queue = new DiskQueue(getDataFolder().toPath());
                VoteForwarder f = new VoteForwarder(s, queue, getLogger(), System::currentTimeMillis);
                f.start();
                fwd = f;
                if (s.pullEnabled()) {
                    VotePoller p = new VotePoller(s, getDataFolder().toPath(), getLogger(), System::currentTimeMillis, this::acceptPulled, id -> {
                        VoteForwarder ff = fwd;
                        if (ff != null) ff.submitDiagnostic(id);
                    });
                    p.start();
                    poller = p;
                }
                getLogger().info("Готово: ключ " + s.keyId() + ", сайт " + s.baseUrl()
                        + (s.pullEnabled() ? ", голоса CraftRadar забираются каждые " + s.pullIntervalSec() + " с" : ", опрос CraftRadar выключен (pull.enabled: false)")
                        + ". В очереди статистики с прошлого запуска: " + f.queueSize());
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
        VotePoller op = poller;
        poller = null;
        if (op != null) op.close();
        VoteForwarder old = fwd;
        fwd = null;
        if (old != null) old.close(); // ждёт до 5 с, недоотправленное остаётся на диске
    }

    @Override public void onDisable() { synchronized (lock) { stopForwarder(); } }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("craftradar.votes.admin")) { sender.sendMessage("Нет прав."); return true; }
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        VoteForwarder f = fwd;
        if (sub.equals("reload")) {
            load(); sender.sendMessage("CraftRadarVotes: конфигурация перезагружена" + (fwd == null ? " (связь с CraftRadar выключена, см. консоль)." : "."));
        } else if (sub.equals("flush")) {
            if (f == null) { sender.sendMessage("Связь с CraftRadar выключена: проверьте key_id/secret в config.yml."); return true; }
            VotePoller p = poller;
            if (p != null) p.pollSoon();
            int n = f.flush();
            sender.sendMessage("CraftRadarVotes: запущено отправок: " + n + ", в очереди: " + f.queueSize());
        } else if (sub.equals("requeue")) {
            if (f == null) { sender.sendMessage("Связь с CraftRadar выключена: проверьте key_id/secret в config.yml."); return true; }
            final String which = args.length > 1 ? args[1] : "all";
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                try { sender.sendMessage("CraftRadarVotes: возвращено в очередь: " + f.requeue(which)); }
                catch (Exception ex) { sender.sendMessage("CraftRadarVotes: ошибка: " + ex.getMessage()); }
            });
        } else if (sub.equals("rewards")) {
            rewardsCmd(sender, args);
        } else if (sub.equals("status")) {
            for (String l : statusLines()) sender.sendMessage(l);
        } else {
            sender.sendMessage("/crvotes status|flush|reload|requeue [all|<id>]|rewards <status|test <ник> [dry]|reload>");
        }
        return true;
    }

    /** /crvotes status: всё по-русски, без секрета. */
    private List<String> statusLines() {
        List<String> out = new java.util.ArrayList<>();
        VoteForwarder f = fwd;
        VotePoller p = poller;
        long now = System.currentTimeMillis();
        out.add("CraftRadar Votes " + getDescription().getVersion());
        if (f == null) {
            out.add("Связь с CraftRadar: выключена или не настроена — вставьте key_id и secret из кабинета в config.yml и выполните /crvotes reload.");
        } else if (p == null) {
            out.add("Опрос CraftRadar: выключен (pull.enabled: false) — голоса CraftRadar придут только через Votifier.");
        } else {
            String m = p.mode();
            out.add("Способ в кабинете: " + ("pull".equals(m) ? "«Наш плагин» — голоса CraftRadar забираются с сайта"
                    : "votifier".equals(m) ? "«Без нашего плагина» — голоса CraftRadar идут через Votifier, опрос их не приносит"
                    : "ещё не известен (сайт пока не ответил)"));
            out.add("Связь с CraftRadar: " + (p.lastOkAt() == 0 ? "успешных опросов ещё не было" : "последний опрос " + Math.max(0, (now - p.lastOkAt()) / 1000) + " с назад"));
            out.add("Голосов CraftRadar с запуска: " + p.received() + ", ждут подтверждения сайтом: " + p.pendingAcks());
            if (p.lastError() != null) out.add("Ошибка опроса: " + p.lastError());
        }
        out.add("NuVotifier: " + (registered ? "найден; его голоса " + votifierRewardsWhy : "не установлен (не обязателен; нужен только для голосов других мониторингов)"));
        out.add("Награды: " + (rewardsSettings.enabled() ? "включены" + (rewardsSettings.dryRun() ? " (dry-run)" : "") : "выключены (rewards.enabled: false)") + " — подробно: /crvotes rewards status");
        if (f != null) {
            out.add("Очередь статистики: " + f.queueSize() + ", в rejected/: " + f.rejectedCount() + " (макс. " + DiskQueue.REJECTED_CAP + ")");
            out.add("Последняя отправка статистики: " + (f.lastSuccess() == 0 ? "нет" : Instant.ofEpochMilli(f.lastSuccess())));
            if (f.lastError() != null) out.add("Ошибка статистики: " + f.lastError());
        }
        out.add("Скины: " + skinsState);
        return out;
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
