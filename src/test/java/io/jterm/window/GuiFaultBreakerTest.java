package io.jterm.window;

import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalSize;
import io.jterm.screen.DefaultScreen;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The event-loop fault laws (Joe 2026-11-19, SSH reattach hang): a paint
 * fault in a dead/slow output must never spin the loop forever — the
 * original "recover and keep going" catch repainted into a CLOSED SSH
 * channel at ~80 renders/sec and grew bbs.log 1 GB in half an hour. LAWS:
 *   1) N consecutive faults (no successful iteration between) STOP the loop;
 *   2) the fault log records the FIRST and the TERMINATING fault of the
 *      streak, not every single one (a 64KB-buffered write fires the log
 *      once per flush — thousands lost to this before the breaker).
 */
class GuiFaultBreakerTest {

    /** A screen whose paint always fails, on both refresh signatures. */
    private static final class FailingScreen extends DefaultScreen {
        FailingScreen(MockTerminal terminal) { super(terminal); }
        private final AtomicInteger paints = new AtomicInteger();
        @Override
        public void refresh(io.jterm.screen.RefreshType type) throws java.io.IOException {
            paints.incrementAndGet();
            throw new java.io.IOException("sink closed (simulated dead SSH channel)");
        }
        @Override
        public void refresh() throws java.io.IOException {
            paints.incrementAndGet();
            throw new java.io.IOException("sink closed (simulated dead SSH channel)");
        }
        int paints() { return paints.get(); }
    }

    @Test
    void consecutivePaintFaultsStopTheLoop() throws Exception {
        var screen = new FailingScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        gui.addWindow(new AbstractWindow("Faults") { });
        var thread = new Thread(() -> {
            try { gui.runEventLoop(); } catch (Exception ignored) { }
        });
        thread.start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && gui.isRunning()) {
            Thread.sleep(25);
        }
        assertFalse(gui.isRunning(),
                "the event loop must stop after the consecutive-fault breaker fires"
                        + " (painted " + screen.paints() + " times)");
        assertTrue(screen.paints() < DefaultTextGUI.MAX_CONSECUTIVE_FAULTS * 4,
                "the failing paint must not be retried hundreds of times: " + screen.paints());
        thread.join(2_000);
    }
}
