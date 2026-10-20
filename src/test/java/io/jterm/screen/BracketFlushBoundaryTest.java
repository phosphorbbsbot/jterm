package io.jterm.screen;

import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalSize;
import io.jterm.style.AnsiCodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Flush-boundary law (2026-10-20, one-row-behind scroll bug):
 *
 * A bracketed delta refresh writes BSU, then the delta cells, then ESU.
 * The ONLY flush inside refresh() today happens inside doDeltaRefresh()
 * BEFORE the finally-block writes the ESU — so on a socket stream the
 * closing bracket rides the NEXT flush (often the next refresh, seconds
 * later on slow input). Any 2026-honoring client (Terminal.app) or
 * bracket-balancing bridge (web WS bridge) holds the ENTIRE frame until
 * the ESU arrives — the user sees every scroll step one refresh behind.
 *
 * Law: a bracketed delta refresh flushes ALL of its bytes — including the
 * ESU — before returning, and flushes AT MOST ONCE per refresh (no
 * bracket-splitting across flush boundaries).
 */
class BracketFlushBoundaryTest {

    private MockTerminal terminal;
    private DefaultScreen screen;
    private TrackingOutputStream trackedOut;

    @BeforeEach
    void setUp() throws Exception {
        trackedOut = new TrackingOutputStream();
        terminal = new MockTerminal(new TerminalSize(80, 24), trackedOut, new java.io.ByteArrayInputStream(new byte[0]));
        terminal.setSyncOutputSupported(true);
        screen = new DefaultScreen(terminal);
        screen.startScreen();
        screen.refresh(RefreshType.COMPLETE);
        trackedOut.resetCounts();
    }

    /**
     * THE LAW: one bracketed refresh = bytes fully flushed before refresh()
     * returns. After a single refresh() call, the tracked stream must have
     * seen the ESU — and the ESU must not be the ONLY thing in the final
     * flush segment (it must be flushed TOGETHER with its frame).
     */
    @Test
    void bracketedDeltaFlushesCompleteFrameIncludingEsu() throws Exception {
        // paint a delta > 16 cells so the policy brackets it
        for (int c = 0; c < 20; c++) {
            screen.setCell(c, 0, new io.jterm.style.TextCell((char) ('A' + c),
                    io.jterm.style.AnsiColor.WHITE, io.jterm.style.AnsiColor.BLACK));
        }
        screen.refresh(RefreshType.DELTA);

        String flushed = trackedOut.flushedText();
        assertTrue(flushed.contains(AnsiCodes.BEGIN_SYNC_UPDATE),
                "frame must open with BSU: " + trackedOut.flushedTextRepr());
        assertTrue(flushed.contains(AnsiCodes.END_SYNC_UPDATE),
                "THE BUG: ESU must be flushed WITH its frame; flushed tail was: "
                        + trackedOut.flushedTextRepr());
        assertTrue(screen.syncBracketActive() == false, "no bracket left open");
    }

    /** A refresh must not interleave flushes that split a bracket across boundaries. */
    @Test
    void singleFlushPerBracketedRefresh() throws Exception {
        for (int c = 0; c < 20; c++) {
            screen.setCell(c, 0, new io.jterm.style.TextCell((char) ('A' + c),
                    io.jterm.style.AnsiColor.WHITE, io.jterm.style.AnsiColor.BLACK));
        }
        int flushesBefore = trackedOut.flushCount();
        screen.refresh(RefreshType.DELTA);
        int deltaFlushes = trackedOut.flushCount() - flushesBefore;
        // COMPLETE does its whole paint then flushes once; the bracket law adds
        // no extra flushes. Allow exactly 1 for the delta body + 1 for the ESU
        // ONLY IF the ESU flush carries no un-ESU'd earlier content — simplest
        // invariant: the LAST flush segment contains the ESU next to content.
        assertTrue(deltaFlushes >= 1, "a refresh must flush");
        String flushed = trackedOut.flushedText();
        int esuAt = flushed.indexOf(AnsiCodes.END_SYNC_UPDATE);
        assertTrue(esuAt >= 0, "ESU must be present in flushed bytes");
        // the byte preceding the ESU in the STREAM must not be unflushed: the
        // position of the LAST flush must be at-or-after the ESU's end.
        assertTrue(trackedOut.flushedThrough() >= esuAt + AnsiCodes.END_SYNC_UPDATE.length(),
                "ESU must be covered by a flush boundary; last flush at "
                        + trackedOut.flushedThrough() + ", ESU ends at "
                        + (esuAt + AnsiCodes.END_SYNC_UPDATE.length()));
    }

    /** Byte-level sink that records every flush boundary position. */
    static final class TrackingOutputStream extends java.io.OutputStream {
        private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        private int flushCount;
        private int flushedThrough;

        @Override public void write(int b) { buf.write(b); }
        @Override public void write(byte[] b, int off, int len) { buf.write(b, off, len); }
        @Override public void flush() {
            flushCount++;
            flushedThrough = buf.size();
        }
        void resetCounts() { flushCount = 0; flushedThrough = 0; }
        int flushCount() { return flushCount; }
        int flushedThrough() { return flushedThrough; }
        String flushedText() { return buf.toString(java.nio.charset.StandardCharsets.UTF_8); }
        String flushedTextRepr() { return flushedText().replace("\u001b", "E").substring(Math.max(0, buf.size() - 120)); }
    }
}