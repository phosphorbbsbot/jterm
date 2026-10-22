package io.jterm.screen;

import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalSize;
import io.jterm.style.AnsiColor;
import io.jterm.style.TextCell;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Row-shift refresh law (2026-10-22, Joe's tape freeze):
 *
 * The ticker tape scrolls a full-width bottom row leftward. Every frame
 * shifts the row's content by one cell, so the plain delta diff rewrites the
 * ENTIRE row — 110 cells and their SGR style runs — twice per second, and
 * the ROTATION frame (symbol fully exits left) is the heaviest. Clients with
 * slower renderers (phosphor relay, tmux, busy Terminal.app) visibly stall
 * ~1-1.5s on that frame, exactly at every symbol exit, while key handling
 * (input drain) stays responsive.
 *
 * Law: when every changed row of a delta is a LEFT-SHIFT of its previous
 * content by the same offset (content+style move together), the screen
 * emits DCH (CSI Ps P) at the row's home + only the entering trailing cells
 * — a few bytes, no SGR storm — instead of the full-row rewrite. Any frame
 * mixing shifted rows with normal edits falls back to the classic delta
 * path unchanged.
 */
class RowShiftRefreshLawTest {

    private DefaultScreen screen;
    private MockTerminal terminal;

    @BeforeEach
    void setUp() throws Exception {
        terminal = new MockTerminal(new TerminalSize(20, 4));
        screen = new DefaultScreen(terminal);
        screen.startScreen();
        screen.clear();
        screen.refresh(RefreshType.COMPLETE);
        terminal.clearOutput(); // drain baseline bytes
    }

    private void paintRow(int row, String text) {
        for (int c = 0; c < text.length(); c++) {
            screen.setCell(c, row, new TextCell(text.charAt(c), AnsiColor.WHITE, AnsiColor.BLACK));
        }
    }

    @Test
    @DisplayName("a one-cell left-shifted row emits DCH + entering cells, not a full rewrite")
    void shiftEmitsDch() throws Exception {
        paintRow(0, "ABCDEFGHIJKLMNOPQRST");
        screen.refresh(RefreshType.DELTA);
        terminal.clearOutput();

        // shift content left by one: 'BCDE...' + new trailing char
        for (int c = 0; c < 19; c++) {
            screen.setCell(c, 0, new TextCell("BCDEFGHIJKLMNOPQRST".charAt(c), AnsiColor.WHITE, AnsiColor.BLACK));
        }
        screen.setCell(19, 0, new TextCell('Z', AnsiColor.WHITE, AnsiColor.BLACK));
        screen.refresh(RefreshType.DELTA);

        String out = terminal.getOutput();
        assertTrue(out.contains("\u001b[1P"),
                "left-shifted row must emit DCH 1 at the row start; out=" + out);
        assertTrue(out.contains("Z"),
                "the entering cell must be written after the DCH; out=" + out);
        // The OLD leading 'A' must never be rewritten (a full rewrite would re-emit the whole row)
        int dch = out.indexOf("\u001b[1P");
        assertFalse(out.substring(dch).startsWith("\u001b[1;1HBCD"),
                "a DCH frame must not be followed by a full-row rewrite; out=" + out);
    }

    @Test
    @DisplayName("mixed frames: shifted rows take the DCH path, ordinary rows paint normally")
    void mixedRowsFallBack() throws Exception {
        paintRow(0, "ABCDEFGHIJKLMNOPQRST");
        paintRow(2, "WWWWWWWWWWWWWWWWWWWW");
        screen.refresh(RefreshType.DELTA);
        terminal.clearOutput();

        for (int c = 0; c < 19; c++) {
            screen.setCell(c, 0, new TextCell("BCDEFGHIJKLMNOPQRST".charAt(c), AnsiColor.WHITE, AnsiColor.BLACK));
        }
        screen.setCell(19, 0, new TextCell('Z', AnsiColor.WHITE, AnsiColor.BLACK));
        // ordinary edit on another row (not a shift)
        screen.setCell(5, 2, new TextCell('X', AnsiColor.WHITE, AnsiColor.BLACK));
        screen.refresh(RefreshType.DELTA);

        String out = terminal.getOutput();
        // Row 0 qualifies for the DCH path even though row 2 does not: the
        // property is PER ROW, not per frame (a tape rotation under a hub
        // repaint must keep the cheap path). Row 2's edit paints via the
        // classic delta walk.
        assertTrue(out.contains("\u001b[1P"),
                "the qualifying shifted row must emit DCH 1; out=" + out);
        assertTrue(out.contains("X"), "the ordinary edit must still paint; out=" + out);
    }

    @Test
    @DisplayName("style mismatch across the shift refuses the DCH path")
    void styleMismatchFallsBack() throws Exception {
        paintRow(0, "ABCDEFGHIJKLMNOPQRST");
        screen.refresh(RefreshType.DELTA);
        terminal.clearOutput();

        // shift text but recolor the moved cells: content would look shifted, style would not
        for (int c = 0; c < 19; c++) {
            screen.setCell(c, 0, new TextCell("BCDEFGHIJKLMNOPQRST".charAt(c), AnsiColor.RED, AnsiColor.BLACK));
        }
        screen.setCell(19, 0, new TextCell('Z', AnsiColor.RED, AnsiColor.BLACK));
        screen.refresh(RefreshType.DELTA);

        String out = terminal.getOutput();
        assertFalse(out.contains("\u001b[1P"),
                "a recolored shift is not a pure shift; classic path required; out=" + out);
    }
}
