package ru.craftradar.votes.rewards;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class CommandExecutorTest {
    static class FakeGw implements PlayerGateway {
        boolean online = true, economy = true, depositOk = true; int rest = 0;
        final List<String> msgs = new ArrayList<>(), bc = new ArrayList<>();
        public boolean isOnline(String p) { return online; }
        public int giveItems(String p, String m, int a) { return rest; }
        public boolean hasEconomy() { return economy; }
        public double balance(String p) { return 0; }
        public boolean deposit(String p, double a) { return depositOk; }
        public void sendMessage(String p, String t) { msgs.add(t); }
        public void broadcast(String t) { bc.add(t); }
    }

    final FakeGw gw = new FakeGw();
    final List<String> cmds = new ArrayList<>();
    final CommandExecutor ex = new CommandExecutor(gw, c -> cmds.add(c));

    ActionResult cmd(String player, String text) { return ex.execute(Action.command("k#0", player, text)); }

    @Test void substitutesPlayerAndDispatches() {
        assertEquals(ActionResult.Outcome.DONE, cmd("Steve_1", "give {player} diamond 1").outcome());
        assertEquals(List.of("give Steve_1 diamond 1"), cmds);
    }

    @Test void dispatchFalseIsFailed() {
        var e = new CommandExecutor(gw, c -> false);
        assertEquals(ActionResult.Outcome.FAILED, e.execute(Action.command("k#0", "Steve", "say hi")).outcome());
    }

    @Test void invalidNamesNeverReachDispatch() {
        for (String n : new String[]{null, "", "a b", "Steve;op", "Steve\n", "12345678901234567", "§bX", "{player}", "Ник"})
            assertEquals(ActionResult.Outcome.FAILED, cmd(n, "say {player}").outcome(), String.valueOf(n));
        assertTrue(cmds.isEmpty());
    }

    @Test void injectionViaNameImpossible() {
        assertEquals(ActionResult.Outcome.FAILED, cmd("x\nop Steve", "say {player}").outcome());
        assertTrue(cmds.isEmpty());
    }

    @Test void unknownPlaceholdersRejected() {
        for (String t : new String[]{"say {uuid}", "say {Player}", "say {player} {x}", "say {}", "say {{player}}"})
            assertEquals(ActionResult.Outcome.FAILED, cmd("Steve", t).outcome(), t);
        assertTrue(cmds.isEmpty());
    }

    @Test void newlinesAndControlRejected() {
        for (String t : new String[]{"say a\nop X", "say a\rb", "say a\u0000b", "say a b", "say a\u0085b"})
            assertEquals(ActionResult.Outcome.FAILED, cmd("Steve", t).outcome());
        assertTrue(cmds.isEmpty());
    }

    @Test void badCommandShapeRejected() {
        for (String t : new String[]{null, "", "   ", "/op {player}", "x".repeat(257)})
            assertEquals(ActionResult.Outcome.FAILED, cmd("Steve", t).outcome());
        assertEquals(ActionResult.Outcome.DONE, cmd("Steve", "x".repeat(256)).outcome());
        assertEquals(1, cmds.size());
    }

    @Test void dispatchExceptionBecomesFailedWithoutLeakingCommand() {
        var e = new CommandExecutor(gw, c -> { throw new IllegalStateException("boom " + c); });
        var r = e.execute(Action.command("k#0", "Steve", "secret {player}"));
        assertEquals(ActionResult.Outcome.FAILED, r.outcome());
        assertFalse(r.detail().contains("secret"));
    }

    @Test void runsOnMainThreadViaScheduler() throws Exception {
        ExecutorService main = Executors.newSingleThreadExecutor(r -> new Thread(r, "fake-main"));
        try {
            List<String> threads = new ArrayList<>();
            CommandExecutor.MainThread mt = new CommandExecutor.MainThread() {
                public <T> T call(java.util.function.Supplier<T> t) throws Exception { return main.submit(t::get).get(); }
            };
            var e = new CommandExecutor(gw, c -> threads.add(Thread.currentThread().getName()), mt);
            assertEquals(ActionResult.Outcome.DONE, e.execute(Action.command("k#0", "Steve", "say hi")).outcome());
            assertEquals(List.of("fake-main"), threads);
        } finally { main.shutdownNow(); }
    }

    @Test void invalidDataDoesNotTouchScheduler() {
        int[] calls = {0};
        CommandExecutor.MainThread mt = new CommandExecutor.MainThread() {
            public <T> T call(java.util.function.Supplier<T> t) { calls[0]++; return t.get(); }
        };
        var e = new CommandExecutor(gw, c -> true, mt);
        e.execute(Action.command("k#0", "bad name", "say hi"));
        assertEquals(0, calls[0]);
    }

    @Test void schedulerFailureIsFailed() {
        CommandExecutor.MainThread mt = new CommandExecutor.MainThread() {
            public <T> T call(java.util.function.Supplier<T> t) throws Exception { throw new TimeoutException(); }
        };
        var e = new CommandExecutor(gw, c -> cmds.add(c), mt);
        assertEquals(ActionResult.Outcome.FAILED, e.execute(Action.command("k#0", "Steve", "say hi")).outcome());
        assertTrue(cmds.isEmpty());
    }

    @Test void messageAndBroadcast() {
        assertEquals(ActionResult.Outcome.DONE, ex.execute(Action.message("k#1", "Steve", "&aПривет, {player}")).outcome());
        assertEquals(List.of("&aПривет, Steve"), gw.msgs);
        assertEquals(ActionResult.Outcome.DONE, ex.execute(Action.broadcast("k#2", "Steve", "{player} проголосовал")).outcome());
        assertEquals(List.of("Steve проголосовал"), gw.bc);
        gw.online = false;
        assertEquals(ActionResult.Outcome.RETRY_LATER, ex.execute(Action.message("k#1", "Steve", "hi")).outcome());
        assertEquals(1, gw.msgs.size());
        assertEquals(ActionResult.Outcome.FAILED, ex.execute(Action.broadcast("k#2", "Steve", "{uuid}")).outcome());
    }

    @Test void itemsAndMoney() {
        gw.rest = 2;
        var r = ex.execute(Action.item("k#3", "Steve", "DIAMOND", 5));
        assertEquals(ActionResult.Outcome.RETRY_LATER, r.outcome());
        assertEquals(2, r.remaining());
        gw.rest = 0;
        assertEquals(ActionResult.Outcome.DONE, ex.execute(Action.item("k#3", "Steve", "DIAMOND", 5)).outcome());
        gw.online = false;
        assertEquals(5, ex.execute(Action.item("k#3", "Steve", "DIAMOND", 5)).remaining());
        gw.online = true;
        assertEquals(ActionResult.Outcome.DONE, ex.execute(Action.money("k#4", "Steve", 10)).outcome());
        gw.depositOk = false;
        assertEquals(ActionResult.Outcome.FAILED, ex.execute(Action.money("k#4", "Steve", 10)).outcome());
        gw.economy = false;
        assertEquals(ActionResult.Outcome.RETRY_LATER, ex.execute(Action.money("k#4", "Steve", 10)).outcome());
        gw.economy = true; gw.depositOk = true;
        assertEquals(ActionResult.Outcome.FAILED, ex.execute(Action.money("k#4", "Steve", Double.NaN)).outcome());
        assertEquals(ActionResult.Outcome.FAILED, ex.execute(Action.money("k#4", "Steve", -1)).outcome());
    }
}
