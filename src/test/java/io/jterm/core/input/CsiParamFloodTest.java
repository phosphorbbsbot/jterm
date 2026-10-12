package io.jterm.core.input;

import io.jterm.core.input.KeyType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security regression: the CSI parameter accumulator must be capped. A
 * hostile networked client can stream an unterminated escape sequence
 * (ESC [ + endless digits); pre-fix, readCsiSequence buffered every byte
 * in an unbounded StringBuilder (~6.9MB heap per 2MB sent) — an OOM
 * denial-of-service vector on multi-caller BBS deployments. The cap makes
 * the decoder abandon the sequence after a bounded number of param bytes.
 */
public class CsiParamFloodTest {

    /** Counts bytes the decoder pulls from the stream. */
    static final class CountingIn extends InputStream {
        private final ByteArrayInputStream in;
        long consumed = 0;
        CountingIn(byte[] data) { this.in = new ByteArrayInputStream(data); }
        @Override public int read() throws IOException {
            int b = in.read();
            if (b >= 0) consumed++;
            return b;
        }
        @Override public int available() throws IOException { return in.available(); }
    }

    @Test
    void unterminatedCsiParamsDoNotConsumeUnboundedInput() throws Exception {
        byte[] flood = new byte[1_000_000];
        flood[0] = 0x1b; flood[1] = '[';
        Arrays.fill(flood, 2, flood.length, (byte) '0');
        var in = new CountingIn(flood);
        var ks = new InputDecoder(in).poll();
        assertTrue(ks.isPresent(), "poll must return a (safe) keystroke");
        assertNotEquals(KeyType.CHARACTER, ks.orElseThrow().type(),
            "flooded params must not decode as user input");
        // ESC + '[' + capped params (64) = at most ~66 bytes consumed for
        // this keystroke. Pre-fix this consumed the FULL 1M.
        assertTrue(in.consumed <= 128,
            "decoder consumed " + in.consumed + " bytes for one keystroke — unbounded param accumulation");
    }

    @Test
    void legitimateLongCsiSequencesStillDecode() throws Exception {
        String seq = "\u001B[1;2;3;4;5;6;7;8;9;10;11;12;13;14;15;16R"; // 16 params
        var ks = new InputDecoder(new ByteArrayInputStream(seq.getBytes())).poll();
        assertTrue(ks.isPresent(), "valid multi-param CSI must still parse");
    }
}
