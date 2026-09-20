package io.jterm.core.input;

import org.junit.jupiter.api.Test;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for "Ctrl-T after ESC is swallowed" (surfaced by the
 * chat overlay reopen flow: ESC closes the overlay, user immediately hits
 * Ctrl-T to reopen). The decoder's escape-sequence wait treats any byte that
 * arrives within the escape window as the sequence's second byte; a
 * standalone control byte like 0x14 (Ctrl-T) is then dropped as UNKNOWN.
 * Contract: ESC followed by a CONTROL byte must deliver the ESCAPE *and*
 * the control keystroke — never swallow it into the escape parser.
 */
class InputDecoderCtrlAfterEscTest {

    private record Rig(InputDecoder decoder, PipedOutputStream feed) {}

    private Rig newRig() throws Exception {
        var in = new PipedInputStream(8192);
        var feed = new PipedOutputStream(in);
        return new Rig(new InputDecoder(in), feed);
    }

    private static void waitDecodeReady() {
        try {
            Thread.sleep(120); // escape window (50ms) must have expired or data arrived
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void escThenCtrlTDeliversBothKeys() throws Exception {
        var rig = newRig();
        // ESC first — decoder enters its escape-sequence wait
        rig.feed().write(0x1b);
        rig.feed().flush();
        Thread.sleep(80); // let the 50ms window expire: ESC is standalone

        // Ctrl-T right after the ESC — a NEW press, not part of any sequence
        rig.feed().write(0x14);
        rig.feed().flush();
        waitDecodeReady();

        var first = rig.decoder().poll().orElseThrow();
        assertEquals(KeyType.ESCAPE, first.type(), "standalone ESC must deliver ESCAPE");

        var second = rig.decoder().poll().orElseThrow();
        assertEquals(KeyType.CHARACTER, second.type(),
                "Ctrl-T after ESC must not be swallowed by the escape parser");
        assertTrue(second.ctrl(), "and it must keep its Ctrl modifier");
        assertEquals('T', Character.toUpperCase(second.character()));
        rig.feed().close();
    }

    @Test
    void escThenUnknownByteDoesNotEatSubsequentKeys() throws Exception {
        // The old parser read the "second" byte even when it was garbage and
        // DROPPED it (UNKNOWN). A control byte in the escape window must be
        // pushed back and delivered on the next poll instead.
        var rig = newRig();
        rig.feed().write(0x1b);
        rig.feed().write(0x14); // Ctrl-T lands INSIDE the escape window
        rig.feed().flush();
        waitDecodeReady();

        var first = rig.decoder().poll().orElseThrow();
        // ESC must still deliver (the 0x14 was not a valid sequence byte)
        // AND the 0x14 must come back as its own Ctrl-T on a following poll.
        var second = rig.decoder().poll().orElse(null);
        if (first.type() == KeyType.ESCAPE) {
            assertNotNull(second, "the control byte after ESC must not be dropped");
            assertEquals(KeyType.CHARACTER, second.type());
            assertTrue(second.ctrl(), "pushed-back byte keeps Ctrl modifier");
        } else {
            // Decoder chose to deliver Ctrl-T first — ESC must follow.
            var third = rig.decoder().poll().orElseThrow();
            assertEquals(KeyType.ESCAPE, third.type());
        }
        rig.feed().close();
    }
}