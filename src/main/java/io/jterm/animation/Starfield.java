package io.jterm.animation;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.style.RgbColor;
import io.jterm.style.TextCell;

import java.util.Random;

/**
 * Menu ambient star scene: sparse drifting star dots with occasional
 * twinkle over a deep-space fill. A deterministic {@link AnimatedBackground}
 * — all motion derives from the render counter and the construction seed,
 * never from the clock (golden tick laws).
 *
 * <p><b>Laws (tests: {@code StarfieldTest}):</b></p>
 * <ul>
 *   <li><b>STAR-COUNT</b> — one star per 160 cells, clamped to [3, 32].</li>
 *   <li><b>STAR-SEEDS</b> — scatter is the {@code (nextInt(cols), nextInt(rows))}
 *       sequence of {@link Random}(seed); a re-layout replays the SAME seed.</li>
 *   <li><b>DEEP-SPACE</b> — every cell paints space on RgbColor(6,8,16);
 *       stars paint over it, later stars win. Cells are cached constants so
 *       static frames stay byte-identical to the delta engine.</li>
 *   <li><b>DRIFT</b> — render t (0-based) advances no star; t divisible by 4
 *       steps every star right one cell, wrapping at the width (a full field
 *       cycle in width*4 renders).</li>
 *   <li><b>TWINKLE</b> — bright iff (t + phase) % 30 &lt; 2, where
 *       phase = (x0 + 7*y0) % 30; bright glyph '*' in warm white
 *       RgbColor(255,249,196), dim glyph '.' in RgbColor(148,164,196).</li>
 * </ul>
 */
public class Starfield implements AnimatedBackground {

    private static final int TARGET_FPS = 10;
    static final RgbColor SPACE = new RgbColor(6, 8, 16);
    static final RgbColor DIM_STAR = new RgbColor(148, 164, 196);
    static final RgbColor BRIGHT_STAR = new RgbColor(255, 249, 196);

    static final TextCell BG_CELL = new TextCell(' ', SPACE, SPACE);
    static final TextCell DIM_CELL = new TextCell('.', DIM_STAR, SPACE);
    static final TextCell BRIGHT_CELL = new TextCell('*', BRIGHT_STAR, SPACE);

    private static final int DRIFT_LAG = 4;
    private static final int TWINKLE_PERIOD = 30;
    private static final int TWINKLE_WINDOW = 2;

    private final long seed;
    private volatile Star[] stars;
    private volatile TerminalSize lastSize;
    private volatile boolean running;
    private volatile int step;

    private static final class Star {
        final int x0;
        final int y0;
        final int phase;
        int x;

        Star(int x, int y, int cols) {
            this.x0 = x;
            this.y0 = y;
            this.x = Math.floorMod(x, cols);
            this.phase = Math.floorMod(x + 7 * y, TWINKLE_PERIOD);
        }
    }

    /** Constructs the ambient with a fresh random seed. */
    public Starfield() {
        this(new Random().nextLong());
    }

    /** Constructs the ambient from a pinned seed (deterministic scatter). */
    public Starfield(long seed) {
        this.seed = seed;
    }

    @Override
    public void renderFrame(TextGraphics graphics, TerminalSize size) {
        lastSize = size;
        if (size.columns() <= 0 || size.rows() <= 0) {
            return;
        }
        // Frame 0 (t=0) draws the scatter as-is; later multiples of
        // DRIFT_LAG step every star one cell right.
        if (step > 0 && step % DRIFT_LAG == 0) {
            advanceStars(size.columns());
        }
        graphics.fillRectangle(0, 0, size.columns(), size.rows(), BG_CELL);
        var s = stars;
        if (s == null) {
            return;
        }
        for (var star : s) {
            boolean bright = ((step + star.phase) % TWINKLE_PERIOD) < TWINKLE_WINDOW;
            int cellX = Math.floorMod(star.x, size.columns());
            // Out-of-bounds writes are ignored by ScreenBuffer, so a stale
            // scatter simply clamps away at the new geometry until replay.
            graphics.setCell(cellX, star.y0, bright ? BRIGHT_CELL : DIM_CELL);
        }
        step++;
    }

    private void advanceStars(int cols) {
        // The render guard returns before step++ advances, so this only runs
        // once a layout exists — stars is non-null by construction.
        for (var star : stars) {
            star.x = Math.floorMod(star.x + 1, cols);
        }
    }

    @Override
    public void onResize(TerminalSize newSize) {
        lastSize = newSize;
        // STAR-SEEDS law: re-lay replays the SAME seed into the new geometry.
        relayout(newSize);
    }

    private void relayout(TerminalSize size) {
        int count = Math.clamp((long) size.area() / 160, 3, 32);
        var rnd = new Random(seed);
        var laid = new Star[count];
        for (int i = 0; i < count; i++) {
            int x = rnd.nextInt(size.columns());
            int y = rnd.nextInt(size.rows());
            laid[i] = new Star(x, y, size.columns());
        }
        stars = laid;
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
        return TARGET_FPS;
    }

    @Override
    public TerminalSize lastSize() {
        return lastSize;
    }

    int starCount() {
        var s = stars;
        return s != null ? s.length : 0;
    }

    int starX(int index) {
        return stars[index].x;
    }

    int starY(int index) {
        return stars[index].y0;
    }

    int starPhase(int index) {
        return stars[index].phase;
    }
}
