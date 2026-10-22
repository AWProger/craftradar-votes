package ru.craftradar.votes;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    // Векторы получены независимо: php hash_hmac('sha256', "<ts>.<key_id>.<body>", secret)
    @Test void signatureKnownAnswers() {
        String b1 = "{\"service_name\":\"TopCraft\",\"username\":\"Steve\",\"uuid\":null,\"address\":\"203.0.113.7\",\"timestamp\":\"1761000000123\",\"external_id\":null,\"correlation_id\":null}";
        assertEquals("99f6d4a6f9fa27fe33115f817685a4af412b1e60cb25e1bd0052a2d303091257",
                Signer.sign("s3cr3t", 1761000000L, "abc123keyid", b1.getBytes(StandardCharsets.UTF_8)));
        assertEquals("55cc6bbce44954411d7715891dc22dcdd1d4e163717d4f9bf3cd568bcb120a75",
                Signer.sign("secret with space", 1700000000L, "k", "{\"username\":\"Алекс\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("13265f2c644a44092fb92d9119ac3e9d4bc1e10787e5993b80fe1a6d5198354d",
                Signer.sign("0123456789abcdef0123456789abcdef", 1L, "key_01", "{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void longBackoffBounds() {
        Random r = new Random(3);
        for (int a = 1; a <= 30; a++) for (int i = 0; i < 100; i++) {
            long d = Backoff.longDelayMs(a, r);
            long exp = Math.min(Backoff.LONG_CAP_MS, Backoff.LONG_BASE_MS << Math.min(a - 1, 20));
            assertTrue(d <= Backoff.LONG_CAP_MS && d >= (long) (exp * 0.8) - 1);
        }
    }

    @Test void correlation() {
        assertEquals("0123456789abcdef", Correlation.decode("crdv1:0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        assertNull(Correlation.decode("crdv2:0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
        assertNull(Correlation.decode("crdv1:0123456789abcde".getBytes(StandardCharsets.UTF_8)));
        assertNull(Correlation.decode("crdv1:0123456789abcdef0".getBytes(StandardCharsets.UTF_8)));
        assertNull(Correlation.decode("crdv1:0123456789ABCDEF".getBytes(StandardCharsets.UTF_8)));
        assertNull(Correlation.decode("crdv1:0123456789abcdef\n".getBytes(StandardCharsets.UTF_8)));
        assertNull(Correlation.decode(null));
        assertNull(Correlation.decode(new byte[0]));
        assertNull(Correlation.decode(new byte[]{(byte) 0xff, (byte) 0xfe, 'c', 'r'}));
    }

    @Test void backoffBounds() {
        Random r = new Random(1);
        for (int a = 1; a <= 40; a++) {
            long exp = Math.min(Backoff.CAP_MS, Backoff.BASE_MS << Math.min(a - 1, 20));
            for (int i = 0; i < 200; i++) {
                long d = Backoff.delayMs(a, r);
                assertTrue(d <= Backoff.CAP_MS, "cap at attempt " + a);
                assertTrue(d >= (long) (exp * 0.8) - 1, "lower bound at attempt " + a);
                assertTrue(d <= (long) (exp * 1.2) + 1 || d == Backoff.CAP_MS);
            }
        }
        assertTrue(Backoff.delayMs(30, new Random(2)) >= (long) (Backoff.CAP_MS * 0.8) - 1);
    }

    @Test void settingsValidation() {
        assertTrue(new Settings(true, "https://craftradar.ru", "keyid", "s", false, 10, "info").validate().isEmpty());
        assertEquals(2, new Settings(true, "https://craftradar.ru", "", " ", false, 10, "info").validate().size());
        assertFalse(new Settings(true, "http://x.ru", "keyid", "s", false, 10, "info").validate().isEmpty());
        assertTrue(new Settings(true, "http://x.ru", "keyid", "s", true, 10, "info").validate().isEmpty());
        assertFalse(new Settings(true, "not a url", "keyid", "s", false, 10, "info").validate().isEmpty());
        assertFalse(new Settings(true, "ftp://x.ru", "keyid", "s", false, 10, "info").validate().isEmpty());
        assertFalse(new Settings(true, "https://x.ru", "k", "topsecret", false, 10, "info").toString().contains("topsecret"));
        assertEquals("https://x.ru/api/vote_ingest.php", new Settings(true, "https://x.ru/", "keyid", "s", false, 1, "info").endpoint().toString());
        for (String bad : new String[]{"k", "abc", "key id", "key\n1", "кей_айди", "a".repeat(65), "a".repeat(25)})
            assertFalse(new Settings(true, "https://x.ru", bad, "s", false, 1, "info").validate().isEmpty(), bad);
        assertFalse(new Settings(true, "https://x.ru", "keyid", "s\n", false, 1, "info").validate().isEmpty());
        // граница сайта (includes/voting/ingest.php): 24 символа ещё можно, формат выдачи crk_ + 16 hex
        assertTrue(new Settings(true, "https://x.ru", "a".repeat(24), "s", false, 1, "info").validate().isEmpty());
        assertTrue(new Settings(true, "https://x.ru", "crk_0123456789abcdef", "s", false, 1, "info").validate().isEmpty());
        Settings sfx = new Settings(true, "https://x.ru/api/vote_ingest.php/", "keyid", "s", false, 1, "info");
        assertTrue(sfx.apiUrlHadSuffix());
        assertEquals("https://x.ru/api/vote_ingest.php", sfx.endpoint().toString());
        assertEquals("https://x.ru", sfx.baseUrl());
        assertTrue(sfx.validate().isEmpty());
    }
}
