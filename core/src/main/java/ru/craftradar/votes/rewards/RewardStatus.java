package ru.craftradar.votes.rewards;

/** Статусы журнала (контракт §4). GRANTED только когда все действия DONE. */
public enum RewardStatus {
    VOTE_RECEIVED, REJECTED_DUPLICATE, REJECTED_SERVICE, REJECTED_PLAYER, LIMIT_REACHED,
    COMPUTED, QUEUED, ACTION_DONE, ACTION_FAILED, ACTION_UNCERTAIN, GRANTED,
    SKIPPED_OFFLINE, DRY_RUN, CONFIG_INVALID
}
