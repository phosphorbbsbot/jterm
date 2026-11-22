package io.jterm.style;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for {@link AnsiColor}: fg/bg sequences for every enum constant,
 * boundary/DEFAULT behavior, and byte-array contents.
 */
class AnsiColorTest {

    // ---- Existing tests (preserved) ----

    @Test
    void allSeventeenValuesExist() {
        assertEquals(17, AnsiColor.values().length);
    }

    @Test
    void foregroundSequences() {
        assertArrayEquals("30".getBytes(), AnsiColor.BLACK.fgSequence());
        assertArrayEquals("31".getBytes(), AnsiColor.RED.fgSequence());
        assertArrayEquals("32".getBytes(), AnsiColor.GREEN.fgSequence());
        assertArrayEquals("37".getBytes(), AnsiColor.WHITE.fgSequence());
        assertArrayEquals("39".getBytes(), AnsiColor.DEFAULT.fgSequence());
        assertArrayEquals("97".getBytes(), AnsiColor.BRIGHT_WHITE.fgSequence());
    }

    @Test
    void backgroundSequences() {
        assertArrayEquals("40".getBytes(), AnsiColor.BLACK.bgSequence());
        assertArrayEquals("41".getBytes(), AnsiColor.RED.bgSequence());
        assertArrayEquals("49".getBytes(), AnsiColor.DEFAULT.bgSequence());
        assertArrayEquals("107".getBytes(), AnsiColor.BRIGHT_WHITE.bgSequence());
    }

    @Test
    void brightColorsHaveIndex8OrHigher() {
        assertTrue(AnsiColor.BRIGHT_BLACK.ordinal() >= AnsiColor.WHITE.ordinal());
    }

    // ---- New tests: exhaustive fg sequences ----

    @Test
    void blackFg() {
        assertArrayEquals("30".getBytes(), AnsiColor.BLACK.fgSequence());
    }

    @Test
    void redFg() {
        assertArrayEquals("31".getBytes(), AnsiColor.RED.fgSequence());
    }

    @Test
    void greenFg() {
        assertArrayEquals("32".getBytes(), AnsiColor.GREEN.fgSequence());
    }

    @Test
    void yellowFg() {
        assertArrayEquals("33".getBytes(), AnsiColor.YELLOW.fgSequence());
    }

    @Test
    void blueFg() {
        assertArrayEquals("34".getBytes(), AnsiColor.BLUE.fgSequence());
    }

    @Test
    void magentaFg() {
        assertArrayEquals("35".getBytes(), AnsiColor.MAGENTA.fgSequence());
    }

    @Test
    void cyanFg() {
        assertArrayEquals("36".getBytes(), AnsiColor.CYAN.fgSequence());
    }

    @Test
    void whiteFg() {
        assertArrayEquals("37".getBytes(), AnsiColor.WHITE.fgSequence());
    }

    @Test
    void brightBlackFg() {
        assertArrayEquals("90".getBytes(), AnsiColor.BRIGHT_BLACK.fgSequence());
    }

    @Test
    void brightRedFg() {
        assertArrayEquals("91".getBytes(), AnsiColor.BRIGHT_RED.fgSequence());
    }

    @Test
    void brightGreenFg() {
        assertArrayEquals("92".getBytes(), AnsiColor.BRIGHT_GREEN.fgSequence());
    }

    @Test
    void brightYellowFg() {
        assertArrayEquals("93".getBytes(), AnsiColor.BRIGHT_YELLOW.fgSequence());
    }

    @Test
    void brightBlueFg() {
        assertArrayEquals("94".getBytes(), AnsiColor.BRIGHT_BLUE.fgSequence());
    }

    @Test
    void brightMagentaFg() {
        assertArrayEquals("95".getBytes(), AnsiColor.BRIGHT_MAGENTA.fgSequence());
    }

    @Test
    void brightCyanFg() {
        assertArrayEquals("96".getBytes(), AnsiColor.BRIGHT_CYAN.fgSequence());
    }

    @Test
    void brightWhiteFg() {
        assertArrayEquals("97".getBytes(), AnsiColor.BRIGHT_WHITE.fgSequence());
    }

    @Test
    void defaultFg() {
        assertArrayEquals("39".getBytes(), AnsiColor.DEFAULT.fgSequence());
    }

    // ---- New tests: exhaustive bg sequences ----

    @Test
    void blackBg() {
        assertArrayEquals("40".getBytes(), AnsiColor.BLACK.bgSequence());
    }

    @Test
    void redBg() {
        assertArrayEquals("41".getBytes(), AnsiColor.RED.bgSequence());
    }

    @Test
    void greenBg() {
        assertArrayEquals("42".getBytes(), AnsiColor.GREEN.bgSequence());
    }

    @Test
    void yellowBg() {
        assertArrayEquals("43".getBytes(), AnsiColor.YELLOW.bgSequence());
    }

    @Test
    void blueBg() {
        assertArrayEquals("44".getBytes(), AnsiColor.BLUE.bgSequence());
    }

    @Test
    void magentaBg() {
        assertArrayEquals("45".getBytes(), AnsiColor.MAGENTA.bgSequence());
    }

    @Test
    void cyanBg() {
        assertArrayEquals("46".getBytes(), AnsiColor.CYAN.bgSequence());
    }

    @Test
    void whiteBg() {
        assertArrayEquals("47".getBytes(), AnsiColor.WHITE.bgSequence());
    }

    @Test
    void brightBlackBg() {
        assertArrayEquals("100".getBytes(), AnsiColor.BRIGHT_BLACK.bgSequence());
    }

    @Test
    void brightRedBg() {
        assertArrayEquals("101".getBytes(), AnsiColor.BRIGHT_RED.bgSequence());
    }

    @Test
    void brightGreenBg() {
        assertArrayEquals("102".getBytes(), AnsiColor.BRIGHT_GREEN.bgSequence());
    }

    @Test
    void brightYellowBg() {
        assertArrayEquals("103".getBytes(), AnsiColor.BRIGHT_YELLOW.bgSequence());
    }

    @Test
    void brightBlueBg() {
        assertArrayEquals("104".getBytes(), AnsiColor.BRIGHT_BLUE.bgSequence());
    }

    @Test
    void brightMagentaBg() {
        assertArrayEquals("105".getBytes(), AnsiColor.BRIGHT_MAGENTA.bgSequence());
    }

    @Test
    void brightCyanBg() {
        assertArrayEquals("106".getBytes(), AnsiColor.BRIGHT_CYAN.bgSequence());
    }

    @Test
    void brightWhiteBg() {
        assertArrayEquals("107".getBytes(), AnsiColor.BRIGHT_WHITE.bgSequence());
    }

    @Test
    void defaultBg() {
        assertArrayEquals("49".getBytes(), AnsiColor.DEFAULT.bgSequence());
    }

    // ---- New tests: fg/bg relationship ----

    @ParameterizedTest
    @EnumSource(AnsiColor.class)
    void fgAndBgDifferBy10ForNormalColors(AnsiColor color) {
        byte[] fg = color.fgSequence();
        byte[] bg = color.bgSequence();
        // For non-DEFAULT colors, bg = fg + 10 numerically.
        if (color != AnsiColor.DEFAULT) {
            int fgVal = Integer.parseInt(new String(fg, StandardCharsets.UTF_8));
            int bgVal = Integer.parseInt(new String(bg, StandardCharsets.UTF_8));
            assertEquals(fgVal + 10, bgVal, "bg should be fg+10 for " + color);
        }
    }

    @ParameterizedTest
    @EnumSource(AnsiColor.class)
    void fgSequenceNotNullAndNonEmpty(AnsiColor color) {
        assertNotNull(color.fgSequence());
        assertTrue(color.fgSequence().length > 0, "fg sequence empty for " + color);
    }

    @ParameterizedTest
    @EnumSource(AnsiColor.class)
    void bgSequenceNotNullAndNonEmpty(AnsiColor color) {
        assertNotNull(color.bgSequence());
        assertTrue(color.bgSequence().length > 0, "bg sequence empty for " + color);
    }

    @ParameterizedTest
    @EnumSource(AnsiColor.class)
    void fgAndBgAreStableAcrossCalls(AnsiColor color) {
        // GC law (Joe, 2026-11-21): the palette SGR bytes are built ONCE —
        // every call returns the SAME cached array (content-stable by
        // construction; callers treat it as read-only, they only writeRaw).
        byte[] fg1 = color.fgSequence();
        byte[] fg2 = color.fgSequence();
        assertArrayEquals(fg1, fg2);
        assertSame(fg1, fg2); // one cached array per palette color
    }

    // ---- New tests: Color interface polymorphism ----

    @Test
    void ansiColorImplementsColor() {
        Color c = AnsiColor.RED;
        assertArrayEquals("31".getBytes(), c.fgSequence());
        assertArrayEquals("41".getBytes(), c.bgSequence());
    }

    @Test
    void defaultImplementsColor() {
        Color c = AnsiColor.DEFAULT;
        assertArrayEquals("39".getBytes(), c.fgSequence());
        assertArrayEquals("49".getBytes(), c.bgSequence());
    }

    @Test
    void allAnsiColorsImplementColorInterface() {
        for (AnsiColor c : AnsiColor.values()) {
            assertInstanceOf(Color.class, c);
            assertNotNull(c.fgSequence());
            assertNotNull(c.bgSequence());
        }
    }

    // ---- New tests: toString (enum default) and name ----

    @Test
    void nameMatchesEnumConstant() {
        assertEquals("BLACK", AnsiColor.BLACK.name());
        assertEquals("RED", AnsiColor.RED.name());
        assertEquals("DEFAULT", AnsiColor.DEFAULT.name());
        assertEquals("BRIGHT_WHITE", AnsiColor.BRIGHT_WHITE.name());
    }

    @Test
    void toStringReturnsName() {
        // Enum.toString() returns name() by default
        assertEquals("BLACK", AnsiColor.BLACK.toString());
        assertEquals("DEFAULT", AnsiColor.DEFAULT.toString());
    }

    @Test
    void valueOfReturnsConstant() {
        assertSame(AnsiColor.BLACK, AnsiColor.valueOf("BLACK"));
        assertSame(AnsiColor.BRIGHT_CYAN, AnsiColor.valueOf("BRIGHT_CYAN"));
        assertSame(AnsiColor.DEFAULT, AnsiColor.valueOf("DEFAULT"));
    }

    @Test
    void valueOfInvalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> AnsiColor.valueOf("NOT_A_COLOR"));
    }

    @Test
    void valueOfNullThrows() {
        assertThrows(NullPointerException.class, () -> AnsiColor.valueOf(null));
    }

    // ---- New tests: ordinal ordering ----

    @Test
    void normalColorsComeBeforeBrightColors() {
        // BLACK..WHITE are indices 0-7, BRIGHT_BLACK..BRIGHT_WHITE are 8-15, DEFAULT is 16
        assertTrue(AnsiColor.BLACK.ordinal() < AnsiColor.WHITE.ordinal());
        assertTrue(AnsiColor.WHITE.ordinal() < AnsiColor.BRIGHT_BLACK.ordinal());
        assertTrue(AnsiColor.BRIGHT_BLACK.ordinal() < AnsiColor.BRIGHT_WHITE.ordinal());
        assertTrue(AnsiColor.BRIGHT_WHITE.ordinal() < AnsiColor.DEFAULT.ordinal());
    }

    @Test
    void defaultIsLastConstant() {
        // DEFAULT has index -1 and is the last enum constant
        int maxOrdinal = -1;
        for (AnsiColor c : AnsiColor.values()) {
            maxOrdinal = Math.max(maxOrdinal, c.ordinal());
        }
        assertEquals(maxOrdinal, AnsiColor.DEFAULT.ordinal());
    }

    // ---- New tests: IndexedColor via AnsiColor equivalence ----

    @Test
    void ansiColorFgMatchesIndexedColorForSystemColors() {
        // AnsiColor.BLACK (index 0) fg = "30"; IndexedColor(0) fg = "38;5;0"
        // Different encoding (16-color vs 256-color), both valid.
        assertArrayEquals("30".getBytes(), AnsiColor.BLACK.fgSequence());
        assertArrayEquals("38;5;0".getBytes(), new IndexedColor(0).fgSequence());
    }

    @Test
    void ansiColorBgMatchesIndexedColorForSystemColors() {
        assertArrayEquals("40".getBytes(), AnsiColor.BLACK.bgSequence());
        assertArrayEquals("48;5;0".getBytes(), new IndexedColor(0).bgSequence());
    }

    // ---- New tests: byte content verification (UTF-8) ----

    @Test
    void fgSequenceIsAscii() {
        for (AnsiColor c : AnsiColor.values()) {
            String s = new String(c.fgSequence(), StandardCharsets.UTF_8);
            for (char ch : s.toCharArray()) {
                assertTrue(ch >= '0' && ch <= '9', "non-digit in fg for " + c + ": " + ch);
            }
        }
    }

    @Test
    void bgSequenceIsAscii() {
        for (AnsiColor c : AnsiColor.values()) {
            String s = new String(c.bgSequence(), StandardCharsets.UTF_8);
            for (char ch : s.toCharArray()) {
                assertTrue(ch >= '0' && ch <= '9', "non-digit in bg for " + c + ": " + ch);
            }
        }
    }

    // ---- New tests: boundary value ranges ----

    @Test
    void fgSequencesAreInValidSgrRange() {
        // Normal colors: 30-37, bright: 90-97, default: 39
        for (AnsiColor c : AnsiColor.values()) {
            int val = Integer.parseInt(new String(c.fgSequence(), StandardCharsets.UTF_8));
            assertTrue((val >= 30 && val <= 37) || (val >= 90 && val <= 97) || val == 39,
                "fg value out of range for " + c + ": " + val);
        }
    }

    @Test
    void bgSequencesAreInValidSgrRange() {
        // Normal colors: 40-47, bright: 100-107, default: 49
        for (AnsiColor c : AnsiColor.values()) {
            int val = Integer.parseInt(new String(c.bgSequence(), StandardCharsets.UTF_8));
            assertTrue((val >= 40 && val <= 47) || (val >= 100 && val <= 107) || val == 49,
                "bg value out of range for " + c + ": " + val);
        }
    }
}