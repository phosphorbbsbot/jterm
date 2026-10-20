package io.jterm.screen;

import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalSize;
import io.jterm.style.AnsiCodes;
import io.jterm.style.TextCell;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD RED tests for synchronized output (DEC private mode 2026).
 * When a terminal declares sync support, DefaultScreen must wrap each
 * emitted frame in BEGIN_SYNC_UPDATE / END_SYNC_UPDATE (BSU/ESU) via
 * terminal.writeRaw, balanced even on mid-frame failure (try/finally),
 * and must emit absolutely nothing for empty deltas.
 */
class DefaultScreenSyncOutputTest {

    private static final String BSU = AnsiCodes.BEGIN_SYNC_UPDATE;
    private static final String ESU = AnsiCodes.END_SYNC_UPDATE;

    /** Count non-overlapping occurrences of needle in haystack. */
    private static int count(String haystack, String needle) {
        int n = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            n++;
            idx += needle.length();
        }
        return n;
    }

    /** Render escapes readably in failure messages. */
    private static String repr(String s) {
        return s.replace("\u001B", "<ESC>");
    }

    @Test
    void completeRefreshIsBracketedBySyncPair() throws Exception {
        var term = new MockTerminal(new TerminalSize(10, 5));
        var screen = new DefaultScreen(term);
        screen.startScreen();
        term.clearOutput();

        term.setSyncOutputSupported(true);
        screen.refresh(RefreshType.COMPLETE);

        String out = term.getOutput();
        assertFalse(out.isEmpty(), "a complete refresh must emit a frame");
        // Card-6 bracket policy: COMPLETE frames paint UNBRACKETED so slow
        // clients render progressively (a bracketed full-screen redraw held
        // the web terminal's renderer for the entire frame transfer).
        assertEquals(0, count(out, BSU), "COMPLETE must be unbracketed: " + repr(out));
        assertEquals(0, count(out, ESU), "COMPLETE must be unbracketed: " + repr(out));
        assertFalse(screen.syncBracketActive(), "bracket must not stay active after refresh");
    }

    @Test
    void unsupportedTerminalProducesLegacyOutputWithoutSyncSequences() throws Exception {
        var term = new MockTerminal(new TerminalSize(10, 5));
        var screen = new DefaultScreen(term);
        screen.startScreen();
        term.clearOutput();

        term.setSyncOutputSupported(false); // explicit: same as the interface default
        screen.setCell(0, 0, new TextCell('Z'));
        screen.refresh(RefreshType.COMPLETE);

        String out = term.getOutput();
        assertFalse(out.contains("2026"), "no 2026 sequences when unsupported: " + repr(out));
        assertFalse(out.isEmpty(), "frame must still be emitted");
        assertFalse(screen.syncBracketActive(), "no bracket state may linger");

        term.clearOutput();
        screen.setCell(1, 0, new TextCell('Y'));
        screen.refresh(RefreshType.DELTA); // same on the delta path
        String deltaOut = term.getOutput();
        assertFalse(deltaOut.contains("2026"), "no 2026 sequences on delta path: " + repr(deltaOut));
        assertTrue(deltaOut.contains("Y"), "delta frame content must be emitted: " + repr(deltaOut));
    }

    @Test
    void emptyFrameEmitsNothing() throws Exception {
        var term = new MockTerminal(new TerminalSize(10, 5));
        var screen = new DefaultScreen(term);
        screen.startScreen();
        term.setSyncOutputSupported(true);
        screen.refresh(RefreshType.COMPLETE); // establish front == back
        term.clearOutput();

        screen.refresh(RefreshType.DELTA); // no diffs → nothing at all, not even a lone BSU
        assertEquals("", term.getOutput(),
                "empty delta must emit no bytes when sync supported, got: " + repr(term.getOutput()));

        screen.refresh(RefreshType.AUTOMATIC); // no diffs → nothing at all
        assertEquals("", term.getOutput(),
                "empty automatic frame must emit no bytes, got: " + repr(term.getOutput()));
    }

    @Test
    void deltaRefreshWithChangesIsBracketed() throws Exception {
        var term = new MockTerminal(new TerminalSize(10, 5));
        var screen = new DefaultScreen(term);
        screen.startScreen();
        term.setSyncOutputSupported(true);
        screen.refresh(RefreshType.COMPLETE);
        term.clearOutput();

        screen.setCell(3, 2, new TextCell('X'));
        screen.refresh(RefreshType.DELTA);

        String out = term.getOutput();
        assertTrue(out.contains("X"), "delta frame content must be emitted: " + repr(out));
        assertEquals(1, count(out, BSU), "exactly one BSU: " + repr(out));
        assertEquals(1, count(out, ESU), "exactly one ESU: " + repr(out));
        assertTrue(out.startsWith(BSU), "delta refresh must open with BSU: " + repr(out));
        assertTrue(out.endsWith(ESU), "delta refresh must close with ESU: " + repr(out));
    }

    @Test
    void automaticRefreshBracketsSmallFramesButNotLarge() throws Exception {
        var term = new MockTerminal(new TerminalSize(10, 5));
        var screen = new DefaultScreen(term);
        screen.startScreen();
        term.setSyncOutputSupported(true);
        screen.refresh(RefreshType.COMPLETE);
        term.clearOutput();

        // Small diff (1 of 50 cells) → delta path, still bracketed.
        screen.setCell(4, 1, new TextCell('a'));
        screen.refresh(RefreshType.AUTOMATIC);
        String small = term.getOutput();
        assertTrue(small.contains("a"), repr(small));
        assertTrue(small.startsWith(BSU), "small automatic frame must open with BSU: " + repr(small));
        assertTrue(small.endsWith(ESU), "small automatic frame must close with ESU: " + repr(small));
        assertEquals(1, count(small, BSU), repr(small));
        assertEquals(1, count(small, ESU), repr(small));

        term.clearOutput();
        // Large diff (all 50 cells > half-area threshold → complete path):
        // per the card-6 bracket policy, large/complete frames paint
        // UNBRACKETED so slow clients render progressively instead of holding
        // the screen for the whole frame transfer.
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 10; c++) {
                screen.setCell(c, r, new TextCell('#'));
            }
        }
        screen.refresh(RefreshType.AUTOMATIC);
        String large = term.getOutput();
        assertTrue(large.contains("#"), repr(large));
        assertEquals(0, count(large, BSU), "large automatic frame must NOT bracket: " + repr(large));
        assertEquals(0, count(large, ESU), "large automatic frame must NOT bracket: " + repr(large));
    }

    /**
     * MockTerminal that can (a) throw on flush to simulate mid-frame failure and
     * (b) capture {@code DefaultScreen#syncBracketActive()} at the first frame byte.
     */
    static class ProbeTerminal extends MockTerminal {
        BooleanSupplier bracketProbe;
        Boolean bracketActiveAtFirstFrameByte;
        boolean failFlush;

        ProbeTerminal(TerminalSize size) {
            super(size);
        }

        @Override
        public void putCharacter(char c) throws IOException {
            if (bracketActiveAtFirstFrameByte == null && bracketProbe != null) {
                bracketActiveAtFirstFrameByte = bracketProbe.getAsBoolean();
            }
            super.putCharacter(c);
        }

        @Override
        public void flush() throws IOException {
            if (failFlush) {
                throw new IOException("synthetic flush failure");
            }
            super.flush();
        }
    }

    @Test
    void failureMidFrameStillEmitsEndBracket() throws Exception {
        var term = new ProbeTerminal(new TerminalSize(10, 5));
        var screen = new DefaultScreen(term);
        assertFalse(screen.syncBracketActive(), "no bracket before any refresh");
        screen.startScreen();
        term.setSyncOutputSupported(true);
        term.bracketProbe = screen::syncBracketActive;

        // Bracket policy (card 6) brackets SMALL deltas only — drive the
        // mid-frame flush failure through a small delta (1 of 50 cells, well
        // under the quarter-area cap), which is bracketed and always flushes.
        // Base COMPLETE first (unbracketed), then arm the failure.
        screen.refresh(RefreshType.COMPLETE);
        term.clearOutput();
        // Reset the first-byte capture: the base COMPLETE frame was emitted
        // UNBRACKETED (card-6 policy), and only the bracketed delta frame
        // below is under test.
        term.bracketActiveAtFirstFrameByte = null;
        term.failFlush = true;
        screen.setCell(2, 1, new TextCell('F'));

        var ex = assertThrows(IOException.class, () -> screen.refresh(RefreshType.DELTA));
        assertEquals("synthetic flush failure", ex.getMessage());

        String out = term.getOutput();
        assertEquals(1, count(out, BSU), "BSU still emitted: " + repr(out));
        assertEquals(1, count(out, ESU), "ESU must be emitted even when the frame write fails: " + repr(out));
        assertTrue(out.indexOf(BSU) < out.indexOf(ESU), "bracket order preserved on failure path");
        assertNotNull(term.bracketActiveAtFirstFrameByte, "frame bytes must have been written");
        assertTrue(term.bracketActiveAtFirstFrameByte, "bracket must be active while frame bytes are emitted");
        assertFalse(screen.syncBracketActive(), "bracket must not dangle after failure");
    }
}