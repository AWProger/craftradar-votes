package ru.craftradar.votes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** add() не должен показывать запись опросу очереди раньше, чем файл записан: иначе read() не находит файл, удаляет запись из индекса, а файл остаётся сиротой до перезапуска. */
class DiskQueueAddRaceTest {
    @TempDir Path tmp;

    @Test void concurrentPumpNeverDropsFreshlyAddedItem() throws Exception {
        DiskQueue q = new DiskQueue(tmp);
        AtomicBoolean stop = new AtomicBoolean();
        Thread pump = new Thread(() -> {   // как periodic pump: берёт созревшие записи и читает их с диска
            while (!stop.get()) {
                for (String id : q.dueIds(Long.MAX_VALUE - 1)) {
                    try { q.read(id); } catch (Exception ignored) {}
                }
            }
        });
        pump.start();
        int n = 150;
        try {
            for (int i = 0; i < n; i++) assertTrue(q.add(DiskQueue.key("S", "u" + i, "1"), "{\"n\":" + i + "}", 1L));
        } finally { stop.set(true); pump.join(); }
        assertEquals(n, q.size(), "ни одна добавленная запись не пропала из индекса");
        assertEquals(n, q.dueIds(Long.MAX_VALUE - 1).size(), "и все готовы к отправке после записи файла");
        try (var files = java.nio.file.Files.list(tmp.resolve("queue"))) {
            assertEquals(n, files.filter(p -> p.toString().endsWith(".json")).count(), "файлов столько же: сирот нет");
        }
    }
}
