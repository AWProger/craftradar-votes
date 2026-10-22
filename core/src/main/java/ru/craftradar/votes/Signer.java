package ru.craftradar.votes;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/** HMAC-SHA256(secret, "<ts>.<key_id>.<raw_body>") в нижнем hex — контракт §5. */
public final class Signer {
    private Signer() {}

    public static String sign(String secret, long ts, String keyId, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((ts + "." + keyId + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            byte[] d = mac.doFinal();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
