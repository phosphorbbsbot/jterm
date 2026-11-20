package io.jterm.animation;

import io.jterm.core.ColorDepth;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.HalfBlockBuffer;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.RgbColor;
import io.jterm.style.TextCell;

/**
 * The Phosphor doorway intro (animation plan card 2, 2026-11-19): a glowing
 * phosphor bloom accumulates from darkness, resolves the PHOSPHOR wordmark at
 * sub-cell resolution, holds, and hands off to the login screen. The pure
 * stage engine of the intro — a deterministic {@link AnimatedBackground}
 * whose every frame derives from the RENDER COUNTER (one render = one 60 fps
 * logic tick, never the clock), so golden tick tests pin its exact timeline.
 *
 * <p>Stages (60 Hz logic ticks; 1 tick = 1/60 s):</p>
 * <ul>
 *   <li><b>BLACK</b> — {@value #BLACK_TICKS} ticks: the bare un-lit ground.</li>
 *   <li><b>BLOOM</b> — {@value #BLOOM_TICKS} ticks: a radial glow accumulates
 *       per cell from the screen center (light level = falloff × growth,
 *       band-quantized to {@value #BLOOM_BANDS} band steps through the
 *       truecolor 48;2/38;2 pipeline).</li>
 *   <li><b>LOGO</b> — {@value #LOGO_TICKS} ticks: the bloom freezes at full;
 *       the block logo resolves progressively left-to-right with a per-cell
 *       settle jitter under a warm cream sweep highlight.</li>
 *   <li><b>HOLD</b> — {@value #HOLD_TICKS} ticks: the complete logo frame is
 *       static (a continued render is byte-identical — idle-friendly).</li>
 * </ul>
 *
 * <p><b>Laws (tests: {@code PhosphorDoorwayEffectTest}):</b></p>
 * <ul>
 *   <li><b>LOGIC CLOCK</b> — one render = one logic tick = 1/60 s; the total
 *       play is {@value #TOTAL_TICKS} ticks &lt;= {@value #HARD_CAP_TICKS}
 *       (the 5.0 s hard cap). A slowed render loop survives the cap via
 *       {@link #setTicksPerRender}: N ticks per render cover the same
 *       timeline in TOTAL/N renders.</li>
 *   <li><b>SPACE CELL</b> — glow-only cells paint ' ' with fg == bg (the
 *       ambient space-cell byte law); the only texture glyph is ░.</li>
 *   <li><b>PHOSPHOR GOLD</b> — the logo and resolved glyph fg is
 *       {@link #PHOSPHOR_GOLD}, the ART_DECO law gold; the sweep highlight is
 *       {@link #SWEEP_CREAM}; the ground is {@link #GROUND}.</li>
 *   <li><b>SUB-CELL LOGO</b> — the wordmark is the authored 5-column letter
 *       bitmaps through {@link HalfBlockBuffer}: block tier 47 wide (cols
 *       ≥ 49), no-gap fallback 40 wide (43..48), nothing below or on rows
 *       &lt; 6; glyph cells only paint the half-block law characters █▀▄ and
 *       resolve to exactly {@value #BLOCK_GLYPH_CELLS} cells on the block
 *       tier.</li>
 *   <li><b>DEPTH DEGRADE</b> — colors ride the ordinary TextGraphics →
 *       DefaultScreen pipeline: TRUECOLOR emits 48;2/38;2, ANSI16/C256
 *       tiers receive mapped palette colors, and no bracket is synthesized
 *       here (bracketing is the screen tier's sync law).</li>
 *   <li><b>THREAD-FREE</b> — the effect owns no timers or threads; the
 *       hosting window drives {@link #renderFrame}. {@link #start}/{@link
 *       #stop} only flip the contract flag.</li>
 * </ul>
 */
public class PhosphorDoorwayEffect implements AnimatedBackground {

    /** The logic clock: the timeline is expressed in 60 fps logic ticks. */
    public static final int LOGIC_FPS = 60;
    /** BLACK stage length in logic ticks (0.2 s). */
    public static final int BLACK_TICKS = 12;
    /** BLOOM stage length in logic ticks (2.5 s). */
    public static final int BLOOM_TICKS = 150;
    /** LOGO resolve length in logic ticks (1.6 s). */
    public static final int LOGO_TICKS = 96;
    /** HOLD stage length in logic ticks (0.5 s). */
    public static final int HOLD_TICKS = 30;
    /** Total play length: the stage constants tile this exactly. */
    public static final int TOTAL_TICKS = BLACK_TICKS + BLOOM_TICKS + LOGO_TICKS + HOLD_TICKS;
    /** HARD CAP: the play must finish at or before this many logic ticks (5.0 s). */
    public static final int HARD_CAP_TICKS = 300;
    /** Band steps the bloom glow quantizes to (0..{@value #BLOOM_BANDS}-1). */
    public static final int BLOOM_BANDS = 12;
    /** Authored glyph-cell count of the wordmark on the block tier. */
    public static final int BLOCK_GLYPH_CELLS = 105;

    /** The Phosphor palette law colors. */
    public static final RgbColor GROUND = new RgbColor(6, 8, 16);
    /** The Phosphor gold — the ART_DECO law constant (255, 214, 71). */
    public static final RgbColor PHOSPHOR_GOLD = new RgbColor(255, 214, 71);
    /** The sweep highlight warm cream. */
    public static final RgbColor SWEEP_CREAM = new RgbColor(255, 249, 196);

    /** Intro stages, in play order. */
    public enum Stage { BLACK, BLOOM, LOGO, HOLD }

    // ── Logo art: authored 5-column, 8-bitmap-row letter shapes ───────

    private static final String WORD = "PHOSPHOR";
    private static final int LETTER_WIDTH = 5;
    private static final int LETTER_SUB_ROWS = 8;
    private static final int LOGO_COLUMNS = WORD.length() * (LETTER_WIDTH + 1) - 1; // 47
    private static final int LOGO_CELL_ROWS = LETTER_SUB_ROWS / 2;                  // 4

    private static final int[][] LETTER_P = {
            {1, 1, 1, 1, 0}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 1, 1, 1, 0},
            {1, 0, 0, 0, 0}, {1, 0, 0, 0, 0}, {1, 0, 0, 0, 0}, {1, 0, 0, 0, 0}};
    private static final int[][] LETTER_H = {
            {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 1, 1, 1, 1},
            {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}};
    private static final int[][] LETTER_O = {
            {0, 1, 1, 1, 0}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1},
            {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {0, 1, 1, 1, 0}};
    private static final int[][] LETTER_S = {
            {0, 1, 1, 1, 1}, {1, 0, 0, 0, 0}, {1, 0, 0, 0, 0}, {0, 1, 1, 1, 0},
            {0, 0, 0, 0, 1}, {0, 0, 0, 0, 1}, {0, 0, 0, 0, 1}, {1, 1, 1, 1, 0}};
    private static final int[][] LETTER_R = {
            {1, 1, 1, 1, 0}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 1, 1, 1, 0},
            {1, 0, 1, 0, 0}, {1, 0, 0, 1, 0}, {1, 0, 0, 1, 0}, {1, 0, 0, 0, 1}};

    /** The wordmark letter bitmaps, one per WORD character in order. */
    private static final int[][][] LETTERS = {
            LETTER_P, LETTER_H, LETTER_O, LETTER_S, LETTER_P, LETTER_H, LETTER_O, LETTER_R};

    private static final ScreenBuffer BLOCK_ART = buildWordmark(1);
    private static final ScreenBuffer GAPLESS_ART = buildWordmark(0);

    private static ScreenBuffer buildWordmark(int letterSpacing) {
        int cols = WORD.length() * (LETTER_WIDTH + letterSpacing) - letterSpacing;
        var art = new HalfBlockBuffer(cols, LETTER_SUB_ROWS);
        int x = 0;
        for (int i = 0; i < WORD.length(); i++) {
            var bitmap = LETTERS[i];
            for (int sr = 0; sr < LETTER_SUB_ROWS; sr++) {
                for (int lw = 0; lw < LETTER_WIDTH; lw++) {
                    if (bitmap[sr][lw] == 1) {
                        art.setSubCell(x + lw, sr, PHOSPHOR_GOLD);
                    }
                }
            }
            x += LETTER_WIDTH + letterSpacing;
        }
        return art.toScreenBuffer();
    }

    // ── State ─────────────────────────────────────────────────────────

    private volatile TerminalSize lastSize;
    /** The next logic tick to paint (render counter; the ONLY clock). */
    private volatile int tick;
    private volatile Stage lastStage = Stage.BLACK;
    private volatile int ticksPerRender = 1;
    private volatile boolean running;

    /** Constructs the doorway effect. */
    public PhosphorDoorwayEffect() {
    }

    // ── Timeline API ──────────────────────────────────────────────────

    /**
     * Pure stage table for a logic tick (the golden sequencing law): the
     * axis clamps — a negative tick reads BLACK, a tick past the total reads
     * HOLD.
     *
     * @param logicTick the 60 Hz logic tick
     * @return the stage whose tick interval contains {@code logicTick}
     */
    public static Stage stageAt(int logicTick) {
        if (logicTick < BLACK_TICKS) {
            return Stage.BLACK;
        }
        if (logicTick < BLACK_TICKS + BLOOM_TICKS) {
            return Stage.BLOOM;
        }
        if (logicTick < BLACK_TICKS + BLOOM_TICKS + LOGO_TICKS) {
            return Stage.LOGO;
        }
        return Stage.HOLD;
    }

    /** @return the stage of the last painted frame. */
    Stage stage() {
        return lastStage;
    }

    /** @return the next logic tick to paint (the render counter). */
    public int tickCount() {
        return tick;
    }

    /** @return true when the play has reached the total (the door may exit). */
    public boolean isFinished() {
        return tick >= TOTAL_TICKS;
    }

    /**
     * Sets how many logic ticks every render advances (≥ 1). The hosting
     * window sets this from its render fps so the timeline covers the same
     * real time at any frame rate (the real-time hard cap law).
     *
     * @param ticksPerRender logic ticks per render (non-positive resets to 1)
     */
    public void setTicksPerRender(int ticksPerRender) {
        this.ticksPerRender = Math.max(1, ticksPerRender);
    }

    /** @return how many logic ticks every render advances. */
    public int getTicksPerRender() {
        return ticksPerRender;
    }

    // ── AnimatedBackground contract ───────────────────────────────────

    @Override
    public void renderFrame(TextGraphics graphics, TerminalSize size) {
        lastSize = size;
        var stage = stageAt(tick);
        lastStage = stage;
        if (size.columns() > 0 && size.rows() > 0) {
            paintFrame(graphics, size, stage, tick);
        }
        tick += ticksPerRender; // the ONLY advance: the render counter, never the clock
    }

    @Override
    public void onResize(TerminalSize newSize) {
        lastSize = newSize;
        // Stateless per-cell math: a re-layout needs no state reset; the next
        // frame re-derives the bloom center and logo tier at the new geometry.
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int targetFps() {
        return LOGIC_FPS;
    }

    @Override
    public TerminalSize lastSize() {
        return lastSize;
    }

    // ── Frame painting ────────────────────────────────────────────────

    private void paintFrame(TextGraphics graphics, TerminalSize size, Stage stage, int t) {
        int cols = size.columns(), rows = size.rows();
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                graphics.setCell(x, y, bloomCell(x, y, cols, rows, stage, t));
            }
        }
        var layout = logoLayout(cols, rows);
        if (layout != null) {
            paintLogo(layout, size, stage, t, graphics);
        }
    }

    /**
     * The grain phase rolls every 6 logic ticks through the bloom and the
     * resolve, then FREEZES at the resolve total so the hold frame is static.
     */
    private static final int SHIMMER_ROLL = 6;

    private static int shimmerPhase(int t) {
        return Math.min(t, BLACK_TICKS + BLOOM_TICKS + LOGO_TICKS) / SHIMMER_ROLL;
    }

    /** Bloom cell law: band-quantized radial light over the ground. */
    private TextCell bloomCell(int x, int y, int cols, int rows, Stage stage, int t) {
        double cx = (cols - 1) / 2.0, cy = (rows - 1) / 2.0;
        double maxR = Math.hypot(Math.max(cx, cols - 1 - cx), Math.max(cy, rows - 1 - cy));
        double d = Math.hypot(x - cx, y - cy);
        double frac = d == 0 ? 1 : Math.max(0, 1 - d / maxR);

        double light;
        if (stage == Stage.BLACK) {
            light = 0;
        } else if (t < BLACK_TICKS + BLOOM_TICKS) {
            double p = (t - BLACK_TICKS) / (double) BLOOM_TICKS;
            double radius = maxR * (0.1 + 0.9 * Math.sqrt(p));
            double growth = 0.25 + 0.75 * p;
            double inGlow = Math.max(0, 1 - d / radius);
            light = Math.pow(inGlow, 1.5) * growth;
        } else {
            // LOGO/HOLD: the bloom froze at full light (the bloom-end value).
            light = Math.pow(frac, 1.5);
        }
        var bg = bandColor(light);
        char ch = ' ';
        var fg = bg;
        if (band(light) >= 2 && (x * 31 + y * 13 + shimmerPhase(t)) % 7 == 0) {
            // Deterministic phosphor grain: shading glyph rides the band above.
            ch = '░';
            fg = bandIndex(minBand(band(light) + 1, BLOOM_BANDS - 1));
        }
        return new TextCell(ch, fg, bg);
    }

    /** Quantizes a light level to its band index 0..BLOOM_BANDS-1. */
    private int band(double light) {
        return minBand((int) Math.round(light * (BLOOM_BANDS - 1)), BLOOM_BANDS - 1);
    }

    private int minBand(int value, int limit) {
        return Math.max(0, Math.min(limit, value));
    }

    /** The band palette: GROUND at band 0, the Phosphor gold at the top band. */
    private RgbColor bandIndex(int band) {
        double k = band / (double) (BLOOM_BANDS - 1);
        return lerp(GROUND, PHOSPHOR_GOLD, k);
    }

    /** Coarse quantized color for a light level. */
    private RgbColor bandColor(double light) {
        return bandIndex(band(light));
    }

    private static RgbColor lerp(RgbColor from, RgbColor to, double k) {
        return new RgbColor(
                (int) Math.round(from.r() + (to.r() - from.r()) * k),
                (int) Math.round(from.g() + (to.g() - from.g()) * k),
                (int) Math.round(from.b() + (to.b() - from.b()) * k));
    }

    // ── Logo layout + resolve ─────────────────────────────────────────

    private record LogoLayout(ScreenBuffer art, int x0, int y0) {
    }

    /** Tier law: block art at ≥ 49 columns, no-gap fallback at ≥ 42, none below or on rows < 6. */
    private LogoLayout logoLayout(int cols, int rows) {
        if (rows < 6) {
            return null;
        }
        ScreenBuffer art;
        int x0;
        if (cols >= LOGO_COLUMNS + 2) {
            art = BLOCK_ART;
            x0 = (cols - LOGO_COLUMNS) / 2;
        } else if (cols >= GAPLESS_ART.size().columns() + 2) {
            art = GAPLESS_ART;
            x0 = (cols - GAPLESS_ART.size().columns()) / 2;
        } else {
            return null;
        }
        int y0 = (rows - LOGO_CELL_ROWS) / 2;
        return new LogoLayout(art, x0, y0);
    }

    /**
     * Paints resolved wordmark cells over the bloom. During LOGO the reveal
     * progresses left-to-right under the cream sweep; from HOLD on the
     * complete logo paints static in gold.
     */
    private void paintLogo(LogoLayout layout, TerminalSize size, Stage stage, int t,
                           TextGraphics graphics) {
        var art = layout.art();
        int artCols = art.size().columns();
        double resolveQ;
        if (stage == Stage.HOLD) {
            resolveQ = 1.0;
        } else {
            resolveQ = Math.max(0.0, (t - (BLACK_TICKS + BLOOM_TICKS)) / (double) LOGO_TICKS);
        }
        int sweepX = layout.x0() + (int) Math.floor(resolveQ * 1.15 * artCols);
        for (int r = 0; r < LOGO_CELL_ROWS; r++) {
            for (int c = 0; c < artCols; c++) {
                var painted = art.getCell(c, r);
                if (painted.character().equals(" ")) {
                    continue; // no glyph in either half — the bloom shows through
                }
                if (resolveQ < cellResolveQ(c, r, artCols)) {
                    continue; // not resolved yet — the bloom shows through
                }
                int x = layout.x0() + c, y = layout.y0() + r;
                RgbColor fg = PHOSPHOR_GOLD;
                if (stage == Stage.LOGO && Math.abs(x - sweepX) <= 1) {
                    fg = SWEEP_CREAM;
                }
                // The bloom bg stays; the glyph rides as fg (a half-block char
                // leaves the empty half glowing through).
                graphics.setCell(x, y, new TextCell(painted.character().charAt(0), fg, cellBg(size, layout, c, r, stage, t)));
            }
        }
    }

    /** The cellResolveQ threshold: left-to-right wave with a deterministic settle jitter. */
    private static double cellResolveQ(int c, int r, int artCols) {
        double jitter = (((c * 61) + (r * 127)) & 0xff) / 255.0;
        return 0.7 * (c + 0.5) / artCols + 0.2 * jitter;
    }

    private TextCell bloomCellAt(int x, int y, TerminalSize size, Stage stage, int t) {
        return bloomCell(x, y, size.columns(), size.rows(), stage, t);
    }

    /** Re-derives the bloom bg for a logo cell so the glyph paints over live glow. */
    private RgbColor cellBg(TerminalSize size, LogoLayout layout, int c, int r, Stage stage, int t) {
        var cell = bloomCellAt(layout.x0() + c, layout.y0() + r, size, stage, t);
        return (RgbColor) cell.bg();
    }
}
