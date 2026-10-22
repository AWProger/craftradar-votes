package ru.craftradar.votes;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Общие с сайтом векторы подписи (docs/protocol-vectors.json плагина; побайтовая копия — в репо сайта).
 * Путь — от корня проекта плагина (тест запускается из core/).
 */
class ProtocolVectorsTest {
    @Test void signerMatchesSharedVectors() throws Exception {
        Path p = Path.of(System.getProperty("user.dir")).resolve("../docs/protocol-vectors.json").normalize();
        if (!Files.exists(p)) p = Path.of("docs/protocol-vectors.json");
        JsonObject root = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
        int n = 0;
        for (JsonElement e : root.getAsJsonArray("vectors")) {
            JsonObject v = e.getAsJsonObject();
            String got = Signer.sign(v.get("secret").getAsString(), Long.parseLong(v.get("ts").getAsString()),
                    v.get("key_id").getAsString(), v.get("body").getAsString().getBytes(StandardCharsets.UTF_8));
            assertEquals(v.get("signature").getAsString(), got, v.get("name").getAsString());
            n++;
        }
        assertEquals(5, n, "число векторов изменилось — обновите и тест сайта");
    }
}
