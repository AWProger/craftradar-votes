package ru.craftradar.votes.rewards;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Владелец: A3. Строгая проверка секции rewards (контракт §2): неизвестные ключи, типы, диапазоны.
 * Любая ошибка -> settings == null, настройки частично не применяются. Ошибки с путём: rewards.default.items[0].material: ...
 * Вход — только вложенные Map/List (YAML-дерево); вложенные MemorySection Bukkit вызывающий должен развернуть сам.
 */
public final class ConfigValidator {
    private ConfigValidator() {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^{}]*}");
    private static final int MAX_TEXT = 256, MAX_COMMANDS = 20, MAX_LIST = 50;

    /** raw — getValues(false)-дерево секции rewards (null = секции нет -> disabled). materialExists — проверка Bukkit Material (не legacy, не AIR). */
    public static ValidationResult validate(Map<String, Object> raw, Predicate<String> materialExists) {
        if (raw == null) return new ValidationResult(RewardsSettings.disabled(), java.util.Collections.<String>emptyList());
        V v = new V(materialExists);
        RewardsSettings s = v.root(raw);
        return v.errors.isEmpty() ? new ValidationResult(s, java.util.Collections.<String>emptyList()) : new ValidationResult(null, v.errors);
    }

    private static final class V {
        final List<String> errors = new ArrayList<>();
        final Predicate<String> materialExists;
        V(Predicate<String> m) { materialExists = m; }

        void err(String path, String msg) { errors.add(path + ": " + msg); }

        /** Секция-Map со строковыми ключами; иначе ошибка и null. Неизвестные ключи — ошибки. */
        Map<?, ?> section(Object o, String path, Set<String> allowed) {
            if (!(o instanceof Map<?, ?>)) { err(path, "ожидается секция (map), получено " + typeName(o)); return null; }
            Map<?, ?> m = (Map<?, ?>) o;
            for (Object k : m.keySet()) {
                if (!(k instanceof String) || !allowed.contains((String) k))
                    err(path + "." + k, "неизвестный ключ (допустимы: " + String.join(", ", allowed) + ")");
            }
            return m;
        }

        boolean bool(Map<?, ?> m, String key, String path, boolean def) {
            if (!m.containsKey(key)) return def;
            Object o = m.get(key);
            if (o instanceof Boolean) return (Boolean) o;
            err(path + "." + key, "ожидается true/false, получено " + typeName(o));
            return def;
        }

        /** Целое (Integer/Long, не дробное) в диапазоне. */
        int integer(Map<?, ?> m, String key, String path, int def, long min, long max) {
            if (!m.containsKey(key)) return def;
            return intValue(m.get(key), path + "." + key, min, max, def);
        }

        int intValue(Object o, String p, long min, long max, int def) {
            if (!(o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte)) {
                err(p, "ожидается целое число, получено " + typeName(o)); return def;
            }
            long n = ((Number) o).longValue();
            if (n < min || n > max) { err(p, "значение " + n + " вне диапазона " + min + ".." + max); return def; }
            return (int) n;
        }

        double money(Map<?, ?> m, String path) {
            if (!m.containsKey("money")) return 0;
            Object o = m.get("money");
            String p = path + ".money";
            if (!(o instanceof Number) || o instanceof java.math.BigInteger || o instanceof java.math.BigDecimal) {
                err(p, "ожидается число, получено " + typeName(o)); return 0;
            }
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d) || d < 0 || d > 1e9) { err(p, "значение " + d + " вне диапазона 0..1e9"); return 0; }
            return d;
        }

        /** Текст message/broadcast/команды: строка, без лишних плейсхолдеров. Пустую строку пропускает вызывающий. */
        String text(Map<?, ?> m, String key, String path) {
            if (!m.containsKey(key)) return "";
            Object o = m.get(key);
            String p = path + "." + key;
            if (!(o instanceof String)) { err(p, "ожидается строка, получено " + typeName(o)); return ""; }
            String s = (String) o;
            if (s.length() > MAX_TEXT) { err(p, "длина " + s.length() + " больше " + MAX_TEXT); return ""; }
            if (PlaceholderGuard.hasLineBreakOrControl(s)) { err(p, "перевод строки и управляющие символы запрещены"); return ""; }
            placeholders(s, p);
            return s;
        }

        void placeholders(String s, String p) {
            Matcher mt = PLACEHOLDER.matcher(s);
            while (mt.find()) if (!mt.group().equals("{player}")) err(p, "неизвестный плейсхолдер " + mt.group() + " (разрешён только {player})");
        }

        List<String> commands(Map<?, ?> m, String path) {
            List<String> out = new ArrayList<>();
            if (!m.containsKey("commands")) return out;
            String p = path + ".commands";
            if (!(m.get("commands") instanceof List<?>)) { err(p, "ожидается список, получено " + typeName(m.get("commands"))); return out; }
            List<?> l = (List<?>) m.get("commands");
            if (l.size() > MAX_COMMANDS) err(p, "команд " + l.size() + " больше " + MAX_COMMANDS);
            for (int i = 0; i < l.size(); i++) {
                String ip = p + "[" + i + "]";
                if (!(l.get(i) instanceof String)) { err(ip, "ожидается строка, получено " + typeName(l.get(i))); continue; }
                String c = (String) l.get(i);
                if (blank(c)) { err(ip, "пустая команда"); continue; }
                if (c.length() > MAX_TEXT) { err(ip, "длина " + c.length() + " больше " + MAX_TEXT); continue; }
                if (PlaceholderGuard.hasLineBreakOrControl(c)) { err(ip, "перевод строки и управляющие символы в команде запрещены"); continue; }
                if (c.startsWith("/")) { err(ip, "команда не должна начинаться с '/'"); continue; }
                placeholders(c, ip);
                out.add(c);
            }
            return out;
        }

        List<RewardsSettings.ItemSpec> items(Map<?, ?> m, String path) {
            List<RewardsSettings.ItemSpec> out = new ArrayList<>();
            if (!m.containsKey("items")) return out;
            String p = path + ".items";
            if (!(m.get("items") instanceof List<?>)) { err(p, "ожидается список, получено " + typeName(m.get("items"))); return out; }
            List<?> l = (List<?>) m.get("items");
            if (l.size() > MAX_LIST) err(p, "предметов " + l.size() + " больше " + MAX_LIST);
            for (int i = 0; i < l.size(); i++) {
                String ip = p + "[" + i + "]";
                Map<?, ?> e = section(l.get(i), ip, keys("material", "amount"));
                if (e == null) continue;
                String mat = null;
                if (!e.containsKey("material")) err(ip + ".material", "обязательный ключ");
                else if (!(e.get("material") instanceof String)) err(ip + ".material", "ожидается строка, получено " + typeName(e.get("material")));
                else {
                    String ms = (String) e.get("material");
                    if (blank(ms) || !materialExists.test(ms)) err(ip + ".material", "неизвестный или недопустимый материал '" + ms + "' (не legacy, не AIR)");
                    else mat = ms;
                }
                int amount = 1;
                if (!e.containsKey("amount")) err(ip + ".amount", "обязательный ключ");
                else amount = intValue(e.get("amount"), ip + ".amount", 1, 2304, 1);
                if (mat != null) out.add(new RewardsSettings.ItemSpec(mat, amount));
            }
            return out;
        }

        /** Общие поля блока награды; extra — ключи сверх commands/items/money/message(+broadcast для default). */
        RewardsSettings.RewardBlock block(Map<?, ?> m, String path, boolean withBroadcast) {
            String broadcast = withBroadcast ? text(m, "broadcast", path) : "";
            return new RewardsSettings.RewardBlock(commands(m, path), items(m, path), money(m, path), text(m, "message", path), broadcast);
        }

        static Set<String> keys(String... k) { return new LinkedHashSet<>(java.util.Arrays.asList(k)); }

        RewardsSettings root(Map<String, Object> raw) {
            String P = "rewards";
            Map<?, ?> m = section(raw, P, keys("enabled", "dry-run", "services", "timezone", "daily-limit",
                    "duplicate-protection", "offline-queue", "default", "weighted-pool", "streaks", "logging"));
            if (m == null) return null;
            RewardsSettings d = RewardsSettings.disabled();
            boolean enabled = bool(m, "enabled", P, false), dryRun = bool(m, "dry-run", P, false);

            Set<String> services = new LinkedHashSet<>();
            if (m.containsKey("services")) {
                if (!(m.get("services") instanceof List<?>)) err(P + ".services", "ожидается список, получено " + typeName(m.get("services")));
                else for (int i = 0; i < ((List<?>) m.get("services")).size(); i++) {
                    Object sv = ((List<?>) m.get("services")).get(i);
                    if (sv instanceof String && !blank((String) sv)) services.add(((String) sv).trim().toLowerCase(java.util.Locale.ROOT));
                    else err(P + ".services[" + i + "]", "ожидается непустая строка");
                }
            }

            ZoneId zone = d.timezone();
            if (m.containsKey("timezone")) {
                if (!(m.get("timezone") instanceof String)) err(P + ".timezone", "ожидается строка, получено " + typeName(m.get("timezone")));
                else {
                    String s = (String) m.get("timezone");
                    try { zone = ZoneId.of(s); } catch (Exception e) { err(P + ".timezone", "неизвестный часовой пояс '" + s + "' (нужен IANA, например Europe/Moscow)"); }
                }
            }

            int dailyLimit = integer(m, "daily-limit", P, 0, 0, 1000);

            RewardsSettings.DuplicateProtection dup = d.duplicateProtection();
            if (m.containsKey("duplicate-protection")) {
                String p = P + ".duplicate-protection";
                Map<?, ?> s = section(m.get("duplicate-protection"), p, keys("enabled", "retention-days"));
                if (s != null) dup = new RewardsSettings.DuplicateProtection(bool(s, "enabled", p, true), integer(s, "retention-days", p, 30, 1, 3650));
            }

            RewardsSettings.OfflineQueueSettings oq = d.offlineQueue();
            if (m.containsKey("offline-queue")) {
                String p = P + ".offline-queue";
                Map<?, ?> s = section(m.get("offline-queue"), p, keys("enabled", "max-per-player", "expire-days"));
                if (s != null) oq = new RewardsSettings.OfflineQueueSettings(bool(s, "enabled", p, true),
                        integer(s, "max-per-player", p, 50, 1, 1000), integer(s, "expire-days", p, 7, 1, 3650));
            }

            RewardsSettings.RewardBlock def = RewardsSettings.RewardBlock.empty();
            if (m.containsKey("default")) {
                String p = P + ".default";
                Map<?, ?> s = section(m.get("default"), p, keys("commands", "items", "money", "message", "broadcast"));
                if (s != null) def = block(s, p, true);
            }

            RewardsSettings.WeightedPool pool = d.weightedPool();
            if (m.containsKey("weighted-pool")) {
                String p = P + ".weighted-pool";
                Map<?, ?> s = section(m.get("weighted-pool"), p, keys("enabled", "entries"));
                if (s != null) {
                    boolean en = bool(s, "enabled", p, false);
                    List<RewardsSettings.PoolEntry> entries = new ArrayList<>();
                    if (s.containsKey("entries")) {
                        if (!(s.get("entries") instanceof List<?>)) err(p + ".entries", "ожидается список, получено " + typeName(s.get("entries")));
                        else {
                            List<?> l = (List<?>) s.get("entries");
                            if (l.size() > MAX_LIST) err(p + ".entries", "записей " + l.size() + " больше " + MAX_LIST);
                            for (int i = 0; i < l.size(); i++) {
                                String ep = p + ".entries[" + i + "]";
                                Map<?, ?> e = section(l.get(i), ep, keys("weight", "commands", "items", "money", "message"));
                                if (e == null) continue;
                                int w = 1;
                                if (!e.containsKey("weight")) err(ep + ".weight", "обязательный ключ");
                                else w = intValue(e.get("weight"), ep + ".weight", 1, 1_000_000, 1);
                                entries.add(new RewardsSettings.PoolEntry(w, block(e, ep, false)));
                            }
                        }
                    }
                    if (en && entries.isEmpty()) err(p + ".entries", "пул включён, но записей нет");
                    pool = new RewardsSettings.WeightedPool(en, entries);
                }
            }

            RewardsSettings.Streaks streaks = d.streaks();
            if (m.containsKey("streaks")) {
                String p = P + ".streaks";
                Map<?, ?> s = section(m.get("streaks"), p, keys("enabled", "milestones"));
                if (s != null) {
                    boolean en = bool(s, "enabled", p, false);
                    List<RewardsSettings.Milestone> ms = new ArrayList<>();
                    if (s.containsKey("milestones")) {
                        if (!(s.get("milestones") instanceof List<?>)) err(p + ".milestones", "ожидается список, получено " + typeName(s.get("milestones")));
                        else {
                            List<?> l = (List<?>) s.get("milestones");
                            if (l.size() > MAX_LIST) err(p + ".milestones", "милестоунов " + l.size() + " больше " + MAX_LIST);
                            for (int i = 0; i < l.size(); i++) {
                                String mp = p + ".milestones[" + i + "]";
                                Map<?, ?> e = section(l.get(i), mp, keys("days", "repeat", "commands", "items", "money", "message"));
                                if (e == null) continue;
                                int days = 1;
                                if (!e.containsKey("days")) err(mp + ".days", "обязательный ключ");
                                else days = intValue(e.get("days"), mp + ".days", 1, 3650, 1);
                                ms.add(new RewardsSettings.Milestone(days, bool(e, "repeat", mp, false), block(e, mp, false)));
                            }
                        }
                    }
                    streaks = new RewardsSettings.Streaks(en, ms);
                }
            }

            boolean logging = d.logging();
            if (m.containsKey("logging")) {
                String p = P + ".logging";
                Map<?, ?> s = section(m.get("logging"), p, keys("enabled"));
                if (s != null) logging = bool(s, "enabled", p, true);
            }

            return new RewardsSettings(enabled, dryRun, services, zone, dailyLimit, dup, oq, def, pool, streaks, logging);
        }
    }

    private static boolean blank(String s) { return ru.craftradar.votes.J8.blank(s); }

    private static String typeName(Object o) {
        if (o == null) return "пусто (null)";
        if (o instanceof Map) return "секция";
        if (o instanceof List) return "список";
        if (o instanceof String) return "строка";
        if (o instanceof Boolean) return "boolean";
        if (o instanceof Number) return "число " + o;
        return o.getClass().getSimpleName();
    }
}
