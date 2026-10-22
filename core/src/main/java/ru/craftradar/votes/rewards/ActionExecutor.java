package ru.craftradar.votes.rewards;

/** Исполняет одно действие и сообщает итог; не бросает исключений наружу (сбой = FAILED). */
public interface ActionExecutor {
    ActionResult execute(Action action);
}
