package io.jterm.animation;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.RgbColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden tick laws for {@link DecoGradient} — the slow Art-Deco gradient wash.
 *
 * <p>Spec (the class doc carries the same laws):
 * <ul>
 *   <li>PALETTE (Art-Deco anchors; the GOLD anchor is mandatory):
 *       P0 lacquer RgbColor(16,12,8), P1 bronze RgbColor(64,46,18),
 *       P2 amber RgbColor(150,112,40), P3 gold RgbColor(255,214,71),
 *       P4 bronze shadow RgbColor(84,66,28).</li>
 *   <li>WASH: column c at phase p paints lerp(P[i], P[i+1], frac) where
 *       t = ((c - p) mod cols) * (len-1) / cols, i = floor(t),
 *       frac = t - i, and lerp rounds half-up per channel.
 *       Cells are ' ' with fg == bg.</li>
 *   <li>PHASE: 6 renders per one-column shift (very slow); the field slides
 *       LEFT as the phase grows; phase wraps at cols — a full wash cycle in
 *       cols*6 renders.</li>
 * </ul>
 */
class DecoGradientTest {

    private static final TerminalSize SIZE_8x4 = new TerminalSize(8, 4);
    private static final RgbColor P0 = new RgbColor(16, 12, 8);
    private static final RgbColor P1 = new RgbColor(64, 46, 18);
    private static final RgbColor P2 = new RgbColor(150, 112, 40);
    private static final RgbColor GOLD = new RgbColor(255, 214, 71);
    private static final RgbColor P4 = new RgbColor(84, 66, 28);

    @Test
    @DisplayName("implements the AnimatedBackground contract at 4 fps")
    void implementsAnimatedBackground() {
        var g = new DecoGradient();
        assertTrue(g instanceof AnimatedBackground);
        assertEquals(4, g.targetFps());
        assertFalse(g.isRunning());
        g.start();
        assertTrue(g.isRunning());
        g.stop();
        assertFalse(g.isRunning());
        g.onResize(SIZE_8x4);
        assertEquals(SIZE_8x4, g.lastSize());
    }

    @Test
    @DisplayName("PALETTE: the ART_DECO gold anchor is in the palette")
    void paletteContainsGoldAnchor() {
        var colors = DecoGradient.PALETTE;
        assertTrue(colors.contains(GOLD),
                "palette law: gradient carries the ART_DECO gold anchor");
        assertEquals(5, colors.size(), "palette law: five anchors");
        assertEquals(P0, colors.get(0));
        assertEquals(P1, colors.get(1));
        assertEquals(P2, colors.get(2));
        assertEquals(P4, colors.get(4));
    }

    @Test
    @DisplayName("WASH golden columns at phase 0 (8-col terminal)")
    void washGoldenColumnsPhase0() {
        var g = new DecoGradient();
        g.onResize(SIZE_8x4);
        var buffer = new ScreenBuffer(SIZE_8x4);
        g.renderFrame(new TextGraphics(buffer), SIZE_8x4);

        // Hand-derived from the law: u = c * 4 / 8.
        RgbColor[] expected = {
                P0,                    // c=0: u=0.0   -> P0
                new RgbColor(40, 29, 13),    // c=1: u=0.5 -> lerp(P0,P1,.5)
                P1,                    // c=2: u=1.0   -> P1
                new RgbColor(107, 79, 29),   // c=3: u=1.5 -> lerp(P1,P2,.5)
                P2,                    // c=4: u=2.0   -> P2
                new RgbColor(203, 163, 56),  // c=5: u=2.5 -> lerp(P2,P3,.5)
                GOLD,                  // c=6: u=3.0   -> P3 (the gold anchor)
                new RgbColor(170, 140, 50)   // c=7: u=3.5 -> lerp(P3,P4,.5)
        };
        for (int c = 0; c < 8; c++) {
            for (int r = 0; r < 4; r++) {
                var cell = buffer.getCell(c, r);
                assertEquals(" ", cell.character(), "wash cell chars are spaces (" + c + ")");
                assertEquals(expected[c], cell.bg(), "column " + c + " golden bg");
                assertEquals(expected[c], cell.fg(), "wash fg rides its bg color (" + c + ")");
            }
        }
    }

    @Test
    @DisplayName("PHASE: one column right-shift every 6 renders; wraps at cols")
    void phaseShiftLaw() {
        var g = new DecoGradient();
        g.onResize(SIZE_8x4);

        // Renders t=0..5 all paint phase 0.
        for (int t = 0; t < 5; t++) {
            g.renderFrame(new TextGraphics(new ScreenBuffer(SIZE_8x4)), SIZE_8x4);
        }
        assertEquals(0, g.phase(), "the wash holds its phase through renders t=0..4");

        var shiftedPaint = new ScreenBuffer(SIZE_8x4);
        g.renderFrame(new TextGraphics(shiftedPaint), SIZE_8x4); // t=5: paints phase 0
        assertEquals(1, g.phase(), "after render t=5 the phase bumps to 1");

        // Render t=6 paints the phase-1 field.
        var painted1 = new ScreenBuffer(SIZE_8x4);
        g.renderFrame(new TextGraphics(painted1), SIZE_8x4);
        assertEquals(1, g.phase(), "the phase holds across the next shift window");

        // The phase-1 field: column c paints the phase-0 ramp at (c-1) mod 8.
        // Column 0 wraps to u=3.5 -> (170,140,50).
        assertEquals(new RgbColor(170, 140, 50), painted1.getCell(0, 0).bg());
        // Column 5 takes old column 4's color; column 6 old column 5's;
        // the gold anchor (u=3.0) lands on column 7.
        assertEquals(P2, painted1.getCell(5, 0).bg());
        assertEquals(new RgbColor(203, 163, 56), painted1.getCell(6, 0).bg());
        assertEquals(GOLD, painted1.getCell(7, 0).bg(), "the gold anchor slides right one column");
    }

    @Test
    @DisplayName("full wash cycle: cols*6 renders return the field to phase 0")
    void fullCycleLaw() {
        var g = new DecoGradient();
        g.onResize(SIZE_8x4);
        var frame0 = new ScreenBuffer(SIZE_8x4);
        g.renderFrame(new TextGraphics(frame0), SIZE_8x4);

        for (int t = 1; t < 48; t++) {
            g.renderFrame(new TextGraphics(new ScreenBuffer(SIZE_8x4)), SIZE_8x4);
        }
        var frame48 = new ScreenBuffer(SIZE_8x4);
        g.renderFrame(new TextGraphics(frame48), SIZE_8x4);
        assertEquals(0, g.phase(), "phase wrapped through all 8 columns in 48 renders");
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 8; c++) {
                var c0 = frame0.getCell(c, r);
                var c48 = frame48.getCell(c, r);
                assertEquals(c0.character(), c48.character(),
                        "full-cycle law (char " + c + "," + r + ")");
                assertEquals(c0.fg(), c48.fg(), "full-cycle law (fg " + c + "," + r + ")");
                assertEquals(c0.bg(), c48.bg(), "full-cycle law (bg " + c + "," + r + ")");
            }
        }
    }

    @Test
    @DisplayName("resize keeps the wash state: same size rebuild, phase persists")
    void resizeLaw() {
        var g = new DecoGradient();
        g.onResize(SIZE_8x4);
        for (int t = 0; t < 6; t++) {
            g.renderFrame(new TextGraphics(new ScreenBuffer(SIZE_8x4)), SIZE_8x4);
        }
        assertEquals(1, g.phase());

        g.onResize(SIZE_8x4);
        assertEquals(1, g.phase(), "a resize must not reset the wash state");
        assertEquals(SIZE_8x4, g.lastSize());
    }

    @Test
    @DisplayName("fills the whole frame (cells identical across a row)")
    void fillsWholeFrame() {
        var g = new DecoGradient();
        g.onResize(new TerminalSize(6, 3));
        var buffer = new ScreenBuffer(new TerminalSize(6, 3));
        g.renderFrame(new TextGraphics(buffer), new TerminalSize(6, 3));
        boolean allSpaces = true;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 6; c++) {
                if (!buffer.getCell(c, r).character().equals(" ")) allSpaces = false;
            }
        }
        assertTrue(allSpaces, "the wash is a full-frame fill");
        // Column 0 of a 6-col terminal: u = 0 -> lacquer.
        assertEquals(P0, buffer.getCell(0, 0).bg());
    }

    @Test
    @DisplayName("pre-sized ctor starts the wash immediately")
    void preSizedCtorLaw() {
        var g = new DecoGradient(SIZE_8x4);
        var buf = new ScreenBuffer(SIZE_8x4);
        g.renderFrame(new TextGraphics(buf), SIZE_8x4);
        assertEquals(P0, buf.getCell(0, 0).bg(), "the pre-sized wash paints phase 0");
        assertEquals(P2, buf.getCell(4, 0).bg(), "anchors land at the scaled columns");
    }

    @Test
    @DisplayName("degenerate sizes render nothing and never crash")
    void degenerateSizeLaw() {
        var g = new DecoGradient(SIZE_8x4);
        var zeroCols = new TerminalSize(0, 4);
        var zeroRows = new TerminalSize(8, 0);
        assertDoesNotThrow(() -> g.renderFrame(new TextGraphics(new ScreenBuffer(zeroCols)), zeroCols));
        assertDoesNotThrow(() -> g.renderFrame(new TextGraphics(new ScreenBuffer(zeroRows)), zeroRows));
        assertEquals(0, g.phase(), "a degenerate render shifts nothing");
        var after = new ScreenBuffer(SIZE_8x4);
        g.renderFrame(new TextGraphics(after), SIZE_8x4);
        assertEquals(P0, after.getCell(0, 0).bg(), "the wash survives degenerate frames");
    }
}
