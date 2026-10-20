package io.jterm.screen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalSize;
import io.jterm.style.TextCell;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Latency-sensitive echo law (2026-10-20): SMALL typing deltas (chat input,
 * login username echo) must NOT be wrapped in mode-2026 brackets. Reason:
 * the WS bridge coalesces frames at read boundaries; an ESU that slips into
 * the NEXT read parks the browser's renderer until it arrives — the user
 * sees text one character late ("everything up to but not including the
 * last character", chat typing, 2026-10-20). A tiny echo cannot tear:
 * painting unbracketed costs nothing visually and removes the hold.
 * Larger deltas keep the bracket (atomicity matters more there).
 */
class SmallEchoNoBracketTest {

    private static final String BSU = "\u001b[?2026h";
    private static final String ESU = "\u001b[?2026l";

    private static int count(String haystack, String needle) {
        int n = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            n++;
            idx += needle.length();
        }
        return n;
    }

    private static String repr(String s) {
        return s.replace("\u001B", "<ESC>");
    }

    /** New screen, sync ON, painted with '.' everywhere, COMPLETE emitted, output cleared. */
    private DefaultScreen baseScreen(MockTerminal term) throws Exception {
        var screen = new DefaultScreen(term);
        screen.startScreen();
        term.clearOutput();
        term.setSyncOutputSupported(true);
        for (int y = 0; y < term.getTerminalSize().rows(); y++) {
            for (int x = 0; x < term.getTerminalSize().columns(); x++) {
                screen.setCell(x, y, new TextCell('.'));
            }
        }
        screen.refresh(RefreshType.COMPLETE);
        term.clearOutput();
        return screen;
    }

    @Test
    @DisplayName("tiny typing delta (1-5 cells) paints UNBRACKETED — no bridge hold latency")
    void tinyDeltaSkipsBracket() throws Exception {
        var term = new MockTerminal(new TerminalSize(80, 24));   // area 1920, cap 480, tiny ≤ 16
        var screen = baseScreen(term);
        screen.setCell(40, 12, new TextCell('x'));
        screen.setCell(40, 12, new TextCell('!'));

        screen.refresh(RefreshType.DELTA);

        String out = term.getOutput();
        assertTrue(!out.isEmpty(), "echo frame emits");
        assertEquals(0, count(out, BSU), "tiny echo delta must be unbracketed: " + repr(out));
        assertEquals(0, count(out, ESU), "tiny echo delta must be unbracketed");
        assertTrue(out.contains("x") || out.contains("!"), "delta content present");
    }

    @Test
    @DisplayName("mid-size delta (16..cap) keeps the bracket — no regression")
    void midSizeDeltaStillBrackets() throws Exception {
        var term = new MockTerminal(new TerminalSize(80, 24));   // cap 480
        var screen = baseScreen(term);
        // 3 rows x 80 = 240 cells — above the tiny threshold, below the cap
        for (int r = 10; r < 13; r++) {
            for (int c = 0; c < 80; c++) {
                screen.setCell(c, r, new TextCell('y'));
            }
        }

        screen.refresh(RefreshType.DELTA);

        String out = term.getOutput();
        assertTrue(count(out, BSU) >= 1, "mid-size delta keeps the bracket: " + repr(out));
        assertTrue(count(out, ESU) >= 1, "bracket closes");
    }
}
