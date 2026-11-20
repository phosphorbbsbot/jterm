package io.jterm.animation;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.RgbColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden tick laws for {@link Starfield} — the menu ambient star scene.
 *
 * <p>Spec (the class doc carries the same laws):
 * <ul>
 *   <li>STAR-COUNT: one star per 160 cells, clamped to [3, 32].</li>
 *   <li>STAR-SEEDS: scatter is the (nextInt(cols), nextInt(rows)) sequence of
 *       the constructor {@link Random}; a re-layout re-derives the SAME
 *       sequence (replayed through a fresh Random over the stored seed).</li>
 *   <li>DEEP-SPACE: every cell paints ' ' on RgbColor(6,8,16); stars paint
 *       over it, later stars win.</li>
 *   <li>DRIFT: render t (0-based) advances no star; t divisible by 4 steps
 *       every star right 1 cell, wrapping at the width — a full field cycle
 *       in width×4 renders.</li>
 *   <li>TWINKLE: bright iff (t + phase) % 30 &lt; 2; phase = (x0 + 7*y0) % 30;
 *       bright glyph '*' at RgbColor(255,249,196), dim glyph '.' at
 *       RgbColor(148,164,196).</li>
 * </ul>
 */
class StarfieldTest {

    private static final TerminalSize SIZE_40x10 = new TerminalSize(40, 10);
    private static final RgbColor SPACE = new RgbColor(6, 8, 16);
    private static final RgbColor DIM_FG = new RgbColor(148, 164, 196);
    private static final RgbColor BRIGHT_FG = new RgbColor(255, 249, 196);

    @Test
    @DisplayName("implements the AnimatedBackground contract at 10 fps")
    void implementsAnimatedBackground() {
        var sf = new Starfield(1);
        assertTrue(sf instanceof AnimatedBackground);
        assertEquals(10, sf.targetFps());
        assertFalse(sf.isRunning());
        sf.start();
        assertTrue(sf.isRunning());
        sf.stop();
        assertFalse(sf.isRunning());
        sf.onResize(SIZE_40x10);
        assertEquals(SIZE_40x10, sf.lastSize());
    }

    @Test
    @DisplayName("STAR-COUNT: one star per 160 cells clamped to [3,32]")
    void starCountLaw() {
        var tiny = new Starfield(1);
        tiny.onResize(new TerminalSize(10, 5)); // 50/160 = 0 -> floor of 3
        assertEquals(3, tiny.starCount(), "small terminal starves to the floor of 3");

        var mid = new Starfield(1);
        mid.onResize(SIZE_40x10); // 400/160 = 2 -> floor of 3
        assertEquals(3, mid.starCount());

        var wide = new Starfield(1);
        wide.onResize(new TerminalSize(200, 30)); // 6000/160 = 37 -> cap
        assertEquals(32, wide.starCount(), "large terminal caps at 32");
    }

    @Test
    @DisplayName("STAR-SEEDS: scatter is the seeded (x,y) sequence; same seed replays")
    void seedDeterminism() {
        // Mirror the documented scatter sequence and demand exact hits.
        var mask = scatterMask(7, 40, 10, 3);

        var sf = new Starfield(7);
        sf.onResize(SIZE_40x10);
        for (int i = 0; i < sf.starCount(); i++) {
            assertEquals(mask[i][0], sf.starX(i), "star " + i + " x from the seed sequence");
            assertEquals(mask[i][1], sf.starY(i), "star " + i + " y from the seed sequence");
        }

        var replay = new Starfield(7);
        replay.onResize(SIZE_40x10);
        for (int i = 0; i < sf.starCount(); i++) {
            assertEquals(sf.starX(i), replay.starX(i), "same seed replays the same scatter");
            assertEquals(sf.starY(i), replay.starY(i), "same seed replays the same scatter");
        }
    }

    @Test
    @DisplayName("no layout pushed: the deep-space fill renders and no stars exist")
    void noLayoutRendersFill() {
        var sf = new Starfield(7);
        var buf = new ScreenBuffer(SIZE_40x10);
        sf.renderFrame(new TextGraphics(buf), SIZE_40x10);
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 40; c++) {
                var cell = buf.getCell(c, r);
                assertEquals(" ", cell.character(), "no-layout render is the bare fill");
                assertEquals(SPACE, cell.bg());
            }
        }
        assertEquals(0, sf.starCount(), "the scatter appears only after a layout is pushed");
    }

    @Test
    @DisplayName("a stale smaller-size render clamps: OOB writes are ignored")
    void smallerRenderClamps() {
        var sf = new Starfield(7);
        sf.onResize(SIZE_40x10);
        var small = new TerminalSize(8, 4);
        var buf = new ScreenBuffer(small);
        assertDoesNotThrow(() -> sf.renderFrame(new TextGraphics(buf), small));
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 8; c++) {
                assertEquals(SPACE, buf.getCell(c, r).bg(), "smaller frame keeps the deep space");
            }
        }
    }

    @Test
    @DisplayName("no-arg ctor scatters a real scene from a random seed")
    void noArgCtorLaw() {
        var sf = new Starfield();
        sf.onResize(SIZE_40x10);
        assertEquals(3, sf.starCount(), "the random seed still lays the STAR-COUNT-law scatter");
        var buf = new ScreenBuffer(SIZE_40x10);
        sf.renderFrame(new TextGraphics(buf), SIZE_40x10);
        assertEquals(SPACE, buf.getCell(0, 0).bg(), "the random scene paints the deep space");
    }

    @Test
    @DisplayName("drifting without a layout holds: no scatter until onResize")
    void advanceWithoutLayoutHolds() {
        var sf = new Starfield(7);
        // Five renders with no onResize: the t=4 advance fires on absent stars.
        for (int t = 0; t < 5; t++) {
            var buf = new ScreenBuffer(SIZE_40x10);
            sf.renderFrame(new TextGraphics(buf), SIZE_40x10);
            for (int r = 0; r < 10; r++) {
                for (int c = 0; c < 40; c++) {
                    assertEquals(" ", buf.getCell(c, r).character(), "no layout, no stars (t=" + t + ")");
                }
            }
        }
        assertEquals(0, sf.starCount());
    }

    @Test
    @DisplayName("degenerate sizes render nothing and never crash")
    void degenerateSizeLaw() {
        var sf = new Starfield(7);
        sf.onResize(SIZE_40x10);
        var zeroCols = new TerminalSize(0, 10);
        var zeroRows = new TerminalSize(40, 0);
        assertDoesNotThrow(() -> sf.renderFrame(new TextGraphics(new ScreenBuffer(zeroCols)), zeroCols));
        assertDoesNotThrow(() -> sf.renderFrame(new TextGraphics(new ScreenBuffer(zeroRows)), zeroRows));
    }

    @Test
    @DisplayName("scatter cells sit in frame 0; every other cell is deep space")
    void frame0PaintsScatterOnDeepSpace() {
        var sf = new Starfield(7);
        sf.onResize(SIZE_40x10);
        int count = sf.starCount();
        boolean[][] star = new boolean[10][40];
        for (int i = 0; i < count; i++) star[sf.starY(i)][sf.starX(i)] = true;

        var buffer = new ScreenBuffer(SIZE_40x10);
        sf.renderFrame(new TextGraphics(buffer), SIZE_40x10);

        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 40; c++) {
                var cell = buffer.getCell(c, r);
                assertEquals(SPACE, cell.bg(), "deep-space bg at " + c + "," + r);
                if (star[r][c]) {
                    assertTrue(cell.character().equals(".") || cell.character().equals("*"),
                            "scatter cell " + c + "," + r + " paints a star glyph");
                    assertTrue(cell.fg().equals(DIM_FG) || cell.fg().equals(BRIGHT_FG),
                            "scatter fg is dim or bright");
                } else {
                    assertEquals(" ", cell.character(), "non-star cell at " + c + "," + r);
                    assertEquals(SPACE, cell.fg(), "non-star fg rides the bg color");
                }
            }
        }
    }

    @Test
    @DisplayName("DRIFT: +1 cell every 4th render; full field cycle in width*4 renders")
    void driftLaw() {
        var sf = new Starfield(7);
        var size = SIZE_40x10;
        sf.onResize(size);
        int count = sf.starCount();
        int[] x0 = new int[count];
        for (int i = 0; i < count; i++) x0[i] = sf.starX(i);

        var frame0 = render(sf, size);

        for (int t = 1; t <= 3; t++) scratch(sf, size);
        for (int i = 0; i < count; i++) {
            assertEquals(x0[i], sf.starX(i), "renders 1-3 hold the scatter (star " + i + ")");
        }

        render(sf, size); // t=4
        for (int i = 0; i < count; i++) {
            assertEquals((x0[i] + 1) % size.columns(), sf.starX(i),
                    "render 4 steps star " + i + " right by exactly 1");
        }

        for (int t = 5; t <= 8; t++) scratch(sf, size);
        for (int i = 0; i < count; i++) {
            assertEquals((x0[i] + 2) % size.columns(), sf.starX(i),
                    "render 8 steps star " + i + " right by exactly 2");
        }

        // Full cycle: the drift cycle is width*4 = 160 renders and the
        // twinkle cycle is 30 — the COMMON cycle is lcm(160,30) = 480, which
        // lands every star on both its original position and twinkle state.
        for (int t = 9; t < 480; t++) scratch(sf, size);
        var frame480 = render(sf, size);
        for (int r = 0; r < size.rows(); r++) {
            for (int c = 0; c < size.columns(); c++) {
                var ca = frame0.getCell(c, r);
                var cb = frame480.getCell(c, r);
                assertEquals(ca.character(), cb.character(),
                        "full-cycle law: render 480 == render 0 (char " + c + "," + r + ")");
                assertEquals(ca.fg(), cb.fg(),
                        "full-cycle law: render 480 == render 0 (fg " + c + "," + r + ")");
                assertEquals(ca.bg(), cb.bg(),
                        "full-cycle law: render 480 == render 0 (bg " + c + "," + r + ")");
            }
        }
    }

    @Test
    @DisplayName("TWINKLE: bright iff (t+phase)%30 < 2; warm-white '*' vs dim '.'")
    void twinkleLaw() {
        var sf = new Starfield(7);
        var size = SIZE_40x10;
        sf.onResize(size);
        int phase = sf.starPhase(0);
        int y = sf.starY(0); // stars drift only horizontally

        // First bright render: (t + phase) % 30 == 0.
        int firstBright = (30 - phase) % 30;
        for (int t = 0; t < firstBright; t++) scratch(sf, size);
        var bright1 = new ScreenBuffer(size);
        sf.renderFrame(new TextGraphics(bright1), size);
        assertEquals("*", bright1.getCell(sf.starX(0), y).character(),
                "twinkle window opens on the bright glyph");
        assertEquals(BRIGHT_FG, bright1.getCell(sf.starX(0), y).fg(),
                "twinkle fg is the warm white");
        assertEquals(SPACE, bright1.getCell(sf.starX(0), y).bg(),
                "twinkle rides the deep-space bg");

        var bright2 = new ScreenBuffer(size);
        sf.renderFrame(new TextGraphics(bright2), size);
        assertEquals("*", bright2.getCell(sf.starX(0), y).character(),
                "second window frame still bright");

        var dim = new ScreenBuffer(size);
        sf.renderFrame(new TextGraphics(dim), size);
        assertEquals(".", dim.getCell(sf.starX(0), y).character(),
                "window closes after exactly 2 frames");
        assertEquals(DIM_FG, dim.getCell(sf.starX(0), y).fg(),
                "dim fg is the cool white-blue");

        // Period law: 30 renders after the window's first bright frame the
        // next window opens.
        for (int t = 0; t < 27; t++) scratch(sf, size);
        var again = new ScreenBuffer(size);
        sf.renderFrame(new TextGraphics(again), size);
        assertEquals("*", again.getCell(sf.starX(0), y).character(),
                "twinkle repeats every 30 renders");
    }

    @Test
    @DisplayName("RESIZE re-lays the SAME seed scatter at the new size")
    void resizeRelayoutLaw() {
        var sf = new Starfield(7);
        sf.onResize(SIZE_40x10);

        var fresh = new Starfield(7);
        fresh.onResize(new TerminalSize(60, 20));
        sf.onResize(new TerminalSize(60, 20));

        assertEquals(fresh.starCount(), sf.starCount(),
                "re-layout matches a fresh construction at the new size");
        for (int i = 0; i < fresh.starCount(); i++) {
            assertEquals(fresh.starX(i), sf.starX(i), "same seed same scatter at new size (x)");
            assertEquals(fresh.starY(i), sf.starY(i), "same seed same scatter at new size (y)");
        }
    }

    @Test
    @DisplayName("renders non-blank and differs from a blank buffer")
    void rendersNonBlank() {
        var sf = new Starfield(7);
        var size = SIZE_40x10;
        sf.onResize(size);
        var rendered = new ScreenBuffer(size);
        sf.renderFrame(new TextGraphics(rendered), size);
        var blank = new ScreenBuffer(size);
        boolean anyDifferent = false;
        for (int r = 0; r < size.rows() && !anyDifferent; r++) {
            for (int c = 0; c < size.columns(); c++) {
                if (!rendered.getCell(c, r).equals(blank.getCell(c, r))) {
                    anyDifferent = true;
                    break;
                }
            }
        }
        assertTrue(anyDifferent, "frame 0 must paint the deep-space fill");
    }

    /** Replays the documented scatter sequence for Random(seed). */
    private int[][] scatterMask(int seed, int cols, int rows, int count) {
        var rnd = new Random(seed);
        var mask = new int[count][2];
        for (int i = 0; i < count; i++) {
            mask[i][0] = rnd.nextInt(cols);
            mask[i][1] = rnd.nextInt(rows);
        }
        return mask;
    }

    private ScreenBuffer render(Starfield sf, TerminalSize size) {
        var buf = new ScreenBuffer(size);
        sf.renderFrame(new TextGraphics(buf), size);
        return buf;
    }

    private void scratch(Starfield sf, TerminalSize size) {
        sf.renderFrame(new TextGraphics(new ScreenBuffer(size)), size);
    }
}
