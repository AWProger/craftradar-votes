package ru.craftradar.votes.rewards;

import java.util.Objects;

/**
 * Одно действие награды. id = {eventKey}#{n}. Плоская запись (без иерархии), чтобы JSON-состояние
 * сериализовалось без адаптеров. Поля неиспользуемые для типа: null / 0.
 * COMMAND: text = команда с уже подставленным ником; ITEM: material+amount; MONEY: money;
 * MESSAGE/BROADCAST: text (цветовые & коды транслирует шлюз).
 * Имена полей = ключи JSON в rewards-state.json (совместимость с 1.0.0, где это был record).
 */
public final class Action {
    public enum Type { COMMAND, ITEM, MONEY, MESSAGE, BROADCAST }

    private final String id, player;
    private final Type type;
    private final String text, material;
    private final int amount;
    private final double money;

    public Action(String id, String player, Type type, String text, String material, int amount, double money) {
        this.id = id; this.player = player; this.type = type; this.text = text; this.material = material; this.amount = amount; this.money = money;
    }
    public String id() { return id; }
    public String player() { return player; }
    public Type type() { return type; }
    public String text() { return text; }
    public String material() { return material; }
    public int amount() { return amount; }
    public double money() { return money; }

    public static Action command(String id, String player, String cmd) { return new Action(id, player, Type.COMMAND, cmd, null, 0, 0); }
    public static Action item(String id, String player, String material, int amount) { return new Action(id, player, Type.ITEM, null, material, amount, 0); }
    public static Action money(String id, String player, double money) { return new Action(id, player, Type.MONEY, null, null, 0, money); }
    public static Action message(String id, String player, String text) { return new Action(id, player, Type.MESSAGE, text, null, 0, 0); }
    public static Action broadcast(String id, String player, String text) { return new Action(id, player, Type.BROADCAST, text, null, 0, 0); }

    /** Копия с другим количеством (остаток предметов после частичной выдачи). */
    public Action withAmount(int newAmount) { return new Action(id, player, type, text, material, newAmount, money); }

    /** Опасные (не идемпотентные) действия: at-most-once, см. §4. */
    public boolean atMostOnce() { return type == Type.COMMAND || type == Type.MONEY; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Action)) return false;
        Action a = (Action) o;
        return amount == a.amount && Double.compare(money, a.money) == 0 && type == a.type && Objects.equals(id, a.id)
                && Objects.equals(player, a.player) && Objects.equals(text, a.text) && Objects.equals(material, a.material);
    }
    @Override public int hashCode() { return Objects.hash(id, player, type, text, material, amount, money); }
    @Override public String toString() {
        return "Action[id=" + id + ", player=" + player + ", type=" + type + ", text=" + text + ", material=" + material + ", amount=" + amount + ", money=" + money + "]";
    }
}
