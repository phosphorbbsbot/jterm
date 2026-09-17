package io.jterm.animation;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import io.jterm.style.TextCell;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ZoomInTransition} and {@link ZoomOutTransition}: the
 * zoom family of screen transitions. Zoom-in grows the NEW screen from a
 * single point at the center of the screen until it fills the screen
 * (magnifying the new content as it grows); zoom-out is the mirror image —
 * the OLD screen shrinks toward the center point while the new screen is
 * revealed behind it.
 */
class ZoomTransitionTest {

    private static final TerminalSize SIZE = new TerminalSize(40, 12);
    private static final char OLD_CHAR = 'O';
    private static final char NEW_CHAR = 'N';

    /** Creates an old screen buffer filled with 'O' cells. */
    private ScreenBuffer oldScreen() {
        var buf = new ScreenBuffer(SIZE);
        for (int r = 0; r < SIZE.rows(); r++)
            for (int c = 0; c < SIZE.columns(); c++)
                buf.setCell(c, r, new TextCell(OLD_CHAR,
                        AnsiColor.WHITE, AnsiColor.BLACK));
        return buf;
    }

    /** Creates a graphics buffer (simulating new content) filled with 'N' cells. */
    private TextGraphics newContentGraphics() {
        var buf = new ScreenBuffer(SIZE);
        for (int r = 0; r < SIZE.rows(); r++)
            for (int c = 0; c < SIZE.columns(); c++)
                buf.setCell(c, r, new TextCell(NEW_CHAR,
                        AnsiColor.WHITE, AnsiColor.BLACK));
        return new TextGraphics(buf);
    }

    // ------------------------------------------------------------------
    // ZoomInTransition — new screen grows from the center point
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Zoom-in: durationMs returns the configured value")
    void zoomInDurationMs() {
        var t = new ZoomInTransition(700, oldScreen());
        assertEquals(700, t.durationMs());
    }

    @Test
    @DisplayName("Zoom-in: targetFps returns 30")
    void zoomInTargetFps() {
        var t = new ZoomInTransition(700, oldScreen());
        assertEquals(30, t.targetFps());
    }

    @Test
    @DisplayName("Zoom-in at progress 0.0 shows the entire old screen")
    void zoomInProgressZeroShowsOldScreen() {
        var t = new ZoomInTransition(700, oldScreen());
        var g = newContentGraphics();
        t.renderFrame(g, SIZE, 0.0);

        for (int r = 0; r < SIZE.rows(); r++)
            for (int c = 0; c < SIZE.columns(); c++)
                assertEquals(String.valueOf(OLD_CHAR), g.getCell(c, r).character(),
                        "Cell at (" + c + "," + r + ") should show old content at p=0");
    }

    @Test
    @DisplayName("Zoom-in at progress 1.0 shows the entire new screen")
    void zoomInProgressOneShowsNewScreen() {
        var t = new ZoomInTransition(700, oldScreen());
        var g = newContentGraphics();
        t.renderFrame(g, SIZE, 1.0);

        for (int r = 0; r < SIZE.rows(); r++)
            for (int c = 0; c < SIZE.columns(); c++)
                assertEquals(String.valueOf(NEW_CHAR), g.getCell(c, r).character(),
                        "Cell at (" + c + "," + r + ") should show new content at p=1");
    }

    @Test
    @DisplayName("Zoom-in mid-progress: center shows new content")
    void zoomInHalfCenterShowsNew() {
        var t = new ZoomInTransition(700, oldScreen());
        var g = newContentGraphics();
        t.renderFrame(g, SIZE, 0.5);

        int centerCol = SIZE.columns() / 2;
        int centerRow = SIZE.rows() / 2;
        assertEquals(String.valueOf(NEW_CHAR), g.getCell(centerCol, centerRow).character(),
                "Center should show new (zoomed) content at p=0.5");
    }

    @Test
    @DisplayName("Zoom-in mid-progress: corners show old content")
    void zoomInHalfCornersShowOld() {
        var t = new ZoomInTransition(700, oldScreen());
        var g = newContentGraphics();
        t.renderFrame(g, SIZE, 0.5);

        assertEquals(String.valueOf(OLD_CHAR), g.getCell(0, 0).character(),
                "Top-left corner should still show old content at p=0.5");
        assertEquals(String.valueOf(OLD_CHAR), g.getCell(SIZE.columns() - 1, SIZE.rows() - 1).character(),
                "Bottom-right corner should still show old content at p=0.5");
    }

    @Test
    @DisplayName("Zoom-in: the zoomed region grows monotonically")
    void zoomInRegionGrows() {
        var t = new ZoomInTransition(700, oldScreen());

        // Count new-content cells at p=0.25 and p=0.75 — later progress
        // must show at least as many new cells.
        int early = countCells(t, 3, NEW_CHAR);
        int late = countCells(t, 7, NEW_CHAR);
        assertTrue(late > early,
                "Zoomed region at p=0.7 (" + late + " cells) should exceed p=0.3 (" + early + " cells)");
    }

    /** Counts cells showing {@code ch} after rendering at progress p10/10. */
    private int countCells(TransitionEffect t, int p10, char ch) {
        var g = newContentGraphics();
        t.renderFrame(g, SIZE, p10 / 10.0);
        int n = 0;
        for (int r = 0; r < SIZE.rows(); r++)
            for (int c = 0; c < SIZE.columns(); c++)
                if (String.valueOf(ch).equals(g.getCell(c, r).character())) n++;
        return n;
    }

    @Test
    @DisplayName("Zoom-in: progress beyond 1.0 clamps to new content")
    void zoomInBeyondOneClamped() {
        var t = new ZoomInTransition(700, oldScreen());
        var g = newContentGraphics();
        t.renderFrame(g, SIZE, 1.5);

        for (int r = 0; r < SIZE.rows(); r++)
            for (int c = 0; c < SIZE.columns(); c++)
                assertEquals(String.valueOf(NEW_CHAR), g.getCell(c, r).character(),
                        "Cell at (" + c + "," + r + ") should show new content at p>1");
    }

    @Test
    @DisplayName("Zoom-in: progress below 0.0 clamps to old content")
    void zoomInBelowZeroClamped() {
        var t = new ZoomInTransition(700, oldScreen());
        var g = newContentGraphics();
        t.renderFrame(g, SIZE, -0.5);

        for (int r = 0; r < SIZE.rows(); r++)
            for (int c = 0; c < SIZE.columns(); c++)
                assertEquals(String.valueOf(OLD_CHAR), g.getCell(c, r).character(),
                        "Cell at (" + c + "," + r + ") should show old content at p<0");
    }

    @Test
    @DisplayName("Zoom-in: zoomed content is sampled (scaled), not a plain reveal")
    void zoomInSamplesNewContent() {
        // Distinctive new screen: vertical column stripes A|B|B|B repeating.
        // Under magnification (scale s), destination cell c samples source
        // column round(center + (c - center)/s). Pick a destination where
        // the sampled value differs from the raw pattern at that same
        // coordinate — proves real sampling, not a box reveal.
        var t = new ZoomInTransition(700, oldScreen());
        var g = stripeGraphics();
        t.renderFrame(g, SIZE, 0.5);

        int centerCol = SIZE.columns() / 2;
        int centerRow = SIZE.rows() / 2;
        double scale = 0.5;
        int destCol = centerCol + 6;
        int srcCol = (int) Math.round(centerCol + (destCol - centerCol) / scale);
        assertTrue(srcCol < SIZE.columns(), "test setup: sample within bounds");
        char sampled = stripeChar(srcCol);
        char raw = stripeChar(destCol);
        char actual = g.getCell(destCol, centerRow).character().charAt(0);
        assertEquals(String.valueOf(sampled), String.valueOf(actual),
                "Zoomed cell at col " + destCol + " should sample source col " + srcCol);
        // And the sampled value must genuinely differ from raw here,
        // otherwise this test proves nothing.
        assertNotEquals(raw, sampled, "test sanity: sampling must change the pattern at this coord");
    }

    /** Column-stripe cell char for column c: A at every 4th column, B otherwise. */
    private char stripeChar(int c) {
        return (c % 4 == 0) ? 'A' : 'B';
    }

    /** Builds graphics with the stripe pattern as the (new) content. */
    private TextGraphics stripeGraphics() {
        var buf = new ScreenBuffer(SIZE);
        for (int r = 0; r < SIZE.rows(); r++)
            for (int c = 0; c < SIZE.columns(); c++)
                buf.setCell(c, r, new TextCell(stripeChar(c),
                        AnsiColor.WHITE, AnsiColor.BLACK));
        return new TextGraphics(buf);
    }

    @Test
    @DisplayName("Zoom-in: handles 1x1 terminal without crashing")
    void zoomInHandlesOneByOne() {
        var oneByOne = new TerminalSize(1, 1);
        var old = new ScreenBuffer(oneByOne);
        old.setCell(0, 0, new TextCell(OLD_CHAR, AnsiColor.WHITE, AnsiColor.BLACK));
        var t = new ZoomInTransition(700, old);

        var buf = new ScreenBuffer(oneByOne);
        buf.setCell(0, 0, new TextCell(NEW_CHAR, AnsiColor.WHITE, AnsiColor.BLACK));
        var g = new TextGraphics(buf);

        assertDoesNotThrow(() -> t.renderFrame(g, oneByOne, 0.5));
    }

    // ------------------------------------------------------------------
    // ZoomOutTransition — the mirror image
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Zoom-out: durationMs returns the configured value")
    void zoomOutDurationMs() {
        var t = new ZoomOutTransition(700, oldScreen());
        assertEquals(700, t.durationMs());
    }

    @Test
    @DisplayName("Zoom-out: targetFps returns 30")
    void zoomOutTargetFps() {
        var t = new ZoomOutTransition(700, oldScreen());
        assertEquals(30, t.targetFps());
    }

    @Test
    @DisplayName("Zoom-out at progress 0.0 shows the entire old screen")
    void zoomOutProgressZeroShowsOldScreen() {
        var t = new ZoomOutTransition(700, oldScreen());
        var g = newContentGraphics();
        t.renderFrame(g, SIZE, 0.0);

        for (int r = 0; r < SIZE.rows(); r++)
            for (int c = 0; c < SIZE.columns(); c++)
                assertEquals(String.valueOf(OLD_CHAR), g.getCell(c, r).character(),
                        "Cell at (" + c + "," + r + ") should show old content at p=0");
    }

    @Test
    @DisplayName("Zoom-out at progress 1.0 shows the entire new screen")
    void zoomOutProgressOneShowsNewScreen() {
        var t = new ZoomOutTransition(700, oldScreen());
        var g = newContentGraphics();
        t.renderFrame(g, SIZE, 1.0);

        for (int r = 0; r < SIZE.rows(); r++)
            for (int c = 0; c < SIZE.columns(); c++)
                assertEquals(String.valueOf(NEW_CHAR), g.getCell(c, r).character(),
                        "Cell at (" + c + "," + r + ") should show new content at p=1");
    }

    @Test
    @DisplayName("Zoom-out mid-progress: center shows old content (shrinking)")
    void zoomOutHalfCenterShowsOld() {
        var t = new ZoomOutTransition(700, oldScreen());
        var g = newContentGraphics();
        t.renderFrame(g, SIZE, 0.5);

        int centerCol = SIZE.columns() / 2;
        int centerRow = SIZE.rows() / 2;
        assertEquals(String.valueOf(OLD_CHAR), g.getCell(centerCol, centerRow).character(),
                "Center should show shrinking old content at p=0.5");
    }

    @Test
    @DisplayName("Zoom-out mid-progress: corners show new content (revealed behind)")
    void zoomOutHalfCornersShowNew() {
        var t = new ZoomOutTransition(700, oldScreen());
        var g = newContentGraphics();
        t.renderFrame(g, SIZE, 0.5);

        // The old screen has shrunk to half size around the center; the
        // corners are far outside it → new content.
        assertEquals(String.valueOf(NEW_CHAR), g.getCell(0, 0).character(),
                "Top-left corner should show new content at p=0.5");
        assertEquals(String.valueOf(NEW_CHAR), g.getCell(SIZE.columns() - 1, SIZE.rows() - 1).character(),
                "Bottom-right corner should show new content at p=0.5");
    }

    @Test
    @DisplayName("Zoom-out: the old region shrinks as progress advances")
    void zoomOutRegionShrinks() {
        var t = new ZoomOutTransition(700, oldScreen());

        // Count old-content cells at p=0.3 and p=0.7 — later must be fewer.
        int early = countCells(t, 3, OLD_CHAR);
        int late = countCells(t, 7, OLD_CHAR);
        assertTrue(late < early,
                "Old region at p=0.7 (" + late + " cells) should be smaller than at p=0.3 (" + early + " cells)");
    }

    @Test
    @DisplayName("Zoom-out: progress beyond 1.0 clamps to new content")
    void zoomOutBeyondOneClamped() {
        var t = new ZoomOutTransition(700, oldScreen());
        var g = newContentGraphics();
        t.renderFrame(g, SIZE, 1.5);

        for (int r = 0; r < SIZE.rows(); r++)
            for (int c = 0; c < SIZE.columns(); c++)
                assertEquals(String.valueOf(NEW_CHAR), g.getCell(c, r).character(),
                        "Cell at (" + c + "," + r + ") should show new content at p>1");
    }

    @Test
    @DisplayName("Zoom-out: progress below 0.0 clamps to old content")
    void zoomOutBelowZeroClamped() {
        var t = new ZoomOutTransition(700, oldScreen());
        var g = newContentGraphics();
        t.renderFrame(g, SIZE, -0.5);

        for (int r = 0; r < SIZE.rows(); r++)
            for (int c = 0; c < SIZE.columns(); c++)
                assertEquals(String.valueOf(OLD_CHAR), g.getCell(c, r).character(),
                        "Cell at (" + c + "," + r + ") should show old content at p<0");
    }

    @Test
    @DisplayName("Zoom-out: handles 1x1 terminal without crashing")
    void zoomOutHandlesOneByOne() {
        var oneByOne = new TerminalSize(1, 1);
        var old = new ScreenBuffer(oneByOne);
        old.setCell(0, 0, new TextCell(OLD_CHAR, AnsiColor.WHITE, AnsiColor.BLACK));
        var t = new ZoomOutTransition(700, old);

        var buf = new ScreenBuffer(oneByOne);
        buf.setCell(0, 0, new TextCell(NEW_CHAR, AnsiColor.WHITE, AnsiColor.BLACK));
        var g = new TextGraphics(buf);

        assertDoesNotThrow(() -> t.renderFrame(g, oneByOne, 0.5));
    }
}