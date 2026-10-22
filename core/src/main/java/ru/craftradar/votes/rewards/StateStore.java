package ru.craftradar.votes.rewards;

import java.io.IOException;

/** Хранилище состояния (rewards-state.json). save атомарен: tmp + rename. */
public interface StateStore {
    RewardsState load() throws IOException;
    void save(RewardsState state) throws IOException;
}
