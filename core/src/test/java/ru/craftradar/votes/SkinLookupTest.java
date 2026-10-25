package ru.craftradar.votes;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Модуль skins: запрос скина по нику к сайту — 200/404/ошибки, кэш, тайм-аут, чужие адреса, правило «выбранный вручную скин не трогаем». */
class SkinLookupTest {
    HttpServer srv;
    String base;
    final List<String> paths = new CopyOnWriteArrayList<>();
    final AtomicLong now = new AtomicLong(1_761_000_000_000L);

    @BeforeEach void up() throws Exception {
        srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + srv.getAddress().getPort();
        srv.createContext("/api/v1/skins/player/", ex -> {
            String nick = ex.getRequestURI().getPath().substring("/api/v1/skins/player/".length());
            paths.add(nick);
            int code = 200;
            String body;
            switch (nick) {
                case "Steve": body = "{\"api_version\":\"1\",\"data\":{\"nick\":\"Steve\",\"skin_url\":\"" + base + "/skin_texture.php?h=" + "ab".repeat(32) + "\",\"model\":\"classic\",\"cape_url\":null,\"updated_at\":\"2026-10-25T10:00:00+03:00\"}}"; break;
                case "Alex": body = "{\"api_version\":\"1\",\"data\":{\"nick\":\"Alex\",\"skin_url\":\"" + base + "/skin_texture.php?h=" + "cd".repeat(32) + "\",\"model\":\"slim\",\"cape_url\":null}}"; break;
                case "Evil": body = "{\"data\":{\"skin_url\":\"https://evil.example/x.png\",\"model\":\"classic\"}}"; break;
                case "Junk": body = "<html>"; break;
                case "Busy": code = 429; body = "{}"; break;
                case "Slow": try { Thread.sleep(1500); } catch (InterruptedException ignored) {} body = "{}"; break;
                default: code = 404; body = "{\"api_version\":\"1\",\"data\":null,\"error\":{\"code\":\"not_found\"}}";
            }
            byte[] r = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, r.length); ex.getResponseBody().write(r); ex.close();
        });
        srv.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        srv.start();
    }
    @AfterEach void down() { srv.stop(0); }

    SkinLookup sl() { return new SkinLookup(base, 500, 600_000, now::get); }

    @Test void foundClassicAndSlim() {
        SkinLookup s = sl();
        SkinLookup.Result r = s.lookup("Steve");
        assertEquals(SkinLookup.Status.FOUND, r.status);
        assertEquals(base + "/skin_texture.php?h=" + "ab".repeat(32), r.url);
        assertFalse(r.slim);
        assertTrue(s.lookup("Alex").slim);
    }

    @Test void notFoundAndErrors() {
        SkinLookup s = sl();
        assertEquals(SkinLookup.Status.NONE, s.lookup("Nobody").status);
        assertEquals(SkinLookup.Status.ERROR, s.lookup("Busy").status, "429 — ошибка, не «нет скина»");
        assertEquals(SkinLookup.Status.ERROR, s.lookup("Junk").status);
        assertEquals(SkinLookup.Status.ERROR, s.lookup("Evil").status, "skin_url не с нашего сайта");
        long t = System.nanoTime();
        assertEquals(SkinLookup.Status.ERROR, s.lookup("Slow").status, "тайм-аут");
        assertTrue(System.nanoTime() - t < 1_400_000_000L, "тайм-аут 500 мс соблюдён");
    }

    @Test void badNickNotRequested() {
        SkinLookup s = sl();
        for (String n : new String[]{"ab", ".BedrockGuy", "a b", "x".repeat(17), "Ник"}) assertEquals(SkinLookup.Status.NONE, s.lookup(n).status, n);
        assertTrue(paths.isEmpty(), paths.toString());
    }

    @Test void cacheCaseInsensitiveWithTtl() {
        SkinLookup s = sl();
        s.lookup("Steve"); s.lookup("steve"); s.lookup("Nobody"); s.lookup("NOBODY");
        assertEquals(List.of("Steve", "Nobody"), paths, "повтор и другой регистр — из кэша");
        now.addAndGet(600_001);
        s.lookup("Steve");
        assertEquals(3, paths.size(), "после ttl — новый запрос");
        s.lookup("Busy"); s.lookup("Busy");
        assertEquals(4, paths.size(), "ошибка кэшируется на минуту");
        now.addAndGet(SkinLookup.ERROR_TTL_MS + 1);
        s.lookup("Busy");
        assertEquals(5, paths.size());
    }

    @Test void mayReplaceOnlyEmptyOrOwn() {
        SkinLookup s = sl();
        assertTrue(s.mayReplace(null, null), "скина в SkinsRestorer нет");
        assertTrue(s.mayReplace("URL", base + "/skin_texture.php?h=" + "ab".repeat(32)), "наш скин можно обновить");
        assertFalse(s.mayReplace("URL", "https://example.com/my.png"), "/skin url — выбор игрока");
        assertFalse(s.mayReplace("URL", base + "evil.example/x.png"), "префикс без «/» — не наш");
        assertFalse(s.mayReplace("PLAYER", "069a79f4-44e9-4726-a5be-fca90e38aaf5"), "/skin Notch");
        assertFalse(s.mayReplace("CUSTOM", "vip"), "скин админа");
    }
}
