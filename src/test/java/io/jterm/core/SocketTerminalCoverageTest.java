package io.jterm.core;

import io.jterm.core.input.KeyType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-completion tests for {@link SocketTerminal}: pollInput after
 * close, pollInput(timeout) interruption, reader-loop null-decoder/idle/
 * IOException branches, attach steal-notice failure tolerance, stopReader,
 * and silenceOutput. Complements SocketTerminalTest/SocketTerminalAttachTest.
 */
class SocketTerminalCoverageTest {

    private SocketTerminal terminal(InputStream in, OutputStream out) {
        return new SocketTerminal(in, out, new TerminalSize(80, 24));
    }

    // ===== pollInput paths =====

    @Test
    void pollInputReturnsEmptyAfterStopReader() throws Exception {
        var t = terminal(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        t.stopReader();
        assertTrue(t.pollInput().isEmpty());
        assertTrue(t.pollInput(20).isEmpty());
    }

    @Test
    void pollInputTimeoutReturnsEmptyOnInterrupt() throws Exception {
        var t = terminal(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        var result = new AtomicReference<Optional<io.jterm.core.input.KeyStroke>>();
        var started = new CountDownLatch(1);
        var thread = Thread.ofPlatform().start(() -> {
            started.countDown();
            try {
                result.set(t.pollInput(5000));
            } catch (Exception e) {
                result.set(Optional.empty());
            }
        });
        started.await();
        Thread.sleep(50);
        thread.interrupt();
        thread.join(2000);
        assertNotNull(result.get(), "poll returned after interrupt");
        assertTrue(result.get().isEmpty(), "interrupted poll yields empty");
    }

    // ===== reader-loop paths =====

    @Test
    void readerLoopIOExceptionProducesEof() throws Exception {
        var exploding = new InputStream() {
            @Override public int read() throws IOException { throw new InterruptedIOException("boom"); }
            @Override public int available() { return 1; }  // non-zero: bypasses idle sleep
        };
        var t = terminal(exploding, new ByteArrayOutputStream());
        var ks = t.pollInput(2000);
        assertTrue(ks.isPresent(), "EOF keystroke after reader failure");
        assertEquals(KeyType.EOF, ks.get().type());
    }

    @Test
    void readerLoopSurvivesIdleAndProcessesLaterInput() throws Exception {
        var pump = new SocketTerminalReaderLifecycleHelpers.Pump();
        var t = terminal(pump, new ByteArrayOutputStream());
        Thread.sleep(50);   // reader idles (available()==0 → sleep(1) branch)
        pump.push((int) 'q');
        var ks = t.pollInput(2000);
        assertTrue(ks.isPresent(), "input after idle is decoded");
        assertEquals('q', ks.get().character());
        t.close();
    }

    // ===== attach / steal-notice =====

    @Test
    void attachWithNullStealNoticeSkipsOldStreamWrite() throws Exception {
        var t = terminal(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        var secondOut = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> t.attach(new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)), secondOut, null));
        // No steal notice on the old stream: it never received a write.
        t.close();
    }

    @Test
    void attachSwallowsBrokenOldStreamOnNotice() throws Exception {
        var t = terminal(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        var broken = new OutputStream() {
            @Override public void write(int b) throws IOException { throw new IOException("dead"); }
            @Override public void write(byte[] b, int off, int len) throws IOException { throw new IOException("dead"); }
            @Override public void flush() throws IOException { throw new IOException("dead"); }
        };
        var secondOut = new ByteArrayOutputStream();
        // Steal notice write fails — must be swallowed, swap proceeds.
        assertDoesNotThrow(() -> t.attach(new ByteArrayInputStream(new byte[0]), secondOut, "BYE"));
        t.writeRaw("after".getBytes(StandardCharsets.UTF_8));
        t.flush();
        assertTrue(secondOut.toString(StandardCharsets.UTF_8).contains("after"), "output flows to new stream");
        t.close();
    }

    @Test
    void attachDeliversNoticeThenSwaps() throws Exception {
        var firstOut = new ByteArrayOutputStream();
        var t = terminal(new ByteArrayInputStream(new byte[0]), firstOut);
        var secondOut = new ByteArrayOutputStream();
        t.attach(new ByteArrayInputStream(new byte[0]), secondOut, "STOLEN");
        String first = firstOut.toString(StandardCharsets.UTF_8);
        assertTrue(first.contains("STOLEN"), "displaced client sees the notice");
        t.close();
    }

    // ===== silenceOutput =====

    @Test
    void silenceOutputDiscardsWrites() throws Exception {
        var captured = new ByteArrayOutputStream();
        var t = terminal(new ByteArrayInputStream(new byte[0]), captured);
        t.silenceOutput();
        t.writeRaw("GHOST".getBytes(StandardCharsets.UTF_8));
        t.flush();
        assertFalse(captured.toString(StandardCharsets.UTF_8).contains("GHOST"),
            "silenced output discards frames");
        t.close();
    }

    // ===== close idempotence =====

    @Test
    void closeIsIdempotent() throws Exception {
        var t = terminal(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        t.enterPrivateMode();
        t.close();
        assertDoesNotThrow(t::close);
    }
}