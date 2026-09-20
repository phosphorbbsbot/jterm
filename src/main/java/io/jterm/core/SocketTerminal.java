package io.jterm.core;

import io.jterm.core.input.InputDecoder;
import io.jterm.core.input.KeyStroke;
import io.jterm.style.AnsiCodes;
import io.jterm.style.Color;
import io.jterm.style.Cp437;
import io.jterm.style.SGR;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Terminal implementation that wraps a network socket's InputStream and OutputStream.
 * Used for remote terminal sessions (SSH/Telnet) in the BBS project.
 */
public class SocketTerminal implements Terminal {

    private volatile InputStream in;
    private volatile OutputStream out;
    private volatile InputDecoder decoder;
    private final TerminalSize fixedSize;
    private volatile TerminalSize currentSize;
    private final List<TerminalResizeListener> resizeListeners = new CopyOnWriteArrayList<>();
    private volatile boolean cp437Mode = false;

    // --- Blocking input pipeline ---
    // A dedicated reader thread blocks on the InputStream, decodes keystrokes
    // via InputDecoder, and pushes them into this queue. This lets pollInput(timeout)
    // use BlockingQueue.poll() for true blocking — sub-millisecond input latency
    // instead of the old 16ms sleep loop.
    private final LinkedBlockingQueue<KeyStroke> inputQueue = new LinkedBlockingQueue<>(256);
    private volatile Thread readerThread;
    private volatile boolean inputClosed = false;
    private volatile boolean inPrivateMode = false;

    /**
     * Construct a terminal from the socket's input and output streams, with an explicit size.
     * This is the constructor used for testing and for protocols where the terminal size is
     * negotiated externally (e.g. Telnet NAWS or SSH pty-req).
     *
     * @param in   the socket input stream (raw bytes from the remote client)
     * @param out  the socket output stream (raw bytes to the remote client)
     * @param size the fixed terminal size
     */
    public SocketTerminal(InputStream in, OutputStream out, TerminalSize size) {
        this.in = in;
        this.out = new BufferedOutputStream(out, 65536);  // large enough for 200×100 UTF-8 full render
        this.decoder = new InputDecoder(in);
        this.fixedSize = size;
        this.currentSize = size;
        startReaderThread();
    }

    /**
     * Construct a terminal from the socket's streams and query the remote terminal for its size.
     * Falls back to 80×24 if discovery is unavailable.
     *
     * @param in  the socket input stream (raw bytes from the remote client)
     * @param out the socket output stream (raw bytes to the remote client)
     * @throws IOException if the size query fails or an I/O error occurs
     */
    public SocketTerminal(InputStream in, OutputStream out) throws IOException {
        this(in, out, queryTerminalSize(in, out));
    }

    private static TerminalSize queryTerminalSize(InputStream in, OutputStream out) throws IOException {
        // 1. Try ANSI ESC[18t query
        var size = TerminalSizeQuery.query(in, out, 500);
        if (size != null) return size;

        // 2. Final fallback — no stty on network connections
        return new TerminalSize(80, 24);
    }

    /**
     * Switch to the alternate screen buffer and enable raw mode.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void enterPrivateMode() throws IOException {
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
        if (cp437Mode) {
            byte b = Cp437.toCp437(c);
            if (b != -1) {
                writeRaw(new byte[]{b});
            } else {
                // No CP437 mapping — write space as fallback
                writeRaw(new byte[]{0x20});
            }
        } else {
            var bytes = Character.toString(c).getBytes(StandardCharsets.UTF_8);
            writeRaw(bytes);
        }
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
        return currentSize;
    }

    /**
     * Updates the terminal size (e.g. from Telnet NAWS). Notifies resize listeners.
     *
     * @param size the new terminal size
     */
    public void setTerminalSize(TerminalSize size) {
        var old = this.currentSize;
        this.currentSize = size;
        if (!size.equals(old)) {
            for (var listener : resizeListeners) {
                listener.onResized(size);
            }
        }
    }

    /**
     * Enable or disable CP437 output mode. When enabled, characters are
     * translated from Unicode to CP437 single-byte encoding on output.
     * Enable this for BBS clients that expect CP437 (e.g. MuffinTerm).
     *
     * @param cp437Mode true to enable CP437 translation
     */
    public void setCp437Mode(boolean cp437Mode) {
        this.cp437Mode = cp437Mode;
    }

    /**
     * Return whether CP437 output translation is enabled.
     *
     * @return true if cp437mode, false otherwise
     */
    public boolean isCp437Mode() {
        return cp437Mode;
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

    /**
     * Background reader thread: blocks on the InputStream, decodes keystrokes
     * via InputDecoder, and pushes them into the inputQueue. This decouples
     * the event loop from InputStream blocking — the event loop can use
     * BlockingQueue.poll(timeout) for near-instant input delivery.
     */
    private void startReaderThread() {
        readerThread = Thread.ofVirtual().name("socket-input-reader").start(this::readLoop);
    }

    private void readLoop() {
        try {
            while (!inputClosed && !Thread.currentThread().isInterrupted()) {
                var currentDecoder = decoder;
                var currentIn = in;
                var ks = currentDecoder != null ? currentDecoder.poll() : java.util.Optional.<KeyStroke>empty();
                if (ks.isPresent()) {
                    // Put WITHOUT honoring interrupts: an interrupt racing a
                    // put() would orphan a decoded keystroke across an
                    // attach() swap (lost key press). Interrupt status is
                    // re-asserted so the loop still notices shutdown.
                    boolean interrupted = false;
                    while (true) {
                        try {
                            inputQueue.put(ks.get());
                            break;
                        } catch (InterruptedException ie) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                } else {
                    // No data available — check if the stream is still open
                    // by probing available(). If the stream is exhausted, exit.
                    if (currentIn == null || currentIn.available() == 0) {
                        if (inputClosed) return;   // attach/close replaced the stream
                        // Brief sleep to avoid busy-spin when no data.
                        // The decoder.poll() already checks available(),
                        // so this is only hit when the stream is idle.
                        Thread.sleep(1);
                    }
                }
            }
        } catch (InterruptedException e) {
            // Normal shutdown (attach/close): flush any keystroke the decoder
            // already consumed — an interrupt landing between read() and
            // queue.put() would otherwise orphan the user's last key press
            // across a reattach.
            flushPendingDecodedKeys();
        } catch (IOException e) {
            // Stream closed or error — push EOF so readers can detect it
            if (!inputClosed) {
                inputQueue.offer(new KeyStroke(io.jterm.core.input.KeyType.EOF));
            }
        }
    }

    /**
     * Best-effort drain of keystrokes the decoder has already read from the
     * stream but not yet published to the input queue. Called when the reader
     * thread is shutting down so a concurrent attach() swap cannot lose keys
     * that were mid-decode.
     */
    private void flushPendingDecodedKeys() {
        try {
            var currentDecoder = decoder;
            if (currentDecoder == null) return;
            KeyStroke pending;
            while ((pending = currentDecoder.poll().orElse(null)) != null) {
                if (!inputQueue.offer(pending)) break;
            }
        } catch (IOException ignored) {
            // Stream already closed — nothing to flush.
        }
    }

    /**
     * Message written down the DISPLACED socket when an attach steals the
     * terminal from a live connection (session persistence plan, Phase 1
     * Task 1.7). Terminated with CRLF so raw terminal clients render it as
     * a standalone line.
     */
    public static final String STEAL_NOTICE =
            "-- Session attached from another connection. This terminal has been disconnected. --\r\n";

    /**
     * Attaches new socket streams to this terminal, replacing the streams of a
     * disconnected connection. Used for session re-attachment: the user's
     * screen state survives the disconnect, and the terminal's output is
     * re-bound to the new socket so the next refresh paints on it. The input
     * reader thread is restarted against the new stream.
     *
     * <p>This convenience overload delegates to {@link #attach(InputStream,
     * OutputStream, String)} with the default {@link #STEAL_NOTICE}. Writing
     * the notice is harmless on the plain detach→re-attach path: the old
     * socket is already disconnected, so the write silently fails and is
     * swallowed.</p>
     *
     * @param newIn  the new connection's input stream (not null)
     * @param newOut the new connection's output stream (not null)
     */
    public void attach(InputStream newIn, OutputStream newOut) {
        attach(newIn, newOut, STEAL_NOTICE);
    }

    /**
     * Attaches new socket streams to this terminal, optionally notifying the
     * DISPLACED connection first (session steal, session persistence plan,
     * Phase 1 Task 1.7). Before the streams are swapped — while the old
     * {@code OutputStream} reference is still reachable — {@code stealNotice}
     * is written down the OLD stream and flushed. Once swapped, the old
     * stream is unreachable, so this is the only place the displaced client
     * can be told. The stream is then closed cleanly so the displaced
     * connection sees an EOF instead of hanging.
     *
     * <p>Failure semantics: an I/O error while writing the notice (old
     * socket already broken) is swallowed — notification failure must never
     * block or fail the steal. Pass {@code null} to skip the notice.</p>
     *
     * @param newIn       the new connection's input stream (not null)
     * @param newOut      the new connection's output stream (not null)
     * @param stealNotice message written down the old stream before it is
     *                    dropped, or null for none
     */
    public synchronized void attach(InputStream newIn, OutputStream newOut, String stealNotice) {
        if (newIn == null) throw new NullPointerException("newIn");
        if (newOut == null) throw new NullPointerException("newOut");
        // Stop the previous reader thread and WAIT for it to exit: the old
        // reader may be mid-decode on the old stream. Interrupting and
        // immediately swapping the decoder used to orphan any keystroke the
        // old reader had decoded (or was decoding) — the user's key presses
        // around a reattach silently vanished ("Ctrl-T is very random").
        inputClosed = true;
        var previousReader = readerThread;
        if (previousReader != null) {
            previousReader.interrupt();
            try {
                previousReader.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Notify the displaced client BEFORE the swap: after `this.out` is
        // rebound below, the old OutputStream reference is unreachable and
        // the notice could never be delivered. A broken old socket throws
        // here — swallowed so the steal always proceeds (Task 1.7 edge case).
        if (stealNotice != null) {
            var abandonedOut = this.out;
            try {
                abandonedOut.write(stealNotice.getBytes(StandardCharsets.UTF_8));
                abandonedOut.flush();
                abandonedOut.close();
            } catch (IOException ignored) {
                // Old socket already dead: the steal must proceed regardless.
            }
        }
        this.in = newIn;
        this.out = new BufferedOutputStream(newOut, 65536);
        this.decoder = new InputDecoder(newIn);
        // Keystrokes already decoded and queued survive the swap — clearing
        // the queue dropped the user's last typed keys (Ctrl-T!) on every
        // reattach. The new reader continues feeding the same queue.
        inputClosed = false;
        readerThread = Thread.ofVirtual().name("socket-input-reader").start(this::readLoop);
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
     * Returns the terminal's current input stream.
     *
     * <p>Session-persistence support (jterm layer): after
     * {@link #attach(InputStream, OutputStream)} the returned stream is the
     * newly attached one, so callers can hand the live streams to another
     * terminal or session without touching private state.</p>
     *
     * @return the current input stream (never null)
     */
    public InputStream getInputStream() {
        return in;
    }

    /**
     * Returns the terminal's current output stream. This is the buffered
     * stream the terminal itself writes ANSI output through — data written
     * here interleaves with the terminal's own writes in order.
     *
     * <p>Session-persistence support (jterm layer): after
     * {@link #attach(InputStream, OutputStream)} writes through the returned
     * stream land on the newly attached connection.</p>
     *
     * @return the current buffered output stream (never null)
     */
    public OutputStream getOutputStream() {
        return out;
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
     * Stops the background reader thread without closing the underlying I/O
     * streams. Used during session-persistence reattach: the anonymous
     * session's reader must stop so the persistent session's reader (started
     * by {@link #attach}) can take over the same input stream exclusively.
     *
     * <p>After this call, the terminal will no longer deliver key events.
     * The input and output streams remain open for a subsequent
     * {@link #attach} call on a different terminal instance.</p>
     */
    public void stopReader() {
        inputClosed = true;
        var previousReader = readerThread;
        if (previousReader != null) {
            previousReader.interrupt();
        }
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
        in.close();
        out.close();
    }

    /**
     * Replace this terminal's output stream with a sink that discards all
     * writes. Used during session re-attachment: after the persistent session
     * takes over the connection's I/O, the dying anonymous event loop thread
     * must not write stale frames (e.g. a login-screen refresh) to the shared
     * channel on top of the restored session's screen.
     */
    public synchronized void silenceOutput() {
        this.out = new BufferedOutputStream(new OutputStream() {
            @Override public void write(int b) { /* discard */ }
            @Override public void write(byte[] b, int off, int len) { /* discard */ }
            @Override public void flush() { /* discard */ }
        }, 65536);
    }

    /** Write raw bytes directly to the terminal output. Thread-safe with respect to output. */
    public synchronized void writeRaw(byte[] data) throws IOException {
        out.write(data);
    }
}
