package io.jterm.core;

import io.jterm.core.input.KeyStroke;
import io.jterm.style.Color;
import io.jterm.style.SGR;
import java.io.IOException;
import java.util.Optional;

/** Lowest-level terminal interface. Controls cursor, colors, SGR, and raw output. */
public interface Terminal extends AutoCloseable {

    /**
     * Enters private/alt-screen mode (hides cursor, saves screen state).
     *
     * @throws IOException if an I/O error occurs
     */
    void enterPrivateMode() throws IOException;

    /**
     * Exits private mode, restoring the prior screen state.
     *
     * @throws IOException if an I/O error occurs
     */
    void exitPrivateMode() throws IOException;

    /**
     * Clears the entire screen.
     *
     * @throws IOException if an I/O error occurs
     */
    void clearScreen() throws IOException;

    /**
     * Moves the cursor to the given column and row (zero-indexed).
     *
     * @param column zero-indexed column, growing rightward
     * @param row    zero-indexed row, growing downward
     * @throws IOException if an I/O error occurs
     */
    void setCursorPosition(int column, int row) throws IOException;

    /**
     * Shows or hides the terminal cursor.
     *
     * @param visible true to show the real cursor, false to hide it
     * @throws IOException if an I/O error occurs
     */
    void setCursorVisible(boolean visible) throws IOException;

    /**
     * Writes a single character to the terminal output.
     *
     * @param c the character to write
     * @throws IOException if an I/O error occurs
     */
    void putCharacter(char c) throws IOException;

    /**
     * Flushes any buffered output to the terminal.
     *
     * @throws IOException if an I/O error occurs
     */
    void flush() throws IOException;

    /**
     * Sets the foreground color for subsequent output.
     *
     * @param color the foreground color to apply
     * @throws IOException if an I/O error occurs
     */
    void setForegroundColor(Color color) throws IOException;

    /**
     * Sets the background color for subsequent output.
     *
     * @param color the background color to apply
     * @throws IOException if an I/O error occurs
     */
    void setBackgroundColor(Color color) throws IOException;

    /**
     * Enables a Select Graphic Rendition modifier.
     *
     * @param sgr the SGR modifier to enable
     * @throws IOException if an I/O error occurs
     */
    void enableSGR(SGR sgr) throws IOException;

    /**
     * Disables a Select Graphic Rendition modifier.
     *
     * @param sgr the SGR modifier to disable
     * @throws IOException if an I/O error occurs
     */
    void disableSGR(SGR sgr) throws IOException;

    /**
     * Resets all colors and SGR modifiers to terminal defaults.
     *
     * @throws IOException if an I/O error occurs
     */
    void resetColorAndSGR() throws IOException;

    /**
     * Returns the current terminal size in columns × rows.
     *
     * @return the current terminal size
     * @throws IOException if an I/O error occurs
     */
    TerminalSize getTerminalSize() throws IOException;

    /**
     * Non-blocking input poll; returns the next keystroke or empty if none available.
     *
     * @return the next keystroke, or an empty Optional if none is available
     * @throws IOException if an I/O error occurs
     */
    Optional<KeyStroke> pollInput() throws IOException;

    /**
     * Blocking input read; waits for and returns the next keystroke.
     *
     * @return the next keystroke (blocks until one arrives)
     * @throws IOException if an I/O error occurs
     */
    KeyStroke readInput() throws IOException;

    /**
     * Polls for input with a timeout. Returns the next keystroke if available
     * within {@code timeoutMillis}, or an empty Optional if the timeout elapses.
     * A timeout of 0 behaves like {@link #pollInput()} (non-blocking).
     *
     * @param timeoutMillis maximum time to wait for input in milliseconds
     * @return the next keystroke, or an empty Optional on timeout
     * @throws IOException if an I/O error occurs
     */
    default Optional<KeyStroke> pollInput(long timeoutMillis) throws IOException {
        // Default implementation: poll once, then sleep in small increments
        // Real implementations should override with a BlockingQueue for true blocking.
        var ks = pollInput();
        if (ks.isPresent()) return ks;
        if (timeoutMillis <= 0) return Optional.empty();
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (ks.isEmpty() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(1); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            ks = pollInput();
        }
        return ks;
    }

    /**
     * Registers a listener notified when the terminal is resized.
     *
     * @param listener the resize listener to add
     */
    void addResizeListener(TerminalResizeListener listener);

    /**
     * Removes a previously registered resize listener.
     *
     * @param listener the resize listener to remove
     */
    void removeResizeListener(TerminalResizeListener listener);

    /**
     * Closes the terminal, restoring original state and releasing resources.
     *
     * @throws IOException if an I/O error occurs
     */
    @Override
    void close() throws IOException;

    /**
     * Returns whether this terminal supports synchronized output updates
     * (DEC private mode 2026, BSU/ESU bracketing).
     *
     * @return true if synchronized output is supported, false by default
     */
    default boolean syncOutputSupported() {
        return false;
    }

    /**
     * Sets whether this terminal should be treated as supporting synchronized
     * output. The default implementation is a no-op.
     *
     * @param supported true to enable synchronized output support
     */
    default void setSyncOutputSupported(boolean supported) {}

    /**
     * Write raw bytes directly to the terminal output.
     *
     * @param data the raw bytes to write
     * @throws IOException if an I/O error occurs
     */
    default void writeRaw(byte[] data) throws IOException {
        for (byte b : data) {
            putCharacter((char) b);
        }
    }
}