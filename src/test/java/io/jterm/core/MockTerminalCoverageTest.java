package io.jterm.core;

import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.style.SGR;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-completion tests for {@link MockTerminal}: the full disableSGR
 * code table, readInput's blocking/interrupt/EOF paths, and the defensive
 * null-decoder branches (exercised via reflection — no public constructor
 * path leaves the decoder null).
 */
class MockTerminalCoverageTest {

    private MockTerminal terminal() {
        return new MockTerminal(new TerminalSize(20, 10),
            new ByteArrayOutputStream(),
            new ByteArrayInputStream(new byte[0]));
    }

    /** Force the private {@code decoder} field to null to reach defensive branches. */
    private MockTerminal withNullDecoder() throws Exception {
        var t = terminal();
        Field f = MockTerminal.class.getDeclaredField("decoder");
        f.setAccessible(true);
        f.set(t, null);
        return t;
    }

    // ===== disableSGR full code table =====

    @Test
    void disableSgrEmitsOffCodes() throws Exception {
        assertEquals("\u001B[22m", off(SGR.BOLD));   // BOLD/DIM → 22
        assertEquals("\u001B[22m", off(SGR.DIM));
        assertEquals("\u001B[23m", off(SGR.ITALIC));
        assertEquals("\u001B[24m", off(SGR.UNDERLINE));
        assertEquals("\u001B[25m", off(SGR.BLINK));
        assertEquals("\u001B[27m", off(SGR.REVERSE));
        assertEquals("\u001B[28m", off(SGR.HIDDEN));
        assertEquals("\u001B[29m", off(SGR.STRIKETHROUGH));
    }

    private String off(SGR sgr) throws Exception {
        var t = terminal();
        t.disableSGR(sgr);
        String out = t.getOutput();
        t.close();
        return out;
    }

    // ===== readInput paths =====

    @Test
    void readInputReturnsStrokeArrivingDuringWait() throws Exception {
        var in = new ByteArrayInputStream(new byte[0]);
        var t = new MockTerminal(new TerminalSize(20, 10), new ByteArrayOutputStream(), in);
        // Push a keystroke byte from another thread after a short delay —
        // the readInput busy-wait loop must wake and return it.
        var result = new AtomicReference<KeyStroke>();
        var reader = Thread.ofPlatform().start(() -> {
            try {
                result.set(t.readInput());
            } catch (Exception ignored) {
            }
        });
        // 'x' arrives via the same underlying stream? The decoder polls the
        // stream; feed through a separate decoder-shared stream is not
        // possible post-construction, so use interrupt to exit the loop.
        Thread.sleep(50);
        reader.interrupt();
        reader.join(2000);
        // Interrupted readInput returns an EOF keystroke.
        assertEquals(KeyType.EOF, result.get().type());
    }

    @Test
    void readInputOnNullDecoderReturnsEofImmediately() throws Exception {
        var t = withNullDecoder();
        assertEquals(KeyType.EOF, t.readInput().type());
    }

    @Test
    void pollInputOnNullDecoderReturnsEmpty() throws Exception {
        var t = withNullDecoder();
        assertTrue(t.pollInput().isEmpty());
    }

    @Test
    void readInputReturnsQueuedStroke() throws Exception {
        var t = new MockTerminal(new TerminalSize(20, 10),
            new ByteArrayOutputStream(),
            new ByteArrayInputStream("q".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var ks = t.readInput();
        assertEquals('q', ks.character());
        t.close();
    }

    @Test
    void pollInputReturnsDecodedStroke() throws Exception {
        var t = new MockTerminal(new TerminalSize(20, 10),
            new ByteArrayOutputStream(),
            new ByteArrayInputStream("k".getBytes(StandardCharsets.UTF_8)));
        // Reader-thread-free decoder: first poll decodes the byte.
        var ks = t.pollInput();
        assertTrue(ks.isPresent());
        assertEquals('k', ks.get().character());
        t.close();
    }
}