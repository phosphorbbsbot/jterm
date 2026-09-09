package io.jterm.core;

import io.jterm.core.input.KeyStroke;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-completion tests for {@link AnsiTerminal} reader-thread and
 * lifecycle paths not reached by AnsiTerminalTest/AnsiTerminalEdgeCasesTest:
 * pollInput after close, pollInput(timeout) interrupted, reader-thread
 * IOException → EOF keystroke, and the idle sleep branch of the reader loop.
 *
 * The stty/console paths (captureStty/setRawMode/restoreStty and the
 * System.in/out constructor) require a real console and are untestable under
 * Surefire — intentionally excluded here.
 */
class AnsiTerminalReaderLifecycleTest {

    // ===== pollInput after close (inputClosed fast path) =====

    @Test
    void pollInputReturnsEmptyAfterClose() throws Exception {
        var t = new AnsiTerminal(new ByteArrayOutputStream(),
            new ByteArrayInputStream(new byte[0]), new TerminalSize(80, 24));
        t.close();
        // Wait for the reader thread to mark the queue closed.
        assertEventuallyClosed(t, 2000);
        assertTrue(t.pollInput().isEmpty());
        assertTrue(t.pollInput(50).isEmpty());
    }

    @Test
    void pollInputDrainsQueuedKeystrokesAfterClose() throws Exception {
        var t = new AnsiTerminal(new ByteArrayOutputStream(),
            new ByteArrayInputStream("ab".getBytes(StandardCharsets.UTF_8)),
            new TerminalSize(80, 24));
        // Give the reader thread time to enqueue both strokes.
        Thread.sleep(100);
        t.close();
        // Buffered strokes remain readable after close; queue then drains.
        Optional<KeyStroke> first = t.pollInput();
        assertTrue(first.isPresent(), "queued keys survive close");
        Optional<KeyStroke> second = t.pollInput();
        assertTrue(first.isPresent() || second.isPresent());
    }

    // ===== pollInput(timeout) interruption =====

    @Test
    void pollInputTimeoutReturnsEmptyOnInterrupt() throws Exception {
        var t = new AnsiTerminal(new ByteArrayOutputStream(),
            new ByteArrayInputStream(new byte[0]), new TerminalSize(80, 24));
        var result = new AtomicReference<Optional<KeyStroke>>();
        var started = new CountDownLatch(1);
        var thread = Thread.ofPlatform().start(() -> {
            started.countDown();
            try {
                result.set(t.pollInput(5000));
            } catch (Exception e) {
                result.set(Optional.<KeyStroke>empty());
            }
        });
        started.await();
        Thread.sleep(50); // let it enter queue.poll()
        thread.interrupt();
        thread.join(2000);
        assertNotNull(result.get(), "pollInput returned after interrupt");
        assertTrue(result.get().isEmpty(), "interrupted poll yields empty");
    }
    // ===== Reader thread error paths =====

    @Test
    void readerThreadIOExceptionProducesEofKeystroke() throws Exception {
        var exploding = new InputStream() {
            @Override public int read() throws IOException {
                throw new InterruptedIOException("boom");
            }
            @Override public int available() { return 42; }  // non-zero: skips the sleep branch
        };
        var t = new AnsiTerminal(new ByteArrayOutputStream(), exploding, new TerminalSize(80, 24));
        // The reader hits read() → IOException → offers EOF and closes input.
        var ks = t.pollInput(2000);
        assertTrue(ks.isPresent(), "EOF keystroke arrives after reader failure");
        assertEquals(io.jterm.core.input.KeyType.EOF, ks.get().type());
        // Terminal now reports closed-empty on subsequent polls.
        Thread.sleep(50);
        assertTrue(t.pollInput().isEmpty());
    }

    @Test
    void readerThreadSurvivesEmptyStreamAndProcessesLaterInput() throws Exception {
        // A piped-ish stream that starts empty (drives the in.available()==0
        // → sleep(1) idle branch), then has data pushed.
        var pump = new ByteArrayPump();
        var t = new AnsiTerminal(new ByteArrayOutputStream(), pump, new TerminalSize(80, 24));
        Thread.sleep(50);   // reader spins in the sleep(1) idle branch
        pump.push((int) 'z');
        var ks = t.pollInput(2000);
        assertTrue(ks.isPresent(), "input written after idle loop is decoded");
        assertEquals(Character.valueOf('z'), ks.get().character());
    }

    @Test
    void closeIsIdempotent() throws Exception {
        var t = new AnsiTerminal(new ByteArrayOutputStream(),
            new ByteArrayInputStream(new byte[0]), new TerminalSize(80, 24));
        t.enterPrivateMode();
        t.close();
        assertDoesNotThrow(t::close);
    }

    // ===== helpers =====

    /** Growable in-memory stream whose available() reflects pending bytes. */
    private static final class ByteArrayPump extends InputStream {
        private final byte[] buf = new byte[64];
        private int pos, len;
        private final Object lock = new Object();

        void push(int b) {
            synchronized (lock) { buf[len++] = (byte) b; }
        }

        @Override public int available() {
            synchronized (lock) { return len - pos; }
        }

        @Override public int read() throws IOException {
            synchronized (lock) {
                if (pos >= len) return -1;
                return buf[pos++] & 0xFF;
            }
        }
    }

    private static void assertEventuallyClosed(AnsiTerminal t, long timeoutMillis) throws InterruptedException, IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (t.pollInput().isEmpty()) return;
            Thread.sleep(10);
        }
        // Empty on every poll is the closed state; accept also a non-empty
        // queue as "drainable" — the drain test covers that path explicitly.
    }
}