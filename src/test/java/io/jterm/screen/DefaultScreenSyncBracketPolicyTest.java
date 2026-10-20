package io.jterm.screen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalSize;
import io.jterm.style.TextCell;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bracket-EMISSION policy (sync-output-coalescing card 6): synchronized
 * output is a NO-TEARS guarantee for SHORT incremental updates. Holding all
 * rendering across a full-screen redraw freezes slow clients for the entire
 * frame transfer (observed live 2026-10-19: web terminal, menu change under
 * mode 2026 = seconds of nothing while a ~50KB COMPLETE frame trickles over
 * the link).
 *
 * <p>Laws:
 * <ul>
 *   <li>COMPLETE refreshes are NEVER bracketed — they paint progressively,
 *       byte-for-byte like mode-2026-off.</li>
 *   <li>DELTA frames are bracketed only when the delta is SMALL relative to
 *       the screen (≤ 25% of cells); large deltas paint unbracketed.</li>
 *   <li>AUTOMATIC uses the same delta-based decision (its complete-refresh
 *       fallback is likewise unbracketed).</li>
 *   <li>Content bytes for a given buffer state are IDENTICAL bracketed vs
 *       unbracketed (brackets only wrap, never alter the frame).</li>
 *   <li>The cap is on the DELTA COUNT, not bytes — the diff is already
 *       computed at decision time, no extra scan needed.</li>
 * </ul></p>
 */
class DefaultScreenSyncBracketPolicyTest {

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
    @DisplayName("COMPLETE never brackets even when sync is supported")
    void completeNeverBracketed() throws Exception {
        var term = new MockTerminal(new TerminalSize(20, 10));
        var screen = new DefaultScreen(term);
        screen.startScreen();
        term.setSyncOutputSupported(true);
        term.clearOutput();

        screen.refresh(RefreshType.COMPLETE);

        String out = term.getOutput();
        assertFalse(out.isEmpty(), "complete refresh still emits the frame");
        assertEquals(0, count(out, BSU), "COMPLETE must be unbracketed: " + repr(out));
        assertEquals(0, count(out, ESU), "COMPLETE must be unbracketed: " + repr(out));
    }

    @Test
    @DisplayName("small DELTA still brackets (no regression of the core win)")
    void smallDeltaBracketed() throws Exception {
        var term = new MockTerminal(new TerminalSize(20, 10));
        var screen = baseScreen(term);
        // 3 cells = well under the 25% cap of 200
        screen.setCell(1, 1, new TextCell('X'));
        screen.setCell(2, 2, new TextCell('Y'));
        screen.setCell(3, 3, new TextCell('Z'));

        screen.refresh(RefreshType.DELTA);

        String out = term.getOutput();
        assertEquals(1, count(out, BSU), "small delta brackets: " + repr(out));
        assertEquals(1, count(out, ESU), "small delta brackets: " + repr(out));
        assertTrue(out.startsWith(BSU) && out.endsWith(ESU));
    }

    @Test
    @DisplayName("large DELTA (over cap) paints unbracketed")
    void largeDeltaUnbracketed() throws Exception {
        var term = new MockTerminal(new TerminalSize(20, 10));
        var screen = baseScreen(term);
        // 60 changed cells = 30% of 200, above the 25% cap
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 3; y++) {
                screen.setCell(x, y, new TextCell('#'));
            }
        }

        screen.refresh(RefreshType.DELTA);

        String out = term.getOutput();
        assertFalse(out.isEmpty(), "large delta still emits content");
        assertEquals(0, count(out, BSU), "large delta must NOT bracket: " + repr(out));
        assertEquals(0, count(out, ESU), "large delta must NOT bracket: " + repr(out));
        assertTrue(out.contains("#"), "content must be emitted: " + repr(out));
    }

    @Test
    @DisplayName("exactly-at-cap delta brackets (boundary: <= 25%)")
    void atCapDeltaBracketed() throws Exception {
        var term = new MockTerminal(new TerminalSize(20, 10));
        var screen = baseScreen(term);
        // 50 cells = exactly 25% of 200 → still bracketed
        for (int i = 0; i < 50; i++) {
            screen.setCell(i % 20, i / 20, new TextCell('*'));
        }

        screen.refresh(RefreshType.DELTA);

        String out = term.getOutput();
        assertEquals(1, count(out, BSU), "at-cap delta brackets: " + repr(out));
    }

    @Test
    @DisplayName("content bytes identical bracketed vs unbracketed (wrap-only law)")
    void contentIdenticalEitherWay() throws Exception {
        var term = new MockTerminal(new TerminalSize(20, 10));
        var screen = baseScreen(term);
        screen.setCell(5, 4, new TextCell('+'));
        screen.refresh(RefreshType.DELTA);
        String bracketed = term.getOutput().replace(BSU, "").replace(ESU, "");

        // Same change on a sync-OFF terminal for comparison
        var term2 = new MockTerminal(new TerminalSize(20, 10));
        var screen2 = new DefaultScreen(term2);
        screen2.startScreen();
        term2.clearOutput();
        term2.setSyncOutputSupported(false);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 20; x++) {
                screen2.setCell(x, y, new TextCell('.'));
            }
        }
        screen2.refresh(RefreshType.COMPLETE);
        term2.clearOutput();
        screen2.setCell(5, 4, new TextCell('+'));
        screen2.refresh(RefreshType.DELTA);
        String plain = term2.getOutput();

        assertEquals(plain, bracketed,
                "bracketing must be wrap-only: " + repr(bracketed) + " vs " + repr(plain));
    }

    @Test
    @DisplayName("AUTOMATIC large-change repaint unbracketed")
    void automaticLargeUnbracketed() throws Exception {
        var term = new MockTerminal(new TerminalSize(20, 10));
        var screen = baseScreen(term);
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 6; y++) {
                screen.setCell(x, y, new TextCell('~'));
            }
        }

        screen.refresh(RefreshType.AUTOMATIC);

        String out = term.getOutput();
        assertEquals(0, count(out, BSU), "large automatic frame unbracketed: " + repr(out));
    }
}