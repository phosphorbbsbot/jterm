package io.jterm.screen;

import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalSize;
import io.jterm.style.AnsiCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Blink-visibility law (menu-blink regression, 2026-10-20):
 *
 * The menu-selection blink renders two tiny transient deltas (highlight-ON,
 * highlight-OFF, each ~20 cells) immediately before a burst of transition
 * frames. When a 2026-capable client is attached, the bracket policy wraps
 * every non-tiny delta in BSU/ESU. Terminal.app honors mode-2026 by holding
 * bracketed regions and repainting atomically, and a short bracketed blink
 * followed within ~100ms by a flood of larger unbracketed transition frames
 * gets coalesced away — the user never sees either blink phase (Terminal.app
 * telnet: zero blinks; web xterm.js: one of two; MuffinTerm without 2026:
 * both). Latency-sensitive transient deltas must paint UNBRACKETED.
 *
 * Law: the gui can suppress bracket EMission for refreshes issued while the
 * suppression window is open; DefaultScreen honors it on every refresh path
 * (DELTA and COMPLETE alike) without changing the byte payload.
 */
class BlinkSyncSuppressTest {

    private DefaultScreen screen;
    private MockTerminal terminal;

    @BeforeEach
    void setUp() throws Exception {
        terminal = new MockTerminal(new TerminalSize(80, 24));
        terminal.setSyncOutputSupported(true);   // pretend the client declared 2026 support
        screen = new DefaultScreen(terminal);
        screen.startScreen();
        screen.clear();
        screen.refresh(RefreshType.COMPLETE);
        terminal.getOutput(); // drain baseline
    }

    /** Baseline: without suppression, a small (>16 cells) delta IS bracketed. */
    @Test
    void smallDeltaIsBracketedWhenSyncSupported() throws Exception {
        for (int c = 0; c < 20; c++) {
            screen.setCell(c, 0, new io.jterm.style.TextCell((char) ('A' + c), io.jterm.style.AnsiColor.WHITE, io.jterm.style.AnsiColor.BLACK));
        }
        screen.refresh(RefreshType.DELTA);
        String out = terminal.getOutput();
        assertTrue(out.contains(AnsiCodes.BEGIN_SYNC_UPDATE),
                "small delta >16 cells must bracket when sync supported; output was: " + out);
        assertTrue(out.contains(AnsiCodes.END_SYNC_UPDATE));
    }

    /** THE LAW: with the suppression window open, the same delta paints unbracketed. */
    @Test
    void suppressedRefreshPaintsUnbracketed() throws Exception {
        screen.setSyncBracketSuppressed(true);
        try {
            for (int c = 0; c < 20; c++) {
                screen.setCell(c, 0, new io.jterm.style.TextCell((char) ('A' + c), io.jterm.style.AnsiColor.WHITE, io.jterm.style.AnsiColor.BLACK));
            }
            screen.refresh(RefreshType.DELTA);
            String out = terminal.getOutput();
            assertFalse(out.contains(AnsiCodes.BEGIN_SYNC_UPDATE),
                    "suppressed delta must not bracket; output was: " + out);
            // content unchanged: the delta bytes must still be emitted
            assertTrue(out.contains("A"), "suppressed refresh must still paint");
            assertFalse(out.contains(AnsiCodes.END_SYNC_UPDATE));
            assertFalse(screen.syncBracketActive(), "no bracket must remain open");
        } finally {
            screen.setSyncBracketSuppressed(false);
        }
    }

    /** After the suppression window closes, bracketing resumes. */
    @Test
    void bracketingResumesAfterSuppressionCloses() throws Exception {
        for (int c = 0; c < 20; c++) {
            screen.setCell(c, 1, new io.jterm.style.TextCell((char) ('a' + c), io.jterm.style.AnsiColor.WHITE, io.jterm.style.AnsiColor.BLACK));
        }
        screen.setSyncBracketSuppressed(true);
        screen.refresh(RefreshType.DELTA);
        terminal.getOutput();
        screen.setSyncBracketSuppressed(false);
        for (int c = 0; c < 20; c++) {
            screen.setCell(c, 1, new io.jterm.style.TextCell((char) ('z' - c), io.jterm.style.AnsiColor.WHITE, io.jterm.style.AnsiColor.BLACK));
        }
        screen.refresh(RefreshType.DELTA);
        String out = terminal.getOutput();
        assertTrue(out.contains(AnsiCodes.BEGIN_SYNC_UPDATE),
                "normal bracketing must resume after suppression closes; output was: " + out);
    }
}