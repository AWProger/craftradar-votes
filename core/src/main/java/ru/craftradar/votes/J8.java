package ru.craftradar.votes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;

/** Замены API Java 9–17, которых нет в Java 8 (ядро собирается под Java 8). Семантика — как у оригиналов. */
public final class J8 {
    private J8() {}

    /** = String.isBlank(). */
    public static boolean blank(String s) {
        for (int i = 0; i < s.length(); ) { int c = s.codePointAt(i); if (!Character.isWhitespace(c)) return false; i += Character.charCount(c); }
        return true;
    }

    /** = Files.readString(p, UTF_8): невалидный UTF-8 — MalformedInputException (CharacterCodingException). */
    public static String readString(Path p) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Files.readAllBytes(p))).toString();
    }

    /** = Files.writeString(p, s, UTF_8, opts). */
    public static void writeString(Path p, String s, OpenOption... opts) throws IOException {
        Files.write(p, s.getBytes(StandardCharsets.UTF_8), opts);
    }

    /** = HexFormat.of().formatHex(d). */
    public static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return sb.toString();
    }
}
