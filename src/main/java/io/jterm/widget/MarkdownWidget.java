package io.jterm.widget;

import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.graphics.TextGraphics;
import io.jterm.style.AnsiColor;
import io.jterm.style.SGR;
import io.jterm.style.TextCell;
import io.jterm.style.Theme;
import io.jterm.style.ThemeManager;
import io.jterm.util.Symbols;
import io.jterm.util.TerminalTextUtils;

/**
 * Renders parsed markdown ({@link MarkdownParser}) inside the widget tree with
 * theme-driven styling: headings bold on the theme header colors, bullets in
 * the accent color, rules as full-width box-drawing lines, block quotes with a
 * left bar, and fenced code blocks framed by a box border.
 *
 * <p>The widget owns a {@link MarkdownDocument} produced by
 * {@link MarkdownParser#parse(String, int)}; the document is re-wrapped and
 * drawn on demand, so theme changes at draw time are honored. Arrow keys scroll
 * the document when it overflows the component height, mirroring
 * {@link ListBox} navigation.</p>
 *
 * <p>A raw-view mode ({@link #setRawView(boolean)}) displays the unstyled
 * source text instead of the rendered document — the plain-text fallback.</p>
 */
public class MarkdownWidget extends AbstractComponent {

    private String markdown;
    private MarkdownDocument document;
    private int wrapWidth;
    private int scrollOffset;
    private boolean rawView;

    /**
     * Creates a widget rendering the given markdown at the given wrap width.
     *
     * @param markdown  the markdown source (may be {@code null} or empty)
     * @param wrapWidth the wrap width in columns (values &lt; 2 are treated as 2)
     */
    public MarkdownWidget(String markdown, int wrapWidth) {
        this.wrapWidth = Math.max(2, wrapWidth);
        this.scrollOffset = 0;
        this.rawView = false;
        setMarkdown(markdown);
    }

    /**
     * Replaces the markdown source and reparses it.
     *
     * @param markdown the new markdown source (may be {@code null})
     */
    public void setMarkdown(String markdown) {
        this.markdown = markdown == null ? "" : markdown;
        this.document = MarkdownParser.parse(markdown, wrapWidth);
        this.scrollOffset = 0;
        invalidate();
    }

    /**
     * Returns the parsed document currently displayed.
     *
     * @return the parsed document (never {@code null})
     */
    public MarkdownDocument document() {
        return document;
    }

    /**
     * Returns the current scroll offset (first visible document row).
     *
     * @return the scroll offset, clamped to the document
     */
    public int getScrollOffset() {
        return scrollOffset;
    }

    /**
     * Sets the scroll offset (first visible document row), clamped to the
     * document length.
     *
     * @param offset the desired offset
     */
    public void setScrollOffset(int offset) {
        this.scrollOffset = Math.max(0, Math.min(offset, maxOffset()));
        invalidate();
    }

    /**
     * Enables or disables raw view: the unstyled markdown source instead of the
     * rendered document.
     *
     * @param raw {@code true} to display the raw source
     */
    public void setRawView(boolean raw) {
        this.rawView = raw;
        this.scrollOffset = 0;
        invalidate();
    }

    /**
     * Returns whether raw view is active.
     *
     * @return {@code true} if the raw source is displayed
     */
    public boolean isRawView() {
        return rawView;
    }

    private int maxOffset() {
        return rawView
                ? Math.max(0, rawRowCount() - Math.max(1, getSize().rows()))
                : Math.max(0, document.lines().size() - Math.max(1, getSize().rows()));
    }

    private int rawRowCount() {
        return markdown.isEmpty() ? 0 : markdown.split("\n", -1).length;
    }

    /** Computes preferred size: widest rendered row by the content height. */
    @Override
    protected TerminalSize calculatePreferredSize() {
        int rows = rawView ? rawRowCount() : document.lines().size();
        int cols = 1;
        for (MarkdownLine line : document.lines()) {
            cols = Math.max(cols, TerminalTextUtils.getTrueWidth(line.text()));
        }
        return new TerminalSize(cols, Math.max(1, rows));
    }

    /**
     * Handles scroll keys: arrows, page up/down, home/end.
     *
     * @param keyStroke the keystroke to handle
     * @return {@code true} if the keystroke was consumed
     */
    @Override
    public boolean handleKeyStroke(KeyStroke keyStroke) {
        int page = Math.max(1, getSize().rows() - 1);
        switch (keyStroke.type()) {
            case ARROW_UP -> { setScrollOffset(scrollOffset - 1); return true; }
            case ARROW_DOWN -> { setScrollOffset(scrollOffset + 1); return true; }
            case PAGE_UP -> { setScrollOffset(scrollOffset - page); return true; }
            case PAGE_DOWN -> { setScrollOffset(scrollOffset + page); return true; }
            case HOME -> { setScrollOffset(0); return true; }
            case END -> { setScrollOffset(Integer.MAX_VALUE); return true; }
            default -> { return false; }
        }
    }

    /** Draws the visible portion of the document (or raw source) with theme styles. */
    @Override
    protected void drawComponent(TextGraphics graphics) {
        var theme = ThemeManager.active();
        TerminalSize size = getSize();
        graphics.fillRectangle(0, 0, size.columns(), size.rows(),
                new TextCell(' ', theme.foreground(), theme.background()));
        if (rawView) {
            drawRaw(graphics, theme);
            return;
        }
        int rows = document.lines().size();
        for (int r = 0; r < size.rows(); r++) {
            int idx = scrollOffset + r;
            if (idx >= rows) break;
            // Frame the code block: top corners above the first row of a run,
            // bottom corners below the last row.
            if (isCode(idx) && !isCode(idx - 1) && r > 0) {
                drawCodeFrame(graphics, r - 1, theme, size.columns(), true);
            }
            if (isCode(idx) && !isCode(idx + 1)) {
                if (r + 1 < size.rows()) {
                    drawCodeFrame(graphics, r + 1, theme, size.columns(), false);
                }
            }
            drawLine(graphics, r, document.lines().get(idx), theme, size.columns());
        }
    }

    private boolean isCode(int docIndex) {
        if (docIndex < 0 || docIndex >= document.lines().size()) return false;
        return document.lines().get(docIndex).kind() == MarkdownLine.Kind.CODE;
    }

    private void drawCodeFrame(TextGraphics graphics, int row, Theme theme, int width, boolean top) {
        var tl = top ? Symbols.TL_CORNER : Symbols.BL_CORNER;
        var tr = top ? Symbols.TR_CORNER : Symbols.BR_CORNER;
        var corner = new TextCell(tl.charAt(0), theme.border(), theme.background());
        var hline = new TextCell(Symbols.H_LINE.charAt(0), theme.border(), theme.background());
        var cornerR = new TextCell(tr.charAt(0), theme.border(), theme.background());
        graphics.drawString(0, row, String.valueOf(tl), corner);
        if (width > 2) {
            graphics.fillRectangle(1, row, width - 2, 1, hline);
        }
        graphics.drawString(width - 1, row, String.valueOf(tr), cornerR);
    }

    private void drawRaw(TextGraphics graphics, Theme theme) {
        TerminalSize size = getSize();
        if (markdown.isEmpty()) return;
        String[] lines = markdown.split("\n", -1);
        for (int r = 0; r < size.rows(); r++) {
            int idx = scrollOffset + r;
            if (idx >= lines.length) break;
            graphics.drawString(0, r, TerminalTextUtils.truncate(lines[idx], size.columns()),
                    new TextCell(' ', theme.foreground(), theme.background()));
        }
    }

    private void drawLine(TextGraphics graphics, int row, MarkdownLine line, Theme theme, int width) {
        switch (line.kind()) {
            case HEADING -> drawHeading(graphics, row, line, theme, width);
            case RULE -> drawRule(graphics, row, theme, width);
            case CODE -> drawCodeRow(graphics, row, line, theme, width);
            case QUOTE -> drawQuoteRow(graphics, row, line, theme, width);
            case LIST_ITEM -> drawListItem(graphics, row, line, theme, width);
            case PARAGRAPH -> drawStyled(graphics, row, line, 0,
                    new TextCell(' ', theme.foreground(), theme.background()));
            default -> drawStyled(graphics, row, line, 0,
                    new TextCell(' ', theme.foreground(), theme.background()));
        }
    }

    private void drawHeading(TextGraphics graphics, int row, MarkdownLine line, Theme theme, int width) {
        var base = new TextCell(' ', theme.headerFg(), theme.headerBg(), SGR.BOLD);
        fillRow(graphics, row, base, width);
        drawStyled(graphics, row, line, 0, base);
    }

    private void drawRule(TextGraphics graphics, int row, Theme theme, int width) {
        var cell = new TextCell(Symbols.H_LINE.charAt(0), theme.border(), theme.background());
        graphics.fillRectangle(0, row, width, 1, cell);
    }

    private void drawCodeRow(TextGraphics graphics, int row, MarkdownLine line, Theme theme, int width) {
        // First code row draws the box top, middle rows the sides, and a closing
        // row is drawn after the last code row of the block (see draw()).
        int cols = Math.min(width, TerminalTextUtils.getTrueWidth(line.text()) + 2);
        var frame = new TextCell(' ', theme.border(), theme.background());
        var corner = new TextCell(Symbols.TL_CORNER.charAt(0), theme.border(), theme.background());
        var body = new TextCell(' ', theme.accent(), theme.background());
        // Frame columns 0..cols-1: borders at 0 and cols-1, text at 1.
        graphics.drawString(1, row, TerminalTextUtils.truncate(line.text(), Math.max(0, width - 2)), body);
        // Vertical bars on both sides (skip corners on first/last code rows).
        graphics.drawString(0, row, String.valueOf(Symbols.V_LINE), frame);
        if (cols < width) {
            graphics.drawString(cols - 1 < 0 ? 0 : cols - 1, row, String.valueOf(Symbols.V_LINE), frame);
        }
    }

    private void drawStyled(TextGraphics graphics, int row, MarkdownLine line, int colOffset, TextCell base) {
        drawStyledAt(graphics, row, line, colOffset, base);
    }

    /**
     * Draws one markdown line's text at the given starting column, applying
     * span styles per run of equal style. Columns are display columns
     * (span offsets are column-based), so double-width characters advance the
     * pen by 2 as they are drawn.
     *
     * @param graphics  the draw target
     * @param row       the row to draw at
     * @param line      the markdown line
     * @param startCol  the column where the text begins (after structural indent)
     * @param base      the base cell style for unstyled text
     */
    private void drawStyledAt(TextGraphics graphics, int row, MarkdownLine line, int startCol, TextCell base) {
        String text = line.text();
        int col = startCol;          // buffer column where the next source col lands
        int srcCol = 0;              // source column (CJK counts 2)
        while (srcCol < text.length()) {
            MarkdownLine.Span.Style style = styleAt(line, srcCol);
            int runEnd = srcCol;
            int runCols = 0;
            while (runEnd < text.length() && styleAt(line, runEnd) == style) {
                runCols += TerminalTextUtils.isCharDoubleWidth(text.charAt(runEnd)) ? 2 : 1;
                runEnd++;
            }
            String piece = text.substring(srcCol, runEnd);
            var cell = cellFor(style, base, ThemeManager.active());
            graphics.drawString(col, row, piece, cell);
            col += runCols;
            srcCol = runEnd;
        }
    }

    private MarkdownLine.Span.Style styleAt(MarkdownLine line, int col) {
        MarkdownLine.Span.Style matched = null;
        for (MarkdownLine.Span s : line.spans()) {
            if (s.contains(col)) matched = s.style();
        }
        return matched;
    }

    private TextCell cellFor(MarkdownLine.Span.Style style, TextCell base, Theme theme) {
        if (style == null) return base;
        return switch (style) {
            case BOLD -> base.withModifier(SGR.BOLD);
            case ITALIC -> base.withModifier(SGR.ITALIC);
            case CODE -> new TextCell(' ', theme.accent(), base.bg(), SGR.BOLD);
            case DIM -> new TextCell(' ', AnsiColor.BRIGHT_BLACK, base.bg(), SGR.DIM);
        };
    }

    private void fillRow(TextGraphics graphics, int row, TextCell cell, int width) {
        graphics.fillRectangle(0, row, width, 1, cell);
    }

    private void drawListItem(TextGraphics graphics, int row, MarkdownLine line, Theme theme, int width) {
        var textBase = new TextCell(' ', theme.foreground(), theme.background());
        int indent = 4 + 2 * line.listDepth();
        var bullet = new TextCell('•', theme.accent(), theme.background());
        graphics.drawString(bulletCol(line.listDepth()), row, "•", bullet);
        drawStyledAt(graphics, row, line, indent, textBase);
    }

    private void drawQuoteRow(TextGraphics graphics, int row, MarkdownLine line, Theme theme, int width) {
        var bar = new TextCell(Symbols.V_LINE.charAt(0), theme.border(), theme.background());
        graphics.drawString(0, row, String.valueOf(Symbols.V_LINE), bar);
        drawStyledAt(graphics, row, line, 2, new TextCell(' ', theme.foreground(), theme.background()));
    }

    private int bulletCol(int depth) {
        return 2 * depth;
    }
}