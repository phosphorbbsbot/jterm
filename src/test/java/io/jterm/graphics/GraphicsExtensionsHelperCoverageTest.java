package io.jterm.graphics;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import io.jterm.style.TextCell;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the remaining graphics coverage gaps: the default {@link
 * TextGraphicsExtensions} constructor and the {@link TextGraphicsHelper}
 * default constructor plus {@code sub()} boundary branches (over-wide,
 * negative offsets, fully-out-of-range sub-regions).
 */
class GraphicsExtensionsHelperCoverageTest {

    private static final TextCell CELL = new TextCell('x', AnsiColor.WHITE, AnsiColor.BLACK);

    @Test
    void subGraphicsDelegatesToClippedGraphics() {
        var buffer = new ScreenBuffer(new TerminalSize(10, 6));
        var g = new TextGraphics(buffer);
        var sub = TextGraphicsExtensions.subGraphics(g, new TerminalPosition(2, 1), new TerminalSize(5, 3));
        assertNotNull(sub);
        assertEquals(new TerminalSize(5, 3), sub.getSize());
        // setCell writes through the clip to the parent buffer at the offset.
        sub.setCell(0, 0, CELL);
        assertEquals('x', buffer.getCell(2, 1).character().charAt(0));
    }

    @Test
    void textGraphicsExtensionsConstructorIsInstantiable() {
        var ext = new TextGraphicsExtensions();
        assertNotNull(ext);
        // Still fully functional through the instance.
        var buffer = new ScreenBuffer(new TerminalSize(4, 4));
        var sub = ext.subGraphics(new TextGraphics(buffer), TerminalPosition.TOP_LEFT, new TerminalSize(2, 2));
        assertEquals(new TerminalSize(2, 2), sub.getSize());
    }

    @Test
    void textGraphicsHelperConstructorIsInstantiable() {
        var helper = new TextGraphicsHelper();
        assertNotNull(helper);
    }

    @Test
    void helperCreateBuildsGraphicsBackedByBuffer() {
        var buffer = new ScreenBuffer(new TerminalSize(6, 3));
        var g = TextGraphicsHelper.create(buffer);
        g.putCell(3, 1, CELL);
        assertEquals('x', buffer.getCell(3, 1).character().charAt(0));
    }

    @Test
    void helperSubClipsOverwideSubRegionToParentBounds() {
        var g = new TextGraphics(new ScreenBuffer(new TerminalSize(10, 4)));
        var sub = TextGraphicsHelper.sub(g, 2, 1, 30, 10);
        // 10 - 2 = 8 columns, 4 - 1 = 3 rows — clipped to what fits the parent.
        assertEquals(new TerminalSize(8, 3), sub.getSize());
    }

    @Test
    void helperSubWithNegativeOffsetsDoesNotThrow() {
        var g = new TextGraphics(new ScreenBuffer(new TerminalSize(10, 4)));
        // Negative offsets are accepted: width/height are min(w, columns - x),
        // which grows with negative x rather than clamping to empty.
        var sub = TextGraphicsHelper.sub(g, -5, -5, 4, 4);
        assertEquals(new TerminalSize(4, 4), sub.getSize());
    }

    @Test
    void helperSubFullyOutOfRangeIsEmpty() {
        var g = new TextGraphics(new ScreenBuffer(new TerminalSize(10, 4)));
        var sub = TextGraphicsHelper.sub(g, 10, 4, 5, 5);
        assertEquals(new TerminalSize(0, 0), sub.getSize());
    }

    @Test
    void helperSubExactFitReturnsSameSizeAsParent() {
        var buffer = new ScreenBuffer(new TerminalSize(7, 5));
        var g = new TextGraphics(buffer);
        var sub = TextGraphicsHelper.sub(g, 0, 0, 7, 5);
        assertEquals(new TerminalSize(7, 5), sub.getSize());
        // The sub-graphics is independent: writing to it must not touch the parent buffer
        // (a new backing buffer, unlike ClippedTextGraphics which writes through to the parent).
        sub.putCell(0, 0, CELL);
        assertEquals(' ', buffer.getCell(0, 0).character().charAt(0));
    }
}