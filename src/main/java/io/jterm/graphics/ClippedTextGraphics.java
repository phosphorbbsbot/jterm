package io.jterm.graphics;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.StyledSegment;
import io.jterm.style.TextCell;
import io.jterm.style.TextStyleResolver;

import java.util.List;

/** Graphics view that writes through to a parent buffer at an offset. */
public class ClippedTextGraphics extends TextGraphics {
    private final TextGraphics parent;
    private final TerminalPosition offset;

    /**
     * CURSOR-ANCHOR LAW: absolute origin of this clip — chained through the
     * parent so widgets report caret cells in absolute screen coordinates.
     */
    @Override
    public TerminalPosition absoluteOrigin() {
        var parentOrigin = parent.absoluteOrigin();
        return new TerminalPosition(offset.column() + parentOrigin.column(),
                offset.row() + parentOrigin.row());
    }

    /**
     * Create a clipped graphics view that writes through to a parent at an offset.
     *
     * @param parent the parent graphics context
     * @param offset the position offset within the parent
     * @param size   the size of this clipped region
     */
    public ClippedTextGraphics(TextGraphics parent, TerminalPosition offset, TerminalSize size) {
        super(new ScreenBuffer(size));
        this.parent = parent;
        this.offset = offset;
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
        if (x < 0 || y < 0 || x >= getSize().columns() || y >= getSize().rows()) return;
        super.setCell(x, y, cell);
        parent.setCell(offset.column() + x, offset.row() + y, cell);
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
        // TextGraphics.drawLine writes to this.buffer only — we must delegate
        // to setCell so each point also reaches the parent buffer.
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0, y = y0;
        while (true) {
            setCell(x, y, cell);
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 < dx) { err += dx; y += sy; }
        }
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
        // Collect Bresenham path, then write each point via setCell so both
        // the local and parent buffers receive the smoothed character.
        java.util.List<int[]> path = new java.util.ArrayList<>();
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int cx = x0, cy = y0;
        while (true) {
            path.add(new int[]{cx, cy});
            if (cx == x1 && cy == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; cx += sx; }
            if (e2 < dx) { err += dx; cy += sy; }
        }
        for (int i = 0; i < path.size(); i++) {
            int[] pt = path.get(i);
            int[] prev = i > 0 ? path.get(i - 1) : null;
            int[] next = i < path.size() - 1 ? path.get(i + 1) : null;
            char ch = lineCharFor(pt, prev, next);
            setCell(pt[0], pt[1], cell.withCharacter(ch));
        }
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
        int maxCol = x + width - 1;
        int maxRow = y + height - 1;
        for (int c = x; c <= maxCol; c++) {
            setCell(c, y, borderCell);
            setCell(c, maxRow, borderCell);
        }
        for (int r = y + 1; r < maxRow; r++) {
            setCell(x, r, borderCell);
            setCell(maxCol, r, borderCell);
        }
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
        for (int r = Math.max(0, y); r < y + height && r < getSize().rows(); r++) {
            for (int c = Math.max(0, x); c < x + width && c < getSize().columns(); c++) {
                setCell(c, r, cell);
            }
        }
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
        int col = x;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (col >= 0 && col < getSize().columns() && y >= 0 && y < getSize().rows()) {
                setCell(col, y, template.withCharacter(c));
            }
            col += isCharDoubleWidth(c) ? 2 : 1;
        }
    }

    /**
     * Draw a styled string at the given position with per-character clipping.
     * Each character is written via {@link #setCell} so it respects the
     * clipped region boundaries.
     *
     * @param x            the x coordinate
     * @param y            the y coordinate
     * @param text         the text to draw
     * @param defaultStyle the default cell style
     * @param resolver     the per-character style resolver
     */
    @Override
    public void drawStyledString(int x, int y, String text, TextCell defaultStyle, TextStyleResolver resolver) {
        int col = x;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (col >= 0 && col < getSize().columns() && y >= 0 && y < getSize().rows()) {
                TextCell override = resolver.resolveStyle(i, c, defaultStyle);
                TextCell cell = override != null ? override.withCharacter(c) : defaultStyle.withCharacter(c);
                setCell(col, y, cell);
            }
            col += isCharDoubleWidth(c) ? 2 : 1;
        }
    }

    /**
     * Draw a styled string with segments at the given position with
     * per-character clipping.
     *
     * @param x            the x coordinate
     * @param y            the y coordinate
     * @param text         the text to draw
     * @param defaultStyle the default cell style
     * @param segments     the styled segments
     */
    @Override
    public void drawStyledString(int x, int y, String text, TextCell defaultStyle, List<StyledSegment> segments) {
        drawStyledString(x, y, text, defaultStyle, (charIndex, c, defStyle) -> {
            StyledSegment matched = null;
            for (StyledSegment seg : segments) {
                if (seg.contains(charIndex)) {
                    matched = seg;
                }
            }
            return matched != null ? matched.applyTo(c, defStyle.bg()) : null;
        });
    }

    private static boolean isCharDoubleWidth(char c) {
        var block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.HANGUL_SYLLABLES
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }
}
