package io.jterm.core;

import io.jterm.core.input.InputDecoder;
import io.jterm.core.input.KeyStroke;
import io.jterm.style.AnsiCodes;
import io.jterm.style.Color;
import io.jterm.style.SGR;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Unix ANSI terminal implementation. Supports raw mode, alt screen, and escape sequences. */
public class AnsiTerminal implements Terminal {
    private final OutputStream out;
    private final InputDecoder decoder;
    private final InputStream in;
    private final TerminalSize fixedSize;
    private final List<TerminalResizeListener> resizeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final String originalStty;
    private final boolean ownStty;
    private TerminalSize lastSize;

    // --- Blocking input pipeline (same as SocketTerminal) ---
    private final LinkedBlockingQueue<KeyStroke> inputQueue = new LinkedBlockingQueue<>(256);
    private Thread readerThread;
    private volatile boolean inputClosed = false;
    private volatile boolean inPrivateMode = false;

    /**
     * Construct terminal using provided streams and size (useful for tests).
     *
     * @param out  output stream the terminal writes escape sequences to
     * @param in   input stream the terminal reads keystrokes from
     * @param size fixed terminal size to report (never queried from the OS)
     */
    public AnsiTerminal(OutputStream out, InputStream in, TerminalSize size) {
        this.out = new BufferedOutputStream(out, 4096);
        this.in = in;
        this.decoder = new InputDecoder(in);
        this.fixedSize = size;
        this.lastSize = size;
        this.originalStty = null;
        this.ownStty = false;
        startReaderThread();
    }

    /**
     * Construct terminal using System.in/out and querying real terminal size.
     *
     * @throws IOException if the terminal size cannot be queried or stty state cannot be captured
     */
    public AnsiTerminal() throws IOException {
        this.out = new BufferedOutputStream(System.out, 4096);
        this.in = System.in;
        this.decoder = new InputDecoder(System.in);
        this.fixedSize = null;
        this.lastSize = queryTerminalSize();
        // Skip stty capture/restore when there's no real console (e.g. Maven Surefire).
        // In that environment, stty calls block and eventually timeout, wasting ~1.5s.
        boolean hasConsole = System.console() != null;
        this.originalStty = hasConsole ? captureStty() : null;
        this.ownStty = hasConsole;
        startReaderThread();
    }

    /**
     * Package-private constructor for testing: uses provided streams and queries
     * terminal size dynamically via ESC[18t (no stty, no /dev/tty). Does not
     * own stty since these are not the real terminal.
     */
    AnsiTerminal(OutputStream out, InputStream in) {
        this.out = new BufferedOutputStream(out, 4096);
        this.in = in;
        this.decoder = new InputDecoder(in);
        this.fixedSize = null;
        this.lastSize = queryTerminalSize(in, out);
        this.originalStty = null;
        this.ownStty = false;
        startReaderThread();
    }

    private static TerminalSize queryTerminalSize(InputStream in, OutputStream out) {
        // 1. Try ANSI ESC[18t query over the provided streams
        var size = TerminalSizeQuery.query(in, out, 500);
        if (size != null) return size;
        // 2. Final fallback
        return new TerminalSize(80, 24);
    }

    private static String captureStty() throws IOException {
        try {
            var pb = new ProcessBuilder("sh", "-c", "stty -g </dev/tty").redirectErrorStream(true);
            var p = pb.start();
            // Read output in a separate thread so readAllBytes() doesn't
            // block forever if the process hangs (e.g. no /dev/tty in CI).
            var ref = new java.util.concurrent.atomic.AtomicReference<byte[]>();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    ref.set(p.getInputStream().readAllBytes());
                } catch (IOException ignored) {
                }
            });
            if (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
            }
            reader.join(200);
            var bytes = ref.get();
            return bytes != null ? new String(bytes, StandardCharsets.UTF_8).trim() : "";
        } catch (InterruptedException | IOException e) {
            return null;
        }
    }

    private static void setRawMode() {
        try {
            var p = new ProcessBuilder("sh", "-c", "stty raw -echo -icanon </dev/tty").inheritIO().start();
            if (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException | IOException e) {
            // ignore
        }
    }

    private static void restoreStty(String settings) {
        if (settings == null || settings.isEmpty()) return;
        try {
            var p = new ProcessBuilder("sh", "-c", "stty " + settings + " </dev/tty").inheritIO().start();
            if (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException | IOException e) {
            // ignore
        }
    }

    /**
     * Parses the output of {@code stty size} — two whitespace-separated
     * integers "rows cols" — into a {@link TerminalSize}. Returns {@code null}
     * when the input is null, blank, not exactly two fields, or non-numeric;
     * the caller then falls back to the 80×24 default. Pure helper: no I/O,
     * so tests can drive every parse branch without a real console.
     *
     * @param output raw {@code stty size} output (may be null)
     * @return the parsed size, or {@code null} if unparseable
     */
    static TerminalSize parseSttySize(String output) {
        if (output == null) return null;
        var parts = output.trim().split("\\s+");
        if (parts.length == 2) {
            try {
                return new TerminalSize(Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static TerminalSize queryTerminalSize() {
        // 0. No real console (e.g. Maven Surefire) — skip both ESC[18t and stty.
        if (System.console() == null) return new TerminalSize(80, 24);
        // 1. Try ANSI ESC[18t query over System.in/System.out
        var size = TerminalSizeQuery.query(System.in, System.out, 500);
        if (size != null) return size;

        // 2. Last resort: stty size (local only, requires a real console)
        try {
            var pb = new ProcessBuilder("sh", "-c", "stty size </dev/tty").redirectErrorStream(true);
            var p = pb.start();
            // Read output in a separate thread so readAllBytes() doesn't
            // block forever if the process hangs (e.g. no /dev/tty in CI).
            var ref = new java.util.concurrent.atomic.AtomicReference<byte[]>();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    ref.set(p.getInputStream().readAllBytes());
                } catch (IOException ignored) {
                }
            });
            if (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
            }
            reader.join(200);
            var bytes = ref.get();
            if (bytes != null) {
                var parsed = parseSttySize(new String(bytes, StandardCharsets.UTF_8));
                if (parsed != null) {
                    return parsed;
                }
            }
        } catch (Exception e) {
            // fall through
        }

        // 3. Final fallback
        return new TerminalSize(80, 24);
    }

    /**
     * Switch to the alternate screen buffer and enable raw mode.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void enterPrivateMode() throws IOException {
        if (ownStty) setRawMode();
        writeRaw(AnsiCodes.ENTER_ALT_SCREEN.getBytes(StandardCharsets.UTF_8));
        writeRaw(AnsiCodes.HIDE_CURSOR.getBytes(StandardCharsets.UTF_8));
        writeRaw(AnsiCodes.CLEAR_SCREEN.getBytes(StandardCharsets.UTF_8));
        flush();
        inPrivateMode = true;
    }

    /**
     * Leave the alternate screen buffer and restore original terminal settings.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void exitPrivateMode() throws IOException {
        if (!inPrivateMode) return;
        writeRaw(AnsiCodes.SHOW_CURSOR.getBytes(StandardCharsets.UTF_8));
        writeRaw(AnsiCodes.EXIT_ALT_SCREEN.getBytes(StandardCharsets.UTF_8));
        flush();
        inPrivateMode = false;
        if (ownStty) restoreStty(originalStty);
    }

    /**
     * Clear the terminal screen.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void clearScreen() throws IOException {
        writeRaw(AnsiCodes.CLEAR_SCREEN.getBytes(StandardCharsets.UTF_8));
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
        writeRaw(AnsiCodes.cursorTo(row, column));
    }

    /**
     * Show or hide the terminal cursor.
     *
     * @param visible true to show, false to hide
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void setCursorVisible(boolean visible) throws IOException {
        writeRaw((visible ? AnsiCodes.SHOW_CURSOR : AnsiCodes.HIDE_CURSOR).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Write a single character to the terminal.
     *
     * @param c the character to write
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void putCharacter(char c) throws IOException {
        var bytes = Character.toString(c).getBytes(StandardCharsets.UTF_8);
        writeRaw(bytes);
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
        writeRaw(AnsiCodes.setForeground(color));
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
        writeRaw(AnsiCodes.setBackground(color));
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
        writeRaw(AnsiCodes.enable(sgr));
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
        writeRaw(AnsiCodes.disable(sgr));
    }

    /**
     * Reset all colors and SGR attributes to defaults.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void resetColorAndSGR() throws IOException {
        writeRaw(AnsiCodes.reset());
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
        if (fixedSize != null) return fixedSize;
        return lastSize;
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
        if (inputClosed && inputQueue.isEmpty()) return Optional.empty();
        return Optional.ofNullable(inputQueue.poll());
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
        try {
            return inputQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new KeyStroke(io.jterm.core.input.KeyType.EOF);
        }
    }

    /**
     * Poll for the next key stroke without blocking indefinitely.
     *
     * @param timeoutMillis maximum time to wait in milliseconds
     *
     * @return an Optional containing the key stroke, or empty if none available
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public Optional<KeyStroke> pollInput(long timeoutMillis) throws IOException {
        if (timeoutMillis <= 0) return pollInput();
        try {
            var ks = inputQueue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            return Optional.ofNullable(ks);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private void startReaderThread() {
        readerThread = Thread.ofVirtual().name("ansi-input-reader").start(() -> {
            try {
                while (!inputClosed && !Thread.currentThread().isInterrupted()) {
                    var ks = decoder.poll();
                    if (ks.isPresent()) {
                        inputQueue.put(ks.get());
                    } else {
                        if (in.available() == 0) {
                            Thread.sleep(1);
                        }
                    }
                }
            } catch (InterruptedException e) {
                // Normal shutdown
            } catch (IOException e) {
                inputQueue.offer(new KeyStroke(io.jterm.core.input.KeyType.EOF));
            }
            inputClosed = true;
        });
    }

    /**
     * Register a listener for terminal resize events.
     *
     * @param listener the listener to register
     */
    @Override
    public void addResizeListener(TerminalResizeListener listener) {
        resizeListeners.add(listener);
    }

    /**
     * Remove a previously registered resize listener.
     *
     * @param listener the listener to register
     */
    @Override
    public void removeResizeListener(TerminalResizeListener listener) {
        resizeListeners.remove(listener);
    }

    /**
     * Close the terminal and release resources.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void close() throws IOException {
        inputClosed = true;
        if (readerThread != null) readerThread.interrupt();
        exitPrivateMode();
    }

    /** Write raw bytes directly to the terminal output. */
    public void writeRaw(byte[] data) throws IOException {
        out.write(data);
    }

    /**
     * For testing: capture output written so far.
     *
     * @return all bytes written to the terminal so far, decoded as a string
     */
    public String getCapturedOutput() {
        return "";
    }
}
