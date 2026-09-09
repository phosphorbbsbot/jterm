package io.jterm.graphics;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import io.jterm.style.SGR;
import io.jterm.style.TextCell;
import io.jterm.style.TextStyleResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-completion tests for {@link ClippedTextGraphics}: every public
 * draw method, boundary clipping behavior, and the styled-string segment
 * path. Asserts write-through to the parent buffer at the clip offset and
 * correct clipping at the region edges.
 */
class ClippedTextGraphicsCoverageTest {

    private record Clip(ScreenBuffer parent, ClippedTextGraphics g, int ox, int oy, int w, int h) {
        static Clip of(int pw, int ph, int ox, int oy, int w, int h) {
            var parent = new ScreenBuffer(new TerminalSize(pw, ph));
            var g = new ClippedTextGraphics(new TextGraphics(parent), new TerminalPosition(ox, oy), new TerminalSize(w, h));
            return new Clip(parent, g, ox, oy, w, h);
        }
    }

    // ===== drawRectangle =====

    @Test
    void drawRectangleWritesAllFourEdges() {
        var c = Clip.of(10, 10, 1, 1, 5, 4);
        c.g().drawRectangle(0, 0, 5, 4, new TextCell('*'));
        // Top and bottom edges span the full width
        for (int i = 0; i < 5; i++) {
            assertEquals('*', c.parent().getCell(1 + i, 1).character().charAt(0), "top edge col " + i);
            assertEquals('*', c.parent().getCell(1 + i, 4).character().charAt(0), "bottom edge col " + i);
        }
        // Side edges span interior rows
        for (int r = 2; r < 4; r++) {
            assertEquals('*', c.parent().getCell(1, r).character().charAt(0), "left edge row " + r);
            assertEquals('*', c.parent().getCell(5, r).character().charAt(0), "right edge row " + r);
        }
        // Interior untouched
        assertEquals(' ', c.parent().getCell(3, 2).character().charAt(0));
    }

    @Test
    void drawRectangleClipsToClipRegion() {
        var c = Clip.of(10, 10, 2, 2, 3, 3);
        // Rectangle wider/taller than the clip region — overflow must not
        // reach the parent outside the region.
        c.g().drawRectangle(0, 0, 8, 8, new TextCell('*'));
        assertEquals('*', c.parent().getCell(2, 2).character().charAt(0));
        assertEquals(' ', c.parent().getCell(5, 5).character().charAt(0), "beyond clip region");
        assertEquals(' ', c.parent().getCell(9, 9).character().charAt(0));
    }

    // ===== fillRectangle clipping edges =====

    @Test
    void fillRectangleClampsNegativeOrigin() {
        var c = Clip.of(6, 6, 0, 0, 4, 4);
        // Fill starting before the clip origin: negative coords clamped to 0.
        c.g().fillRectangle(-2, -2, 4, 4, new TextCell('#'));
        assertEquals('#', c.parent().getCell(0, 0).character().charAt(0));
        assertEquals('#', c.parent().getCell(1, 1).character().charAt(0));
    }

    @Test
    void fillRectangleStopsAtClipBounds() {
        var c = Clip.of(8, 8, 1, 1, 3, 3);
        // Fill extending past the clip region: truncated at region edge.
        c.g().fillRectangle(2, 2, 10, 10, new TextCell('.'));
        assertEquals('.', c.parent().getCell(3, 3).character().charAt(0));
        assertEquals(' ', c.parent().getCell(4, 4).character().charAt(0), "past clip width");
        assertEquals(' ', c.parent().getCell(4, 1).character().charAt(0), "row above region");
    }

    // ===== drawLineSmooth =====

    @Test
    void drawLineSmoothWritesThroughWithLineChars() {
        var c = Clip.of(10, 10, 1, 1, 6, 6);
        c.g().drawLineSmooth(0, 0, 3, 0, new TextCell('*'));
        // Horizontal smooth line: endpoints use the ASCII '-' line char (the
        // default LINE_CHARS table, not Unicode box drawing).
        assertEquals('-', c.parent().getCell(1, 1).character().charAt(0));
        assertEquals('-', c.parent().getCell(4, 1).character().charAt(0));
    }

    @Test
    void drawLineSmoothSinglePoint() {
        var c = Clip.of(6, 6, 1, 1, 3, 3);
        // Isolated point (no prev/next) renders as the '@' junction glyph.
        c.g().drawLineSmooth(1, 1, 1, 1, new TextCell('o'));
        assertEquals('@', c.parent().getCell(2, 2).character().charAt(0));
    }

    // ===== drawString clipping and double width =====

    @Test
    void drawStringClipsOverflow() {
        var c = Clip.of(10, 4, 2, 1, 3, 1);
        c.g().drawString(0, 0, "abcdef", new TextCell(' '));
        // Only first 3 chars land (cols 2,3,4 in parent); the rest clipped.
        assertEquals('a', c.parent().getCell(2, 1).character().charAt(0));
        assertEquals('b', c.parent().getCell(3, 1).character().charAt(0));
        assertEquals('c', c.parent().getCell(4, 1).character().charAt(0));
        assertEquals(' ', c.parent().getCell(5, 1).character().charAt(0));
    }

    @Test
    void drawStringSkipsNegativeStart() {
        var c = Clip.of(10, 4, 2, 1, 5, 1);
        // Start at x=-2: first two chars clipped by the negative-column guard.
        c.g().drawString(-2, 0, "abcd", new TextCell(' '));
        assertEquals('c', c.parent().getCell(2, 1).character().charAt(0));
        assertEquals('d', c.parent().getCell(3, 1).character().charAt(0));
    }

    @Test
    void drawStringAdvancesDoubleWidthByTwo() {
        var c = Clip.of(12, 4, 0, 0, 8, 1);
        // CJK char occupies two columns.
        c.g().drawString(0, 0, "漢a", new TextCell(' '));
        assertEquals("漢", c.parent().getCell(0, 0).character());
        assertEquals('a', c.parent().getCell(2, 0).character().charAt(0));
    }

    @Test
    void drawStringOnRowOutsideVerticalBoundsWritesNothing() {
        var c = Clip.of(10, 4, 0, 0, 4, 2);
        c.g().drawString(0, 5, "hi", new TextCell(' '));
        // y=2 is outside the 2-row region.
        assertEquals(' ', c.parent().getCell(0, 2).character().charAt(0));
    }

    // ===== drawStyledString (resolver + segments) =====

    @Test
    void drawStyledStringAppliesResolverOverrides() {
        var c = Clip.of(10, 4, 1, 1, 5, 1);
        TextStyleResolver resolver = (i, ch, defStyle) ->
            i % 2 == 0 ? new TextCell(ch, AnsiColor.RED, AnsiColor.DEFAULT) : null;
        c.g().drawStyledString(0, 0, "abcde", new TextCell(' '), resolver);
        // Even indexes red, odd indexes default fg.
        assertEquals(AnsiColor.RED, c.parent().getCell(1, 1).fg());
        assertEquals(AnsiColor.DEFAULT, c.parent().getCell(2, 1).fg());
        assertEquals(AnsiColor.RED, c.parent().getCell(3, 1).fg());
    }

    @Test
    void drawStyledStringClipsLikeDrawString() {
        var c = Clip.of(10, 4, 2, 1, 2, 1);
        c.g().drawStyledString(0, 0, "xyz", new TextCell(' '), (i, ch, d) -> null);
        assertEquals('x', c.parent().getCell(2, 1).character().charAt(0));
        assertEquals('y', c.parent().getCell(3, 1).character().charAt(0));
        assertEquals(' ', c.parent().getCell(4, 1).character().charAt(0), "z clipped");
    }

    @Test
    void drawStyledStringWithSegmentsAppliesMatchedSegmentStyle() {
        var c = Clip.of(10, 4, 1, 1, 6, 1);
        var seg = new io.jterm.style.StyledSegment(1, 3, AnsiColor.CYAN);
        c.g().drawStyledString(0, 0, "abcde", new TextCell(' '), List.of(seg));
        // Indexes 1-2 cyan, rest default.
        assertEquals(AnsiColor.DEFAULT, c.parent().getCell(1, 1).fg());
        assertEquals(AnsiColor.CYAN, c.parent().getCell(2, 1).fg());
        assertEquals(AnsiColor.CYAN, c.parent().getCell(3, 1).fg());
        assertEquals(AnsiColor.DEFAULT, c.parent().getCell(4, 1).fg());
    }

    @Test
    void drawStyledStringWithSegmentsUsesResolverNullDefault() {
        var c = Clip.of(10, 4, 0, 0, 4, 1);
        // No segment matches index 0 → resolver returns null → default style.
        var seg = new io.jterm.style.StyledSegment(2, 4, AnsiColor.GREEN);
        c.g().drawStyledString(0, 0, "abcd", new TextCell(' '), List.of(seg));
        assertEquals(AnsiColor.DEFAULT, c.parent().getCell(0, 0).fg());
        assertEquals(AnsiColor.GREEN, c.parent().getCell(2, 0).fg());
    }

    @Test
    void drawStyledStringResolverStyleCarriesCharacter() {
        var c = Clip.of(10, 4, 0, 0, 4, 1);
        // Resolver returns a cell with a DIFFERENT character — the drawn char
        // must win (withCharacter(c) replaces it).
        TextStyleResolver resolver = (i, ch, defStyle) -> new TextCell('Z', AnsiColor.RED, AnsiColor.DEFAULT);
        c.g().drawStyledString(0, 0, "ab", new TextCell(' '), resolver);
        assertEquals('a', c.parent().getCell(0, 0).character().charAt(0));
        assertEquals(AnsiColor.RED, c.parent().getCell(0, 0).fg());
    }

    // ===== SGR passthrough via drawStyledString modifier path =====

    @Test
    void drawStyledStringAppliesModifiers() {
        var c = Clip.of(10, 4, 0, 0, 4, 1);
        TextStyleResolver resolver = (i, ch, defStyle) ->
            new TextCell(ch, AnsiColor.DEFAULT, AnsiColor.DEFAULT, SGR.BOLD);
        c.g().drawStyledString(0, 0, "ab", new TextCell(' '), resolver);
        assertTrue(c.parent().getCell(0, 0).modifiers().contains(SGR.BOLD));
    }
}