package io.jterm.animation;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.RgbColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden tick laws for {@link Clouds} — drifting muted blue/purple cloud
 * shapes with a moon-gold rim.
 *
 * <p>Spec (the class doc carries the same laws):
 * <ul>
 *   <li>PALETTE (muted blue/purple + the GOLD moon rim, mandatory):
 *       P0 purple RgbColor(86,66,116), P1 blue RgbColor(58,70,112),
 *       P2 steel-blue RgbColor(72,88,138), rim gold RgbColor(255,214,71),
 *       sky RgbColor(24,30,52).</li>
 *   <li>LAYOUT (deterministic from the size, 3 clouds back-to-front):
 *       width = max(5, cols/3 + 2), height = max(2, rows/6),
 *       band y0 = max(0, min(rows - height, rows/2 - height + 1));
 *       cloud i spans base(i) = i * cols/3, drift position
 *       x0(i) = (base(i) + drifts[i]) mod (cols + width) - width.</li>
 *   <li>SHAPE: bottom row spans the full width; the top row is tapered one
 *       cell per side; every even (x - x0) top cell paints the rim '·' in
 *       the gold anchor.</li>
 *   <li>DRIFT: render t advances cloud i by one cell when t &gt; 0 and
 *       t % LAG[i] == 0, LAG = {5, 3, 2} (back clouds drift slowest) —
 *       full wrap after (cols + width) drifts.</li>
 *   <li>FRONT WINS: paints back-to-front, so a shared cell shows the
 *       frontmost (lowest-LAG) layer's color.</li>
 * </ul>
 */
class CloudsTest {

    private static final TerminalSize SIZE_24x8 = new TerminalSize(24, 8);
    private static final RgbColor SKY = new RgbColor(24, 30, 52);
    private static final RgbColor PURPLE = new RgbColor(86, 66, 116);
    private static final RgbColor BLUE = new RgbColor(58, 70, 112);
    private static final RgbColor STEEL = new RgbColor(72, 88, 138);
    private static final RgbColor GOLD = new RgbColor(255, 214, 71);

    @Test
    @DisplayName("implements the AnimatedBackground contract at 6 fps")
    void implementsAnimatedBackground() {
        var c = new Clouds();
        assertTrue(c instanceof AnimatedBackground);
        assertEquals(6, c.targetFps());
        assertFalse(c.isRunning());
        c.start();
        assertTrue(c.isRunning());
        c.stop();
        assertFalse(c.isRunning());
        c.onResize(SIZE_24x8);
        assertEquals(SIZE_24x8, c.lastSize());
    }

    @Test
    @DisplayName("PALETTE: muted blue/purple tones + the ART_DECO gold rim anchor")
    void paletteLaws() {
        assertTrue(Clouds.PALETTE.contains(GOLD), "palette law: clouds carry the gold rim anchor");
        assertEquals(4, Clouds.PALETTE.size());
        assertEquals(PURPLE, Clouds.PALETTE.get(0));
        assertEquals(BLUE, Clouds.PALETTE.get(1));
        assertEquals(STEEL, Clouds.PALETTE.get(2));
    }

    @Test
    @DisplayName("LAYOUT golden geometry at 24x8")
    void layoutGeometry() {
        var c = new Clouds();
        c.onResize(SIZE_24x8);
        assertEquals(10, c.cloudWidth(), "width = max(5, cols/3 + 2) = 10");
        assertEquals(2, c.cloudHeight(), "height = max(2, rows/6) = 2");
        assertEquals(3, c.cloudY(0), "band y0 = rows/2 - h + 1 = 3");
        assertEquals(3, c.cloudY(1));
        assertEquals(3, c.cloudY(2));
        assertEquals(0, c.cloudX(0), "cloud 0 starts at base 0");
        assertEquals(8, c.cloudX(1), "cloud 1 starts at base cols/3");
        assertEquals(16, c.cloudX(2), "cloud 2 starts at base 2*cols/3");
        assertEquals(0, c.cloudDrifts(0), "no drift before the first render");
    }

    @Test
    @DisplayName("SHAPE golden cells at t=0 (24x8): body / rim / overlap / sky")
    void shapeGoldenCells() {
        var c = new Clouds();
        c.onResize(SIZE_24x8);
        var buffer = new ScreenBuffer(SIZE_24x8);
        c.renderFrame(new TextGraphics(buffer), SIZE_24x8);

        // Cloud 0 (purple): bottom row [0..9], top row [1..8].
        assertCell(buffer, 0, 4, " ", PURPLE, "cloud0 bottom body");
        assertCell(buffer, 3, 4, " ", PURPLE, "cloud0 bottom body");
        assertCell(buffer, 1, 3, " ", PURPLE, "odd top cell is body");
        assertCell(buffer, 2, 3, "·", GOLD, PURPLE, "even top cell is the gold rim");
        assertCell(buffer, 8, 3, "·", GOLD, PURPLE, "rim at x0+8");

        // Overlap law: (9,4) is inside cloud0's bottom [0..9] AND cloud1's
        // bottom [8..17] — the front (blue) layer wins.
        assertCell(buffer, 9, 4, " ", BLUE, "front layer wins the shared cell");
        assertCell(buffer, 15, 4, " ", BLUE, "cloud1 bottom body");
        assertCell(buffer, 11, 3, " ", BLUE, "cloud1 odd top is body");
        assertCell(buffer, 10, 3, "·", GOLD, BLUE, "cloud1 rim");

        // Cloud 2 (steel): bottom [16..23], top [17..22].
        assertCell(buffer, 20, 4, " ", STEEL, "cloud2 bottom body");
        assertCell(buffer, 23, 4, " ", STEEL, "cloud2 bottom body");
        assertCell(buffer, 18, 3, "·", GOLD, STEEL, "cloud2 rim");

        // Sky law: every uncovered cell is ' ' on the muted sky.
        assertCell(buffer, 23, 0, " ", SKY, SKY, "sky above the band");
        assertCell(buffer, 0, 7, " ", SKY, SKY, "sky below the band");
        assertCell(buffer, 15, 3, " ", BLUE, BLUE, "cloud1 top-edge body");
    }

    @Test
    @DisplayName("DRIFT: LAG {5,3,2}; per-layer steps wrap through the spawn span")
    void driftLaw() {
        var c = new Clouds();
        c.onResize(SIZE_24x8);
        var frame0 = new ScreenBuffer(SIZE_24x8);
        c.renderFrame(new TextGraphics(frame0), SIZE_24x8); // t=0: no advances

        scratch(c); // t=1: no advances yet
        assertEquals(0, c.cloudDrifts(2), "no drift at t=1");

        scratch(c); // t=2: layer2 (LAG 2) advances
        assertEquals(1, c.cloudDrifts(2), "layer 2 steps at t=2");
        assertEquals(17, c.cloudX(2), "cloud 2 slid right one cell");
        assertEquals(0, c.cloudDrifts(0), "back layer holds");
        assertEquals(0, c.cloudDrifts(1), "middle layer holds");

        scratch(c); // t=3: layer1 (LAG 3)
        assertEquals(1, c.cloudDrifts(1), "layer 1 steps at t=3");
        assertEquals(9, c.cloudX(1), "cloud 1 slid right one cell");

        scratch(c); // t=4: layer2 again
        assertEquals(2, c.cloudDrifts(2));

        scratch(c); // t=5: layer0 (LAG 5); layer2 holds on odd t
        assertEquals(1, c.cloudDrifts(0), "layer 0 steps at t=5");
        assertEquals(2, c.cloudDrifts(2), "layer 2 holds on odd t");

        // Wrap law: layer 2 fully cycles after (cols + width) = 34 steps.
        for (int t = 6; t <= 67; t++) c.renderFrame(new TextGraphics(new ScreenBuffer(SIZE_24x8)), SIZE_24x8);
        assertEquals(33, c.cloudDrifts(2), "advances happened on t=2..66");
        var wrapped = new ScreenBuffer(SIZE_24x8);
        c.renderFrame(new TextGraphics(wrapped), SIZE_24x8); // t=68 advances layer 2
        assertEquals(34, c.cloudDrifts(2));
        assertEquals(16, c.cloudX(2), "34 steps return cloud 2 to its base");
    }

    @Test
    @DisplayName("front layer repainting: cloud2's wrap re-covers the body cells")
    void wrappedCellsRepaint() {
        var c = new Clouds();
        c.onResize(SIZE_24x8);
        for (int t = 0; t < 68; t++) scratch(c);
        // cloud2 wrapped back to base 16: its body cells are steel again.
        var buffer = new ScreenBuffer(SIZE_24x8);
        c.renderFrame(new TextGraphics(buffer), SIZE_24x8);
        assertCell(buffer, 20, 4, " ", STEEL, "cloud2 body after the full wrap");
    }

    @Test
    @DisplayName("RESIZE re-derives layout from the new size; drifts survive")
    void resizeLaw() {
        var c = new Clouds();
        c.onResize(SIZE_24x8);
        scratch(c); // t=0: no advances
        scratch(c); // t=1
        scratch(c); // t=2: cloud2 drifts once
        assertEquals(1, c.cloudDrifts(2));

        var fresh = new Clouds();
        fresh.onResize(new TerminalSize(40, 8));
        c.onResize(new TerminalSize(40, 8));

        assertEquals(fresh.cloudWidth(), c.cloudWidth(), "width re-derived at the new size");
        assertEquals(fresh.cloudHeight(), c.cloudHeight());
        assertEquals(fresh.cloudY(0), c.cloudY(0));
        // 40-col: base(2) = 26; the 1 kept drift lands x = 27.
        assertEquals(27, c.cloudX(2), "kept drift mapped onto the new geometry");
        assertEquals(1, c.cloudDrifts(2), "drifts survive the resize");
    }

    @Test
    @DisplayName("determinism: two instances render byte-identical frames")
    void determinismLaw() {
        var a = new Clouds();
        var b = new Clouds();
        a.onResize(SIZE_24x8);
        b.onResize(SIZE_24x8);
        for (int t = 0; t < 50; t++) {
            var bufA = new ScreenBuffer(SIZE_24x8);
            a.renderFrame(new TextGraphics(bufA), SIZE_24x8);
            var bufB = new ScreenBuffer(SIZE_24x8);
            b.renderFrame(new TextGraphics(bufB), SIZE_24x8);
            for (int r = 0; r < 8; r++) {
                for (int cc = 0; cc < 24; cc++) {
                    var ca = bufA.getCell(cc, r);
                    var cb = bufB.getCell(cc, r);
                    assertEquals(ca.character(), cb.character(), "char (t=" + t + ")");
                    assertEquals(ca.fg(), cb.fg(), "fg (t=" + t + ")");
                    assertEquals(ca.bg(), cb.bg(), "bg (t=" + t + ")");
                }
            }
        }
    }

    @Test
    @DisplayName("renders non-blank at tiny sizes without crashing")
    void tinySizeResilience() {
        var c = new Clouds();
        var tiny = new TerminalSize(6, 4);
        c.onResize(tiny);
        var buf = new ScreenBuffer(tiny);
        assertDoesNotThrow(() -> c.renderFrame(new TextGraphics(buf), tiny));
        // Even 6x4 paints something: the sky fill alone differs from blank.
        boolean anySky = false;
        for (int r = 0; r < 4 && !anySky; r++) {
            for (int cc = 0; cc < 6; cc++) {
                if (SKY.equals(buf.getCell(cc, r).bg())) anySky = true;
            }
        }
        assertTrue(anySky, "the sky fill covers the frame");
    }

    @Test
    @DisplayName("pre-sized ctor lays the deck immediately")
    void preSizedCtorLaw() {
        var c = new Clouds(SIZE_24x8);
        assertEquals(10, c.cloudWidth(), "the pre-sized ctor derives the geometry");
        assertEquals(3, c.cloudY(0));
        var buf = new ScreenBuffer(SIZE_24x8);
        c.renderFrame(new TextGraphics(buf), SIZE_24x8);
        assertCell(buf, 0, 4, " ", PURPLE, "cloud0 painted from the pre-sized ctor");
    }

    @Test
    @DisplayName("degenerate sizes render nothing and never crash")
    void degenerateSizeLaw() {
        var c = new Clouds(SIZE_24x8);
        var zeroCols = new TerminalSize(0, 8);
        var zeroRows = new TerminalSize(24, 0);
        assertDoesNotThrow(() -> c.renderFrame(new TextGraphics(new ScreenBuffer(zeroCols)), zeroCols));
        assertDoesNotThrow(() -> c.renderFrame(new TextGraphics(new ScreenBuffer(zeroRows)), zeroRows));
        assertEquals(0, c.cloudDrifts(0), "a degenerate render advances nothing");
    }

    private void assertCell(ScreenBuffer buf, int x, int y, String ch, RgbColor fg, RgbColor bg, String msg) {
        var cell = buf.getCell(x, y);
        assertEquals(ch, cell.character(), msg + " (char " + x + "," + y + ")");
        assertEquals(fg, cell.fg(), msg + " (fg " + x + "," + y + ")");
        assertEquals(bg, cell.bg(), msg + " (bg " + x + "," + y + ")");
    }

    private void assertCell(ScreenBuffer buf, int x, int y, String ch, RgbColor bg, String msg) {
        var cell = buf.getCell(x, y);
        assertEquals(ch, cell.character(), msg + " (char " + x + "," + y + ")");
        assertEquals(bg, cell.bg(), msg + " (bg " + x + "," + y + ")");
    }

    private void scratch(Clouds c) {
        c.renderFrame(new TextGraphics(new ScreenBuffer(SIZE_24x8)), SIZE_24x8);
    }
}
