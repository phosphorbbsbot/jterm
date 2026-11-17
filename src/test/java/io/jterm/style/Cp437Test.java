package io.jterm.style;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * CP437 mapping law tests.
 *
 * <p>Each Unicode box-drawing glyph maps to exactly ONE CP437 byte, and a
 * glyph that EXISTS in CP437 maps to its true code — never to a neighbor.
 * A duplicate put() with a stale key silently redirects a real glyph to the
 * wrong byte (2026-11-17: '╨' was double-keyed, the second put stomped the
 * correct ┴ with ╧'s 0xCF, so CP437 clients rendered '╧' where '┴' was meant).
 */
class Cp437Test {

    private static byte b(int v) { return (byte) v; }

    @Test
    @DisplayName("up-horizontal single ┴ keeps its own byte 0xC1; '╨' must not steal it")
        void upHorizontalKeepsC1() {
            assertEquals(b(0xC1), Cp437.toCp437('┴'), "┴ (U+2534) must be CP437 0xC1");
            assertEquals(b(0xC1), Cp437.toCp437('╨'), "╨ (U+256A) degrades to ┴ 0xC1, never ╧'s 0xCF");
    }

    @Test
    @DisplayName("single-up-double-down ╧ maps to 0xCF ('╨/╧' family degradation)")
        void singleUpDoubleDownIsCF() {
            assertEquals(b(0xCF), Cp437.toCp437('╧'), "╧ (U+2567) must be CP437 0xCF");
    }

    @Test
    @DisplayName("round-corner degradations stay stable (MuffinTerm-era law)")
        void roundedCornersDegradeToSquare() {
            assertEquals(b(0xD6), Cp437.toCp437('╭'));
            assertEquals(b(0xBF), Cp437.toCp437('╮'));
            assertEquals(b(0xD0), Cp437.toCp437('╰'));
            assertEquals(b(0xD9), Cp437.toCp437('╯'));
    }

    @Test
    @DisplayName("core box set maps to true CP437 codes")
        void coreBoxSet() {
            assertEquals(b(0xC4), Cp437.toCp437('─'));
            assertEquals(b(0xB3), Cp437.toCp437('│'));
            assertEquals(b(0xDA), Cp437.toCp437('┌'));
            assertEquals(b(0xBF), Cp437.toCp437('┐'));
            assertEquals(b(0xC0), Cp437.toCp437('└'));
            assertEquals(b(0xD9), Cp437.toCp437('┘'));
            assertEquals(b(0xC5), Cp437.toCp437('┼'));
            assertEquals(b(0xCD), Cp437.toCp437('═'));
            assertEquals(b(0xBA), Cp437.toCp437('║'));
            assertEquals(b(0xCE), Cp437.toCp437('╬'));
    }

    @Test
    @DisplayName("plain ASCII passes through")
        void asciiPassThrough() {
            assertNotEquals(-1, Cp437.toCp437('A'));
            assertEquals(-1, Cp437.toCp437('☃'), "no invented mappings for glyph-less codepoints");
    }
}
