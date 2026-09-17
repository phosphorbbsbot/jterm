package io.jterm.animation;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;

/**
 * Zoom-in screen transition: the new screen grows from a single point at
 * the center of the screen until it fills the screen.
 * <p>
 * The new content is treated as an image that is scaled up around the
 * screen center as the transition progresses. At progress p the visible
 * new-screen image has scale p (0 → a point, 1 → full size); destination
 * cells inside the scaled image sample the new content with
 * nearest-neighbor mapping {@code src = center + (dest - center) / p}, so
 * the content visibly magnifies as it grows — a true zoom, not a box
 * reveal. Destination cells outside the scaled image still show the old
 * screen content.
 * <p>
 * At progress 0.0 the old screen is fully visible; at progress 1.0 the
 * new screen fills the terminal.
 */
public class ZoomInTransition implements TransitionEffect {
    private final long durationMs;
    private final ScreenBuffer oldScreen;

    /**
     * Create a zoom-in transition.
     *
     * @param durationMs total duration in milliseconds
     * @param oldScreen  buffer containing the old screen content
     */
    public ZoomInTransition(long durationMs, ScreenBuffer oldScreen) {
        this.durationMs = durationMs;
        this.oldScreen = oldScreen;
    }

    @Override
    /** Returns the transition duration in milliseconds.
     * @return the duration in ms */
    public long durationMs() {
        return durationMs;
    }

    @Override
    public int targetFps() {
        return 30;
    }

    @Override
    /** Renders a single frame of the transition.
     * @param graphics the graphics context (contains the NEW screen content)
     * @param size the terminal size
     * @param progress the transition progress (0.0 to 1.0) */
    public void renderFrame(TextGraphics graphics, TerminalSize size, double progress) {
        if (size.rows() <= 0 || size.columns() <= 0) return;

        double p = Math.max(0.0, Math.min(1.0, progress));
        if (p >= 1.0) return; // new content already fully visible
        if (p <= 0.0) {
            for (int r = 0; r < size.rows(); r++)
                for (int c = 0; c < size.columns(); c++)
                    graphics.setCell(c, r, oldScreen.getCell(c, r));
            return;
        }

        // Capture the new content before overwriting
        var newBuffer = new ScreenBuffer(size);
        for (int r = 0; r < size.rows(); r++)
            for (int c = 0; c < size.columns(); c++)
                newBuffer.setCell(c, r, graphics.getCell(c, r));

        int centerCol = size.columns() / 2;
        int centerRow = size.rows() / 2;

        for (int r = 0; r < size.rows(); r++) {
            for (int c = 0; c < size.columns(); c++) {
                // Inverse-map the destination cell into the scaled new screen.
                // Nearest-neighbor: round after the inverse-scale transform.
                int srcCol = (int) Math.round(centerCol + (c - centerCol) / p);
                int srcRow = (int) Math.round(centerRow + (r - centerRow) / p);
                if (srcCol >= 0 && srcCol < size.columns()
                        && srcRow >= 0 && srcRow < size.rows()) {
                    // Inside the scaled image: magnified new content
                    graphics.setCell(c, r, newBuffer.getCell(srcCol, srcRow));
                } else {
                    // Not yet covered by the zooming image: old content
                    graphics.setCell(c, r, oldScreen.getCell(c, r));
                }
            }
        }
    }
}