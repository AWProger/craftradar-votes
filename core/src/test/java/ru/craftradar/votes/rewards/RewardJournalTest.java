package ru.craftradar.votes.rewards;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RewardJournalTest {
    @TempDir Path dir;
    private final Clock clock = () -> 1_700_000_000_000L;

    @Test void lineIsParsableAndSingle() throws Exception {
        RewardJournal j = new RewardJournal(dir, clock, true);
        j.log(RewardStatus.ACTION_DONE, "crdv1:abc", "Steve", "команда\nвторая\tстрока");
        List<String> lines = Files.readAllLines(dir.resolve("rewards.log"));
        assertEquals(1, lines.size());
        String[] f = lines.get(0).split("\t", -1);
        assertEquals(5, f.length);
        assertEquals("2023-11-14T22:13:20Z", f[0]);
        assertEquals("ACTION_DONE", f[1]);
        assertEquals("crdv1:abc", f[2]);
        assertEquals("Steve", f[3]);
        assertEquals("команда вторая строка", f[4]);
    }

    @Test void secretsRedactedAndEmptyFieldsDashed() throws Exception {
        RewardJournal j = new RewardJournal(dir, clock, true);
        j.log(RewardStatus.VOTE_RECEIVED, null, null, "token=SUPERSECRET password: hunter2 ok");
        String s = Files.readString(dir.resolve("rewards.log"));
        assertFalse(s.contains("SUPERSECRET"));
        assertFalse(s.contains("hunter2"));
        assertTrue(s.contains("token=***"));
        assertTrue(s.contains("\t-\t-\t"));
    }

    @Test void rotatesBySize() throws Exception {
        RewardJournal j = new RewardJournal(dir, clock, true, 400);
        for (int i = 0; i < 20; i++) j.log(RewardStatus.COMPUTED, "k" + i, "P", "x".repeat(40));
        assertTrue(Files.exists(dir.resolve("rewards.log.1")));
        assertTrue(Files.size(dir.resolve("rewards.log")) <= 400);
        assertTrue(Files.size(dir.resolve("rewards.log.1")) <= 400);
        assertTrue(Files.readString(dir.resolve("rewards.log")).contains("k19"));
    }

    @Test void disabledWritesNoFileButCounts() {
        RewardJournal j = new RewardJournal(dir, clock, false);
        j.log(RewardStatus.GRANTED, "k", "P", "");
        assertFalse(Files.exists(dir.resolve("rewards.log")));
        assertEquals(1L, j.counts().get(RewardStatus.GRANTED));
        assertEquals(0L, j.counts().get(RewardStatus.DRY_RUN));
    }

    @Test void grantedForbiddenAfterUncertain() throws Exception {
        RewardJournal j = new RewardJournal(dir, clock, true);
        j.log(RewardStatus.ACTION_UNCERTAIN, "k1", "P", "рестарт");
        assertThrows(IllegalStateException.class, () -> j.log(RewardStatus.GRANTED, "k1", "P", ""));
        j.log(RewardStatus.GRANTED, "k2", "P", "");
        assertFalse(Files.readString(dir.resolve("rewards.log")).contains("GRANTED\tk1"));
        assertEquals(1L, j.counts().get(RewardStatus.GRANTED));
    }

    @Test void writeFailureDoesNotThrow() throws Exception {
        Path blocker = dir.resolve("file");
        Files.writeString(blocker, "x");
        RewardJournal j = new RewardJournal(blocker, clock, true); // «папка» — файл
        j.log(RewardStatus.COMPUTED, "k", "P", "");
        assertNotNull(j.lastError());
        assertEquals(1L, j.counts().get(RewardStatus.COMPUTED));
    }
}
