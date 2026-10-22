package ru.craftradar.votes.rewards;

/** Всё, что движку нужно от сервера (Bukkit-клей реализует тонко; в тестах — фейк). Вызовы на главном потоке. */
public interface PlayerGateway {
    boolean isOnline(String player);
    /** Выдать предметы с учётом места в инвентаре; возвращает, сколько НЕ поместилось (0 = всё выдано). */
    int giveItems(String player, String material, int amount);
    /** Есть ли экономика (Vault + провайдер). */
    boolean hasEconomy();
    double balance(String player);
    /** false — не удалось (нет экономики, провайдер отказал). */
    boolean deposit(String player, double amount);
    /** Только онлайн-игроку; текст уже с подставленным ником, & коды транслирует реализация. */
    void sendMessage(String player, String text);
    void broadcast(String text);
}
