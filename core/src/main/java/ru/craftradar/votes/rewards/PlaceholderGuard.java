package ru.craftradar.votes.rewards;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Владелец: A5. Ник и плейсхолдеры: единственный допустимый плейсхолдер — {player}. */
public final class PlaceholderGuard {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.]{1,16}$");
    private static final Pattern BRACES = Pattern.compile("\\{[^{}]*\\}");
    private static final String PLAYER = "{player}";

    private PlaceholderGuard() {}

    /** Ник по ^[A-Za-z0-9_.]{1,16}$. */
    public static boolean validPlayerName(String name) {
        // matches() без $-хитростей: "$" в java.regex допускает хвостовой \n, поэтому проверяем через matches()
        return name != null && NAME.matcher(name).matches() && name.indexOf('\n') < 0;
    }

    /** Найти все {...}, кроме {player}; пусто = шаблон допустим. */
    public static List<String> unknownPlaceholders(String template) {
        List<String> res = new ArrayList<>();
        if (template == null) return res;
        Matcher m = BRACES.matcher(template);
        while (m.find()) if (!PLAYER.equals(m.group())) res.add(m.group());
        return res;
    }

    /** Подставить {player}; ник должен быть уже проверен (иначе IllegalArgumentException). */
    public static String substitute(String template, String player) {
        if (!validPlayerName(player)) throw new IllegalArgumentException("недопустимый ник");
        return template.replace(PLAYER, player);
    }

    /** Есть ли управляющие символы и разделители строк (в т.ч. \n, \r, NUL, U+0085, U+2028/2029). */
    public static boolean hasLineBreakOrControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F || c == 0x85 || c == 0x2028 || c == 0x2029) return true;
        }
        return false;
    }
}
