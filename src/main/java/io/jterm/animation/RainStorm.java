package io.jterm.animation;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.style.AnsiColor;
import io.jterm.style.SGR;
import io.jterm.style.TextCell;

import java.util.Random;

/**
 * Animated rain storm background. Rain characters fall diagonally with a
 * slight wind drift. Occasional lightning flashes briefly illuminate the
 * full screen with a white/cyan tint.
 */
public class RainStorm implements AnimatedBackground {

    private static final int TARGET_FPS = 10;
    private static final int MIN_DROPS = 80;
    private static final int MAX_DROPS = 400;
    /** Drops per 80×24 screen area — scales with terminal size. */
    private static final double DROPS_PER_AREA = 100.0 / (80 * 24);

    private Random random = new Random();

    private volatile boolean running;
    private volatile TerminalSize lastSize;
    private volatile double time;

    private Drop[] drops;
    private int flashFrames;        // remaining frames of lightning flash
    private int flashCooldown;      // frames until next flash attempt

    /**
     * Constructs a new RainStorm instance.
     * @param preferredSize the preferred size
     */
    public RainStorm(TerminalSize preferredSize) {
        this(preferredSize, new Random());
    }

    /**
     * Test constructor: injects the RNG so frame assertions can be deterministic. Production
     * callers keep the no-arg form (unseeded). Rain drops/lightning land on random cells, so
     * unseeded randomness makes cell-exact assertions flaky under load (nightly 10/20 hit).
     *
     * @param preferredSize the preferred size
     * @param rng           the random source to use for drop placement and lightning
     */
    public RainStorm(TerminalSize preferredSize, Random rng) {
        this.random = rng;
        onResize(preferredSize);
    }

    @Override
    /**
     * Renders one frame of the animation into the given graphics buffer.
     * @param graphics the graphics
     * @param size the size
     */
    public void renderFrame(TextGraphics graphics, TerminalSize size) {
        lastSize = size;
        if (size.columns() <= 0 || size.rows() <= 0) return;

        renderAtTime(graphics, size, time, false);
        time += 0.1;
    }

    /**
     * Renders one frame of the animation at the given time.
     *
     * @param graphics the graphics to render into
     * @param size     the terminal size to render at
     * @param t        the animation time
     */
    public void renderAtTime(TextGraphics graphics, TerminalSize size, double t) {
        renderAtTime(graphics, size, t, true);
    }

    private void renderAtTime(TextGraphics graphics, TerminalSize size, double t, boolean testMode) {
        if (size.columns() <= 0 || size.rows() <= 0) return;

        ensureDrops(size);

        // Black background.
        TextCell bg = new TextCell(' ', AnsiColor.BLACK, AnsiColor.BLACK);
        graphics.fillRectangle(0, 0, size.columns(), size.rows(), bg);

        // Lightning flash check (only in renderFrame mode, not test mode).
        boolean flashing = false;
        if (!testMode) {
            if (flashFrames > 0) {
                flashFrames--;
                flashing = true;
            } else if (flashCooldown <= 0 && random.nextDouble() < 0.02) {
                flashFrames = 2 + random.nextInt(2); // 2-3 frames
                flashing = true;
            } else {
                flashCooldown--;
            }
        }

        if (flashing) {
            // Fill screen with bright white/cyan background.
            TextCell flash = new TextCell(' ', AnsiColor.BRIGHT_WHITE, AnsiColor.BRIGHT_CYAN);
            graphics.fillRectangle(0, 0, size.columns(), size.rows(), flash);
        }

        // Draw rain drops.
        for (Drop drop : drops) {
            if (drop == null) continue;
            drop.advance();
            int x = drop.screenX();
            int y = drop.screenY();
            if (x < 0 || x >= size.columns() || y < 0 || y >= size.rows()) continue;

            char glyph = drop.glyph();
            AnsiColor fg = flashing ? AnsiColor.BRIGHT_WHITE : AnsiColor.CYAN;
            TextCell cell = new TextCell(glyph, fg, AnsiColor.BLACK);
            graphics.setCell(x, y, cell);
        }
    }

    private void ensureDrops(TerminalSize size) {
        int targetCount = dropCount(size);
        if (drops != null && drops.length == targetCount
                && lastSize != null
                && lastSize.columns() == size.columns()
                && lastSize.rows() == size.rows()) {
            return;
        }
        drops = new Drop[targetCount];
        for (int i = 0; i < targetCount; i++) {
            drops[i] = new Drop(size, random);
        }
    }

    private int dropCount(TerminalSize size) {
        int area = size.columns() * size.rows();
        if (area <= 0) return 0;
        int count = (int) (area * DROPS_PER_AREA);
        return Math.max(MIN_DROPS, Math.min(MAX_DROPS, count));
    }

    @Override
    /**
     * Reallocates internal buffers for the new terminal size.
     * @param newSize the new size
     */
    public void onResize(TerminalSize newSize) {
        this.lastSize = newSize;
        if (newSize.columns() <= 0 || newSize.rows() <= 0) return;
        int count = dropCount(newSize);
        drops = new Drop[count];
        for (int i = 0; i < count; i++) {
            drops[i] = new Drop(newSize, random);
        }
    }

    @Override
    /**
     * Starts the animation.
     */
    public void start() {
        running = true;
    }

    @Override
    /**
     * Stops the animation.
     */
    public void stop() {
        running = false;
    }

    @Override
    /**
     * Returns whether the running flag is set.
     * @return the result
     */
    public boolean isRunning() {
        return running;
    }

    @Override
    /**
     * Returns the target frame rate in frames per second.
     * @return the result
     */
    public int targetFps() {
        return TARGET_FPS;
    }

    @Override
    /**
     * Returns the last terminal size the animation was rendered at.
     * @return the result
     */
    public TerminalSize lastSize() {
        return lastSize;
    }

    /**
     * Returns the current animation time.
     *
     * @return the animation time
     */
    public double getTime() {
        return time;
    }

    /**
     * Returns the currently active rain drops.
     *
     * @return the drops array
     */
    public Drop[] getDrops() {
        return drops;
    }

    /**
     * Returns the number of active rain drops.
     *
     * @return the drop count
     */
    public int getDropCount() {
        return drops == null ? 0 : drops.length;
    }

    /** Rain drop state. */
    public static final class Drop {
        private final int cols;
        private final int rows;
        private final Random random;
        private int x;
        private int y;
        private int speed;     // 1-3
        private int driftCounter;
        private char glyph;

        Drop(TerminalSize size, Random random) {
            this.cols = size.columns();
            this.rows = size.rows();
            this.random = random;
            this.x = random.nextInt(Math.max(1, cols));
            this.y = random.nextInt(Math.max(1, rows));
            this.speed = 1 + random.nextInt(3);
            this.driftCounter = random.nextInt(3);
            char[] glyphs = {'|', '/', '.'};
            this.glyph = glyphs[random.nextInt(glyphs.length)];
        }

        void advance() {
            y += speed;
            driftCounter++;
            if (driftCounter >= 3) {
                x--;
                driftCounter = 0;
            }
            // Respawn at random x at top when reaching bottom.
            if (y >= rows) {
                y = 0;
                x = random.nextInt(Math.max(1, cols));
            }
            // Respawn at random x at top when drifting off left.
            if (x < 0) {
                x = random.nextInt(Math.max(1, cols));
                y = 0;
            }
        }

        int screenX() {
            return x;
        }

        int screenY() {
            return y;
        }

        char glyph() {
            return glyph;
        }

        int speed() {
            return speed;
        }
    }
}
