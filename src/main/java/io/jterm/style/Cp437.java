package io.jterm.style;

import java.util.HashMap;
import java.util.Map;

/**
 * Unicode-to-CP437 translation table for IBM PC / DOS terminal emulation.
 *
 * <p>CP437 (Code Page 437) is the original IBM PC character set. It encodes
 * box-drawing, block-shading, and other symbols as single bytes (0x80-0xFF),
 * while UTF-8 encodes them as 2-3 byte sequences. BBS clients like MuffinTerm
 * expect CP437, not UTF-8.
 *
 * <p>This class provides a lookup from Unicode codepoint to CP437 byte for
 * all box-drawing, block-element, and miscellaneous symbols used by JTerm.
 */
public final class Cp437 {

    private static final Map<Character, Byte> MAP = new HashMap<>();

    static {
        // Box-drawing characters
        put('─', (byte) 0xC4);  // light horizontal
        put('━', (byte) 0xCD);  // heavy horizontal
        put('│', (byte) 0xB3);  // light vertical
        put('┃', (byte) 0xBA);  // heavy vertical
        put('┌', (byte) 0xDA);  // light down-right
        put('┍', (byte) 0xD6);  // light down-right (rounded variant → ╭)
        put('╭', (byte) 0xD6);  // rounded down-right
        put('┐', (byte) 0xBF);  // light down-left
        put('┎', (byte) 0xBF);  // light down-left (rounded variant → ╮)
        put('╮', (byte) 0xBF);  // rounded down-left
        put('└', (byte) 0xC0);  // light up-right
        put('╰', (byte) 0xD0);  // rounded up-right
        put('┘', (byte) 0xD9);  // light up-left
        put('╯', (byte) 0xD9);  // rounded up-left
        put('├', (byte) 0xC3);  // light vertical-right
        put('┝', (byte) 0xC3);  // heavy variant
        put('┤', (byte) 0xB4);  // light vertical-left
        put('┥', (byte) 0xB4);  // heavy variant
        put('┬', (byte) 0xC2);  // light down-horizontal
        put('┴', (byte) 0xC1);  // light up-horizontal
        put('┼', (byte) 0xC5);  // light cross
        put('╞', (byte) 0xC3);  // double vertical-right
        put('╡', (byte) 0xB4);  // double vertical-left
        put('╥', (byte) 0xC2);  // double down-horizontal
        put('╨', (byte) 0xC1);  // double up-horizontal
        put('╪', (byte) 0xC5);  // double cross
        put('═', (byte) 0xCD);  // double horizontal
        put('║', (byte) 0xBA);  // double vertical
        put('╔', (byte) 0xC9);  // double down-right
        put('╗', (byte) 0xBB);  // double down-left
        put('╚', (byte) 0xC8);  // double up-right
        put('╝', (byte) 0xBC);  // double up-left
        put('╠', (byte) 0xCC);  // double vertical-right
        put('╣', (byte) 0xB9);  // double vertical-left
        put('╦', (byte) 0xCB);  // double down-horizontal
        put('╩', (byte) 0xCA);  // double up-horizontal
        put('╬', (byte) 0xCE);  // double cross
        put('╓', (byte) 0xD6);  // single→double down-right
        put('╖', (byte) 0xBF);  // single→double down-left
        put('╙', (byte) 0xD0);  // single→double up-right
        put('╜', (byte) 0xD9);  // single→double up-left
        put('╫', (byte) 0xD5);  // single→double cross (vertical)
        put('╧', (byte) 0xCF);  // single→double up (horizontal) — was wrongly double-keyed '╨', which already sits at 0xC1

        // Block elements / shade characters (CP437)
        put('░', (byte) 0xB0);  // light shade
        put('▒', (byte) 0xB1);  // medium shade
        put('▓', (byte) 0xB2);  // dark shade
        put('█', (byte) 0xDB);  // full block
        put('▄', (byte) 0xDC);  // lower half block
        put('▀', (byte) 0xDF);  // upper half block
        put('▌', (byte) 0xDD);  // left half block
        put('▐', (byte) 0xDE);  // right half block

        // Arrows and miscellaneous
        put('►', (byte) 0x10);  // right-pointing pointer
        put('◄', (byte) 0x11);  // left-pointing pointer
        put('↑', (byte) 0x18);  // up arrow
        put('↓', (byte) 0x19);  // down arrow
        put('→', (byte) 0x1A);  // right arrow
        put('←', (byte) 0x1B);  // left arrow
        put('♠', (byte) 0x06);  // spade
        put('♣', (byte) 0x05);  // club
        put('♥', (byte) 0x03);  // heart
        put('♦', (byte) 0x04);  // diamond
        put('•', (byte) 0x07);  // bullet
        put('○', (byte) 0x09);  // circle
        put('◙', (byte) 0x0D);  // circle with dot
        put('♂', (byte) 0x0B);  // male
        put('♀', (byte) 0x0C);  // female
        put('♪', (byte) 0x0E);  // note
        put('♫', (byte) 0x0D);  // beamed notes (approx)
        put('☀', (byte) 0x0F);  // sun
        put('⌂', (byte) 0x7F);  // house
        put('¢', (byte) 0x9B);  // cent
        put('£', (byte) 0x9C);  // pound
        put('¥', (byte) 0x9D);  // yen
        put('§', (byte) 0x15);  // section
        put('°', (byte) 0xF8);  // degree
        put('±', (byte) 0xF1);  // plus-minus
        put('·', (byte) 0xFA);  // middle dot
        put('÷', (byte) 0xF6);  // division
        put('≈', (byte) 0xF7);  // approx (tilde over =, not exact but close)
        put('√', (byte) 0xFB);  // square root
        put('∞', (byte) 0xEC);  // infinity
        put('∩', (byte) 0xEF);  // intersection
        put('■', (byte) 0xFE);  // square (small)
        put('□', (byte) 0x20);  // square (use space as approx — no direct CP437)

        // Checkbox symbols
        put('✓', (byte) 0xFB);  // check mark (approx)
        put('✗', (byte) 0x78);  // ballot X (use lowercase x)

        // Radio button symbols
        put('●', (byte) 0x07);  // filled circle (bullet)
        put('○', (byte) 0x09);  // empty circle

        // Punctuation
        put('«', (byte) 0xAE);  // guillemet left
        put('»', (byte) 0xAF);  // guillemet right
        put('¿', (byte) 0xA8);  // inverted question
        put('¡', (byte) 0xAD);  // inverted exclamation

        // Accented chars (common subset)
        put('à', (byte) 0x85);
        put('á', (byte) 0xA0);
        put('â', (byte) 0x83);
        put('ä', (byte) 0x84);
        put('ç', (byte) 0x87);
        put('è', (byte) 0x8A);
        put('é', (byte) 0x82);
        put('ê', (byte) 0x88);
        put('ë', (byte) 0x89);
        put('í', (byte) 0xA1);
        put('î', (byte) 0x8C);
        put('ï', (byte) 0x8B);
        put('ñ', (byte) 0xA4);
        put('ò', (byte) 0x8F);
        put('ó', (byte) 0xA2);
        put('ô', (byte) 0x93);
        put('ö', (byte) 0x94);
        put('ù', (byte) 0x97);
        put('ú', (byte) 0xA3);
        put('û', (byte) 0x96);
        put('ü', (byte) 0x81);
    }

    private static void put(char c, byte b) {
        MAP.put(c, b);
    }

    private Cp437() {}

    /**
     * Translate a Unicode character to a CP437 byte.
     *
     * @param c Unicode character
     * @return CP437 byte, or -1 if no mapping exists
     */
    public static byte toCp437(char c) {
        Byte b = MAP.get(c);
        if (b != null) return b;
        if (c < 0x80) return (byte) c;  // ASCII passthrough
        return -1;  // no mapping
    }

    /**
     * Check if a character has a CP437 representation.
     *
     * @param c Unicode character
     * @return true if the character can be encoded in CP437
     */
    public static boolean isEncodable(char c) {
        if (c < 0x80) return true;
        return MAP.containsKey(c);
    }
}