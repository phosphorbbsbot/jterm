package io.jterm.graphics;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.style.Color;
import io.jterm.style.SGR;
import io.jterm.style.StyledSegment;
import io.jterm.style.TextCell;
import io.jterm.style.TextStyleResolver;

import java.util.List;

/** A TextGraphics that translates coordinates to a sub-region of a parent graphics. */
public class SubTextGraphics extends TextGraphics {
    private final TextGraphics parent;
    private final int x;
    private final int y;
    private final int width;
    private final int height;

    /**
     * Create a sub-graphics that translates coordinates to a sub-region of the parent.
     *
     * @param parent the parent graphics context
     * @param x      the x offset within the parent
     * @param y      the y offset within the parent
     * @param width  the width of the sub-region
     * @param height the height of the sub-region
     */
    public SubTextGraphics(TextGraphics parent, int x, int y, int width, int height) {
        super(nullScreenBuffer(x, y, width, height));
        if (parent == null) throw new IllegalArgumentException("parent graphics required");
        this.parent = parent;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /**
     * Return the window size.
     *
     * @return the size
     */
    @Override
    public TerminalSize getSize() {
        return new TerminalSize(width, height);
    }

    /**
     * CURSOR-ANCHOR LAW: absolute 0-based origin of this graphics context —
     * the sum of every parent offset. Widgets report their caret cell relative
     * to an absolute origin so the gui can park the terminal cursor on it.
     *
     * @return the absolute origin (column, row)
     */
    @Override
    public TerminalPosition absoluteOrigin() {
        TerminalPosition p = parent.absoluteOrigin();
        return p.offset(x, y);
    }

    /**
     * Set a cell in the back buffer.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @param cell the cell to write
     */
    @Override
    public void setCell(int x, int y, TextCell cell) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        parent.setCell(this.x + x, this.y + y, cell);
    }

    /**
     * Return the cell at the given coordinates.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     *
     * @return the cell
     */
    @Override
    public TextCell getCell(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return TextCell.EMPTY;
        return parent.getCell(this.x + x, this.y + y);
    }

    /**
     * Fill a rectangular area with the given cell.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @param width the width
     * @param height the height
     * @param cell the cell to write
     */
    @Override
    public void fillRectangle(int x, int y, int width, int height, TextCell cell) {
        int x0 = Math.max(this.x + x, this.x);
        int y0 = Math.max(this.y + y, this.y);
        int x1 = Math.min(this.x + x + width, this.x + this.width);
        int y1 = Math.min(this.y + y + height, this.y + this.height);
        parent.fillRectangle(x0, y0, x1 - x0, y1 - y0, cell);
    }

    /**
     * Draw a rectangle border with the given cell.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @param width the width
     * @param height the height
     * @param borderCell the cell to use for the border
     */
    @Override
    public void drawRectangle(int x, int y, int width, int height, TextCell borderCell) {
        int x0 = Math.max(this.x + x, this.x);
        int y0 = Math.max(this.y + y, this.y);
        int x1 = Math.min(this.x + x + width - 1, this.x + this.width - 1);
        int y1 = Math.min(this.y + y + height - 1, this.y + this.height - 1);
        parent.drawRectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1, borderCell);
    }

    /**
     * Draw a straight line between two points.
     *
     * @param x0 the starting x coordinate
     * @param y0 the starting y coordinate
     * @param x1 the ending x coordinate
     * @param y1 the ending y coordinate
     * @param cell the cell to write
     */
    @Override
    public void drawLine(int x0, int y0, int x1, int y1, TextCell cell) {
        parent.drawLine(this.x + x0, this.y + y0, this.x + x1, this.y + y1, cell);
    }

    /**
     * Draw a smooth (anti-aliased) line between two points.
     *
     * @param x0 the starting x coordinate
     * @param y0 the starting y coordinate
     * @param x1 the ending x coordinate
     * @param y1 the ending y coordinate
     * @param cell the cell to write
     */
    @Override
    public void drawLineSmooth(int x0, int y0, int x1, int y1, TextCell cell) {
        parent.drawLineSmooth(this.x + x0, this.y + y0, this.x + x1, this.y + y1, cell);
    }

    /**
     * Draw a string at the given position.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @param text the text to draw
     * @param template the template cell for styling
     */
    @Override
    public void drawString(int x, int y, String text, TextCell template) {
        parent.drawString(this.x + x, this.y + y, text, template);
    }

    /**
     * Draw a string at the given position.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @param text the text to draw
     * @param fg the foreground color
     * @param bg the background color
     * @param mods optional SGR modifiers
     */
    @Override
    public void drawString(int x, int y, String text, Color fg, Color bg, SGR... mods) {
        parent.drawString(this.x + x, this.y + y, text, fg, bg, mods);
    }

    /**
     * Draw a styled string at the given position, delegating to the parent
     * with the offset applied.
     *
     * @param x            the x coordinate within this sub-region
     * @param y            the y coordinate within this sub-region
     * @param text         the text to draw
     * @param defaultStyle the default cell style
     * @param resolver     the per-character style resolver
     */
    @Override
    public void drawStyledString(int x, int y, String text, TextCell defaultStyle, TextStyleResolver resolver) {
        parent.drawStyledString(this.x + x, this.y + y, text, defaultStyle, resolver);
    }

    /**
     * Draw a styled string with segments at the given position, delegating to
     * the parent with the offset applied.
     *
     * @param x            the x coordinate within this sub-region
     * @param y            the y coordinate within this sub-region
     * @param text         the text to draw
     * @param defaultStyle the default cell style
     * @param segments     the styled segments
     */
    @Override
    public void drawStyledString(int x, int y, String text, TextCell defaultStyle, List<StyledSegment> segments) {
        parent.drawStyledString(this.x + x, this.y + y, text, defaultStyle, segments);
    }

    /**
     * Place a cell at the given coordinates.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @param cell the cell to write
     */
    @Override
    public void putCell(int x, int y, TextCell cell) {
        setCell(x, y, cell);
    }

    /**
     * Return a sub-graphics for the given region.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @param width the width
     * @param height the height
     *
     * @return the graphics
     */
    @Override
    public TextGraphics getGraphics(int x, int y, int width, int height) {
        return new SubTextGraphics(parent, this.x + x, this.y + y, width, height);
    }

    private static io.jterm.screen.ScreenBuffer nullScreenBuffer(int x, int y, int width, int height) {
        return new io.jterm.screen.ScreenBuffer(new TerminalSize(Math.max(1, width), Math.max(1, height)));
    }
}
