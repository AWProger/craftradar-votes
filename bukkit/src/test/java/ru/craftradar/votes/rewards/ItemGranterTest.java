package ru.craftradar.votes.rewards;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ItemGranterTest {
    @Test void splitByStack() {
        assertEquals(36, ItemGranter.split(2304, 64).size());
        assertEquals(List.of(64, 64, 22), ItemGranter.split(150, 64));
        assertEquals(List.of(16, 4), ItemGranter.split(20, 16));
        assertEquals(List.of(1, 1, 1), ItemGranter.split(3, 1));
        assertEquals(List.of(3), ItemGranter.split(3, 64));
    }

    @Test void splitEdges() {
        assertEquals(List.of(), ItemGranter.split(0, 64));
        assertEquals(List.of(), ItemGranter.split(-5, 64));
        assertEquals(List.of(1, 1), ItemGranter.split(2, 0)); // защита от нулевого maxStack
        assertEquals(2304, ItemGranter.split(2304, 64).stream().mapToInt(Integer::intValue).sum());
    }

    @Test void remainderSum() {
        assertEquals(0, ItemGranter.sum(List.of()));
        assertEquals(86, ItemGranter.sum(List.of(64, 22)));
    }

}
