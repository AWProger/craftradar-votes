package ru.craftradar.votes;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Модуль skins: скин CraftRadar по нику — GET {base}/api/v1/skins/player/{nick} (ник подтверждён лаунчером CraftRadar).
 * Без Bukkit и SkinsRestorer (их клей — SkinsModule в bukkit). Блокирующий вызов: только из асинхронной задачи. Потокобезопасен.
 */
public final class SkinLookup {
    /** Ники, которые сайт принимает; остальные (Bedrock-префиксы, точки) не запрашиваются. */
    public static final Pattern NICK = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    static final int MAX_CACHE = 5000;
    static final long ERROR_TTL_MS = 60_000;   // сайт недоступен: не долбим его на каждом входе

    public enum Status { FOUND, NONE, ERROR }

    public static final class Result {
        public final Status status;
        public final String url;   // только FOUND: https-адрес PNG на том же сайте
        public final boolean slim;
        final long until;
        Result(Status status, String url, boolean slim, long until) { this.status = status; this.url = url; this.slim = slim; this.until = until; }
    }

    private final String base;
    private final int timeoutMs;
    private final long ttlMs;
    private final LongSupplier clock;
    private final Map<String, Result> cache = new ConcurrentHashMap<>();

    /** base — адрес сайта без завершающего «/» (как Settings.baseUrl()). */
    public SkinLookup(String base, int timeoutMs, long ttlMs, LongSupplier clock) {
        this.base = base; this.timeoutMs = timeoutMs; this.ttlMs = ttlMs; this.clock = clock;
    }

    public String base() { return base; }

    /** Скин по нику (кэш ttl, ошибка — ERROR_TTL_MS). */
    public Result lookup(String nick) {
        if (!NICK.matcher(nick).matches()) return new Result(Status.NONE, null, false, 0);
        String key = nick.toLowerCase(Locale.ROOT);
        long now = clock.getAsLong();
        Result r = cache.get(key);
        if (r != null && r.until > now) return r;
        r = fetch(nick, now);
        if (cache.size() >= MAX_CACHE) cache.clear();   // ponytail: сброс целиком вместо LRU — на сервере тысячи ников, не миллионы
        cache.put(key, r);
        return r;
    }

    private Result fetch(String nick, long now) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create(base + "/api/v1/skins/player/" + URLEncoder.encode(nick, "UTF-8")).toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            String body = VoteForwarder.readBody(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 404) return new Result(Status.NONE, null, false, now + ttlMs);
            if (code != 200) return new Result(Status.ERROR, null, false, now + ERROR_TTL_MS);
            return parse(body, now);
        } catch (IOException | RuntimeException e) {
            return new Result(Status.ERROR, null, false, now + ERROR_TTL_MS);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Ответ 200 -> FOUND; skin_url не с нашего сайта или мусор -> ERROR (SkinsRestorer не должен качать чужие адреса). */
    Result parse(String body, long now) {
        try {
            JsonObject d = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("data");
            String url = d.get("skin_url").getAsString();
            if (!isOurs(url)) return new Result(Status.ERROR, null, false, now + ERROR_TTL_MS);
            boolean slim = d.has("model") && "slim".equals(d.get("model").getAsString());
            return new Result(Status.FOUND, url, slim, now + ttlMs);
        } catch (RuntimeException e) {
            return new Result(Status.ERROR, null, false, now + ERROR_TTL_MS);
        }
    }

    /** Адрес с нашего сайта (тот же base) — значит, этот скин в SkinsRestorer поставил модуль, а не игрок. */
    public boolean isOurs(String url) {
        return url != null && url.startsWith(base + "/") && url.indexOf('\n') < 0 && url.indexOf(' ') < 0;
    }

    /**
     * Можно ли модулю менять скин игрока в SkinsRestorer: скина нет (srType == null) или стоит наш URL-скин.
     * Скин, выбранный игроком или админом (/skin ник|url|custom), не трогаем.
     */
    public boolean mayReplace(String srType, String srIdentifier) {
        return srType == null || ("URL".equals(srType) && isOurs(srIdentifier));
    }
}
