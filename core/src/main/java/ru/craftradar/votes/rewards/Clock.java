package ru.craftradar.votes.rewards;

/** Источник времени (подменяется в тестах). */
public interface Clock {
    long nowMs();
}
