package io.jterm.screen;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.style.TextCell;
import java.io.IOException;

/**
 * A double-buffered terminal screen. Text cells are written into the back
 * buffer and flushed to the terminal on refresh.
 */
public interface Screen extends AutoCloseable {
    /**
     * Enters screen mode (private mode, cursor hidden).
     *
     * @throws IOException if an I/O error occurs
     */
    void startScreen() throws IOException;

    /**
     * Exits screen mode, restoring prior terminal state.
     *
     * @throws IOException if an I/O error occurs
     */
    void stopScreen() throws IOException;

    /**
     * Clears the back buffer using the active theme background.
     */
    void clear();

    /**
     * Sets a cell in the back buffer at the given column/row.
     *
     * @param col  the column
     * @param row  the row
     * @param cell the cell content
     */
    void setCell(int col, int row, TextCell cell);

    /**
     * Sets a cell in the back buffer at the given position.
     *
     * @param pos  the position
     * @param cell the cell content
     */
    void setCell(TerminalPosition pos, TextCell cell);

    /**
     * Returns the front buffer cell at the given coordinates.
     *
     * @param col the column
     * @param row the row
     * @return the cell
     */
    TextCell getFrontCell(int col, int row);

    /**
     * Returns the back buffer cell at the given coordinates.
     *
     * @param col the column
     * @param row the row
     * @return the cell
     */
    TextCell getBackCell(int col, int row);

    /**
     * Refreshes the screen using automatic refresh strategy.
     *
     * @throws IOException if an I/O error occurs
     */
    void refresh() throws IOException;

    /**
     * Refreshes the screen using the given strategy.
     *
     * @param type the refresh strategy
     * @throws IOException if an I/O error occurs
     */
    void refresh(RefreshType type) throws IOException;

    /**
     * Returns the current terminal size.
     *
     * @return the size in columns × rows
     */
    TerminalSize getTerminalSize();

    /**
     * Reallocates buffers if the terminal size changed; returns the current size.
     *
     * @return the current size
     * @throws IOException if an I/O error occurs
     */
    TerminalSize doResizeIfNecessary() throws IOException;

    /**
     * Sets the cursor position for the next refresh.
     *
     * @param position the cursor position
     */
    void setCursorPosition(TerminalPosition position);

    /**
     * Returns the current cursor position.
     *
     * @return the cursor position
     */
    TerminalPosition getCursorPosition();

    /**
     * CURSOR-ANCHOR LAW (2026-11-13): set the screen cell the terminal cursor
     * must be parked on at the end of each emitted frame. {@code null} clears
     * the anchor (no parking — legacy behavior: the cursor rests wherever the
     * last cell write left it). Callers: the gui sets it from the focused text
     * widget's caret every frame, so clients that draw their own cursor render
     * it ON the caret character, never beside it.
     *
     * @param position the anchor cell (0-based), or null
     */
    default void setCursorAnchor(TerminalPosition position) {
        // default: no-op — only backends with real terminal output park cursors
    }

    /**
     * Returns the anchor cell set for the next frame, or null.
     *
     * @return the anchor position
     */
    default TerminalPosition getCursorAnchor() {
        return null;
    }

    /**
     * Closes the screen, stopping it and closing the underlying terminal.
     *
     * @throws IOException if an I/O error occurs
     */
    @Override void close() throws IOException;
}