package ru.craftradar.votes;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Поток C (PROTOCOL §10): опрос, подтверждение, дедуп по id, повтор при отказе обработчика, рестарт, 429/401, диагностика. */
class VotePollerTest {
    static final String KEY = "crk_0123456789abcdef", SECRET = "pull-test-secret";
    @TempDir Path tmp;
    HttpServer srv;
    /** «Сайт»: голоса, пока их id не придёт в ack. */
    final Map<String, String> pending = Collections.synchronizedMap(new LinkedHashMap<>());
    final List<List<String>> acks = new CopyOnWriteArrayList<>();
    final AtomicInteger status = new AtomicInteger(200), sigFailures = new AtomicInteger();
    final AtomicBoolean dropAcks = new AtomicBoolean();
    final AtomicReference<String> mode = new AtomicReference<>("pull"), diag = new AtomicReference<>();
    Settings cfg;

    @BeforeEach void up() throws Exception {
        srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/api/vote_pull.php", ex -> {
            byte[] raw = J8test.readAll(ex.getRequestBody());
            String ts = ex.getRequestHeaders().getFirst("X-CR-Timestamp");
            String exp = Signer.sign(SECRET, Long.parseLong(ts), ex.getRequestHeaders().getFirst("X-CR-Key"), raw);
            String out; int st = status.get();
            if (!KEY.equals(ex.getRequestHeaders().getFirst("X-CR-Key")) || !exp.equals(ex.getRequestHeaders().getFirst("X-CR-Signature"))) {
                sigFailures.incrementAndGet(); st = 401; out = "{\"status\":\"unauthorized\"}";
            } else if (st == 429) {
                out = "{\"status\":\"rate_limited\",\"retry_after\":42}";
            } else if (st != 200) {
                out = "{\"status\":\"error\"}";
            } else {
                JsonObject req = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
                List<String> got = new ArrayList<>();
                for (JsonElement e : req.getAsJsonArray("ack")) got.add(e.getAsString());
                acks.add(got);
                int acked = 0;
                if (!dropAcks.get()) for (String id : got) if (pending.remove(id) != null) acked++;
                JsonObject r = new JsonObject();
                r.addProperty("status", "ok"); r.addProperty("mode", mode.get()); r.addProperty("poll_after", "pull".equals(mode.get()) ? 5 : 60);
                r.addProperty("acked", acked); r.addProperty("more", false);
                JsonArray votes = new JsonArray();
                if ("pull".equals(mode.get())) synchronized (pending) {
                    for (Map.Entry<String, String> e : pending.entrySet()) {
                        JsonObject v = new JsonObject();
                        v.addProperty("id", e.getKey()); v.addProperty("service_name", "CraftRadar"); v.addProperty("username", e.getValue());
                        v.addProperty("address", "203.0.113.7"); v.addProperty("timestamp", "1761000000123");
                        votes.add(v);
                    }
                }
                r.add("votes", votes);
                JsonArray d = new JsonArray();
                if (diag.get() != null) d.add(diag.get());
                r.add("diagnostics", d);
                out = r.toString();
            }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(st, b.length); ex.getResponseBody().write(b); ex.close();
        });
        srv.start();
        cfg = new Settings(true, "http://127.0.0.1:" + srv.getAddress().getPort(), KEY, SECRET, true, 100, "info", true, 5);
    }
    @AfterEach void down() { srv.stop(0); }

    final List<VotePoller.PulledVote> handled = new CopyOnWriteArrayList<>();
    final List<String> diags = new CopyOnWriteArrayList<>();
    final AtomicBoolean sinkOk = new AtomicBoolean(true);

    VotePoller poller() {
        return new VotePoller(cfg, tmp, Logger.getLogger("t"), System::currentTimeMillis,
                v -> { if (!sinkOk.get()) return false; handled.add(v); return true; }, diags::add);
    }

    @Test void receivesThenAcks() {
        pending.put("00000000000000a1", "Steve");
        pending.put("00000000000000a2", "Alex");
        VotePoller p = poller();
        long d = p.pollOnce();
        assertEquals(2, handled.size());
        assertEquals("Steve", handled.get(0).username);
        assertEquals(1761000000123L, handled.get(0).timestampMs());
        assertEquals(1000, d, "есть что подтвердить — сразу второй запрос");
        assertEquals(2, p.pendingAcks());
        d = p.pollOnce();
        assertEquals(Arrays.asList("00000000000000a1", "00000000000000a2"), acks.get(1));
        assertTrue(pending.isEmpty(), "сайт удалил подтверждённые");
        assertEquals(0, p.pendingAcks());
        assertEquals(5000, d, "обычный интервал");
        assertEquals("pull", p.mode());
        assertEquals(0, sigFailures.get());
    }

    @Test void lostAckIsDedupedById() {
        pending.put("00000000000000b1", "Steve");
        dropAcks.set(true);                     // сайт «не получил» ack: вернёт голос снова
        VotePoller p = poller();
        p.pollOnce(); p.pollOnce(); p.pollOnce();
        assertEquals(1, handled.size(), "повтор того же id не обрабатывается");
        assertEquals(1, p.pendingAcks(), "id подтверждается снова");
        dropAcks.set(false);
        p.pollOnce();
        assertTrue(pending.isEmpty());
        assertEquals(1, handled.size());
    }

    @Test void sinkRefusalIsNotAcked() {
        pending.put("00000000000000c1", "Steve");
        sinkOk.set(false);
        VotePoller p = poller();
        long d = p.pollOnce();
        assertTrue(d >= 4000, "пауза перед повтором");
        assertEquals(0, p.pendingAcks(), "не принятый голос не подтверждается");
        p.pollOnce();
        assertEquals(Collections.emptyList(), acks.get(1));
        sinkOk.set(true);
        p.pollOnce(); p.pollOnce();
        assertEquals(1, handled.size());
        assertTrue(pending.isEmpty());
    }

    @Test void restartKeepsSeenAndPendingAcks() {
        pending.put("00000000000000d1", "Steve");
        dropAcks.set(true);
        poller().pollOnce();                    // обработан, ack ещё не дошёл; «сервер перезапущен»
        dropAcks.set(false);
        VotePoller p2 = poller();
        assertEquals(1, p2.pendingAcks(), "ack из pull-state.json");
        p2.pollOnce();
        assertEquals(1, handled.size(), "после рестарта не обработан повторно");
        assertTrue(pending.isEmpty());
    }

    @Test void rateLimitAndAuthBackoff() {
        VotePoller p = poller();
        status.set(429);
        assertEquals(42_000, p.pollOnce(), "retry_after");
        status.set(200);
        Settings bad = new Settings(true, cfg.baseUrl(), KEY, "wrong", true, 100, "info", true, 5);
        VotePoller pb = new VotePoller(bad, tmp.resolve("b"), Logger.getLogger("t"), System::currentTimeMillis, v -> true, id -> { });
        long d = pb.pollOnce();
        assertTrue(d >= 4 * 60_000, "401 — долгий повтор: " + d);
        assertTrue(pb.lastError().contains("ключ не принят"));
        status.set(500);
        long e = p.pollOnce();
        assertTrue(e >= 4000 && e <= 6000, "500 — обычный повтор: " + e);
        assertTrue(p.lastError().contains("HTTP 500"));
    }

    @Test void votifierModeAndDiagnostics() {
        mode.set("votifier");
        pending.put("00000000000000e1", "Steve");
        diag.set("00112233aabbccdd");
        VotePoller p = poller();
        assertEquals(60_000, p.pollOnce(), "poll_after сайта");
        assertEquals("votifier", p.mode());
        assertEquals(0, handled.size(), "в режиме votifier голоса опросом не приходят");
        p.pollOnce();
        assertEquals(Collections.singletonList("00112233aabbccdd"), diags, "диагностика один раз");
    }

    @Test void pullVectorMatchesProtocol() {
        // docs/protocol-vectors.json, pull_request
        assertEquals("aad62c30d937ca905fed48a128b32441cc262adf093c90bce2b7c7d4ffe9eddd",
                Signer.sign("0000000000000000000000000000000000000000000000000000000000000000", 1761000000L, "crk_0123456789abcdef",
                        "{\"ack\":[\"0123456789abcdef\"],\"limit\":50}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("http://127.0.0.1:" + srv.getAddress().getPort() + "/api/vote_pull.php", cfg.pullEndpoint().toString());
        assertTrue(new Settings(true, "https://craftradar.ru", KEY, SECRET, false, 100, "info", true, 1).validate().stream().anyMatch(s -> s.contains("pull.interval_sec")));
    }
}

final class J8test {
    static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096];
        for (int n; (n = in.read(b)) > 0; ) bo.write(b, 0, n);
        return bo.toByteArray();
    }
}
