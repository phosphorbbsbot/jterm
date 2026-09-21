package io.jterm.widget;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.SGR;
import io.jterm.style.ThemeManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MarkdownWidget}: draws parsed markdown to a
 * {@link ScreenBuffer} with theme-driven styling, scroll support, and
 * rule/bullet decoration.
 */
class MarkdownWidgetTest {

    private ScreenBuffer draw(String markdown, int width, int height) {
        var widget = new MarkdownWidget(markdown, width);
        widget.setBounds(new TerminalPosition(0, 0), new TerminalSize(width, height));
        var buf = new ScreenBuffer(new TerminalSize(width, height));
        widget.draw(new TextGraphics(buf));
        return buf;
    }

    @Test
    void headingDrawsBoldOnAccent() {
        var buf = draw("# Title", 20, 3);
        assertEquals('T', buf.getCell(0, 0).character().charAt(0));
        assertEquals(SGR.BOLD, buf.getCell(0, 0).modifiers().iterator().next());
        assertEquals(ThemeManager.active().headerFg(), buf.getCell(0, 0).fg());
    }

    @Test
    void paragraphDrawsPlainText() {
        var buf = draw("Hello world", 20, 3);
        assertEquals('H', buf.getCell(0, 0).character().charAt(0));
        assertEquals('w', buf.getCell(6, 0).character().charAt(0));
    }

    @Test
    void boldSpanAppliesBoldModifier() {
        var buf = draw("plain **bold** x", 20, 3);
        // "plain " is 6 cols, "bold" spans cols 6..10 → cells 6-9 bold.
        assertEquals(SGR.BOLD, buf.getCell(6, 0).modifiers().iterator().next());
        // The plain text before it carries no bold.
        assertFalse(buf.getCell(0, 0).modifiers().contains(SGR.BOLD));
    }

    @Test
    void ruleDrawsBoxDrawingLine() {
        var buf = draw("text\n---\nmore", 20, 5);
        // Row 1 is the rule: a full-width ─ line.
        for (int c = 0; c < 20; c++) {
            assertEquals('─', buf.getCell(c, 1).character().charAt(0), "col " + c);
        }
    }

    @Test
    void listItemDrawsWithBulletAndHangingIndent() {
        var buf = draw("- item", 20, 3);
        // Bullet at col 0 (theme accent, drawn by the widget), text starts at col 4.
        assertEquals('•', buf.getCell(0, 0).character().charAt(0));
        assertEquals('i', buf.getCell(4, 0).character().charAt(0));
    }

    @Test
    void nestedListItemIndentsFurther() {
        var buf = draw("- top\n  - inner", 20, 4);
        // Nested row: bullet deeper, text at 4 + 2 = col 6+.
        int nestedRow = -1;
        for (int r = 0; r < 4; r++) {
            if (buf.getCell(4 + 2, r).character().charAt(0) == 'i') nestedRow = r;
        }
        assertTrue(nestedRow >= 0, "nested item text should render at the extra indent");
        assertEquals('•', buf.getCell(2, nestedRow).character().charAt(0));
    }

    @Test
    void codeBlockDrawsInsideBox() {
        var buf = draw("```\nint x = 1;\n```", 30, 5);
        // A single code row: the closing frame (└──…┘) renders on the row below;
        // text sits inside the frame on the code row itself.
        boolean foundBox = false;
        for (int r = 0; r < 5; r++) {
            String row0 = String.valueOf(buf.getCell(0, r).character().charAt(0));
            if (row0.equals("┌") || row0.equals("└")) foundBox = true;
        }
        assertTrue(foundBox, "code block should be framed with box-drawing corners");
        assertEquals('i', buf.getCell(1, 0).character().charAt(0));
    }

    @Test
    void quoteDrawsWithLeftBar() {
        var buf = draw("> quoted", 20, 3);
        // Col 0 is the left bar (│), text starts at col 2.
        assertEquals('│', buf.getCell(0, 0).character().charAt(0));
        assertEquals('q', buf.getCell(2, 0).character().charAt(0));
    }

    @Test
    void linkRendersLabelThenDimUrl() {
        var buf = draw("[site](http://x.io)", 40, 3);
        // "site (http://x.io)": label normal, URL segment dimmed.
        assertFalse(buf.getCell(0, 0).modifiers().contains(SGR.DIM)); // 's'
        assertTrue(buf.getCell(6, 0).modifiers().contains(SGR.DIM));  // 'h' of http
    }

    @Test
    void scrollOffsetShiftsFirstVisibleRow() {
        // Four paragraphs so the document exceeds a 3-row viewport and offset
        // 1 actually shifts the window.
        String md = "one\n\ntwo\n\nthree\n\nfour";
        var top = draw(md, 20, 3);
        var widget = new MarkdownWidget(md, 20);
        widget.setBounds(new TerminalPosition(0, 0), new TerminalSize(20, 3));
        widget.setScrollOffset(1);
        var scrolled = new ScreenBuffer(new TerminalSize(20, 3));
        widget.draw(new TextGraphics(scrolled));
        // With offset 1 the row that was at buffer row 1 ("two") is now at row 0.
        String before = rowText(top, 1);
        String after = rowText(scrolled, 0);
        assertEquals(before, after);
    }

    @Test
    void emptyDocumentDrawsBlankBuffer() {
        var buf = draw("", 10, 2);
        assertEquals(' ', buf.getCell(0, 0).character().charAt(0));
    }

    @Test
    void rawViewToggleDumpsSourceLines() {
        var widget = new MarkdownWidget("# head", 20);
        widget.setRawView(true);
        widget.setBounds(new TerminalPosition(0, 0), new TerminalSize(20, 3));
        var buf = new ScreenBuffer(new TerminalSize(20, 3));
        widget.draw(new TextGraphics(buf));
        // Raw view shows the literal source: '#',' ','h'...
        assertEquals('#', buf.getCell(0, 0).character().charAt(0));
        assertEquals('h', buf.getCell(2, 0).character().charAt(0));
    }

    @Test
    void setMarkdownReparses() {
        var widget = new MarkdownWidget("first", 20);
        assertEquals(1, widget.document().lines().size());
        widget.setMarkdown("now two lines\n\nsecond para");
        assertEquals(2, widget.document().lines().size());
    }

    @Test
    void handleKeyStrokeScrolls() {
        var widget = new MarkdownWidget("one\n\ntwo\n\nthree", 20);
        widget.setBounds(new TerminalPosition(0, 0), new TerminalSize(20, 2));
        assertTrue(widget.handleKeyStroke(new io.jterm.core.input.KeyStroke(io.jterm.core.input.KeyType.ARROW_DOWN)));
        assertTrue(widget.getScrollOffset() > 0);
        assertTrue(widget.handleKeyStroke(new io.jterm.core.input.KeyStroke(io.jterm.core.input.KeyType.ARROW_UP)));
        assertEquals(0, widget.getScrollOffset());
    }

    @Test
    void documentMatchesParse() {
        var widget = new MarkdownWidget("# T", 20);
        assertEquals(MarkdownParser.parse("# T", 20).lines(), widget.document().lines());
    }

    private String rowText(ScreenBuffer buf, int row) {
        var sb = new StringBuilder();
        for (int c = 0; c < buf.size().columns(); c++) {
            sb.append(buf.getCell(c, row).character().charAt(0));
        }
        return sb.toString().stripTrailing();
    }
}