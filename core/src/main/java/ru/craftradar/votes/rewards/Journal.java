package ru.craftradar.votes.rewards;

/** Журнал наград (rewards.log). Реализация не должна писать секреты. */
public interface Journal {
    void log(RewardStatus status, String eventKey, String player, String detail);
}
