package io.jterm.graphics;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.Color;
import io.jterm.style.SGR;
import io.jterm.style.StyledSegment;
import io.jterm.style.TextCell;
import io.jterm.style.TextStyleResolver;

import java.util.List;

/** Drawing primitives targeting a backing ScreenBuffer. */
public class TextGraphics {
    private final ScreenBuffer buffer;

    /**
     * Creates a graphics context backed by the given buffer.
     *
     * @param buffer the backing screen buffer
     */
    public TextGraphics(ScreenBuffer buffer) {
        this.buffer = buffer;
    }

    /**
     * CURSOR-ANCHOR LAW: absolute 0-based origin of this graphics context.
     * The base graphics starts at (0,0); sub-graphics add their offsets.
     *
     * @return the absolute origin (column, row)
     */
    public TerminalPosition absoluteOrigin() {
        return TerminalPosition.TOP_LEFT;
    }

    /**
     * Returns the size of the drawing area.
     *
     * @return the buffer dimensions
     */
    public TerminalSize getSize() {
        return buffer.size();
    }

    /**
     * Sets the cell at the given coordinates.
     *
     * @param x the column
     * @param y the row
     * @param cell the cell to set
     */
    public void setCell(int x, int y, TextCell cell) {
        buffer.setCell(x, y, cell);
    }

    /**
     * Returns the cell at the given coordinates.
     *
     * @param x the column
     * @param y the row
     * @return the cell
     */
    public TextCell getCell(int x, int y) {
        return buffer.getCell(x, y);
    }

    /**
     * Fills a rectangular area with the given cell.
     *
     * @param x the starting column
     * @param y the starting row
     * @param width the rectangle width
     * @param height the rectangle height
     * @param cell the fill cell
     */
    public void fillRectangle(int x, int y, int width, int height, TextCell cell) {
        for (int r = y; r < y + height && r < buffer.size().rows(); r++) {
            for (int c = x; c < x + width && c < buffer.size().columns(); c++) {
                buffer.setCell(c, r, cell);
            }
        }
    }

    /**
     * Draws the outline of a rectangle using the given border cell.
     *
     * @param x the starting column
     * @param y the starting row
     * @param width the rectangle width
     * @param height the rectangle height
     * @param borderCell the border cell
     */
    public void drawRectangle(int x, int y, int width, int height, TextCell borderCell) {
        int maxRow = Math.min(y + height - 1, buffer.size().rows() - 1);
        int maxCol = Math.min(x + width - 1, buffer.size().columns() - 1);
        for (int c = x; c <= maxCol; c++) {
            buffer.setCell(c, y, borderCell);
            buffer.setCell(c, maxRow, borderCell);
        }
        for (int r = y; r <= maxRow; r++) {
            buffer.setCell(x, r, borderCell);
            buffer.setCell(maxCol, r, borderCell);
        }
    }

    /**
     * Draws a straight line between two points using Bresenham's algorithm.
     *
     * @param x0 start column
     * @param y0 start row
     * @param x1 end column
     * @param y1 end row
     * @param cell the line cell
     */
    public void drawLine(int x0, int y0, int x1, int y1, TextCell cell) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            buffer.setCell(x0, y0, cell);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    /**
     * Draws a line using direction-based character selection. For each
     * Bresenham point, the incoming and outgoing directions (from prev/next
     * points in the path) determine the character. Straight segments use
     * directional chars (-, |, \\, /); corners use anti-aliased chars that
     * connect the two directions smoothly.
     *
     * <p>Inspired by the code-golf ASCII anti-aliasing challenge
     * (https://codegolf.stackexchange.com/questions/5450).
     *
     * @param x0   start column
     * @param y0   start row
     * @param x1   end column
     * @param y1   end row
     * @param cell template cell (character is overridden per-point; fg/bg preserved)
     */
    public void drawLineSmooth(int x0, int y0, int x1, int y1, TextCell cell) {
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
            buffer.setCell(pt[0], pt[1], cell.withCharacter(ch));
        }
    }

    // Direction codes: 0=E, 1=SE, 2=S, 3=SW, 4=W, 5=NW, 6=N, 7=NE
    private static int dirCode(int dx, int dy) {
        if (dx > 0 && dy == 0) return 0;  // E
        if (dx > 0 && dy > 0) return 1;   // SE
        if (dx == 0 && dy > 0) return 2;  // S
        if (dx < 0 && dy > 0) return 3;   // SW
        if (dx < 0 && dy == 0) return 4;  // W
        if (dx < 0 && dy < 0) return 5;   // NW
        if (dx == 0 && dy < 0) return 6;  // N
        if (dx > 0 && dy < 0) return 7;   // NE
        return -1;
    }

    /**
     * Character lookup: [fromDir][toDir] where fromDir is the side where prev is
     * and toDir is the side where next is. Directions: 0=E,1=SE,2=S,3=SW,
     * 4=W,5=NW,6=N,7=NE. The "from" direction is where the line came FROM
     * (i.e., the side of the cell where prev is located).
     */
    private static final char[][] LINE_CHARS = new char[8][8];
    static {
        // Default: use '#' for unexpected combinations
        for (char[] row : LINE_CHARS) java.util.Arrays.fill(row, '#');

        // Straight lines (from = opposite of to)
        LINE_CHARS[0][4] = '-';  // E→W = horizontal
        LINE_CHARS[4][0] = '-';  // W→E = horizontal
        LINE_CHARS[6][2] = '|';  // N→S = vertical
        LINE_CHARS[2][6] = '|';  // S→N = vertical
        LINE_CHARS[5][1] = '\\'; // NW→SE = diagonal
        LINE_CHARS[1][5] = '\\'; // SE→NW = diagonal
        LINE_CHARS[7][3] = '/';  // NE→SW = diagonal
        LINE_CHARS[3][7] = '/';  // SW→NE = diagonal

        // Cardinal corners (code-golf anti-aliased chars)
        // 'd' = right+down, 'b' = left+down, 'Y' = right+up, 'F' = left+up
        LINE_CHARS[0][2] = 'd';  // from E, to S: right+down → 'd'
        LINE_CHARS[2][0] = 'd';  // from S, to E: right+down → 'd'
        LINE_CHARS[4][2] = 'b';  // from W, to S: left+down → 'b'
        LINE_CHARS[2][4] = 'b';  // from S, to W: left+down → 'b'
        LINE_CHARS[0][6] = 'Y';  // from E, to N: right+up → 'Y'
        LINE_CHARS[6][0] = 'Y';  // from N, to E: right+up → 'Y'
        LINE_CHARS[4][6] = 'F';  // from W, to N: left+up → 'F'
        LINE_CHARS[6][4] = 'F';  // from N, to W: left+up → 'F'

        // Cardinal→diagonal transitions: use the code-golf char for the
        // nearest cardinal corner. The diagonal adds one new cardinal
        // component to the existing cardinal direction.
        // E→SE (0→1): adds S to E → like E→S = 'd'
        LINE_CHARS[0][1] = 'd';  // E→SE
        LINE_CHARS[1][0] = 'Y';  // SE→E (removes S, levels to E: from NW, to E = 'Y')
        LINE_CHARS[0][7] = 'Y';  // E→NE (adds N to E: like E→N = 'Y')
        LINE_CHARS[7][0] = 'd';  // NE→E (removes N, levels to E: from SW, to E = 'd')
        LINE_CHARS[4][3] = 'b';  // W→SW (adds S to W: like W→S = 'b')
        LINE_CHARS[3][4] = 'F';  // SW→W (removes S, levels to W: from NE, to W = 'F')
        LINE_CHARS[4][5] = 'F';  // W→NW (adds N to W: like W→N = 'F')
        LINE_CHARS[5][4] = 'b';  // NW→W (removes N, levels to W: from SE, to W = 'b')

        // S→SE (2→1): adds E to S → like S→E = 'd'
        LINE_CHARS[2][1] = 'd';  // S→SE
        LINE_CHARS[1][2] = 'b';  // SE→S (removes E, levels to S: from NW, to S = 'b')
        LINE_CHARS[2][3] = 'b';  // S→SW (adds W to S: like S→W = 'b')
        LINE_CHARS[3][2] = 'd';  // SW→S (removes W, levels to S: from NE, to S = 'd')
        LINE_CHARS[6][7] = 'Y';  // N→NE (adds E to N: like N→E = 'Y')
        LINE_CHARS[7][6] = 'F';  // NE→N (removes E, levels to N: from SW, to N = 'F')
        LINE_CHARS[6][5] = 'F';  // N→NW (adds W to N: like N→W = 'F')
        LINE_CHARS[5][6] = 'Y';  // NW→N (removes W, levels to N: from SE, to N = 'Y')

        // Diagonal→diagonal corners (direction reverses on one axis)
        // SE→SW (1→3): from NW to E... wait. These are rare in Bresenham.
        // Just use the outgoing direction's char as fallback.
        LINE_CHARS[1][3] = 'b';  // SE→SW: goes from down-right to down-left → 'b' (left+down)
        LINE_CHARS[3][1] = 'd';  // SW→SE: goes from down-left to down-right → 'd' (right+down)
        LINE_CHARS[5][7] = 'Y';  // NW→NE: goes from up-left to up-right → 'Y' (right+up)
        LINE_CHARS[7][5] = 'F';  // NE→NW: goes from up-right to up-left → 'F' (left+up)
        LINE_CHARS[1][7] = '-';  // SE→NE: goes from down-right to up-right → vertical flip, use '|'? 
        LINE_CHARS[7][1] = '-';  // NE→SE: similar
        LINE_CHARS[3][5] = '-';  // SW→NW: similar
        LINE_CHARS[5][3] = '-';  // NW→SW: similar
    }

    /**
     * Picks a character for a Bresenham point based on the incoming direction
     * (from prev) and outgoing direction (to next).
     */
    static char lineCharFor(int[] pt, int[] prev, int[] next) {
        // Single point
        if (prev == null && next == null) return '@';

        // Endpoint — use the one direction we have
        if (prev == null) {
            int outDx = Integer.signum(next[0] - pt[0]);
            int outDy = Integer.signum(next[1] - pt[1]);
            return dirChar(outDx, outDy);
        }
        if (next == null) {
            int inDx = Integer.signum(pt[0] - prev[0]);
            int inDy = Integer.signum(pt[1] - prev[1]);
            return dirChar(inDx, inDy);
        }

        // Interior point: determine from/to directions
        int inDx = Integer.signum(pt[0] - prev[0]);
        int inDy = Integer.signum(pt[1] - prev[1]);
        int outDx = Integer.signum(next[0] - pt[0]);
        int outDy = Integer.signum(next[1] - pt[1]);

        int fromDir = dirCode(-inDx, -inDy); // "from" = opposite of incoming = where prev is
        int toDir = dirCode(outDx, outDy);   // "to" = where next is

        if (fromDir < 0 || toDir < 0) return '@';
        return LINE_CHARS[fromDir][toDir];
    }

    /** Character for a single direction (used at endpoints). */
    private static char dirChar(int dx, int dy) {
        if (dy == 0) return '-';       // horizontal E/W
        if (dx == 0) return '|';       // vertical N/S
        if (dx == dy) return '\\';     // SE/NW
        return '/';                     // NE/SW
    }

    /**
     * Draws a string at the given position using the template cell's style.
     *
     * @param x the column
     * @param y the row
     * @param text the text to draw
     * @param template the template cell (character is replaced per char)
     */
    public void drawString(int x, int y, String text, TextCell template) {
        int col = x;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            buffer.setCell(col, y, template.withCharacter(c));
            col += isCharDoubleWidth(c) ? 2 : 1;
        }
    }

    /**
     * Draws a string at the given position with the given foreground,
     * background, and SGR modifiers.
     *
     * @param x the column
     * @param y the row
     * @param text the text to draw
     * @param fg the foreground color
     * @param bg the background color
     * @param mods the SGR modifiers
     */
    public void drawString(int x, int y, String text, Color fg, Color bg, SGR... mods) {
        var modsSet = mods.length == 0 ? java.util.EnumSet.noneOf(SGR.class) : java.util.EnumSet.of(mods[0], mods);
        drawString(x, y, text, new TextCell(' ', fg, bg, modsSet.toArray(new SGR[0])));
    }

    /**
     * Draws a string with per-character style overrides via a
     * {@link TextStyleResolver}. For each character, the resolver is called;
     * if it returns a non-null {@link TextCell}, that cell (with the character
     * replaced) is used. If it returns {@code null}, the default style is used
     * with the character substituted. Double-width characters are handled the
     * same as in {@link #drawString}.
     *
     * @param x            the column
     * @param y            the row
     * @param text         the text to draw
     * @param defaultStyle the default cell style
     * @param resolver     the per-character style resolver
     */
    public void drawStyledString(int x, int y, String text, TextCell defaultStyle, TextStyleResolver resolver) {
        int col = x;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            TextCell override = resolver.resolveStyle(i, c, defaultStyle);
            TextCell cell = override != null ? override.withCharacter(c) : defaultStyle.withCharacter(c);
            buffer.setCell(col, y, cell);
            col += isCharDoubleWidth(c) ? 2 : 1;
        }
    }

    /**
     * Draws a string with per-character styling defined by a list of
     * {@link StyledSegment} ranges. For each character index, the last
     * segment in the list that contains the index wins (allowing later,
     * more specific segments to override earlier, broader ones). If no
     * segment covers the index, the default style is used.
     *
     * @param x            the column
     * @param y            the row
     * @param text         the text to draw
     * @param defaultStyle the default cell style
     * @param segments     the styled segments (last matching wins)
     */
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

    /**
     * Sets a single cell (alias for {@link #setCell}).
     *
     * @param x the column
     * @param y the row
     * @param cell the cell to set
     */
    public void putCell(int x, int y, TextCell cell) {
        buffer.setCell(x, y, cell);
    }

    /**
     * Returns a sub-graphics context for the given region.
     *
     * @param x the column offset
     * @param y the row offset
     * @param width the sub-region width
     * @param height the sub-region height
     * @return the sub-graphics
     */
    public TextGraphics getGraphics(int x, int y, int width, int height) {
        return new SubTextGraphics(this, x, y, width, height);
    }

    private static boolean isCharDoubleWidth(char c) {
        var block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.HANGUL_SYLLABLES
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }
}
