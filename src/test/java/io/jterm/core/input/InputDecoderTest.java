package io.jterm.core.input;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class InputDecoderTest {
    private KeyStroke decode(byte[] input) {
        var decoder = new InputDecoder(new ByteArrayInputStream(input));
        try {
            return decoder.poll().orElseThrow();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<KeyStroke> pollEmpty() {
        var decoder = new InputDecoder(new ByteArrayInputStream(new byte[0]));
        try {
            return decoder.poll();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- Existing tests (preserved) ----------

    @Test
    void arrowUp() {
        assertEquals(KeyType.ARROW_UP, decode("\033[A".getBytes()).type());
    }

    @Test
    void arrowDown() {
        assertEquals(KeyType.ARROW_DOWN, decode("\033[B".getBytes()).type());
    }

    @Test
    void enter() {
        assertEquals(KeyType.ENTER, decode("\r".getBytes()).type());
    }

    @Test
    void backspace() {
        assertEquals(KeyType.BACKSPACE, decode("\177".getBytes()).type());
    }

    @Test
    void tab() {
        assertEquals(KeyType.TAB, decode("\t".getBytes()).type());
    }

    @Test
    void simpleChar() {
        var ks = decode("A".getBytes());
        assertEquals(KeyType.CHARACTER, ks.type());
        assertEquals('A', ks.character());
    }

    @Test
    void ctrlC() {
        var ks = decode(new byte[]{3}); // Ctrl+C = 0x03
        assertEquals(KeyType.CHARACTER, ks.type());
        assertEquals('C', ks.character());
        assertTrue(ks.ctrl());
    }

    @Test
    void altX() {
        var ks = decode("\033x".getBytes());
        assertEquals(KeyType.CHARACTER, ks.type());
        assertEquals('x', ks.character());
        assertTrue(ks.alt());
    }

    @Test
    void f1Key() {
        assertEquals(KeyType.F1, decode("\033OP".getBytes()).type());
    }

    @Test
    void deleteKey() {
        assertEquals(KeyType.DELETE, decode("\033[3~".getBytes()).type());
    }

    @Test
    void pollReturnsEmptyWhenNoData() throws Exception {
        var decoder = new InputDecoder(new ByteArrayInputStream(new byte[0]));
        assertEquals(Optional.empty(), decoder.poll());
    }

    @Test
    void escapeStandalone() {
        var ks = decode(new byte[]{0x1b});
        assertEquals(KeyType.ESCAPE, ks.type());
    }

    @Test
    void ctrlHIsBackspace() {
        var ks = decode(new byte[]{0x08});
        assertEquals(KeyType.BACKSPACE, ks.type());
    }

    @Test
    void ctrlF() {
        var ks = decode(new byte[]{0x06});
        assertEquals(KeyType.CHARACTER, ks.type());
        assertEquals('F', ks.character());
        assertTrue(ks.ctrl());
    }

    @Test
    void ctrlE() {
        var ks = decode(new byte[]{0x05});
        assertEquals(KeyType.CHARACTER, ks.type());
        assertEquals('E', ks.character());
        assertTrue(ks.ctrl());
    }

    @Test
    void ctrlJIsNotEnter() {
        var ks = decode(new byte[]{0x0A});
        assertEquals(KeyType.ENTER, ks.type());
    }

    @Test
    void altF() {
        var ks = decode("\033f".getBytes());
        assertEquals(KeyType.CHARACTER, ks.type());
        assertEquals('f', ks.character());
        assertTrue(ks.alt());
        assertFalse(ks.ctrl());
    }

    @Test
    void altH() {
        var ks = decode("\033h".getBytes());
        assertEquals(KeyType.CHARACTER, ks.type());
        assertEquals('h', ks.character());
        assertTrue(ks.alt());
    }

    // ---------- New tests: ESC key ----------

    @Test
    void escapeStandaloneWhenInputDrained() {
        // Single ESC byte: readEscapeSequence sleeps 5ms then sees no more data -> ESCAPE
        var ks = decode(new byte[]{0x1b});
        assertEquals(KeyType.ESCAPE, ks.type());
        assertFalse(ks.ctrl());
        assertFalse(ks.alt());
    }

    @Test
    void escapeFollowedByNonLetterProducesUnknown() {
        // ESC + space (0x20) is the boundary: 32 is >= 32 && < 127 so it's treated as Alt+space
        var ks = decode(new byte[]{0x1b, 0x20});
        assertEquals(KeyType.CHARACTER, ks.type());
        assertEquals(' ', ks.character());
        assertTrue(ks.alt());
    }

    @Test
    void escapeFollowedByControlCharDeliversBoth() {
        // ESC + 0x01 (Ctrl+A byte): the control byte is a NEW key press, not
        // part of an escape sequence. It must be pushed back and delivered on
        // the next poll — not dropped as UNKNOWN (the old behavior ate keys).
        var decoder = new InputDecoder(new ByteArrayInputStream(new byte[]{0x1b, 0x01}));
        try {
            var first = decoder.poll().orElseThrow();
            assertEquals(KeyType.ESCAPE, first.type());
            var second = decoder.poll().orElseThrow();
            assertEquals(KeyType.CHARACTER, second.type());
            assertTrue(second.ctrl(), "Ctrl+A keeps its modifier");
            assertEquals('A', second.character());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void escapeAtEofReturnsEscape() {
        // ByteArrayInputStream returns -1 at end: ESC then EOF
        var decoder = new InputDecoder(new ByteArrayInputStream(new byte[]{0x1b}));
        try {
            var ks = decoder.poll().orElseThrow();
            assertEquals(KeyType.ESCAPE, ks.type());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- New tests: Alt+key ----------

    @Test
    void altCapitalLetter() {
        var ks = decode("\033X".getBytes());
        assertEquals(KeyType.CHARACTER, ks.type());
        assertEquals('X', ks.character());
        assertTrue(ks.alt());
        assertFalse(ks.ctrl());
        assertFalse(ks.shift());
    }

    @Test
    void altDigit() {
        var ks = decode("\0335".getBytes());
        assertEquals(KeyType.CHARACTER, ks.type());
        assertEquals('5', ks.character());
        assertTrue(ks.alt());
    }

    @Test
    void altLowercaseLetter() {
        var ks = decode("\033z".getBytes());
        assertEquals(KeyType.CHARACTER, ks.type());
        assertEquals('z', ks.character());
        assertTrue(ks.alt());
    }

    @Test
    void altTilde() {
        // '~' is >= 32 && < 127
        var ks = decode(new byte[]{0x1b, '~'});
        assertEquals(KeyType.CHARACTER, ks.type());
        assertEquals('~', ks.character());
        assertTrue(ks.alt());
    }

    // ---------- New tests: CSI sequences ----------

    @Test
    void arrowRight() {
        assertEquals(KeyType.ARROW_RIGHT, decode("\033[C".getBytes()).type());
    }

    @Test
    void arrowLeft() {
        assertEquals(KeyType.ARROW_LEFT, decode("\033[D".getBytes()).type());
    }

    @Test
    void homeCsiH() {
        assertEquals(KeyType.HOME, decode("\033[H".getBytes()).type());
    }

    @Test
    void homeCsiTilde1() {
        assertEquals(KeyType.HOME, decode("\033[1~".getBytes()).type());
    }

    @Test
    void homeCsiTilde7() {
        assertEquals(KeyType.HOME, decode("\033[7~".getBytes()).type());
    }

    @Test
    void endCsiF() {
        assertEquals(KeyType.END, decode("\033[F".getBytes()).type());
    }

    @Test
    void endCsiTilde4() {
        assertEquals(KeyType.END, decode("\033[4~".getBytes()).type());
    }

    @Test
    void endCsiTilde8() {
        assertEquals(KeyType.END, decode("\033[8~".getBytes()).type());
    }

    @Test
    void insertKey() {
        assertEquals(KeyType.INSERT, decode("\033[2~".getBytes()).type());
    }

    @Test
    void pageUp() {
        assertEquals(KeyType.PAGE_UP, decode("\033[5~".getBytes()).type());
    }

    @Test
    void pageDown() {
        assertEquals(KeyType.PAGE_DOWN, decode("\033[6~".getBytes()).type());
    }

    @Test
    void shiftTabMappedToTab() {
        assertEquals(KeyType.TAB, decode("\033[Z".getBytes()).type());
    }

    @Test
    void f1CsiTilde() {
        assertEquals(KeyType.F1, decode("\033[11~".getBytes()).type());
    }

    @Test
    void f2CsiTilde() {
        assertEquals(KeyType.F2, decode("\033[12~".getBytes()).type());
    }

    @Test
    void f3CsiTilde() {
        assertEquals(KeyType.F3, decode("\033[13~".getBytes()).type());
    }

    @Test
    void f4CsiTilde() {
        assertEquals(KeyType.F4, decode("\033[14~".getBytes()).type());
    }

    @Test
    void f5CsiTilde() {
        assertEquals(KeyType.F5, decode("\033[15~".getBytes()).type());
    }

    @Test
    void f6CsiTilde() {
        assertEquals(KeyType.F6, decode("\033[17~".getBytes()).type());
    }

    @Test
    void f7CsiTilde() {
        assertEquals(KeyType.F7, decode("\033[18~".getBytes()).type());
    }

    @Test
    void f8CsiTilde() {
        assertEquals(KeyType.F8, decode("\033[19~".getBytes()).type());
    }

    @Test
    void f9CsiTilde() {
        assertEquals(KeyType.F9, decode("\033[20~".getBytes()).type());
    }

    @Test
    void f10CsiTilde() {
        assertEquals(KeyType.F10, decode("\033[21~".getBytes()).type());
    }

    @Test
    void f11CsiTilde() {
        assertEquals(KeyType.F11, decode("\033[23~".getBytes()).type());
    }

    @Test
    void f12CsiTilde() {
        assertEquals(KeyType.F12, decode("\033[24~".getBytes()).type());
    }

    @Test
    void unknownCsiTilde() {
        assertEquals(KeyType.UNKNOWN, decode("\033[99~".getBytes()).type());
    }

    @Test
    void unknownCsiFinalByte() {
        assertEquals(KeyType.UNKNOWN, decode("\033[g".getBytes()).type());
    }

    @Test
    void csiSequenceWithQuestionMark() {
        // '?' is allowed in params; final byte 'h' -> UNKNOWN (not handled specifically by mapCsi)
        assertEquals(KeyType.UNKNOWN, decode("\033[?25h".getBytes()).type());
    }

    @Test
    void csiSequenceWithSemicolon() {
        // ESC[2;3~ = Insert with Alt modifier (2=Insert, 3=Alt)
        var ks = decode("\033[2;3~".getBytes());
        assertEquals(KeyType.INSERT, ks.type());
        assertTrue(ks.alt());
        assertFalse(ks.shift());
        assertFalse(ks.ctrl());
    }

    @Test
    void csiSequenceTruncatedAtEofReturnsUnknown() {
        // ESC [ then EOF
        var decoder = new InputDecoder(new ByteArrayInputStream(new byte[]{0x1b, '['}));
        try {
            var ks = decoder.poll().orElseThrow();
            assertEquals(KeyType.UNKNOWN, ks.type());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void csiSequenceWithInvalidCharReturnsUnknown() {
        // A control char (0x01) in the middle of params is not 0-9, ;, ?, or 0x40-0x7e -> UNKNOWN
        assertEquals(KeyType.UNKNOWN, decode(new byte[]{0x1b, '[', 0x01, 'A'}).type());
    }

    // ---------- New tests: SS3 sequences (ESC O P/Q/R/S) ----------

    @Test
    void ss3F1() {
        assertEquals(KeyType.F1, decode("\033OP".getBytes()).type());
    }

    @Test
    void ss3F2() {
        assertEquals(KeyType.F2, decode("\033OQ".getBytes()).type());
    }

    @Test
    void ss3F3() {
        assertEquals(KeyType.F3, decode("\033OR".getBytes()).type());
    }

    @Test
    void ss3F4() {
        assertEquals(KeyType.F4, decode("\033OS".getBytes()).type());
    }

    @Test
    void ss3UnknownChar() {
        assertEquals(KeyType.UNKNOWN, decode("\033OX".getBytes()).type());
    }

    @Test
    void ss3AtEofReturnsUnknown() {
        var decoder = new InputDecoder(new ByteArrayInputStream(new byte[]{0x1b, 'O'}));
        try {
            var ks = decoder.poll().orElseThrow();
            assertEquals(KeyType.UNKNOWN, ks.type());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- New tests: Ctrl+A through Ctrl+Z ----------

    static Stream<Arguments> ctrlKeys() {
        // byte 1 -> 'A', ..., byte 26 -> 'Z'
        return Stream.of(
            Arguments.of(1, 'A'),
            Arguments.of(2, 'B'),
            Arguments.of(3, 'C'),
            Arguments.of(4, 'D'),
            Arguments.of(5, 'E'),
            Arguments.of(6, 'F'),
            Arguments.of(7, 'G'),
            Arguments.of(8, 'H'),  // 0x08 also handled as BACKSPACE earlier — but ctrl branch is after 0x7f/0x08 check
            Arguments.of(9, 'I'),   // 0x09 = TAB; handled before ctrl branch
            Arguments.of(10, 'J'),  // 0x0A = \n -> ENTER, handled before ctrl branch
            Arguments.of(11, 'K'),
            Arguments.of(12, 'L'),
            Arguments.of(13, 'M'),  // 0x0D = \r -> ENTER, handled before ctrl branch
            Arguments.of(14, 'N'),
            Arguments.of(15, 'O'),
            Arguments.of(16, 'P'),
            Arguments.of(17, 'Q'),
            Arguments.of(18, 'R'),
            Arguments.of(19, 'S'),
            Arguments.of(20, 'T'),
            Arguments.of(21, 'U'),
            Arguments.of(22, 'V'),
            Arguments.of(23, 'W'),
            Arguments.of(24, 'X'),
            Arguments.of(25, 'Y'),
            Arguments.of(26, 'Z')
        );
    }

    @ParameterizedTest
    @MethodSource("ctrlKeys")
    void ctrlKeyMapsToLetter(int byteVal, char expected) {
        var ks = decode(new byte[]{(byte) byteVal});
        // Note: 0x08 -> BACKSPACE, 0x09 -> TAB, 0x0A/\n -> ENTER, 0x0D/\r -> ENTER
        // These are intercepted before the ctrl branch, so only check the letter-mapping for the rest.
        if (byteVal == 8) {
            assertEquals(KeyType.BACKSPACE, ks.type());
        } else if (byteVal == 9) {
            assertEquals(KeyType.TAB, ks.type());
        } else if (byteVal == 10 || byteVal == 13) {
            assertEquals(KeyType.ENTER, ks.type());
        } else {
            assertEquals(KeyType.CHARACTER, ks.type(), "byte " + byteVal);
            assertEquals(expected, ks.character());
            assertTrue(ks.ctrl(), "byte " + byteVal);
            assertFalse(ks.alt());
            assertFalse(ks.shift());
        }
    }

    @Test
    void ctrlA() {
        var ks = decode(new byte[]{1});
        assertEquals(KeyType.CHARACTER, ks.type());
        assertEquals('A', ks.character());
        assertTrue(ks.ctrl());
    }

    @Test
    void ctrlZ() {
        var ks = decode(new byte[]{26});
        assertEquals(KeyType.CHARACTER, ks.type());
        assertEquals('Z', ks.character());
        assertTrue(ks.ctrl());
    }

    @Test
    void ctrlB() {
        var ks = decode(new byte[]{2});
        assertEquals('B', ks.character());
        assertTrue(ks.ctrl());
    }

    // ---------- New tests: Tab, Backspace, Enter variants ----------

    @Test
    void tabKey() {
        var ks = decode("\t".getBytes());
        assertEquals(KeyType.TAB, ks.type());
    }

    @Test
    void backspace0x7f() {
        var ks = decode(new byte[]{0x7f});
        assertEquals(KeyType.BACKSPACE, ks.type());
    }

    @Test
    void backspace0x08() {
        var ks = decode(new byte[]{0x08});
        assertEquals(KeyType.BACKSPACE, ks.type());
    }

    @Test
    void enterCrLf() {
        var ks = decode("\r\n".getBytes());
        assertEquals(KeyType.ENTER, ks.type());
    }

    @Test
    void enterCrNul() {
        var ks = decode(new byte[]{'\r', '\0'});
        assertEquals(KeyType.ENTER, ks.type());
    }

    @Test
    void enterStandaloneCr() {
        var ks = decode(new byte[]{'\r'});
        assertEquals(KeyType.ENTER, ks.type());
    }

    @Test
    void enterStandaloneLf() {
        var ks = decode(new byte[]{'\n'});
        assertEquals(KeyType.ENTER, ks.type());
    }

    @Test
    void crFollowedByOtherBytePushesBack() {
        // \r followed by 'a' (not \n or \0): the 'a' should be pushed back and returned next poll
        var decoder = new InputDecoder(new ByteArrayInputStream(new byte[]{'\r', 'a'}));
        try {
            var ks1 = decoder.poll().orElseThrow();
            assertEquals(KeyType.ENTER, ks1.type());
            var ks2 = decoder.poll().orElseThrow();
            assertEquals(KeyType.CHARACTER, ks2.type());
            assertEquals('a', ks2.character());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void crFollowedByMinusOneAtEof() {
        // \r at EOF: no following byte, sets skipNextNulOrLf
        var decoder = new InputDecoder(new ByteArrayInputStream(new byte[]{'\r'}));
        try {
            var ks = decoder.poll().orElseThrow();
            assertEquals(KeyType.ENTER, ks.type());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- New tests: NUL byte skipping ----------

    @Test
    void nulByteSkippedAlone() {
        // A single NUL byte — poll() should skip it and return empty (no more data)
        var decoder = new InputDecoder(new ByteArrayInputStream(new byte[]{0x00}));
        try {
            var result = decoder.poll();
            // After skipping NUL, it calls poll() recursively which sees no data -> empty
            assertEquals(Optional.empty(), result);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void nulByteSkippedBeforeCharacter() {
        var decoder = new InputDecoder(new ByteArrayInputStream(new byte[]{0x00, 'x'}));
        try {
            var ks = decoder.poll().orElseThrow();
            assertEquals(KeyType.CHARACTER, ks.type());
            assertEquals('x', ks.character());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void multipleNulBytesSkipped() {
        var decoder = new InputDecoder(new ByteArrayInputStream(new byte[]{0x00, 0x00, 0x00, 'Z'}));
        try {
            var ks = decoder.poll().orElseThrow();
            assertEquals(KeyType.CHARACTER, ks.type());
            assertEquals('Z', ks.character());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- New tests: skipNextNulOrLf flag behavior ----------

    @Test
    void skipNextNulOrLfSkipsLfInNextPacket() {
        // \r arrives alone (no following byte available), then \n in next poll
        // First poll: \r -> ENTER, sets skipNextNulOrLf=true
        // Second poll: \n -> skipped (flag set), returns empty (no more data)
        var decoder = new InputDecoder(new ByteArrayInputStream(new byte[]{'\r', '\n'}));
        try {
            // ByteArrayInputStream.available() returns remaining bytes, so \n is available
            // -> consumed in the same poll, returns ENTER
            var ks = decoder.poll().orElseThrow();
            assertEquals(KeyType.ENTER, ks.type());
            var next = decoder.poll();
            assertEquals(Optional.empty(), next);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void skipNextNulOrLfSkipsNulInNextPacket() {
        // \r then \0: \r alone has no available follow-up (simulated), \0 arrives next
        // With ByteArrayInputStream, available() > 0 so \0 consumed immediately.
        var decoder = new InputDecoder(new ByteArrayInputStream(new byte[]{'\r', '\0'}));
        try {
            var ks = decoder.poll().orElseThrow();
            assertEquals(KeyType.ENTER, ks.type());
            assertEquals(Optional.empty(), decoder.poll());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void skipNextNulOrLfFallsThroughForNonLfByte() {
        // \r alone (sets flag because no follow-up byte available), then 'b' arrives in next poll:
        // flag is cleared, 'b' processed as CHARACTER (not \n/\0, so fall-through).
        // We need a stream where available() reports > 0 at the top-level poll() check (to enter)
        // but 0 when the \r branch checks for a follow-up byte.
        var slow = new java.io.InputStream() {
            final byte[] data = {'\r', 'b'};
            int pos = 0;
            int callCount = 0;
            @Override
            public int read() {
                if (pos >= data.length) return -1;
                return data[pos++];
            }
            @Override
            public int available() {
                callCount++;
                // First available() call (top of poll #1): report 1 so poll proceeds.
                // Second available() call (inside \r branch of poll #1): report 0 to trigger skipNextNulOrLf.
                // Subsequent calls (poll #2 top): report 1 so poll proceeds to read 'b'.
                if (callCount == 1) return 1;
                if (callCount == 2) return 0;
                return 1;
            }
        };
        try {
            var decoder = new InputDecoder(slow);
            var ks1 = decoder.poll().orElseThrow();
            assertEquals(KeyType.ENTER, ks1.type());
            // Now skipNextNulOrLf is true; next poll reads 'b' which is not \n/\0 -> fall through -> CHARACTER
            var ks2 = decoder.poll().orElseThrow();
            assertEquals(KeyType.CHARACTER, ks2.type());
            assertEquals('b', ks2.character());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- New tests: Unknown escape sequences ----------

    @Test
    void unknownEscapeSequenceWithNonCsiNonO() {
        // ESC + '+' (printable but not letter/digit) -> Alt+'+' since '+' >= 32 && < 127
        var ks = decode(new byte[]{0x1b, '+'});
        assertEquals(KeyType.CHARACTER, ks.type());
        assertEquals('+', ks.character());
        assertTrue(ks.alt());
    }

    @Test
    void escapeThenBelDeliversEscapeThenCtrlG() {
        // ESC + 0x07 (BEL): control bytes after ESC are their own keystrokes
        // (pushed back), never swallowed into a bogus UNKNOWN sequence.
        var decoder = new InputDecoder(new ByteArrayInputStream(new byte[]{0x1b, 0x07}));
        try {
            assertEquals(KeyType.ESCAPE, decoder.poll().orElseThrow().type());
            var second = decoder.poll().orElseThrow();
            assertEquals(KeyType.CHARACTER, second.type());
            assertTrue(second.ctrl());
            assertEquals('G', second.character());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void escapeFollowedByBracketThenGarbage() {
        // ESC [ <non-param non-final> -> UNKNOWN
        var ks = decode(new byte[]{0x1b, '[', 0x01});
        assertEquals(KeyType.UNKNOWN, ks.type());
    }

    // ---------- New tests: EOF handling ----------

    @Test
    void eofReturnsEofKeyStroke() {
        // A stream that returns -1 immediately
        var eof = new java.io.InputStream() {
            @Override
            public int read() { return -1; }
            @Override
            public int available() { return 1; } // force poll() to proceed past the available()==0 check
        };
        try {
            var decoder = new InputDecoder(eof);
            var ks = decoder.poll().orElseThrow();
            assertEquals(KeyType.EOF, ks.type());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void eofAfterEscapeReturnsEscape() {
        // ESC then EOF: readEscapeSequence reads second byte == -1 -> ESCAPE
        var decoder = new InputDecoder(new ByteArrayInputStream(new byte[]{0x1b}));
        try {
            var ks = decoder.poll().orElseThrow();
            assertEquals(KeyType.ESCAPE, ks.type());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- New tests: printable characters ----------

    @Test
    void printableCharLowercase() {
        var ks = decode("m".getBytes());
        assertEquals(KeyType.CHARACTER, ks.type());
        assertEquals('m', ks.character());
        assertFalse(ks.ctrl());
        assertFalse(ks.alt());
    }

    @Test
    void printableCharUppercase() {
        var ks = decode("Q".getBytes());
        assertEquals('Q', ks.character());
    }

    @Test
    void printableCharSymbol() {
        var ks = decode("@".getBytes());
        assertEquals('@', ks.character());
        assertFalse(ks.ctrl());
        assertFalse(ks.alt());
    }

    @Test
    void highAsciiCharacter() {
        // byte 200 (0xC8) — not in any special range; should be CHARACTER
        var ks = decode(new byte[]{(byte) 0xC8});
        assertEquals(KeyType.CHARACTER, ks.type());
        assertEquals((char) 0xC8, ks.character());
    }

    // ---------- New tests: consecutive polls ----------

    @Test
    void consecutivePollsReturnConsecutiveChars() {
        var decoder = new InputDecoder(new ByteArrayInputStream("abc".getBytes()));
        try {
            for (char expected : new char[]{'a', 'b', 'c'}) {
                var ks = decoder.poll().orElseThrow();
                assertEquals(KeyType.CHARACTER, ks.type());
                assertEquals(expected, ks.character());
            }
            assertEquals(Optional.empty(), decoder.poll());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void mixedSequencePolls() {
        // 'a', ENTER, ARROW_UP, 'b'
        var decoder = new InputDecoder(new ByteArrayInputStream(
            new byte[]{'a', '\r', 0x1b, '[', 'A', 'b'}));
        try {
            assertEquals('a', decoder.poll().orElseThrow().character());
            assertEquals(KeyType.ENTER, decoder.poll().orElseThrow().type());
            assertEquals(KeyType.ARROW_UP, decoder.poll().orElseThrow().type());
            assertEquals('b', decoder.poll().orElseThrow().character());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- New tests: CSI modifier codes ----------

    @Test
    void shiftArrowUp() {
        var ks = decode("\033[1;2A".getBytes());
        assertEquals(KeyType.ARROW_UP, ks.type());
        assertTrue(ks.shift());
        assertFalse(ks.ctrl());
        assertFalse(ks.alt());
    }

    @Test
    void ctrlArrowUp() {
        var ks = decode("\033[1;5A".getBytes());
        assertEquals(KeyType.ARROW_UP, ks.type());
        assertTrue(ks.ctrl());
        assertFalse(ks.shift());
        assertFalse(ks.alt());
    }

    @Test
    void altArrowUp() {
        var ks = decode("\033[1;3A".getBytes());
        assertEquals(KeyType.ARROW_UP, ks.type());
        assertTrue(ks.alt());
        assertFalse(ks.shift());
        assertFalse(ks.ctrl());
    }

    @Test
    void shiftArrowDown() {
        var ks = decode("\033[1;2B".getBytes());
        assertEquals(KeyType.ARROW_DOWN, ks.type());
        assertTrue(ks.shift());
        assertFalse(ks.ctrl());
        assertFalse(ks.alt());
    }

    @Test
    void ctrlShiftArrowUp() {
        var ks = decode("\033[1;6A".getBytes());
        assertEquals(KeyType.ARROW_UP, ks.type());
        assertTrue(ks.ctrl());
        assertTrue(ks.shift());
        assertFalse(ks.alt());
    }

    @Test
    void shiftHome() {
        var ks = decode("\033[1;2H".getBytes());
        assertEquals(KeyType.HOME, ks.type());
        assertTrue(ks.shift());
        assertFalse(ks.ctrl());
        assertFalse(ks.alt());
    }

    @Test
    void shiftEnd() {
        var ks = decode("\033[1;2F".getBytes());
        assertEquals(KeyType.END, ks.type());
        assertTrue(ks.shift());
        assertFalse(ks.ctrl());
        assertFalse(ks.alt());
    }

    @Test
    void shiftPageUp() {
        var ks = decode("\033[5;2~".getBytes());
        assertEquals(KeyType.PAGE_UP, ks.type());
        assertTrue(ks.shift());
        assertFalse(ks.ctrl());
        assertFalse(ks.alt());
    }

    @Test
    void arrowUpNoParamsHasNoModifiers() {
        var ks = decode("\033[A".getBytes());
        assertEquals(KeyType.ARROW_UP, ks.type());
        assertFalse(ks.shift());
        assertFalse(ks.ctrl());
        assertFalse(ks.alt());
    }
}