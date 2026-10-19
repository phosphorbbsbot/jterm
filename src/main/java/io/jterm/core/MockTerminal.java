package io.jterm.core;

import io.jterm.core.input.KeyStroke;
import io.jterm.style.Color;
import io.jterm.style.SGR;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Terminal implementation backed by byte arrays, suitable for tests and non-tty environments. */
public class MockTerminal implements Terminal {
    private final OutputStream out;
    private final InputStream in;
    private final TerminalSize size;
    private final List<TerminalResizeListener> listeners = new ArrayList<>();
    private final StringBuilder output = new StringBuilder();
    private boolean closed;
    private boolean syncOutputSupported;
    private io.jterm.core.input.InputDecoder decoder;

    /**
     * Create a mock terminal with the given size, using default in-memory streams.
     *
     * @param size the terminal dimensions
     */
    public MockTerminal(TerminalSize size) {
        this(size, new java.io.ByteArrayOutputStream(), new java.io.ByteArrayInputStream(new byte[0]));
    }

    /**
     * Create a mock terminal with the given size, output stream, and input stream.
     *
     * @param size the terminal dimensions
     * @param out  the output stream
     * @param in   the input stream
     */
    public MockTerminal(TerminalSize size, OutputStream out, InputStream in) {
        this.size = size;
        this.out = out;
        this.in = in;
        this.decoder = new io.jterm.core.input.InputDecoder(in);
    }

    /**
     * Switch to the alternate screen buffer and enable raw mode.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void enterPrivateMode() throws IOException {}

    /**
     * Leave the alternate screen buffer and restore original terminal settings.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void exitPrivateMode() throws IOException {}

    /**
     * Clear the terminal screen.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void clearScreen() throws IOException {
        output.append("\033[2J");
    }

    /**
     * Move the cursor to the specified column and row.
     *
     * @param column the column index (0-based)
     * @param row the row index (0-based)
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void setCursorPosition(int column, int row) throws IOException {
        output.append(String.format("\033[%d;%dH", row + 1, column + 1));
    }

    /**
     * Show or hide the terminal cursor.
     *
     * @param visible true to show, false to hide
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void setCursorVisible(boolean visible) throws IOException {}

    /**
     * Write a single character to the terminal.
     *
     * @param c the character to write
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void putCharacter(char c) throws IOException {
        output.append(c);
        out.write(c);
    }

    /**
     * Flush any buffered output to the underlying stream.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void flush() throws IOException {
        out.flush();
    }

    /**
     * Set the terminal foreground color.
     *
     * @param color the color to apply
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void setForegroundColor(Color color) throws IOException {
        output.append("\033[").append(new String(color.fgSequence(), java.nio.charset.StandardCharsets.UTF_8)).append("m");
    }

    /**
     * Set the terminal background color.
     *
     * @param color the color to apply
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void setBackgroundColor(Color color) throws IOException {
        output.append("\033[").append(new String(color.bgSequence(), java.nio.charset.StandardCharsets.UTF_8)).append("m");
    }

    /**
     * Enable a Select Graphic Rendition attribute.
     *
     * @param sgr the SGR attribute
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void enableSGR(SGR sgr) throws IOException {
        output.append("\033[").append(sgr.code).append("m");
    }

    /**
     * Disable a Select Graphic Rendition attribute.
     *
     * @param sgr the SGR attribute
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void disableSGR(SGR sgr) throws IOException {
        output.append("\033[").append(switch (sgr) {
            case BOLD, DIM -> 22;
            case ITALIC -> 23;
            case UNDERLINE -> 24;
            case BLINK -> 25;
            case REVERSE -> 27;
            case HIDDEN -> 28;
            case STRIKETHROUGH -> 29;
        }).append("m");
    }

    /**
     * Reset all colors and SGR attributes to defaults.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void resetColorAndSGR() throws IOException {
        output.append("\033[0m");
    }

    /**
     * Return the current terminal size (columns x rows).
     *
     * @return the terminalsize
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public TerminalSize getTerminalSize() throws IOException {
        return size;
    }

    /**
     * Poll for the next key stroke without blocking indefinitely.
     *
     * @return an Optional containing the key stroke, or empty if none available
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public Optional<KeyStroke> pollInput() throws IOException {
        return decoder != null ? decoder.poll() : Optional.empty();
    }

    /**
     * Block until the next key stroke is available.
     *
     * @return the next key stroke
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public KeyStroke readInput() throws IOException {
        if (decoder == null) return new KeyStroke(io.jterm.core.input.KeyType.EOF);
        while (true) {
            var ks = decoder.poll();
            if (ks.isPresent()) return ks.get();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new KeyStroke(io.jterm.core.input.KeyType.EOF);
            }
        }
    }

    // MockTerminal uses the default pollInput(timeout) from the interface (poll+sleep).

    /**
     * Register a listener for terminal resize events.
     *
     * @param listener the listener to register
     */
    @Override
    public void addResizeListener(TerminalResizeListener listener) {
        listeners.add(listener);
    }

    /**
     * Remove a previously registered resize listener.
     *
     * @param listener the listener to register
     */
    @Override
    public void removeResizeListener(TerminalResizeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Close the terminal and release resources.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void close() throws IOException {
        closed = true;
    }

    /**
     * Return whether synchronized output (DEC private mode 2026) is enabled
     * for this mock terminal.
     *
     * @return true if synchronized output is enabled, false otherwise
     */
    @Override
    public boolean syncOutputSupported() {
        return syncOutputSupported;
    }

    /**
     * Enable or disable synchronized output support on this mock terminal.
     *
     * @param supported true to enable, false to disable
     */
    @Override
    public void setSyncOutputSupported(boolean supported) {
        this.syncOutputSupported = supported;
    }

    /**
     * Return the captured output written to this mock terminal.
     *
     * @return the output
     */
    public String getOutput() {
        return output.toString();
    }

    /**
     * Clear the captured output buffer.
     */
    public void clearOutput() {
        output.setLength(0);
    }

    /**
     * Return whether the terminal has been closed.
     *
     * @return true if closed, false otherwise
     */
    public boolean isClosed() {
        return closed;
    }
}
