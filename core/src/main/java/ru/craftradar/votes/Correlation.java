package ru.craftradar.votes;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class Correlation {
    private static final Pattern P = Pattern.compile("^crdv1:([0-9a-f]{16})$");

    private static final Pattern D = Pattern.compile("^crdg1:([0-9a-f]{16})$");
    public static final String DIAG_SERVICE = "CraftRadarDiagnostic";

    private Correlation() {}

    /** 16 hex из additionalData вида "crdg1:<16 hex>" (диагностика) либо null. */
    public static String decodeDiagnostic(byte[] additionalData) { return match(D, additionalData); }

    /** Чистое решение: диагностический голос (service без регистра или crdg1-маркер, в т.ч. невалидный) никогда не получает награду. */
    public static boolean isDiagnostic(String service, byte[] additionalData) {
        if (service != null && DIAG_SERVICE.equalsIgnoreCase(service.trim())) return true;
        if (additionalData == null) return false;
        return decodeDiagnostic(additionalData) != null
                || new String(additionalData, StandardCharsets.ISO_8859_1).startsWith("crdg1:");
    }

    /** 16 hex из additionalData вида "crdv1:<16 hex>" либо null (в т.ч. при невалидном UTF-8). */
    public static String decode(byte[] additionalData) { return match(P, additionalData); }

    private static String match(Pattern pat, byte[] additionalData) {
        if (additionalData == null) return null;
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(additionalData)).toString();
            java.util.regex.Matcher m = pat.matcher(s);
            return m.matches() ? m.group(1) : null;
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
