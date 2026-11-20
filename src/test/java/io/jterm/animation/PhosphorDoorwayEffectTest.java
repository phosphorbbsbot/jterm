package io.jterm.animation;

import io.jterm.core.ColorDepth;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.Color;
import io.jterm.style.RgbColor;
import io.jterm.style.TextCell;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden tick laws for {@link PhosphorDoorwayEffect} — the pre-login doorway
 * intro (animation plan card 2, 2026-11-19).
 *
 * <p>Spec (the class doc carries the same laws):
 * <ul>
 *   <li>STAGE-SEQUENCING: renders are 60 fps logic ticks; stages are
 *       [0, BLACK_TICKS) BLACK, [BLACK, BLACK+BLOOM_TICKS) BLOOM, next
 *       LOGO_TICKS LOGO, next HOLD_TICKS HOLD; the effect finishes at
 *       TOTAL_TICKS and stays there (a continued render is the static hold
 *       frame).</li>
 *   <li>HARD CAP: TOTAL_TICKS &lt;= HARD_CAP_TICKS = 300 (5.0 s at 60 fps).</li>
 *   <li>BLACK: the first render is the bare ground fill — no glow, no glyphs,
 *       fg rides bg.</li>
 *   <li>BLOOM: light accumulates radially from the screen center, monotonically
 *       over ticks and falls off monotonically with radial distance; the glow
 *       palette is band-quantized; glow-only cells paint ' ' with fg == bg;
 *       the only non-space texture is the ░ grain glyph.</li>
 *   <li>LOGO RESOLVE: the authored PHOSPHOR block logo reveals progressively
 *       left-to-right; it opens blank, reaches exactly the authored
 *       glyph-cell count (105 at 47 columns) at hold start, and glyph cells
 *       only ever paint the half-block law characters █▀▄.</li>
 *   <li>SWEEP: the resolve paints a warm cream sweep highlight; the hold frame
 *       is pure Phosphor gold.</li>
 *   <li>HOLD: static — five consecutive hold frames are cell-identical.</li>
 *   <li>TIERS: 49 columns fit the 47-wide block tier; 43..48 fit the no-gap
 *       fallback; fewer columns or fewer than 6 rows fit no logo and the bloom
 *       alone renders (never throws).</li>
 *   <li>DEGRADES: through the ColorDepth pipeline — TRUECOLOR carries
 *       48;2/38;2 gold; ANSI16 and C256 tiers emit only mapped palette
 *       sequences; a terminal without 2026 sync never sees a bracket.</li>
 *   <li>DETERMINISM: identical drives produce identical frames — motion
 *       derives from the render counter only, never the clock.</li>
 *   <li>TICKS-PER-RENDER: a slowed render loop covers the same logic
 *       timeline (the real-time cap survives any render fps).</li>
 * </ul>
 */
class PhosphorDoorwayEffectTest {

    private static final TerminalSize SIZE_80x23 = new TerminalSize(80, 23);
    private static final RgbColor GROUND = PhosphorDoorwayEffect.GROUND;
    private static final RgbColor GOLD = PhosphorDoorwayEffect.PHOSPHOR_GOLD;
    private static final RgbColor CREAM = PhosphorDoorwayEffect.SWEEP_CREAM;

    /** Paints k frames with a fresh buffer each and returns the LAST frame's buffer. */
    private ScreenBuffer drive(PhosphorDoorwayEffect fx, TerminalSize size, int k) {
        ScreenBuffer buf = null;
        for (int i = 0; i < k; i++) {
            buf = paint(fx, size);
        }
        return buf;
    }

    private ScreenBuffer paint(PhosphorDoorwayEffect fx, TerminalSize size) {
        var buf = new ScreenBuffer(size);
        fx.renderFrame(new TextGraphics(buf), size);
        return buf;
    }

    /** Component-wise cell equality (TextCell.equals compares colors by reference). */
    private static void assertCellEquals(String what, TextCell a, TextCell b) {
        assertEquals(a.character(), b.character(), what + " char");
        assertColorEquals(what + " fg", a.fg(), b.fg());
        assertColorEquals(what + " bg", a.bg(), b.bg());
    }

    private static void assertColorEquals(String what, Color a, Color b) {
        if (a instanceof RgbColor ra && b instanceof RgbColor rb) {
            assertEquals(ra.r(), rb.r(), what + " r");
            assertEquals(ra.g(), rb.g(), what + " g");
            assertEquals(ra.b(), rb.b(), what + " b");
        } else {
            assertEquals(a, b, what + " (non-rgb colors must match exactly)");
        }
    }

    private static boolean colorEquals(Color a, Color b) {
        if (a instanceof RgbColor ra && b instanceof RgbColor rb) {
            return ra.r() == rb.r() && ra.g() == rb.g() && ra.b() == rb.b();
        }
        return a == b;
    }

    private static long light(TextCell cell) {
        if (cell.bg() instanceof RgbColor rgb) {
            return rgb.r() + rgb.g() + rgb.b();
        }
        return -1;
    }

    private static boolean isBlockGlyph(TextCell cell) {
        var ch = cell.character();
        return ch.equals("█") || ch.equals("▀") || ch.equals("▄");
    }

    /** Positions of LOGO glyph cells only — the ░ bloom grain never counts as a logo glyph. */
    private static Set<String> glyphSet(ScreenBuffer buf, TerminalSize size) {
        var set = new HashSet<String>();
        for (int r = 0; r < size.rows(); r++) {
            for (int c = 0; c < size.columns(); c++) {
                if (isBlockGlyph(buf.getCell(c, r))) {
                    set.add(c + "x" + r);
                }
            }
        }
        return set;
    }

    private static void assertWire(String out, String... bannedSequences) {
        for (var banned : bannedSequences) {
            assertFalse(out.contains(banned), "banned sequence '" + banned + "' in: " + head(out));
        }
    }

    private static String head(String s) {
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }

    /** Runs the effect through the default-depth screen to the given tick and returns the wire bytes. */
    private String wireAt(TerminalSize size, ColorDepth depth, int frames) throws IOException {
        var terminal = new io.jterm.core.MockTerminal(size);
        if (depth != null) {
            terminal.setColorDepth(depth);
        }
        var screen = new io.jterm.screen.DefaultScreen(terminal);
        screen.startScreen();
        var fx = new PhosphorDoorwayEffect();
        fx.onResize(size);
        var buf = drive(fx, size, frames);
        for (int r = 0; r < size.rows(); r++) {
            for (int c = 0; c < size.columns(); c++) {
                screen.setCell(c, r, buf.getCell(c, r));
            }
        }
        screen.refresh();
        return terminal.getOutput();
    }

    // ── Contract ──────────────────────────────────────────────────────

    @Test
    @DisplayName("implements the AnimatedBackground contract at 60 logic fps, thread-free")
    void contractLaw() {
        var fx = new PhosphorDoorwayEffect();
        assertInstanceOf(AnimatedBackground.class, fx);
        assertEquals(60, fx.targetFps(), "the logic clock is 60 fps logic ticks");
        assertFalse(fx.isRunning());
        fx.start();
        assertTrue(fx.isRunning());
        fx.stop();
        assertFalse(fx.isRunning());
        fx.onResize(SIZE_80x23);
        assertEquals(SIZE_80x23, fx.lastSize());
        assertFalse(fx.isFinished(), "a fresh effect has not finished");
        assertEquals(0, fx.tickCount(), "no renders, no advance");
        assertEquals(1, fx.getTicksPerRender(), "default drive is one logic tick per render");
    }

    @Test
    @DisplayName("HARD CAP: 300-tick cap law and the stage constants tile the total exactly")
    void hardCapLaw() {
        assertEquals(300, PhosphorDoorwayEffect.HARD_CAP_TICKS, "the cap law is 300 ticks = 5.000 s at 60 fps");
        assertTrue(PhosphorDoorwayEffect.TOTAL_TICKS <= PhosphorDoorwayEffect.HARD_CAP_TICKS,
                "the play must finish inside the cap");
        assertEquals(PhosphorDoorwayEffect.BLACK_TICKS + PhosphorDoorwayEffect.BLOOM_TICKS
                        + PhosphorDoorwayEffect.LOGO_TICKS + PhosphorDoorwayEffect.HOLD_TICKS,
                PhosphorDoorwayEffect.TOTAL_TICKS,
                "the stage constants tile the total exactly (no dead time)");
    }

    // ── Stage sequencing golden ───────────────────────────────────────

    @Test
    @DisplayName("STAGE-SEQUENCING golden: static stageAt table over the tick axis")
    void stageAtGoldenLaw() {
        var black = PhosphorDoorwayEffect.BLACK_TICKS;
        var bloomEnd = black + PhosphorDoorwayEffect.BLOOM_TICKS;
        var logoEnd = bloomEnd + PhosphorDoorwayEffect.LOGO_TICKS;
        var holdEnd = logoEnd + PhosphorDoorwayEffect.HOLD_TICKS;
        var S = PhosphorDoorwayEffect.Stage.class;

        assertEquals(S, PhosphorDoorwayEffect.stageAt(0).getClass());
        assertEquals(PhosphorDoorwayEffect.Stage.BLACK, PhosphorDoorwayEffect.stageAt(0));
        assertEquals(PhosphorDoorwayEffect.Stage.BLACK, PhosphorDoorwayEffect.stageAt(black - 1), "last BLACK tick");
        assertEquals(PhosphorDoorwayEffect.Stage.BLOOM, PhosphorDoorwayEffect.stageAt(black), "first BLOOM tick");
        assertEquals(PhosphorDoorwayEffect.Stage.BLOOM, PhosphorDoorwayEffect.stageAt(bloomEnd - 1), "last BLOOM tick");
        assertEquals(PhosphorDoorwayEffect.Stage.LOGO, PhosphorDoorwayEffect.stageAt(bloomEnd), "first LOGO tick");
        assertEquals(PhosphorDoorwayEffect.Stage.LOGO, PhosphorDoorwayEffect.stageAt(logoEnd - 1), "last LOGO tick");
        assertEquals(PhosphorDoorwayEffect.Stage.HOLD, PhosphorDoorwayEffect.stageAt(logoEnd), "first HOLD tick");
        assertEquals(PhosphorDoorwayEffect.Stage.HOLD, PhosphorDoorwayEffect.stageAt(holdEnd - 1), "last HOLD tick");
        assertEquals(PhosphorDoorwayEffect.Stage.HOLD,
                PhosphorDoorwayEffect.stageAt(PhosphorDoorwayEffect.HARD_CAP_TICKS - 1),
                "still holding at the cap boundary");
        assertEquals(PhosphorDoorwayEffect.Stage.BLACK, PhosphorDoorwayEffect.stageAt(-1), "negative clamps to BLACK");
    }

    @Test
    @DisplayName("STAGE-SEQUENCING golden: driven renders cross every stage boundary at the exact tick count")
    void drivenStageSequencingGolden() {
        var fx = new PhosphorDoorwayEffect();
        fx.onResize(SIZE_80x23);
        drive(fx, SIZE_80x23, PhosphorDoorwayEffect.BLACK_TICKS);
        assertEquals(PhosphorDoorwayEffect.Stage.BLACK, fx.stage(), "renders 1..BLACK_TICKS are the black stage");

        drive(fx, SIZE_80x23, 1);
        assertEquals(PhosphorDoorwayEffect.Stage.BLOOM, fx.stage(), "the next render opens the bloom stage");

        drive(fx, SIZE_80x23, PhosphorDoorwayEffect.BLOOM_TICKS - 1);
        assertEquals(PhosphorDoorwayEffect.Stage.BLOOM, fx.stage(), "the last bloom tick still blooms");

        drive(fx, SIZE_80x23, 1);
        assertEquals(PhosphorDoorwayEffect.Stage.LOGO, fx.stage(), "the next render opens the logo stage");

        drive(fx, SIZE_80x23, PhosphorDoorwayEffect.LOGO_TICKS - 1);
        assertEquals(PhosphorDoorwayEffect.Stage.LOGO, fx.stage(), "the last logo tick still resolves");

        drive(fx, SIZE_80x23, 1);
        assertEquals(PhosphorDoorwayEffect.Stage.HOLD, fx.stage(), "the next render opens the hold stage");

        drive(fx, SIZE_80x23, PhosphorDoorwayEffect.HOLD_TICKS - 1);
        assertEquals(PhosphorDoorwayEffect.Stage.HOLD, fx.stage(), "the last hold tick still holds");
        assertTrue(fx.isFinished(), "the painted tick count reaches TOTAL_TICKS");
    }

    @Test
    @DisplayName("FINISH: the effect finishes exactly at TOTAL_TICKS, never before")
    void finishLaw() {
        var fx = new PhosphorDoorwayEffect();
        fx.onResize(SIZE_80x23);
        drive(fx, SIZE_80x23, PhosphorDoorwayEffect.TOTAL_TICKS - 1);
        assertFalse(fx.isFinished(), "the last pre-finish render has not finished");
        paint(fx, SIZE_80x23);
        assertTrue(fx.isFinished(), "the render at TOTAL_TICKS finishes the effect");
    }

    @Test
    @DisplayName("FINISH is stall-free: continued driving past the finish repaints the static hold frame")
    void finishStaticLaw() {
        var fx = new PhosphorDoorwayEffect();
        fx.onResize(SIZE_80x23);
        drive(fx, SIZE_80x23, PhosphorDoorwayEffect.TOTAL_TICKS);
        var first = paint(fx, SIZE_80x23);
        var second = paint(fx, SIZE_80x23);
        for (int r = 0; r < SIZE_80x23.rows(); r++) {
            for (int c = 0; c < SIZE_80x23.columns(); c++) {
                assertCellEquals("finished frame " + c + "," + r, first.getCell(c, r), second.getCell(c, r));
            }
        }
    }

    // ── BLACK stage ───────────────────────────────────────────────────

    @Test
    @DisplayName("BLACK: the first render is the bare ground fill, no glyphs, no glow")
    void blackStageLaw() {
        var fx = new PhosphorDoorwayEffect();
        fx.onResize(SIZE_80x23);
        var buf = paint(fx, SIZE_80x23);
        for (int r = 0; r < SIZE_80x23.rows(); r++) {
            for (int c = 0; c < SIZE_80x23.columns(); c++) {
                var cell = buf.getCell(c, r);
                assertEquals(" ", cell.character(), "black frame cell " + c + "," + r + " is space");
                assertColorEquals("black bg " + c + "," + r, GROUND, cell.bg());
                assertColorEquals("black fg rides the bg " + c + "," + r, cell.bg(), cell.fg());
            }
        }
    }

    // ── BLOOM laws ────────────────────────────────────────────────────

    @Test
    @DisplayName("BLOOM accumulation: frame 0 is the un-lit ground; center light grows monotonically")
    void bloomAccumulationLaw() {
        var fx = new PhosphorDoorwayEffect();
        fx.onResize(SIZE_80x23);
        var buf = paint(fx, SIZE_80x23);
        int cx = SIZE_80x23.columns() / 2, cy = SIZE_80x23.rows() / 2;
        assertEquals(GROUND, buf.getCell(cx, cy).bg(), "frame 0 is the un-lit ground");

        long prev = -1;
        for (int t = 1; t <= PhosphorDoorwayEffect.BLACK_TICKS + PhosphorDoorwayEffect.BLOOM_TICKS; t++) {
            buf = paint(fx, SIZE_80x23);
            long cur = light(buf.getCell(cx, cy));
            assertTrue(cur >= prev, "center light accumulates monotonically (t=" + t + ": " + cur + " < " + prev + ")");
            prev = cur;
        }
        assertTrue(prev > 0, "the bloom lights the center by its end");
    }

    @Test
    @DisplayName("BLOOM radial falloff: at mid-bloom the center row is the brightest along a logo-free column")
    void bloomRadialFalloffLaw() {
        var fx = new PhosphorDoorwayEffect();
        fx.onResize(SIZE_80x23);
        drive(fx, SIZE_80x23, PhosphorDoorwayEffect.BLACK_TICKS + PhosphorDoorwayEffect.BLOOM_TICKS / 2);
        var buf = paint(fx, SIZE_80x23);
        int col = 70; // right of the 47-wide logo box (16..62)
        int cy = SIZE_80x23.rows() / 2;
        long[] colLight = new long[SIZE_80x23.rows()];
        for (int r = 0; r < SIZE_80x23.rows(); r++) {
            colLight[r] = light(buf.getCell(col, r));
        }
        assertTrue(colLight[cy] >= colLight[0], "center brighter than the top edge");
        assertTrue(colLight[cy] >= colLight[SIZE_80x23.rows() - 1], "center brighter than the bottom edge");
        for (int d = 1; cy - d >= 0; d++) {          // walking up: non-increasing
            assertTrue(colLight[cy - d] <= colLight[cy - d + 1],
                    "light falls off monotonically upward at distance " + d);
        }
        for (int d = 1; cy + d < SIZE_80x23.rows(); d++) { // walking down: non-increasing
            assertTrue(colLight[cy + d] <= colLight[cy + d - 1],
                    "light falls off monotonically downward at distance " + d);
        }
    }

    @Test
    @DisplayName("BLOOM band quantization: the glow palette is band-quantized along a column")
    void bloomBandQuantizationLaw() {
        var fx = new PhosphorDoorwayEffect();
        fx.onResize(SIZE_80x23);
        drive(fx, SIZE_80x23, PhosphorDoorwayEffect.BLACK_TICKS + PhosphorDoorwayEffect.BLOOM_TICKS / 2);
        var buf = paint(fx, SIZE_80x23);
        var bands = new HashSet<Long>();
        for (int r = 0; r < SIZE_80x23.rows(); r++) {
            bands.add(light(buf.getCell(SIZE_80x23.columns() / 2, r)));
        }
        assertTrue(bands.size() <= PhosphorDoorwayEffect.BLOOM_BANDS,
                "band-quantized palette: " + bands.size() + " distinct bg steps > "
                        + PhosphorDoorwayEffect.BLOOM_BANDS);
    }

    @Test
    @DisplayName("BLOOM space-cell law: glow cells paint ' ' with fg == bg; texture only rides ░")
    void bloomSpaceCellLaw() {
        var fx = new PhosphorDoorwayEffect();
        fx.onResize(SIZE_80x23);
        drive(fx, SIZE_80x23, PhosphorDoorwayEffect.BLACK_TICKS + 10);
        var buf = paint(fx, SIZE_80x23);
        for (int r = 0; r < SIZE_80x23.rows(); r++) {
            for (int c = 0; c < SIZE_80x23.columns(); c++) {
                var cell = buf.getCell(c, r);
                if (cell.character().equals(" ")) {
                    assertColorEquals("glow cell fg == bg at " + c + "," + r, cell.bg(), cell.fg());
                } else {
                    assertEquals("░", cell.character(),
                            "bloom texture is only the ░ grain glyph (cell " + c + "," + r + ")");
                }
            }
        }
    }

    // ── LOGO resolve laws ─────────────────────────────────────────────

    @Test
    @DisplayName("LOGO opens blank: the first logo-stage render paints no glyphs yet")
    void logoOpensBlankLaw() {
        var fx = new PhosphorDoorwayEffect();
        fx.onResize(SIZE_80x23);
        drive(fx, SIZE_80x23, PhosphorDoorwayEffect.BLACK_TICKS + PhosphorDoorwayEffect.BLOOM_TICKS);
        var buf = paint(fx, SIZE_80x23);
        assertEquals(0, glyphSet(buf, SIZE_80x23).size(), "the logo stage opens with zero resolved cells");
    }

    @Test
    @DisplayName("LOGO resolves left to right: each later snapshot is a subset of the full resolve")
    void logoResolveMonotoneLaw() {
        int logoStart = PhosphorDoorwayEffect.BLACK_TICKS + PhosphorDoorwayEffect.BLOOM_TICKS;
        int quarter = logoStart + PhosphorDoorwayEffect.LOGO_TICKS / 4;
        int half = logoStart + PhosphorDoorwayEffect.LOGO_TICKS / 2;
        int full = logoStart + PhosphorDoorwayEffect.LOGO_TICKS;

        var q = glyphSet(driveAt(quarter), SIZE_80x23);
        var h = glyphSet(driveAt(half), SIZE_80x23);
        var f = glyphSet(driveAt(full), SIZE_80x23);
        assertTrue(f.containsAll(q), "the quarter resolve holds under further drive");
        assertTrue(f.containsAll(h), "the half resolve holds under further drive");
        assertTrue(q.size() < h.size(), "the resolve reveals progressively ("
                + q.size() + " cells at quarter < " + h.size() + " at half)");
        assertTrue(h.size() < f.size(), "the resolve reveals progressively ("
                + h.size() + " cells at half < " + f.size() + " at full)");
    }

    private ScreenBuffer driveAt(int tickInclusive) {
        return driveAt(SIZE_80x23, tickInclusive);
    }

    private ScreenBuffer driveAt(TerminalSize size, int lastTick) {
        var fx = new PhosphorDoorwayEffect();
        fx.onResize(size);
        return drive(fx, size, lastTick + 1); // drives paints ticks 0..lastTick inclusive
    }

    @Test
    @DisplayName("LOGO font law: the authored PHOSPHOR bitmap resolves to exactly 105 glyph cells")
    void logoFontLaw() {
        var buf = driveAt(SIZE_80x23, PhosphorDoorwayEffect.BLACK_TICKS
                + PhosphorDoorwayEffect.BLOOM_TICKS + PhosphorDoorwayEffect.LOGO_TICKS - 1);
        assertEquals(105, glyphSet(buf, SIZE_80x23).size(), "font law: the authored bitmap resolves fully");
        for (int r = 0; r < SIZE_80x23.rows(); r++) {
            for (int c = 0; c < SIZE_80x23.columns(); c++) {
                var ch = buf.getCell(c, r).character();
                assertTrue(ch.equals(" ") || ch.equals("░") || ch.equals("█") || ch.equals("▀") || ch.equals("▄"),
                        "glyph cells ride the half-block law and the grain is ░ only" +
                                " (cell " + c + "," + r + " = '" + ch + "')");
            }
        }
    }

    @Test
    @DisplayName("LOGO glyph colors: holds carry the Phosphor gold exactly")
    void logoGoldLaw() {
        var buf = driveAt(PhosphorDoorwayEffect.BLACK_TICKS + PhosphorDoorwayEffect.BLOOM_TICKS
                + PhosphorDoorwayEffect.LOGO_TICKS);
        Set<String> glyphPositions = glyphSet(buf, SIZE_80x23);
        assertFalse(glyphPositions.isEmpty());
        for (var pos : glyphPositions) {
            var parts = pos.split("x");
            int c = Integer.parseInt(parts[0]), r = Integer.parseInt(parts[1]);
            assertColorEquals("glyph fg is the Phosphor gold at " + pos, GOLD, buf.getCell(c, r).fg());
        }
    }

    @Test
    @DisplayName("SWEEP: the mid resolve flashes cream on resolved cells; the hold frame is pure gold")
    void sweepLaw() {
        int logoStart = PhosphorDoorwayEffect.BLACK_TICKS + PhosphorDoorwayEffect.BLOOM_TICKS;
        var mid = driveAt(logoStart + PhosphorDoorwayEffect.LOGO_TICKS / 2);
        boolean creamFound = false;
        for (int r = 0; r < SIZE_80x23.rows() && !creamFound; r++) {
            for (int c = 0; c < SIZE_80x23.columns() && !creamFound; c++) {
                creamFound = colorEquals(CREAM, mid.getCell(c, r).fg());
            }
        }
        assertTrue(creamFound, "the sweep highlight flashes cream during the resolve");
        var hold = driveAt(PhosphorDoorwayEffect.TOTAL_TICKS - 1);
        for (int r = 0; r < SIZE_80x23.rows(); r++) {
            for (int c = 0; c < SIZE_80x23.columns(); c++) {
                assertFalse(colorEquals(CREAM, hold.getCell(c, r).fg()),
                        "the sweep has passed by the hold (cell " + c + "," + r + ")");
            }
        }
    }

    // ── HOLD laws ─────────────────────────────────────────────────────

    @Test
    @DisplayName("HOLD is static: consecutive hold frames are cell-identical")
    void holdStaticLaw() {
        var fx = new PhosphorDoorwayEffect();
        fx.onResize(SIZE_80x23);
        drive(fx, SIZE_80x23, PhosphorDoorwayEffect.BLACK_TICKS + PhosphorDoorwayEffect.BLOOM_TICKS
                + PhosphorDoorwayEffect.LOGO_TICKS);
        var before = paint(fx, SIZE_80x23); // the hold frame
        for (int i = 0; i < 5; i++) {
            var now = paint(fx, SIZE_80x23);
            for (int r = 0; r < SIZE_80x23.rows(); r++) {
                for (int c = 0; c < SIZE_80x23.columns(); c++) {
                    assertCellEquals("hold frame " + i + " " + c + "," + r, before.getCell(c, r), now.getCell(c, r));
                }
            }
        }
    }

    // ── Tier laws ─────────────────────────────────────────────────────

    @Test
    @DisplayName("TIER block: 80 columns fit the 47-wide wordmark")
    void tierBlockLaw() {
        var buf = driveAt(SIZE_80x23, PhosphorDoorwayEffect.TOTAL_TICKS - 1);
        assertEquals(105, glyphSet(buf, SIZE_80x23).size());
    }

    @Test
    @DisplayName("TIER fallback: 44 columns fit the no-gap wordmark (same bitmap, narrower box)")
    void tierFallbackLaw() {
        var size = new TerminalSize(44, 14);
        var buf = driveAt(size, PhosphorDoorwayEffect.TOTAL_TICKS - 1);
        var glyphs = glyphSet(buf, size);
        int letters = 8;
        int noGapCols = letters * 5;
        assertEquals(105, glyphs.size(), "the fallback carries the same authored bitmap");
        int x0 = (size.columns() - noGapCols) / 2;
        for (var pos : glyphs) {
            int c = Integer.parseInt(pos.split("x")[0]);
            assertTrue(c >= x0 && c < x0 + noGapCols,
                    "the no-gap wordmark sits inside its 40-column box at " + pos);
        }
    }

    @Test
    @DisplayName("TIER boundary: 40 columns fit no logo — the bloom alone renders")
    void tierNoneLaw() {
        var size = new TerminalSize(40, 14);
        var buf = driveAt(size, PhosphorDoorwayEffect.TOTAL_TICKS - 1);
        assertEquals(0, glyphSet(buf, size).size(), "below the fallback width: no logo at all");
    }

    @Test
    @DisplayName("TIER rows: a 5-row terminal fits no logo; the bloom alone renders and never throws")
    void tierRowsLaw() {
        var size = new TerminalSize(60, 5);
        var fx = new PhosphorDoorwayEffect();
        fx.onResize(size);
        assertDoesNotThrow(() -> drive(fx, size, PhosphorDoorwayEffect.TOTAL_TICKS));
        assertEquals(0, glyphSet(driveAt(size, PhosphorDoorwayEffect.TOTAL_TICKS - 1), size).size(),
                "below the minimum rows: no logo");
    }

    // ── Determinism / resize ──────────────────────────────────────────

    @Test
    @DisplayName("DETERMINISM: two effects driven identically produce identical frames at every tick")
    void determinismLaw() {
        var size = SIZE_80x23;
        var a = new PhosphorDoorwayEffect();
        var b = new PhosphorDoorwayEffect();
        a.onResize(size);
        b.onResize(size);
        for (int t = 0; t < PhosphorDoorwayEffect.TOTAL_TICKS; t++) {
            var ba = new ScreenBuffer(size);
            var bb = new ScreenBuffer(size);
            a.renderFrame(new TextGraphics(ba), size);
            b.renderFrame(new TextGraphics(bb), size);
            for (int r = 0; r < size.rows(); r++) {
                for (int c = 0; c < size.columns(); c++) {
                    assertCellEquals("determinism " + c + "," + r + " at t=" + t,
                            ba.getCell(c, r), bb.getCell(c, r));
                }
            }
        }
    }

    @Test
    @DisplayName("RESIZE re-centers the bloom and keeps the render lawful at the new geometry")
    void resizeLaw() {
        var fx = new PhosphorDoorwayEffect();
        fx.onResize(SIZE_80x23);
        drive(fx, SIZE_80x23, PhosphorDoorwayEffect.BLACK_TICKS + 5);
        var grown = new TerminalSize(100, 30);
        fx.onResize(grown);
        var buf = paint(fx, grown);
        long centerLight = light(buf.getCell(50, 15));
        long cornerLight = light(buf.getCell(0, 0));
        assertTrue(centerLight >= cornerLight, "the re-centered bloom lights the new center");
        var tiny = new TerminalSize(4, 3);
        assertDoesNotThrow(() -> fx.renderFrame(new TextGraphics(new ScreenBuffer(tiny)), tiny));
    }

    @Test
    @DisplayName("degenerate and odd-geometry renders never throw")
    void degenerateLaw() {
        var fx = new PhosphorDoorwayEffect();
        fx.onResize(SIZE_80x23);
        var zeroCols = new TerminalSize(0, 10);
        assertDoesNotThrow(() -> fx.renderFrame(new TextGraphics(new ScreenBuffer(zeroCols)), zeroCols));
        var zeroRows = new TerminalSize(10, 0);
        assertDoesNotThrow(() -> fx.renderFrame(new TextGraphics(new ScreenBuffer(zeroRows)), zeroRows));
        // An odd x odd geometry puts the bloom center on an exact cell (the d == 0 band law).
        var odd = new TerminalSize(41, 23);
        assertDoesNotThrow(() -> fx.renderFrame(new TextGraphics(new ScreenBuffer(odd)), odd));
    }

    // ── Ticks-per-render (real-time cap at any render fps) ───────────

    @Test
    @DisplayName("TICKS-PER-RENDER: 3 ticks per render finish 288 logic ticks in 96 renders")
    void ticksPerRenderLaw() {
        var fx = new PhosphorDoorwayEffect();
        fx.onResize(SIZE_80x23);
        fx.setTicksPerRender(3);
        int renders = 0;
        while (!fx.isFinished() && renders < 1000) {
            fx.renderFrame(new TextGraphics(new ScreenBuffer(SIZE_80x23)), SIZE_80x23);
            renders++;
        }
        assertTrue(fx.isFinished(), "the slowed render still finishes");
        assertEquals(PhosphorDoorwayEffect.TOTAL_TICKS / 3, renders,
                "3 ticks per render cover 288 logic ticks in 96 renders");
        assertTrue(fx.tickCount() <= PhosphorDoorwayEffect.HARD_CAP_TICKS, "the timeline stays inside the cap");
    }

    @Test
    @DisplayName("TICKS-PER-RENDER guard: non-positive resets to the 1-tick default")
    void ticksPerRenderGuardLaw() {
        var fx = new PhosphorDoorwayEffect();
        fx.setTicksPerRender(0);
        assertEquals(1, fx.getTicksPerRender());
        fx.setTicksPerRender(-4);
        assertEquals(1, fx.getTicksPerRender());
    }

    // ── Wire-byte degradation laws (DefaultScreen + MockTerminal) ─────

    @Test
    @DisplayName("DEGRADE ANSI16: mapped base-palette bytes only — no truecolor, no 256, no 2026")
    void ansi16NoSyncLaw() throws IOException {
        var out = wireAt(SIZE_80x23, ColorDepth.ANSI16,
                PhosphorDoorwayEffect.BLACK_TICKS + PhosphorDoorwayEffect.BLOOM_TICKS / 2);
        assertFalse(out.isEmpty(), "the degraded paint is not empty");
        assertWire(out, "38;2;", "48;2;", "38;5;", "48;5;", "\u001b[?2026");
        assertTrue(out.contains("\u001b[3") || out.contains("\u001b[4"),
                "base ANSI16 palette sequences emit: " + head(out));
    }

    @Test
    @DisplayName("DEGRADE C256: mapped 256 palette bytes only — no truecolor, no 2026")
    void c256NoSyncLaw() throws IOException {
        var out = wireAt(SIZE_80x23, ColorDepth.C256,
                PhosphorDoorwayEffect.BLACK_TICKS + PhosphorDoorwayEffect.BLOOM_TICKS);
        assertFalse(out.isEmpty(), "the degraded paint is not empty");
        assertWire(out, "38;2;", "48;2;", "\u001b[?2026");
        assertTrue(out.contains("38;5;") || out.contains("48;5;"),
                "mapped C256 palette sequences emit: " + head(out));
    }

    @Test
    @DisplayName("TRUECOLOR primary: 48;2 glow and the verbatim Phosphor gold 38;2 bytes")
    void truecolorPrimaryLaw() throws IOException {
        var out = wireAt(SIZE_80x23, ColorDepth.TRUECOLOR,
                PhosphorDoorwayEffect.BLACK_TICKS + PhosphorDoorwayEffect.BLOOM_TICKS
                        + PhosphorDoorwayEffect.LOGO_TICKS / 2);
        assertTrue(out.contains("48;2;"), "truecolor bg paints on the default tier: " + head(out));
        assertTrue(out.contains("\u001b[38;2;255;214;71m"),
                "the Phosphor gold law constant paints verbatim: " + head(out));
    }

    @Test
    @DisplayName("NO-2026 even at truecolor: a sync-less terminal never sees a sync bracket")
    void no2026AtTruecolorLaw() throws IOException {
        var out = wireAt(SIZE_80x23, ColorDepth.TRUECOLOR,
                PhosphorDoorwayEffect.BLACK_TICKS + PhosphorDoorwayEffect.BLOOM_TICKS);
        assertWire(out, "\u001b[?2026", "\u001b[?2026;2$y", "\u001b[?2026;0$y");
    }
}
