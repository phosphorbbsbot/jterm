package io.jterm.widget;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers remaining {@link MarkdownWidget} branches: null/empty markdown,
 * raw view drawing and clipping, preferred size in both modes, page/home/end
 * scrolling, code-block frame corners (top above a run starting below row 0,
 * closing frame width branches), and CJK double-width styled runs.
 */
class MarkdownRenderCoverageTest {

    private ScreenBuffer draw(String markdown, int width, int height) {
        var widget = new MarkdownWidget(markdown, width);
        widget.setBounds(new TerminalPosition(0, 0), new TerminalSize(width, height));
        var buf = new ScreenBuffer(new TerminalSize(width, height));
        widget.draw(new TextGraphics(buf));
        return buf;
    }

    // ---- constructor / setMarkdown(null) ------------------------------------

    @Test
    void nullMarkdownYieldsEmptyDocument() {
        var widget = new MarkdownWidget(null, 40);
        assertEquals(0, widget.document().lineCount());
        widget.setBounds(new TerminalPosition(0, 0), new TerminalSize(20, 3));
        var buf = new ScreenBuffer(new TerminalSize(20, 3));
        assertDoesNotThrow(() -> widget.draw(new TextGraphics(buf)));
    }

    // ---- raw view ------------------------------------------------------------

    @Test
    void rawViewToggleAndDrawShowsUntouchedSource() {
        String src = "**not** parsed _at all_\nsecond line";
        var widget = new MarkdownWidget(src, 40);
        assertFalse(widget.isRawView());
        widget.setRawView(true);
        assertTrue(widget.isRawView());
        widget.setBounds(new TerminalPosition(0, 0), new TerminalSize(40, 3));
        var buf = new ScreenBuffer(new TerminalSize(40, 3));
        widget.draw(new TextGraphics(buf));
        // Raw view leaves the markup characters in place.
        assertEquals('*', buf.getCell(0, 0).character().charAt(0));
        // Second raw line lands on row 1.
        assertEquals('s', buf.getCell(0, 1).character().charAt(0));
    }

    @Test
    void rawViewWithEmptyMarkdownDrawsNothing() {
        var widget = new MarkdownWidget("", 40);
        widget.setRawView(true);
        widget.setBounds(new TerminalPosition(0, 0), new TerminalSize(20, 3));
        var buf = new ScreenBuffer(new TerminalSize(20, 3));
        assertDoesNotThrow(() -> widget.draw(new TextGraphics(buf)));
        assertEquals(' ', buf.getCell(0, 0).character().charAt(0));
    }

    @Test
    void rawViewClipsExtraLinesAtViewportHeight() {
        String src = "one\ntwo\nthree\nfour\nfive";
        var widget = new MarkdownWidget(src, 40);
        widget.setRawView(true);
        widget.setBounds(new TerminalPosition(0, 0), new TerminalSize(40, 2));
        var buf = new ScreenBuffer(new TerminalSize(40, 2));
        widget.draw(new TextGraphics(buf));
        assertEquals('o', buf.getCell(0, 0).character().charAt(0));
        assertEquals('t', buf.getCell(0, 1).character().charAt(0));
        // Only two rows — "three" clipped.
        assertEquals(' ', buf.getCell(0, 2).character().charAt(0));
    }

    @Test
    void rawViewScrollOffsetClampedToRawRowBudget() {
        String src = "a\nb\nc\nd\ne";
        var widget = new MarkdownWidget(src, 40);
        widget.setRawView(true);
        widget.setBounds(new TerminalPosition(0, 0), new TerminalSize(40, 2));
        widget.setScrollOffset(99);
        assertEquals(3, widget.getScrollOffset(), "clamped to rawRowCount - viewport height");
        var buf = new ScreenBuffer(new TerminalSize(40, 2));
        assertDoesNotThrow(() -> widget.draw(new TextGraphics(buf)));
        widget.setScrollOffset(-5);
        assertEquals(0, widget.getScrollOffset());
    }

    // ---- preferred size --------------------------------------------------------

    @Test
    void preferredSizeReflectsWidestLineAndRowCount() {
        // Blank lines separate paragraphs — consecutive lines merge into one.
        var widget = new MarkdownWidget("short\n\nabsolutely massive line here", 80);
        var size = widget.getPreferredSize();
        assertTrue(size.rows() >= 2, "two blank-separated paragraphs → at least two rows: " + size);
        assertTrue(size.columns() > 10, "widest line drives width: " + size);
    }

    @Test
    void preferredSizeInRawViewCountsRawLines() {
        var widget = new MarkdownWidget("a\nb\nc", 40);
        widget.setRawView(true);
        var size = widget.getPreferredSize();
        assertEquals(3, size.rows());
    }

    // ---- scrolling keys ---------------------------------------------------------

    @Test
    void pageHomeEndKeysScrollDocument() {
        // Blank lines keep each "pN" a separate paragraph line.
        String src = "p1\n\np2\n\np3\n\np4\n\np5\n\np6";
        var widget = new MarkdownWidget(src, 40);
        widget.setBounds(new TerminalPosition(0, 0), new TerminalSize(40, 3));

        assertTrue(widget.handleKeyStroke(new io.jterm.core.input.KeyStroke(io.jterm.core.input.KeyType.PAGE_DOWN)));
        assertEquals(2, widget.getScrollOffset());
        assertTrue(widget.handleKeyStroke(new io.jterm.core.input.KeyStroke(io.jterm.core.input.KeyType.END)));
        assertEquals(3, widget.getScrollOffset());
        assertTrue(widget.handleKeyStroke(new io.jterm.core.input.KeyStroke(io.jterm.core.input.KeyType.PAGE_UP)));
        assertEquals(1, widget.getScrollOffset(), "page up moves back one page (rows-1)");
        assertTrue(widget.handleKeyStroke(new io.jterm.core.input.KeyStroke(io.jterm.core.input.KeyType.HOME)));
        assertEquals(0, widget.getScrollOffset());
        // Unknown key is not consumed.
        assertFalse(widget.handleKeyStroke(new io.jterm.core.input.KeyStroke(io.jterm.core.input.KeyType.INSERT)));
    }

    // ---- code frame corners -----------------------------------------------------

    @Test
    void codeRunBelowRowZeroGetsTopFrameThenClosingFrame() {
        // A two-line code run: the top frame lands on the row above (r-1 branch),
        // the closing frame on the row below the last code row (r+1 branch), and
        // nothing follows to overwrite it.
        var buf = draw("intro\n\n```\ncode1\nmore\n```", 24, 5);
        boolean sawTop = false;
        boolean sawBottom = false;
        for (int r = 0; r < 5; r++) {
            char c = buf.getCell(0, r).character().charAt(0);
            if (c == '┌') sawTop = true;
            if (c == '└') sawBottom = true;
        }
        assertTrue(sawTop, "frame top corner should appear for a code run not starting at row 0");
        assertTrue(sawBottom, "closing frame corner should appear after the code run");
        // Code text still rendered inside the frame.
        boolean sawCode1 = false;
        boolean sawMore = false;
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 22; c++) {
                if (buf.getCell(c, r).is('c') && buf.getCell(c + 1, r).is('o')) sawCode1 = true;
                if (buf.getCell(c, r).is('m') && buf.getCell(c + 1, r).is('o')) sawMore = true;
            }
        }
        assertTrue(sawCode1, "first code row text should be drawn");
        assertTrue(sawMore, "second code row text should be drawn");
    }

    @Test
    void codeRowNarrowerThanViewportGetsRightVerticalBar() {
        var buf = draw("```\nab\n```", 30, 5);
        // Code row 0: left bar at col 0; cols = min(30, 2+2) = 4 < 30 → right bar at col 3.
        assertEquals('│', buf.getCell(0, 0).character().charAt(0));
        assertEquals('│', buf.getCell(3, 0).character().charAt(0));
    }

    @Test
    void codeRowAsWideAsViewportHasNoRightVerticalBar() {
        var buf = draw("```\n0123456789\n```", 10, 5);
        // cols = min(10, 10+2) = 10 == width → no right bar; text truncated to width-2 = 8.
        assertNotEquals('│', buf.getCell(9, 0).character().charAt(0));
    }

    // ---- styled runs ------------------------------------------------------------

    @Test
    void cjkDoubleWidthCharactersDrawWithoutThrowing() {
        // Each CJK char occupies 2 columns; the bold run starts after "汉 字 ".
        var buf = draw("汉字 **粗**", 40, 3);
        assertEquals('汉', buf.getCell(0, 0).character().charAt(0));
        assertEquals('字', buf.getCell(2, 0).character().charAt(0));
        // After 2 double-width chars (4 columns) plus a space, the bold run starts at col 5.
        assertEquals('粗', buf.getCell(5, 0).character().charAt(0));
    }

    @Test
    void italicSpanAppliesItalicModifier() {
        var buf = draw("pre *mid* post", 40, 3);
        boolean sawItalic = false;
        for (int c = 0; c < 40; c++) {
            if (buf.getCell(c, 0).modifiers().contains(io.jterm.style.SGR.ITALIC)) sawItalic = true;
        }
        assertTrue(sawItalic, "italic run should carry the ITALIC modifier");
    }

    @Test
    void inlineCodeSpanRendersInsideBackticks() {
        var buf = draw("run `ls -la` now", 40, 3);
        // The code span text keeps its content, rendered between offset cols.
        boolean sawDash = false;
        for (int c = 0; c < 40; c++) {
            if (buf.getCell(c, 0).is('-')) sawDash = true;
        }
        assertTrue(sawDash, "inline code content should be visible");
    }
}