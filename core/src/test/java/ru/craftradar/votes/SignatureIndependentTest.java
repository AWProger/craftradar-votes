package ru.craftradar.votes;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Независимая реализация (javax.crypto напрямую, без Signer) против векторов, посчитанных PHP hash_hmac. */
class SignatureIndependentTest {
    static String hmac(String secret, String msg) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(m.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void phpKnownAnswers() throws Exception {
        String b1 = "{\"service_name\":\"TopCraft\",\"username\":\"Steve\",\"uuid\":null,\"address\":\"203.0.113.7\",\"timestamp\":\"1761000000123\",\"external_id\":null,\"correlation_id\":null}";
        assertEquals("99f6d4a6f9fa27fe33115f817685a4af412b1e60cb25e1bd0052a2d303091257", hmac("s3cr3t", "1761000000.abc123keyid." + b1));
        assertEquals("55cc6bbce44954411d7715891dc22dcdd1d4e163717d4f9bf3cd568bcb120a75", hmac("secret with space", "1700000000.k.{\"username\":\"Алекс\"}"));
        assertEquals("13265f2c644a44092fb92d9119ac3e9d4bc1e10787e5993b80fe1a6d5198354d", hmac("0123456789abcdef0123456789abcdef", "1.key_01.{}"));
        // и Signer даёт то же самое
        assertEquals(hmac("s3cr3t", "1761000000.abc123keyid." + b1), Signer.sign("s3cr3t", 1761000000L, "abc123keyid", b1.getBytes(StandardCharsets.UTF_8)));
    }
}
