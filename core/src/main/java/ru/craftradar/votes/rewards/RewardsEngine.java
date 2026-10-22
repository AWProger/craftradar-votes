package ru.craftradar.votes.rewards;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Решение по голосу (§3-4 контракта): фильтр сервиса, ник, дедуп, лимит, серия, расчёт наград,
 * очередь/исполнение, dry-run. Без Bukkit; работает только на главном потоке (состояние не потокобезопасно).
 * Команды и деньги: at-most-once (ATTEMPTED сохраняется на диск до исполнения).
 */
public final class RewardsEngine {
    private final Clock clock;
    private final PlayerGateway gateway;
    private final ActionExecutor executor;
    private final StateStore store;
    private final Journal journal;
    private final Random random;

    private RewardsSettings settings = RewardsSettings.disabled();
    private RewardsState state;      // null, пока не загружено
    private boolean stateFailed;     // состояние не прочиталось: награды не выдаём (иначе двойные выдачи)
    private List<String> lastErrors = java.util.Collections.<String>emptyList();

    public RewardsEngine(Clock clock, PlayerGateway gateway, ActionExecutor executor, StateStore store, Journal journal, Random random) {
        this.clock = clock; this.gateway = gateway; this.executor = executor;
        this.store = store; this.journal = journal; this.random = random;
    }

    /** Применить новые настройки (валидные). Вызывающий оставляет прежний движок при невалидном конфиге. */
    public void configure(RewardsSettings settings) {
        this.settings = settings;
        this.lastErrors = java.util.Collections.<String>emptyList();
    }

    /**
     * reload: валидный результат применяется, невалидный пишет CONFIG_INVALID с путями ошибок и оставляет
     * прежние настройки (прежний валидный движок продолжает работать). Возвращает true, если применено.
     */
    public boolean reload(ValidationResult result) {
        if (result.ok()) { configure(result.settings()); return true; }
        lastErrors = result.errors();
        for (String e : lastErrors) journal.log(RewardStatus.CONFIG_INVALID, "-", "-", e);
        return false;
    }

    /** Обработать реальный голос (главный поток). При rewards.enabled=false ничего не делает и возвращает null. */
    public RewardResult handleVote(VoteInput vote) {
        if (!settings.enabled()) return null;
        String key = vote.eventKey();
        String player = vote.username();
        log(RewardStatus.VOTE_RECEIVED, key, player, "сервис=" + vote.service());

        if (!ensureState()) return result(RewardStatus.CONFIG_INVALID, key, "состояние наград не загружено");
        if (!serviceAllowed(vote.service())) {
            return reject(RewardStatus.REJECTED_SERVICE, key, player, "сервис не в списке rewards.services");
        }
        if (player == null || !PlaceholderGuard.validPlayerName(player)) {
            return reject(RewardStatus.REJECTED_PLAYER, key, "-", "недопустимый ник");
        }
        String lower = player.toLowerCase(Locale.ROOT);
        String service = vote.service() == null ? "" : vote.service().toLowerCase(Locale.ROOT);
        long now = clock.nowMs();
        boolean dry = settings.dryRun();
        // dry-run: все помощники работают на копии, реальное состояние не меняется
        RewardsState st = dry ? scratchCopy(state) : state;

        if (!new DedupeStore(st, settings.duplicateProtection(), clock).markIfNew(key)) {
            return reject(RewardStatus.REJECTED_DUPLICATE, key, player, "событие уже обработано");
        }
        boolean withinLimit = new DailyLimiter(st, clock, settings.timezone(), settings.dailyLimit()).tryAcquire(service, lower);
        int streak = settings.streaks().enabled() ? StreakTracker.record(st, lower, now, settings.timezone()) : 0;
        if (!withinLimit) {
            if (!dry) save();
            return reject(RewardStatus.LIMIT_REACHED, key, player, "суточный лимит наград");
        }

        List<Action> actions = compute(key, player, streak);
        log(RewardStatus.COMPUTED, key, player, "действий=" + actions.size() + ", серия=" + streak);
        if (dry) {
            log(RewardStatus.DRY_RUN, key, player, "выдал бы: " + describe(actions));
            return result(RewardStatus.DRY_RUN, key, describe(actions));
        }
        if (actions.isEmpty()) { save(); return result(RewardStatus.COMPUTED, key, "нет действий"); }
        for (Action a : actions) track(a, ActionStatus.PENDING, "", now);

        if (!gateway.isOnline(player)) {
            RewardResult r = queueOffline(key, lower, actions, now);
            save();
            return r;
        }
        save(); // дедуп, серия и PENDING-действия на диске до исполнения
        Outcome o = run(actions, true);
        if (!o.retry.isEmpty()) {
            if (settings.offlineQueue().enabled() && enqueue(key, lower, o.retry, now)) o.queued = true;
            else { failAll(o.retry); o.failed += o.retry.size(); }
        }
        save();
        return finish(key, player, o);
    }

    /** /crvotes rewards test: действия для онлайн-игрока, без дедупа, серий, лимитов; состояние не трогает. */
    public RewardResult test(String player) {
        String key = "test:" + clock.nowMs();
        if (player == null || !PlaceholderGuard.validPlayerName(player)) {
            return reject(RewardStatus.REJECTED_PLAYER, key, "-", "недопустимый ник");
        }
        if (!gateway.isOnline(player)) return reject(RewardStatus.SKIPPED_OFFLINE, key, player, "игрок не в сети");
        List<Action> actions = compute(key, player, 0);
        if (settings.dryRun()) {
            log(RewardStatus.DRY_RUN, key, player, "выдал бы: " + describe(actions));
            return result(RewardStatus.DRY_RUN, key, describe(actions));
        }
        Outcome o = run(actions, false);
        if (!o.retry.isEmpty()) o.failed += o.retry.size(); // тест не ставит в очередь
        return finish(key, player, o);
    }

    /** Вход игрока: выдать очередь. Остаток (полный инвентарь, нет Vault) возвращается в очередь. */
    public void onJoin(String player) {
        if (!settings.enabled() || settings.dryRun() || player == null || !PlaceholderGuard.validPlayerName(player)) return;
        if (!ensureState() || !gateway.isOnline(player)) return;
        OfflineQueue queue = new OfflineQueue(state, settings.offlineQueue(), clock, journal);
        List<RewardsState.QueuedReward> rewards = queue.drain(player.toLowerCase(Locale.ROOT));
        if (rewards.isEmpty()) return;
        for (RewardsState.QueuedReward qr : rewards) {
            Outcome o = run(qr.actions(), true);
            if (!o.retry.isEmpty()) {
                queue.putBack(new RewardsState.QueuedReward(qr.eventKey(), qr.player(), qr.queuedAtMs(), o.retry));
                o.queued = true;
            }
            finish(qr.eventKey(), player, o);
        }
        save();
    }

    /**
     * После рестарта: ATTEMPTED без итога -> UNCERTAIN, не повторяем. Осиротевшие PENDING (не в очереди:
     * голос прерван посреди исполнения) тоже UNCERTAIN, так как неизвестно, что успело выполниться.
     */
    public void recoverOnStart() {
        if (!ensureState()) return;
        java.util.Set<String> queued = new java.util.HashSet<>();
        state.queue.values().forEach(l -> l.forEach(q -> q.actions().forEach(a -> queued.add(a.id()))));
        long now = clock.nowMs();
        List<String> dead = new ArrayList<>();
        // PENDING вне очереди: исполнение голоса прервано, а ATTEMPTED для команд и денег пишется на диск ДО вызова,
        // значит эти действия не начинались — доставляем через очередь (при входе игрока), а не теряем.
        // Предметы и сообщения ATTEMPTED не пишут: после падения между выдачей и записью DONE они возможны дважды (at-least-once).
        java.util.LinkedHashMap<String, List<Action>> resume = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, RewardsState.ActionRecord> e : state.actions.entrySet()) {
            RewardsState.ActionRecord r = e.getValue();
            if (r.status() == ActionStatus.PENDING && !queued.contains(e.getKey())) {
                String id = e.getKey();
                resume.computeIfAbsent(id.substring(0, Math.max(0, id.lastIndexOf('#'))), k -> new ArrayList<>()).add(r.action());
            }
        }
        OfflineQueue resumeQueue = new OfflineQueue(state, settings.offlineQueue(), clock, journal);
        for (Map.Entry<String, List<Action>> g : resume.entrySet()) {
            List<Action> acts = g.getValue();
            String lower = acts.get(0).player().toLowerCase(Locale.ROOT);
            if (resumeQueue.enqueue(new RewardsState.QueuedReward(g.getKey(), lower, now, acts))) {
                log(RewardStatus.QUEUED, g.getKey(), lower, "восстановлено после рестарта: действия не начинались, будут выданы при входе");
                for (Action a : acts) queued.add(a.id());
            }
        }
        for (Map.Entry<String, RewardsState.ActionRecord> e : state.actions.entrySet()) {
            RewardsState.ActionRecord r = e.getValue();
            boolean attempted = r.status() == ActionStatus.ATTEMPTED;
            if (attempted || (r.status() == ActionStatus.PENDING && !queued.contains(e.getKey()))) {
                e.setValue(new RewardsState.ActionRecord(r.action(), ActionStatus.UNCERTAIN,
                        attempted ? "рестарт после начала исполнения" : "рестарт до исполнения", now));
                dead.add(e.getKey());
                log(RewardStatus.ACTION_UNCERTAIN, e.getKey(), r.action().player(), "не повторяем (at-most-once)");
            }
        }
        if (dead.isEmpty()) { if (!resume.isEmpty()) save(); return; }
        // UNCERTAIN нельзя повторять: убрать из очередей
        java.util.Iterator<Map.Entry<String, List<RewardsState.QueuedReward>>> it = state.queue.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, List<RewardsState.QueuedReward>> e = it.next();
            List<RewardsState.QueuedReward> kept = new ArrayList<>();
            for (RewardsState.QueuedReward q : e.getValue()) {
                List<Action> left = new ArrayList<>();
                for (Action a : q.actions()) if (!dead.contains(a.id())) left.add(a);
                left = java.util.Collections.unmodifiableList(left);
                if (!left.isEmpty()) kept.add(new RewardsState.QueuedReward(q.eventKey(), q.player(), q.queuedAtMs(), left));
            }
            if (kept.isEmpty()) it.remove(); else e.setValue(kept);
        }
        save();
    }

    public String status() {
        StringBuilder sb = new StringBuilder("rewards: ")
                .append(settings.enabled() ? "включены" : "выключены")
                .append(settings.dryRun() ? ", dry-run" : "");
        if (!lastErrors.isEmpty()) {
            sb.append("\nCONFIG_INVALID, работает прежняя конфигурация. Ошибки:");
            lastErrors.forEach(e -> sb.append("\n  ").append(e));
        }
        if (stateFailed) sb.append("\nсостояние не загружено: награды не выдаются");
        else if (state != null) {
            long queued = state.queue.values().stream().mapToLong(List::size).sum();
            long uncertain = state.actions.values().stream().filter(r -> r.status() == ActionStatus.UNCERTAIN).count();
            sb.append("\nв очереди: ").append(queued).append(", неопределённых действий: ").append(uncertain)
              .append(", событий в дедупе: ").append(state.processed.size());
        }
        return sb.toString();
    }

    // ---- внутреннее ----

    private static final class Outcome {
        int done, failed; boolean queued; List<Action> retry = new ArrayList<>();
    }

    /** Исполнить действия по порядку. at-most-once: ATTEMPTED на диск до вызова; не сохранилось = не исполняем. */
    private Outcome run(List<Action> actions, boolean persist) {
        Outcome o = new Outcome();
        for (Action a : actions) {
            if (persist && a.atMostOnce()) {
                track(a, ActionStatus.ATTEMPTED, "", clock.nowMs());
                if (!save()) {
                    track(a, ActionStatus.FAILED, "состояние не сохранено, исполнение отменено", clock.nowMs());
                    log(RewardStatus.ACTION_FAILED, a.id(), a.player(), "состояние не сохранено");
                    o.failed++;
                    continue;
                }
            }
            ActionResult r;
            try { r = executor.execute(a); }
            catch (RuntimeException e) { r = ActionResult.failed("исключение: " + e.getClass().getSimpleName()); }
            if (r == null) r = ActionResult.failed("исполнитель не вернул итог");
            long now = clock.nowMs();
            switch (r.outcome()) {
                case DONE: if (persist) track(a, ActionStatus.DONE, "", now); log(RewardStatus.ACTION_DONE, a.id(), a.player(), a.type().name()); o.done++; break;
                case FAILED: if (persist) track(a, ActionStatus.FAILED, r.detail(), now); log(RewardStatus.ACTION_FAILED, a.id(), a.player(), r.detail()); o.failed++; break;
                case RETRY_LATER: {
                    if (persist) track(a, ActionStatus.PENDING, r.detail(), now);
                    log(RewardStatus.ACTION_FAILED, a.id(), a.player(), "повтор позже: " + r.detail());
                    boolean partial = a.type() == Action.Type.ITEM && r.remaining() > 0 && r.remaining() < a.amount();
                    o.retry.add(partial ? a.withAmount(r.remaining()) : a);
                    break;
                }
            }
        }
        return o;
    }

    private RewardResult finish(String key, String player, Outcome o) {
        if (o.failed > 0) return result(RewardStatus.ACTION_FAILED, key, "неудачных: " + o.failed);
        if (o.queued) { log(RewardStatus.QUEUED, key, player, "остаток в очереди: " + o.retry.size()); return result(RewardStatus.QUEUED, key, "остаток в очереди"); }
        log(RewardStatus.GRANTED, key, player, "действий: " + o.done);
        return result(RewardStatus.GRANTED, key, "");
    }

    private RewardResult queueOffline(String key, String lower, List<Action> actions, long now) {
        if (settings.offlineQueue().enabled() && enqueue(key, lower, actions, now)) {
            log(RewardStatus.QUEUED, key, lower, "игрок не в сети");
            return result(RewardStatus.QUEUED, key, "в очереди");
        }
        String why = settings.offlineQueue().enabled() ? "очередь переполнена" : "игрок не в сети, очередь выключена";
        failAll(actions);
        return reject(RewardStatus.SKIPPED_OFFLINE, key, lower, why);
    }

    private boolean enqueue(String key, String lower, List<Action> actions, long now) {
        boolean ok = new OfflineQueue(state, settings.offlineQueue(), clock, journal)
                .enqueue(new RewardsState.QueuedReward(key, lower, now, actions));
        if (!ok) log(RewardStatus.SKIPPED_OFFLINE, key, lower, "очередь переполнена, награда потеряна");
        return ok;
    }

    /** Действия, которые не выдать и не отложить: итог FAILED (не молча). */
    private void failAll(List<Action> actions) {
        long now = clock.nowMs();
        for (Action a : actions) track(a, ActionStatus.FAILED, "не выдано: нет очереди", now);
    }

    /** default + ровно одна запись пула + все сработавшие милестоуны (§3). */
    private List<Action> compute(String key, String player, int streak) {
        List<RewardsSettings.RewardBlock> blocks = new ArrayList<>();
        blocks.add(settings.defaults());
        RewardsSettings.WeightedPool pool = settings.weightedPool();
        if (pool.enabled()) {
            RewardsSettings.PoolEntry e = WeightedPicker.pick(pool.entries(), random);
            if (e != null) blocks.add(e.reward());
        }
        if (settings.streaks().enabled()) {
            for (RewardsSettings.Milestone m : StreakTracker.triggered(settings.streaks().milestones(), streak)) blocks.add(m.reward());
        }
        List<Action> out = new ArrayList<>();
        for (RewardsSettings.RewardBlock b : blocks) {
            for (String c : b.commands()) out.add(Action.command(id(key, out), player, PlaceholderGuard.substitute(c, player)));
            for (RewardsSettings.ItemSpec i : b.items()) out.add(Action.item(id(key, out), player, i.material(), i.amount()));
            if (b.money() > 0) out.add(Action.money(id(key, out), player, b.money()));
            if (!b.message().isEmpty()) out.add(Action.message(id(key, out), player, PlaceholderGuard.substitute(b.message(), player)));
            if (!b.broadcast().isEmpty()) out.add(Action.broadcast(id(key, out), player, PlaceholderGuard.substitute(b.broadcast(), player)));
        }
        return out;
    }

    private static String id(String key, List<Action> out) { return key + "#" + (out.size() + 1); }

    private static String describe(List<Action> actions) {
        StringBuilder sb = new StringBuilder();
        for (Action a : actions) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(a.type().name().toLowerCase(Locale.ROOT)).append(' ')
              .append(a.type() == Action.Type.ITEM ? a.material() + " x" + a.amount()
                      : a.type() == Action.Type.MONEY ? String.valueOf(a.money()) : a.text());
        }
        return sb.length() == 0 ? "ничего" : sb.toString();
    }

    private boolean serviceAllowed(String service) {
        if (settings.services().isEmpty()) return true;
        if (service == null) return false;
        for (String s : settings.services()) if (s.equalsIgnoreCase(service)) return true;
        return false;
    }

    private void track(Action a, ActionStatus s, String detail, long now) {
        state.actions.put(a.id(), new RewardsState.ActionRecord(a, s, detail, now));
    }

    private boolean ensureState() {
        if (state != null) return true;
        try {
            RewardsState loaded = store.load();
            state = loaded == null ? new RewardsState() : loaded;
            stateFailed = false;
            return true;
        } catch (IOException | RuntimeException e) {
            stateFailed = true;
            journal.log(RewardStatus.CONFIG_INVALID, "-", "-", "не удалось прочитать rewards-state.json: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** Сохранить состояние (с чисткой старых итогов действий). false — не удалось. */
    private boolean save() {
        if (state == null) return false;
        long cutoff = clock.nowMs() - Math.max(1, settings.duplicateProtection().retentionDays()) * 86_400_000L;
        state.actions.values().removeIf(r -> (r.status() == ActionStatus.DONE || r.status() == ActionStatus.FAILED
                || r.status() == ActionStatus.UNCERTAIN) && r.updatedMs() < cutoff);
        try { store.save(state); return true; }
        catch (IOException | RuntimeException e) {
            journal.log(RewardStatus.ACTION_FAILED, "-", "-", "не удалось сохранить rewards-state.json: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private static RewardsState scratchCopy(RewardsState s) {
        RewardsState c = new RewardsState();
        c.processed = new LinkedHashMap<>(s.processed);
        c.streaks = new LinkedHashMap<>(s.streaks);
        c.daily = new LinkedHashMap<>(s.daily);
        return c;
    }

    private RewardResult reject(RewardStatus st, String key, String player, String detail) {
        log(st, key, player, detail);
        return result(st, key, detail);
    }

    private static RewardResult result(RewardStatus st, String key, String detail) { return new RewardResult(st, key, detail); }

    private void log(RewardStatus st, String key, String player, String detail) {
        if (settings.logging()) journal.log(st, key, player == null ? "-" : player, detail);
    }
}
