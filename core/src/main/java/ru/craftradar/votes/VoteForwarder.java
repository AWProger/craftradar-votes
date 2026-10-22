package ru.craftradar.votes;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Ядро: очередь на диске + асинхронная отправка (до 4 запросов одновременно) + ретраи. Не зависит от Bukkit. */
public final class VoteForwarder {
    static final long EXPIRE_MS = 7L * 24 * 3600_000;
    static final long WARN_EVERY_MS = 10 * 60_000;
    static final int IN_FLIGHT = 4;

    private final Settings cfg;
    private final DiskQueue queue;
    private final Logger log;
    static final int CONNECT_TIMEOUT_MS = 5_000, READ_TIMEOUT_MS = 10_000;
    private final Random rnd = new Random();
    private final LongSupplier clock;
    private final Map<String, Long> warned = new ConcurrentHashMap<>();
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();
    private final Semaphore permits = new Semaphore(IN_FLIGHT);
    private final ExecutorService workers = Executors.newFixedThreadPool(IN_FLIGHT, daemon("CraftRadarVotes-send"));
    private volatile ScheduledExecutorService sched;
    private volatile boolean chain;
    private volatile long holdUntil;   // после 429: пауза для всей очереди
    private volatile String lastError = null;
    private volatile long lastSuccess = 0;
    private final boolean debug;

    public VoteForwarder(Settings cfg, DiskQueue queue, Logger log, LongSupplier clock) {
        this.cfg = cfg; this.queue = queue; this.log = log; this.clock = clock;
        this.debug = "debug".equalsIgnoreCase(cfg.logLevel());
    }

    private static ThreadFactory daemon(String name) {
        return r -> { Thread t = new Thread(r, name); t.setDaemon(true); return t; };
    }

    public void start() {
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(daemon("CraftRadarVotes"));
        sched = s; chain = true;
        s.scheduleWithFixedDelay(this::dispatchSafe, 1, 2, TimeUnit.SECONDS);
    }

    /** Останавливает потоки и ждёт до 5 с; недоотправленное остаётся в очереди на диске. */
    public void close() {
        ScheduledExecutorService s = sched;
        if (s != null) s.shutdownNow();
        workers.shutdownNow();
        try {
            if (s != null) s.awaitTermination(5, TimeUnit.SECONDS);
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Вызывается с главного потока: быстрая проверка размера в памяти + одна маленькая запись файла, без сети. */
    public boolean submit(String service, String username, String address, String timestamp, String uuid, byte[] additionalData) {
        if (!cfg.enabled()) return false;
        if (username == null || J8.blank(username)) { log.warning("CraftRadarVotes: голос без username пропущен"); return false; }
        JsonObject o = new JsonObject();
        o.addProperty("service_name", service);
        o.addProperty("username", username);
        if (uuid != null) o.addProperty("uuid", uuid);
        o.addProperty("address", address);
        o.addProperty("timestamp", timestamp);
        o.add("external_id", com.google.gson.JsonNull.INSTANCE);
        o.addProperty("correlation_id", Correlation.decode(additionalData));
        String body = new Gson().newBuilder().serializeNulls().create().toJson(o);
        try {
            if (queue.size() >= cfg.maxQueueSize()) {
                warn("full", "очередь переполнена (max_queue_size=" + cfg.maxQueueSize() + "), новые голоса отбрасываются");
                return false;
            }
            boolean added = queue.add(DiskQueue.key(service, username, timestamp), body, clock.getAsLong());
            if (!added) debug("дубликат события в очереди, пропущен");
            ScheduledExecutorService ex = sched;
            if (added && ex != null) {
                try { ex.execute(this::dispatchSafe); } catch (RejectedExecutionException ignored) { /* выключаемся: событие уже на диске */ }
            }
            return added;
        } catch (IOException e) {
            lastError = "очередь: " + e.getMessage();
            log.severe("CraftRadarVotes: не удалось записать голос в очередь: " + e.getMessage());
            return false;
        }
    }

    static final String DIAG_PREFIX = "{\"event_type\":\"diagnostic_ack\"";

    static String diagBody(String id, long nowMs) {
        JsonObject o = new JsonObject();
        o.addProperty("event_type", "diagnostic_ack");
        o.addProperty("diagnostic_id", id);
        o.addProperty("timestamp", Long.toString(nowMs));
        return o.toString();
    }

    /** Подтверждение диагностики в ту же очередь (ключ diag:<id>, с голосами не пересекается). Не считается голосом. */
    public boolean submitDiagnostic(String id) {
        if (!cfg.enabled() || id == null || !id.matches("[0-9a-f]{16}")) return false;
        try {
            boolean added = queue.add(DiskQueue.key("diag:", id, ""), diagBody(id, clock.getAsLong()), clock.getAsLong());
            ScheduledExecutorService ex = sched;
            if (added && ex != null) {
                try { ex.execute(this::dispatchSafe); } catch (RejectedExecutionException ignored) { }
            }
            return added;
        } catch (IOException e) {
            lastError = "очередь: " + e.getMessage();
            log.severe("CraftRadarVotes: не удалось записать подтверждение диагностики: " + e.getMessage());
            return false;
        }
    }

    private void dispatchSafe() { try { launch(); } catch (Throwable ignored) {} }

    /** Запускает созревшие элементы, пока есть свободные из 4 слотов. Не ждёт завершения. */
    private List<Future<?>> launch() {
        List<Future<?>> fs = new ArrayList<>();
        if (clock.getAsLong() < holdUntil) return fs;
        for (String id : queue.dueIds(clock.getAsLong())) {
            if (clock.getAsLong() < holdUntil) break;
            if (!inflight.add(id)) continue;
            if (!permits.tryAcquire()) { inflight.remove(id); break; }
            try { fs.add(workers.submit(() -> work(id))); }
            catch (RejectedExecutionException e) { inflight.remove(id); permits.release(); break; }
        }
        return fs;
    }

    /** Синхронно обрабатывает всё созревшее (для тестов и flush). Возвращает число попыток. */
    public int pump() {
        int total = 0;
        while (true) {
            List<Future<?>> fs = launch();
            if (fs.isEmpty()) return total;
            total += fs.size();
            for (Future<?> f : fs) {
                try { f.get(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return total; } catch (ExecutionException ignored) {}
            }
        }
    }

    /** Сбросить паузы ретраев и пошло отправлять (без ожидания). Возвращает число запущенных. */
    public int flush() {
        queue.resetNext();
        holdUntil = 0;
        return launch().size();
    }

    private void work(String id) {
        DiskQueue.Item it = null;
        try {
            it = queue.read(id);
            if (it == null) return;
            if (clock.getAsLong() - it.created > EXPIRE_MS) {
                queue.reject(it, 0, "", "expired");
                lastError = "событие старше 7 дней перенесено в rejected/ (expired); вернуть: /crvotes requeue";
                warn("expired", lastError);
                return;
            }
            attempt(it);
        } catch (RuntimeException e) {
            if (it != null) retry(it, "config_error: " + e, 0, true);
            else queue.postpone(id, clock.getAsLong() + Backoff.LONG_BASE_MS);
        } catch (IOException e) {
            queue.postpone(id, clock.getAsLong() + Backoff.LONG_BASE_MS);
            lastError = "очередь: " + e.getMessage();
            log.severe("CraftRadarVotes: ошибка очереди: " + e.getMessage());
        } finally {
            inflight.remove(id);
            permits.release();
            if (chain) dispatchSafe();
        }
    }

    void attempt(DiskQueue.Item it) {
        byte[] body = it.body.getBytes(StandardCharsets.UTF_8);
        long ts = clock.getAsLong() / 1000;
        String sig = Signer.sign(cfg.secret(), ts, cfg.keyId(), body);   // до сети: ошибка подписи = RuntimeException, как в 1.0.0
        int code;
        String resp;
        HttpURLConnection c = null;
        try {
            // HttpURLConnection (Java 8) вместо java.net.http: HTTP/1.1, без редиректов, тайм-ауты 5 с / 10 с
            c = (HttpURLConnection) cfg.endpoint().toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setUseCaches(false);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setFixedLengthStreamingMode(body.length);   // и без тихого повтора POST
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-CR-Key", cfg.keyId());
            c.setRequestProperty("X-CR-Timestamp", Long.toString(ts));
            c.setRequestProperty("X-CR-Signature", sig);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            code = c.getResponseCode();
            resp = readBody(code >= 400 ? c.getErrorStream() : c.getInputStream());
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) return; // остановка: элемент остаётся в очереди как был
            retry(it, "сеть: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage()), 0, false);
            return;
        } finally {
            if (c != null) c.disconnect();
        }
        if (Thread.currentThread().isInterrupted()) return;
        handle(it, code, resp);
    }

    private static final int MAX_RESPONSE = 64 * 1024;

    /** Тело ответа (обрезается до 64 КБ: нужен только сниппет и retry_after). */
    static String readBody(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; bo.size() < MAX_RESPONSE && (n = is.read(buf)) > 0; ) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    void handle(DiskQueue.Item it, int code, String resp) {
        String snippet = resp == null ? "" : resp.substring(0, Math.min(resp.length(), 200)).replaceAll("[\\p{Cntrl}\\u0085\\u2028\\u2029]+", " ");
        try {
            if (code == 200 || code == 202) {
                queue.delete(it.id);
                lastSuccess = clock.getAsLong();
                lastError = null;
                debug("принято (HTTP " + code + ")");
            } else if (code == 429) {
                long ra = 0;
                try { ra = JsonParser.parseString(resp).getAsJsonObject().get("retry_after").getAsLong() * 1000; } catch (Exception ignored) {}
                ra = Math.min(ra, Backoff.CAP_MS);
                holdUntil = Math.max(holdUntil, clock.getAsLong() + Math.max(ra, 1000));
                retry(it, "HTTP 429 rate_limited", ra, false);
            } else if (it.body.startsWith(DIAG_PREFIX) && (code == 404 || code == 409 || code == 410)) {
                // ack_unknown / ack_replay / ack_expired: повтор бессмыслен
                queue.reject(it, code, snippet, "http_" + code);
                debug("подтверждение диагностики отклонено (HTTP " + code + ")");
            } else if (code == 401 || code == 403 || code == 404) {
                // может быть временным: сдвиг часов, мост выключен в кабинете, модерация, деплой, WAF
                retry(it, "HTTP " + code + " " + snippet, 0, true);
                warn("http" + code, "CraftRadar ответил HTTP " + code + " (" + snippet + "). Событие остаётся в очереди и будет повторено (до 7 дней). "
                        + "Проверьте key_id/secret/api_url, включён ли мост в кабинете и время сервера (NTP).");
            } else if (code >= 300 && code < 400) {
                retry(it, "config_error: HTTP " + code + " (редирект) — проверьте api_url: нужен точный https:// адрес без редиректа", 0, true);
                warn("http" + code, "CraftRadar ответил редиректом HTTP " + code + ": укажите в api_url прямой адрес с https:// (http:// обычно перенаправляется на https://). Редиректы не выполняются.");
            } else if (code >= 500 || code == 408 || code < 300) {
                retry(it, "HTTP " + code, 0, false);
            } else { // 400/409 и прочие 4xx: повтор бессмыслен
                queue.reject(it, code, snippet, "http_" + code);
                lastError = "отклонено HTTP " + code + ": " + snippet;
                warn("rej" + code, "голос отклонён сервером CraftRadar (HTTP " + code + "): " + snippet
                        + " — событие в plugins/CraftRadarVotes/rejected/, вернуть: /crvotes requeue");
            }
        } catch (IOException e) {
            lastError = "очередь: " + e.getMessage();
            log.severe("CraftRadarVotes: ошибка очереди: " + e.getMessage());
        }
    }

    private void retry(DiskQueue.Item it, String why, long retryAfterMs, boolean longBackoff) {
        it.attempts++;
        long base = longBackoff ? Backoff.longDelayMs(it.attempts, rnd) : Backoff.delayMs(it.attempts, rnd);
        long d = Math.max(base, Math.min(retryAfterMs, Backoff.CAP_MS));
        it.nextAttemptAt = clock.getAsLong() + d;
        lastError = why + " (попытка " + it.attempts + ", повтор через " + d / 1000 + " с)";
        if (!longBackoff && (it.attempts == 1 || it.attempts % 10 == 0)) log.warning("CraftRadarVotes: " + lastError);
        else if (longBackoff && why.startsWith("config_error")) warn("cfg", lastError);
        try { queue.update(it); } catch (IOException e) { log.severe("CraftRadarVotes: " + e.getMessage()); }
    }

    /** Не чаще одного раза в 10 минут на вид сообщения. */
    private void warn(String kind, String msg) {
        long now = clock.getAsLong();
        boolean[] go = {false};
        warned.compute(kind, (k, v) -> { if (v == null || now - v >= WARN_EVERY_MS) { go[0] = true; return now; } return v; });
        if (go[0]) log.warning("CraftRadarVotes: " + msg);
    }

    private void debug(String msg) { if (debug) log.log(Level.INFO, "CraftRadarVotes [debug]: " + msg); }

    public int requeue(String which) throws IOException {
        return queue.requeue(which, clock.getAsLong()); // отправит ближайший цикл (раз в 2 с)
    }

    public int queueSize() { return queue.size(); }
    public int rejectedCount() { return queue.rejectedCount(); }
    public long lastSuccess() { return lastSuccess; }
    public String lastError() { return lastError; }
    public List<DiskQueue.Item> items() { return queue.list(); }
}
