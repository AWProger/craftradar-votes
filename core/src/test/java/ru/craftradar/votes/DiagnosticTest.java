package ru.craftradar.votes;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticTest {
    static final String ID = "0123456789abcdef";
    @TempDir Path tmp;
    HttpServer srv;
    final AtomicInteger status = new AtomicInteger(200);
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final AtomicLong now = new AtomicLong(1_761_000_000_000L);
    Settings cfg;

    @BeforeEach void up() throws Exception {
        srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] r = "{\"status\":\"x\"}".getBytes();
            ex.sendResponseHeaders(status.get(), r.length); ex.getResponseBody().write(r); ex.close();
        });
        srv.start();
        cfg = new Settings(true, "http://127.0.0.1:" + srv.getAddress().getPort(), "k", "s", true, 100, "info");
    }
    @AfterEach void down() { srv.stop(0); }

    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test void decode() {
        assertEquals(ID, Correlation.decodeDiagnostic(b("crdg1:" + ID)));
        assertNull(Correlation.decodeDiagnostic(b("crdv1:" + ID)));
        assertNull(Correlation.decodeDiagnostic(b("crdg1:" + ID.toUpperCase())));
        assertNull(Correlation.decodeDiagnostic(b("crdg1:abc")));
        assertNull(Correlation.decodeDiagnostic(new byte[]{(byte) 0xff, (byte) 0xfe}));
        assertNull(Correlation.decodeDiagnostic(null));
        assertEquals(ID, Correlation.decodeDiagnostic(b("crdg1:" + ID)));
        assertNull(Correlation.decode(b("crdg1:" + ID))); // голосовой декодер не путается
    }

    @Test void rewardDecision() {
        assertTrue(Correlation.isDiagnostic("CraftRadarDiagnostic", null));
        assertTrue(Correlation.isDiagnostic("craftradardiagnostic", b("x")));
        assertTrue(Correlation.isDiagnostic("Other", b("crdg1:" + ID)));
        assertTrue(Correlation.isDiagnostic("Other", b("crdg1:broken"))); // маркер есть, id нет: всё равно без награды
        // регрессия: обычные голоса
        assertFalse(Correlation.isDiagnostic("CraftRadar", b("crdv1:" + ID)));
        assertFalse(Correlation.isDiagnostic("TopCraft", null));
        assertFalse(Correlation.isDiagnostic(null, null));
        assertFalse(Correlation.isDiagnostic("S", new byte[]{(byte) 0xff}));
    }

    @Test void ackBody() throws Exception {
        String body = VoteForwarder.diagBody(ID, 1761000000123L);
        assertEquals("{\"event_type\":\"diagnostic_ack\",\"diagnostic_id\":\"" + ID + "\",\"timestamp\":\"1761000000123\"}", body);
        assertTrue(body.startsWith(VoteForwarder.DIAG_PREFIX));
    }

    VoteForwarder fwd() throws Exception { return new VoteForwarder(cfg, new DiskQueue(tmp), Logger.getLogger("t"), now::get); }

    @Test void ok200Deletes() throws Exception {
        VoteForwarder f = fwd();
        assertTrue(f.submitDiagnostic(ID));
        assertFalse(f.submitDiagnostic(ID)); // дубль в очереди
        assertFalse(f.submitDiagnostic("zz"));
        assertEquals(1, f.queueSize());
        f.pump();
        assertEquals(0, f.queueSize());
        assertEquals(0, f.rejectedCount());
        assertEquals(1, bodies.size());
        assertTrue(bodies.get(0).contains("\"diagnostic_ack\"") && bodies.get(0).contains(ID));
        assertFalse(bodies.get(0).contains("service_name"));
    }

    @Test void terminalCodesReject() throws Exception {
        for (int code : new int[]{404, 409, 410}) {
            Path d = tmp.resolve("c" + code); java.nio.file.Files.createDirectories(d);
            VoteForwarder f = new VoteForwarder(cfg, new DiskQueue(d), Logger.getLogger("t"), now::get);
            status.set(code);
            f.submitDiagnostic(ID);
            f.pump();
            assertEquals(0, f.queueSize(), "queue " + code);
            assertEquals(1, f.rejectedCount(), "rejected " + code);
            assertEquals(0, f.pump(), "no retry " + code);
        }
    }

    @Test void networkErrorBacksOff() throws Exception {
        status.set(503);
        VoteForwarder f = fwd();
        f.submitDiagnostic(ID);
        assertEquals(1, f.pump());
        assertEquals(1, f.queueSize());
        assertEquals(0, f.rejectedCount());
        assertEquals(0, f.pump()); // ждёт backoff
    }

    @Test void voteStill404RetriesAndKeysDoNotCollide() throws Exception {
        status.set(404);
        VoteForwarder f = fwd();
        f.submit("S", "Steve", "127.0.0.1", "1761000000123", null, b("crdv1:" + ID));
        f.submitDiagnostic(ID);
        assertEquals(2, f.queueSize());
        f.pump();
        assertEquals(1, f.queueSize(), "голос при 404 остаётся (как раньше), диагностика отклонена");
        assertEquals(1, f.rejectedCount());
    }
}
