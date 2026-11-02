package ru.craftradar.votes;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Настройки без зависимости от Bukkit. toString() не содержит секрета. */
public final class Settings {
    private final boolean enabled, allowInsecureHttp;
    private final String apiUrl, keyId, secret, logLevel;
    private final int maxQueueSize;
    private final boolean pullEnabled;
    private final int pullIntervalSec;

    public Settings(boolean enabled, String apiUrl, String keyId, String secret,
                    boolean allowInsecureHttp, int maxQueueSize, String logLevel) {
        this(enabled, apiUrl, keyId, secret, allowInsecureHttp, maxQueueSize, logLevel, true, 5);
    }

    /** pullEnabled/pullIntervalSec — поток C (PROTOCOL §10): забирать голоса CraftRadar с сайта. */
    public Settings(boolean enabled, String apiUrl, String keyId, String secret,
                    boolean allowInsecureHttp, int maxQueueSize, String logLevel, boolean pullEnabled, int pullIntervalSec) {
        this.enabled = enabled; this.apiUrl = apiUrl; this.keyId = keyId; this.secret = secret;
        this.allowInsecureHttp = allowInsecureHttp; this.maxQueueSize = maxQueueSize; this.logLevel = logLevel;
        this.pullEnabled = pullEnabled; this.pullIntervalSec = pullIntervalSec;
    }
    public boolean pullEnabled() { return pullEnabled; }
    public int pullIntervalSec() { return pullIntervalSec; }
    public boolean enabled() { return enabled; }
    public String apiUrl() { return apiUrl; }
    public String keyId() { return keyId; }
    public String secret() { return secret; }
    public boolean allowInsecureHttp() { return allowInsecureHttp; }
    public int maxQueueSize() { return maxQueueSize; }
    public String logLevel() { return logLevel; }

    /** = !s.equals(s.strip()) (Java 11): пробельный символ по краям. */
    private static boolean edgeSpace(String s) {
        return !s.isEmpty() && (Character.isWhitespace(s.codePointAt(0)) || Character.isWhitespace(s.codePointBefore(s.length())));
    }

    // сайт: ^[A-Za-z0-9_-]{1,24}$ (выдаёт crk_ + 16 hex = 20 символов)
    static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9_-]{4,24}$");
    static final String SUFFIX = "/api/vote_ingest.php";

    public List<String> validate() {
        List<String> e = new ArrayList<>();
        if (keyId == null || J8.blank(keyId)) e.add("key_id пуст — получите его в кабинете CraftRadar: «Голосования» сервера → «Выдать ключ»");
        else if (!KEY_ID.matcher(keyId).matches()) e.add("key_id содержит недопустимые символы или длину (нужно [A-Za-z0-9_-], 4..24): проверьте, что скопировали без пробелов и кавычек");
        if (secret == null || J8.blank(secret)) e.add("secret пуст — он показывается один раз при выдаче ключа в кабинете CraftRadar (потеряли — перевыпустите ключ)");
        else if (edgeSpace(secret) || secret.chars().anyMatch(Character::isISOControl))
            e.add("secret содержит пробелы по краям или управляющие символы: скопируйте его заново");
        try {
            URI u = URI.create(baseUrl());
            String sch = u.getScheme();
            if (u.getHost() == null || sch == null) throw new IllegalArgumentException();
            if (sch.equals("http")) {
                if (!allowInsecureHttp) e.add("api_url использует http://; нужен https:// (или allow_insecure_http: true для тестов)");
            } else if (!sch.equals("https")) {
                e.add("api_url: допустимы только https:// или http://");
            }
        } catch (IllegalArgumentException ex) {
            e.add("api_url некорректен: '" + apiUrl + "'");
        }
        if (maxQueueSize < 1) e.add("max_queue_size должен быть >= 1");
        if (pullIntervalSec < 2 || pullIntervalSec > 60) e.add("pull.interval_sec должен быть от 2 до 60 секунд");
        return e;
    }

    private String stripSlashes() {
        String b = apiUrl == null ? "" : apiUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    /** True, если в api_url по ошибке указан полный адрес endpoint'а. */
    public boolean apiUrlHadSuffix() { return stripSlashes().endsWith(SUFFIX); }

    /** api_url без хвоста /api/vote_ingest.php и без завершающих слэшей. */
    public String baseUrl() {
        String b = stripSlashes();
        if (b.endsWith(SUFFIX)) b = b.substring(0, b.length() - SUFFIX.length());
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    public URI endpoint() { return URI.create(baseUrl() + SUFFIX); }

    /** {api_url}/api/vote_pull.php (поток C). */
    public URI pullEndpoint() { return URI.create(baseUrl() + "/api/vote_pull.php"); }

    @Override public String toString() {
        return "Settings[enabled=" + enabled + ", apiUrl=" + apiUrl + ", keyId=" + keyId + ", secret=***]";
    }
}
