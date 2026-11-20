package io.jterm.animation;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.style.RgbColor;
import io.jterm.style.TextCell;

import java.util.List;

/**
 * Menu ambient cloud scene: soft overlapping drifting cloud bodies in muted
 * blue/purple tones, rimmed with the {@link io.jterm.style.Theme#ART_DECO}
 * gold as a moon-glit sliver. A deterministic {@link AnimatedBackground} —
 * the layout derives from the terminal size and the motion from the render
 * counter, never from the clock.
 *
 * <p><b>Laws (tests: {@code CloudsTest}):</b></p>
 * <ul>
 *   <li><b>PALETTE</b> — muted blue/purple body tones; the moon rim is the
 *       ART_DECO gold anchor RgbColor(255,214,71) and must stay in the
 *       palette; the sky fill is RgbColor(24,30,52).</li>
 *   <li><b>LAYOUT</b> — 3 deterministic clouds back-to-front: width
 *       max(5, cols/3 + 2), height max(2, rows/6), band
 *       y0 = max(0, min(rows - h, rows/2 - h + 1)), base(i) = i * cols/3;
 *       drift position x0(i) = (base(i) + drifts[i]) mod (cols + width)
 *       - width — clouds exit right fully before re-entering left.</li>
 *   <li><b>SHAPE</b> — the bottom row spans the full width; the top row is
 *       tapered one cell per side; every even (x - x0) top cell paints the
 *       rim '·' in gold; bodies are cached ' ' cells on their layer color.</li>
 *   <li><b>DRIFT</b> — render t advances cloud i one cell when t &gt; 0 and
 *       t % LAG[i] == 0, LAG = {5, 3, 2} — back clouds drift slowest
 *       (the parallax law); drifts survive resizes.</li>
 *   <li><b>FRONT WINS</b> — layers paint back-to-front; a shared cell shows
 *       the frontmost layer.</li>
 * </ul>
 */
public class Clouds implements AnimatedBackground {

    private static final int TARGET_FPS = 6;
    static final RgbColor SKY = new RgbColor(24, 30, 52);
    static final RgbColor GOLD = new RgbColor(255, 214, 71);

    /** Muted body tones back-to-front, then the moon-gold rim (the anchor). */
    static final List<RgbColor> PALETTE = List.of(
            new RgbColor(86, 66, 116),   // P0 muted purple
            new RgbColor(58, 70, 112),   // P1 muted blue
            new RgbColor(72, 88, 138),   // P2 steel blue
            GOLD);                       // the ART_DECO gold rim anchor

    private static final int[] LAGS = {5, 3, 2};
    private static final char RIM = '·';

    static final TextCell SKY_CELL = new TextCell(' ', SKY, SKY);

    private volatile TerminalSize lastSize;
    private volatile boolean running;
    private volatile int step;
    private volatile int cols;
    private volatile int cloudWidth;
    private volatile int cloudHeight;
    private volatile int bandY;
    private final int[] drifts = new int[LAGS.length];

    /** Constructs the ambient cloud deck. */
    public Clouds() {
    }

    /** Constructs the ambient cloud deck pre-sized for the given terminal. */
    public Clouds(TerminalSize preferredSize) {
        onResize(preferredSize);
    }

    @Override
    public void renderFrame(TextGraphics graphics, TerminalSize size) {
        lastSize = size;
        int cols = size.columns();
        if (cols <= 0 || size.rows() <= 0) {
            return;
        }
        // DRIFT law: back clouds move slowest (parallax).
        for (int i = 0; i < LAGS.length; i++) {
            if (step > 0 && step % LAGS[i] == 0) {
                drifts[i]++;
            }
        }
        graphics.fillRectangle(0, 0, cols, size.rows(), SKY_CELL);
        for (int i = 0; i < LAGS.length; i++) {
            paintCloud(graphics, size, i);
        }
        this.cols = cols;
        step++;
    }

    private void paintCloud(TextGraphics graphics, TerminalSize size, int layer) {
        int x0 = positionFor(layer);
        int width = cloudWidth;
        var body = new TextCell(' ', PALETTE.get(layer), PALETTE.get(layer));
        var rim = new TextCell(RIM, GOLD, PALETTE.get(layer));
        // SHAPE law: tapered top (one cell in per side), full-width bottom.
        int top = Math.max(0, x0 + 1);
        int topEnd = x0 + width - 2;
        for (int x = top; x <= topEnd; x++) {
            int rel = Math.floorMod(x - x0, width);
            if (rel % 2 == 0) {
                graphics.setCell(x, bandY, rim);
            } else {
                graphics.setCell(x, bandY, body);
            }
        }
        int bottom = Math.max(0, x0);
        int bottomEnd = x0 + width - 1;
        for (int x = bottom; x <= bottomEnd; x++) {
            graphics.setCell(x, bandY + 1, body);
        }
    }

    /**
     * The left edge of cloud {@code layer} for the current geometry and
     * drift count. May be negative (entering from the left margin); OOB
     * writes clamp away. The +width term keeps a zero-drift cloud exactly on
     * its base column; the (cols + width) span makes a cloud exit right
     * fully before it re-enters left.
     *
     * @param layer the cloud layer, 0..2
     * @return the current left edge column
     */
    int positionFor(int layer) {
        int span = cols + cloudWidth;
        int base = layer * (cols / 3);
        return Math.floorMod(base + drifts[layer] + cloudWidth, span) - cloudWidth;
    }

    @Override
    public void onResize(TerminalSize newSize) {
        lastSize = newSize;
        cols = newSize.columns();
        cloudWidth = Math.max(5, cols / 3 + 2);
        cloudHeight = Math.max(2, newSize.rows() / 6);
        bandY = Math.max(0, Math.min(newSize.rows() - cloudHeight,
                newSize.rows() / 2 - cloudHeight + 1));
        // DRIFT law: drifts survive the resize; only the geometry re-derives.
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

    int cloudWidth() {
        return cloudWidth;
    }

    int cloudHeight() {
        return cloudHeight;
    }

    int cloudX(int layer) {
        return positionFor(layer);
    }

    int cloudY(int layer) {
        return bandY;
    }

    int cloudDrifts(int layer) {
        return drifts[layer];
    }
}
