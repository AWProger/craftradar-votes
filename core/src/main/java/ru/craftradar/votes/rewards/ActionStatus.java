package ru.craftradar.votes.rewards;

/** Жизненный цикл действия (§4): PENDING -> ATTEMPTED -> DONE | FAILED | UNCERTAIN. */
public enum ActionStatus { PENDING, ATTEMPTED, DONE, FAILED, UNCERTAIN }
