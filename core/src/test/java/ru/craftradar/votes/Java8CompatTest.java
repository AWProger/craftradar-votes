package ru.craftradar.votes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Замены API Java 9-17 (ядро собирается под Java 8) ведут себя как оригиналы. Тесты идут на Java 21, где есть оба. */
class Java8CompatTest {
    @Test void blankMatchesIsBlank() {
        for (String s : new String[]{"", " ", "\t\n", " ", " x", "a", " a ", "\u0000", "\u001c", " "})
            assertEquals(s.isBlank(), J8.blank(s), () -> "blank(" + s.codePoints().boxed().toList() + ")");
    }

    @Test void hexMatchesHexFormat() {
        byte[] d = new byte[256];
        for (int i = 0; i < d.length; i++) d[i] = (byte) i;
        assertEquals(HexFormat.of().formatHex(d), J8.hex(d));
    }

    @Test void readStringStrictLikeFilesReadString(@TempDir Path dir) throws Exception {
        Path ok = dir.resolve("ok"), bad = dir.resolve("bad");
        Files.writeString(ok, "Привет {}", StandardCharsets.UTF_8);
        Files.write(bad, new byte[]{'{', (byte) 0xC3, '}'});
        assertEquals(Files.readString(ok), J8.readString(ok));
        assertThrows(MalformedInputException.class, () -> Files.readString(bad));
        assertThrows(MalformedInputException.class, () -> J8.readString(bad));
    }

    @Test void secretEdgeSpaceLikeStrip() {
        for (String s : new String[]{"abc", " abc", "abc ", " abc", "a b"}) {
            boolean expectErr = !s.equals(s.strip());
            boolean err = new Settings(true, "https://x.ru", "keyid", s, false, 1, "info").validate().stream().anyMatch(e -> e.startsWith("secret"));
            assertEquals(expectErr, err, s);
        }
    }

    @Test void java8BoundedNextLongIsJava17Algorithm() {
        long[] bounds = {1, 2, 3, 7, 64, 1000, 50_000_000L, (1L << 40) + 3, Long.MAX_VALUE};
        for (long seed = 0; seed < 200; seed++)
            for (long b : bounds)
                assertEquals(new Random(seed).nextLong(b), ru.craftradar.votes.rewards.WeightedPickerAccess.java8(new Random(seed), b), "seed=" + seed + " bound=" + b);
    }
}
