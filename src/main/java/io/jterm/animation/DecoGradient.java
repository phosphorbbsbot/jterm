package io.jterm.animation;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.style.RgbColor;
import io.jterm.style.TextCell;

import java.util.List;

/**
 * Menu ambient Art-Deco gradient wash: a very slow truecolor gradient sliding
 * across the frame, anchored on the {@link io.jterm.style.Theme#ART_DECO}
 * gold. A deterministic {@link AnimatedBackground} — motion derives from the
 * render counter, never from the clock.
 *
 * <p><b>Laws (tests: {@code DecoGradientTest}):</b></p>
 * <ul>
 *   <li><b>PALETTE</b> — five Art-Deco anchors ending in a bronze shadow;
 *       the GOLD anchor RgbColor(255,214,71) is the third-to-last and must
 *       stay in the palette.</li>
 *   <li><b>WASH</b> — column c at phase p paints lerp(P[i], P[i+1], frac),
 *       where u = ((c - p) mod cols) * (len-1) / cols, i = floor(u),
 *       frac = u - i, and each channel rounds half-up:
 *       {@code (int) Math.round(a + (b - a) * frac)}. Cells are ' '
 *       with fg riding the bg color.</li>
 *   <li><b>PHASE</b> — one column right-shift every 6 renders (very slow:
 *       4 fps → a full 80-column wash cycle in 2 minutes); phase wraps at
 *       cols and survives resizes.</li>
 * </ul>
 */
public class DecoGradient implements AnimatedBackground {

    private static final int TARGET_FPS = 4;
    private static final int PHASE_LAG = 6;

    /** Art-Deco anchors — the gold anchor is the wash's luminous peak. */
    static final List<RgbColor> PALETTE = List.of(
            new RgbColor(16, 12, 8),     // P0 lacquer
            new RgbColor(64, 46, 18),    // P1 bronze
            new RgbColor(150, 112, 40),  // P2 amber
            new RgbColor(255, 214, 71),  // P3 the ART_DECO gold anchor
            new RgbColor(84, 66, 28));   // P4 bronze shadow

    private volatile TerminalSize lastSize;
    private volatile boolean running;
    private int step;
    private int phase;

    /** Constructs the ambient wash. */
    public DecoGradient() {
    }

    /** Constructs the ambient wash pre-sized for the given terminal. */
    public DecoGradient(TerminalSize preferredSize) {
        onResize(preferredSize);
    }

    @Override
    public void renderFrame(TextGraphics graphics, TerminalSize size) {
        lastSize = size;
        int cols = size.columns();
        int rows = size.rows();
        if (cols <= 0 || rows <= 0) {
            return;
        }
        for (int c = 0; c < cols; c++) {
            graphics.fillRectangle(c, 0, 1, rows, cellFor(c, cols));
        }
        // PHASE law: render t bumps the phase after painting when (t+1)
        // divides through the lag window.
        if ((step + 1) % PHASE_LAG == 0) {
            phase = (phase + 1) % cols;
        }
        step++;
    }

    /**
     * The interpolated wash color for column {@code c} at the current phase
     * on a {@code cols}-wide terminal. Visible for tests.
     */
    RgbColor colorFor(int c, int cols) {
        int span = Math.floorMod(c - phase, cols);
        double u = (double) span * (PALETTE.size() - 1) / cols;
        int i = (int) u;
        double frac = u - i;
        var a = PALETTE.get(i);
        var b = PALETTE.get(i + 1);
        return new RgbColor(
                roundLerp(a.r(), b.r(), frac),
                roundLerp(a.g(), b.g(), frac),
                roundLerp(a.b(), b.b(), frac));
    }

    private TextCell cellFor(int c, int cols) {
        var color = colorFor(c, cols);
        return new TextCell(' ', color, color);
    }

    private static int roundLerp(int a, int b, double frac) {
        return (int) Math.round(a + (b - a) * frac);
    }

    @Override
    public void onResize(TerminalSize newSize) {
        lastSize = newSize;
        // Phase deliberately persists: a resize must not reset the wash.
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

    /**
     * Current wash phase (columns of right-drift).
     *
     * @return the phase counter, 0..cols-1
     */
    int phase() {
        return phase;
    }
}
