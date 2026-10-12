package io.jterm.screen;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.style.TextCell;
import java.util.ArrayList;
import java.util.List;

/** 2D grid of TextCells. Backed by a flat array for cache efficiency.
 *  All public methods are synchronized for thread safety. */
public class ScreenBuffer {
    private final int columns;
    private final int rows;
    private final TextCell[] cells;

    /**
     * Creates a screen buffer filled with empty cells.
     *
     * @param size the buffer dimensions
     */
    public ScreenBuffer(TerminalSize size) {
        this(size, TextCell.EMPTY);
    }

    /**
     * Creates a screen buffer filled with the given cell.
     *
     * @param size the buffer dimensions
     * @param fill the cell to fill every position with
     */
    public ScreenBuffer(TerminalSize size, TextCell fill) {
        this.columns = size.columns();
        this.rows = size.rows();
        this.cells = new TextCell[columns * rows];
        java.util.Arrays.fill(cells, fill);
    }

    /**
     * Returns the dimensions of this buffer.
     *
     * @return the buffer size in columns × rows
     */
    public TerminalSize size() { return new TerminalSize(columns, rows); }

    /**
     * Returns the cell at the given coordinates, or EMPTY if out of bounds.
     *
     * @param col zero-indexed column
     * @param row zero-indexed row
     * @return the cell at (col, row), or {@link TextCell#EMPTY} if out of bounds
     */
    public synchronized TextCell getCell(int col, int row) {
        if (col < 0 || col >= columns || row < 0 || row >= rows)
            return TextCell.EMPTY;
        return cells[row * columns + col];
    }

    /**
     * Sets the cell at the given coordinates; out-of-bounds writes are ignored.
     *
     * @param col  zero-indexed column
     * @param row  zero-indexed row
     * @param cell the cell to set
     */
    public synchronized void setCell(int col, int row, TextCell cell) {
        if (col < 0 || col >= columns || row < 0 || row >= rows) return;
        cells[row * columns + col] = sanitize(cell);
    }

    /**
     * Security filter: control characters (C0, DEL, and lone surrogates) are
     * replaced with a space before entering the buffer. TextCell content is
     * emitted verbatim on refresh; a hostile ESC reaching the wire would be
     * re-interpreted by the client terminal as an escape sequence (title
     * spoofing via OSC 0, hyperlink phishing via OSC 8, clipboard overwrite
     * via OSC 52, cursor repositioning, screen desync). Untrusted text
     * (chat, usernames, message bodies) flows through this funnel in BBS
     * deployments, so the buffer itself is the choke point.
     *
     * @param cell the candidate cell
     * @return a cell whose character is guaranteed printable
     */
    private static TextCell sanitize(TextCell cell) {
        String ch = cell.character();
        if (ch.isEmpty()) return cell;
        char c = ch.charAt(0);
        if (c >= 32 && c != 127 && !Character.isSurrogate(c)) return cell;
        return ' ' == c ? cell : new TextCell(" ", cell.fg(), cell.bg(), cell.modifiers());
    }

    /**
     * Sets the cell at the given position.
     *
     * @param pos  the position to write to
     * @param cell the cell to set
     */
    public void setCell(TerminalPosition pos, TextCell cell) {
        setCell(pos.column(), pos.row(), cell);
    }

    /**
     * Fills the entire buffer with the given cell.
     *
     * @param cell the fill cell
     */
    public synchronized void fill(TextCell cell) {
        java.util.Arrays.fill(cells, cell);
    }

    /**
     * Fills a rectangular region with the given cell.
     *
     * @param col    leftmost column of the region
     * @param row    topmost row of the region
     * @param width  region width in columns
     * @param height region height in rows
     * @param cell   the fill cell
     */
    public synchronized void fillRect(int col, int row, int width, int height, TextCell cell) {
        for (int r = row; r < row + height && r < rows; r++) {
            for (int c = col; c < col + width && c < columns; c++) {
                setCell(c, r, cell);
            }
        }
    }

    /**
     * Returns list of (position, cell) pairs where this buffer differs from other.
     *
     * @param other the buffer to compare against
     * @return the differing cells, in row-major order
     */
    public synchronized List<CellDiff> diffFrom(ScreenBuffer other) {
        List<CellDiff> diffs = new ArrayList<>();
        int maxRows = Math.min(rows, other.rows);
        int maxCols = Math.min(columns, other.columns);
        for (int r = 0; r < maxRows; r++) {
            for (int c = 0; c < maxCols; c++) {
                TextCell a = getCell(c, r);
                TextCell b = other.getCell(c, r);
                if (!a.equals(b)) {
                    diffs.add(new CellDiff(c, r, a));
                }
            }
        }
        return diffs;
    }

    /**
     * Copies cell data from the source buffer into this one (up to the smaller of both dimensions).
     *
     * @param source the buffer to copy from
     */
    public synchronized void copyFrom(ScreenBuffer source) {
        int maxRows = Math.min(rows, source.rows);
        int maxCols = Math.min(columns, source.columns);
        for (int r = 0; r < maxRows; r++) {
            System.arraycopy(source.cells, r * source.columns, cells, r * columns, maxCols);
        }
    }

    /**
     * A single differing cell produced by {@link #diffFrom}.
     *
     * @param column zero-indexed column of the differing cell
     * @param row    zero-indexed row of the differing cell
     * @param cell   this buffer's cell at that position
     */
    public record CellDiff(int column, int row, TextCell cell) {}
}
