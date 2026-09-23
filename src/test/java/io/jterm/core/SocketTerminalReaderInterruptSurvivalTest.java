package io.jterm.core;

import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for "Ctrl-T dead after ESC" (joe/MuffinTerm, 2026-09-20):
 * a STRAY INTERRUPT on the reader thread must never wedge input delivery.
 *
 * <p>Two failure modes existed in {@link SocketTerminal#readLoop()}:
 * <ol>
 *   <li>The keystroke put() retry loop kept the interrupt flag SET across
 *       retries, so a reader whose flag was set when put() was entered
 *       threw InterruptedException instantly on every retry — livelock:
 *       the keystroke was logged as decoded but never entered the queue.</li>
 *   <li>The outer catch(InterruptedException) exited the reader on ANY
 *       interrupt, even a stray one while inputClosed was false — killing
 *       input delivery for the rest of the session.</li>
 * </ol>
 *
 * <p>The interrupt flag is NOT the reader's shutdown signal — the
 * {@code inputClosed} volatile flag is. The reader must therefore survive
 * stray interrupts and only honor inputClosed.</p>
 */
class SocketTerminalReaderInterruptSurvivalTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void readerSurvivesInterruptFlagSetBeforePut() throws Exception {
        var in = new ByteArrayInputStream(new byte[]{0x14}); // Ctrl-T
        var out = new java.io.ByteArrayOutputStream();
        var terminal = new SocketTerminal(in, out, new TerminalSize(80, 24));

        // Set the interrupt flag on the reader thread BEFORE it processes
        // the keystroke — simulates a stray interrupt (e.g. landed during
        // the ESC-window sleep in InputDecoder.readEscapeSequence). With
        // the old retry loop this livelocks: put() throws instantly on
        // every retry and the keystroke never reaches the queue.
        Thread.sleep(100); // let the reader start and block on the idle path
        terminal.readerThreadForTest().interrupt();

        long deadline = System.currentTimeMillis() + 3000;
        Optional<KeyStroke> ks = Optional.empty();
        while (ks.isEmpty() && System.currentTimeMillis() < deadline) {
            ks = terminal.pollInput(50);
        }
        assertTrue(ks.isPresent(), "reader must deliver the keystroke even "
                + "when the interrupt flag was set before put()");
        assertEquals(KeyType.CHARACTER, ks.get().type());
        assertEquals('T', ks.get().character());
        assertTrue(ks.get().ctrl());
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void readerSurvivesStrayInterruptWhileStreamOpen() throws Exception {
        // A stream that delivers a byte only AFTER the interrupt, so the
        // interrupt lands while the reader is idle/blocked, not mid-put.
        var in = new DelayedByteStream();
        var out = new java.io.ByteArrayOutputStream();
        var terminal = new SocketTerminal(in, out, new TerminalSize(80, 24));

        // Let the reader spin up and reach its idle wait, then interrupt it.
        Thread.sleep(100);
        terminal.readerThreadForTest().interrupt();
        in.releaseByte(); // \x14 becomes available AFTER the interrupt

        long deadline = System.currentTimeMillis() + 3000;
        Optional<KeyStroke> ks = Optional.empty();
        while (ks.isEmpty() && System.currentTimeMillis() < deadline) {
            ks = terminal.pollInput(50);
        }
        assertTrue(ks.isPresent(), "a stray interrupt must not kill the "
                + "reader while the stream is still open (inputClosed=false)");
        assertEquals('T', ks.get().character());
    }

    /** Single-byte stream that yields its byte only after releaseByte(). */
    private static final class DelayedByteStream extends java.io.InputStream {
        private final java.util.concurrent.CountDownLatch released =
                new java.util.concurrent.CountDownLatch(1);
        private volatile boolean done;

        void releaseByte() { released.countDown(); }

        @Override
        public int read() {
            try {
                if (!released.await(5, TimeUnit.SECONDS)) return -1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
            if (done) return -1;
            done = true;
            return 0x14;
        }

        @Override
        public int available() {
            return released.getCount() == 0 && !done ? 1 : 0;
        }
    }
}