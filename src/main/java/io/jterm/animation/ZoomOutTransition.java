package io.jterm.animation;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;

/**
 * Zoom-out screen transition: the mirror image of
 * {@link ZoomInTransition}. The old screen shrinks toward a single point
 * at the center of the screen while the new screen is revealed behind it.
 * <p>
 * The old content is treated as an image that is scaled down around the
 * screen center as the transition progresses. At progress p the old-screen
 * image has scale {@code 1 - p} (1 → full size, 0 → a point); destination
 * cells inside the scaled image sample the old content with
 * nearest-neighbor mapping {@code src = center + (dest - center) / (1 - p)},
 * so the old screen visibly recedes into the distance — a true zoom-out.
 * Destination cells outside the shrunken image show the new screen content.
 * <p>
 * At progress 0.0 the old screen is fully visible; at progress 1.0 the
 * new screen fills the terminal.
 */
public class ZoomOutTransition implements TransitionEffect {
    private final long durationMs;
    private final ScreenBuffer oldScreen;

    /**
     * Create a zoom-out transition.
     *
     * @param durationMs total duration in milliseconds
     * @param oldScreen  buffer containing the old screen content
     */
    public ZoomOutTransition(long durationMs, ScreenBuffer oldScreen) {
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

        // The old image's scale: 1 at p=0 down to ~0 at p→1. Guard the
        // tail: below 0.05 the old image is a handful of cells around the
        // center; treat anything beyond as fully zoomed away.
        double scale = 1.0 - p;
        boolean oldGone = scale < 0.05;

        for (int r = 0; r < size.rows(); r++) {
            for (int c = 0; c < size.columns(); c++) {
                if (oldGone) {
                    graphics.setCell(c, r, newBuffer.getCell(c, r));
                    continue;
                }
                // Inverse-map the destination cell into the shrunken old image
                int srcCol = (int) Math.round(centerCol + (c - centerCol) / scale);
                int srcRow = (int) Math.round(centerRow + (r - centerRow) / scale);
                if (srcCol >= 0 && srcCol < size.columns()
                        && srcRow >= 0 && srcRow < size.rows()) {
                    // Inside the shrunken image: receding old content
                    graphics.setCell(c, r, oldScreen.getCell(srcCol, srcRow));
                } else {
                    // Outside: new content revealed behind the old screen
                    graphics.setCell(c, r, newBuffer.getCell(c, r));
                }
            }
        }
    }
}