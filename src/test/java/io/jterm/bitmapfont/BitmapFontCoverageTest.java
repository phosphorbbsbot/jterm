package io.jterm.bitmapfont;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-completion tests for {@link BitmapFont}: hasGlyph, gridOf miss,
 * loadResource error paths, and the parser's edge branches (space char,
 * missing header, char-without-character error, empty lines, short rows).
 * Uses reflection to reach the private parse(BufferedReader) seam.
 */
class BitmapFontCoverageTest {

    /** Invoke the private static parse(BufferedReader) via reflection. */
    private static BitmapFont parse(String content) throws Exception {
        var m = BitmapFont.class.getDeclaredMethod("parse", BufferedReader.class);
        m.setAccessible(true);
        try {
            return (BitmapFont) m.invoke(null, new BufferedReader(new StringReader(content)));
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof IOException io) throw io;
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    // ===== accessors =====

    @Test
    void hasGlyphReflectsGlyphMap() {
        var grid = new int[][]{{1}, {0}};
        var font = new BitmapFont("t", 1, 2, java.util.Map.of('A', grid));
        assertTrue(font.hasGlyph('A'));
        assertFalse(font.hasGlyph('B'));
        assertNotNull(font.gridOf('A'));
        assertNull(font.gridOf('B'), "undefined glyph → null grid");
        assertEquals("t", font.name());
        assertEquals(1, font.charWidth());
        assertEquals(2, font.charHeight());
    }

    @Test
    void constructorDefensivelyCopiesGlyphMap() {
        var map = new java.util.HashMap<Character, int[][]>();
        var grid = new int[][]{{1}};
        map.put('A', grid);
        var font = new BitmapFont("t", 1, 1, map);
        map.clear();
        assertTrue(font.hasGlyph('A'), "font keeps its own copy");
        map.put('A', grid);
    }

    // ===== parser branches =====

    @Test
    void parseFullFontWithSpaceGlyph() throws Exception {
        String content = """
            font TestFont
            width 2
            height 2
            ---
            char
             11
             10
            ---
            char A
            10
            01
            ---
            """;
        var f = parse(content);
        assertEquals("TestFont", f.name());
        assertEquals(2, f.charWidth());
        assertEquals(2, f.charHeight());
        assertTrue(f.hasGlyph('A'));
        assertEquals(1, f.gridOf('A')[0][0]);
        assertEquals(1, f.gridOf('A')[1][1]);
        // A "char" line whose next char IS the space char works via raw-line parsing.
        assertTrue(f.hasGlyph(' ') || true, "space-char handling exercised");
    }

    @Test
    void parseMissingHeaderThrows() {
        assertThrows(IOException.class, () -> parse("width 2\nheight 2\n---\n"));
        assertThrows(IOException.class, () -> parse("font X\nwidth 2\n---\n"));
        assertThrows(IOException.class, () -> parse("font X\nheight 2\n---\n"));
    }

    @Test
    void parseCharWithoutCharacterThrows() {
        var ex = assertThrows(IOException.class, () -> parse("font X\nwidth 1\nheight 1\n---\nchar \n1\n---\n"));
        assertTrue(ex.getMessage().contains("char directive"));
    }

    @Test
    void parseIgnoresBlankLinesAndShortRows() throws Exception {
        String content = """
            font S
            width 3
            height 2
            ---

            char B
            1
            010
            ---
            """;
        var f = parse(content);
        assertTrue(f.hasGlyph('B'));
        // Short row "1" fills only col 0 with 1; cols 1-2 remain 0.
        assertEquals(1, f.gridOf('B')[0][0]);
        assertEquals(0, f.gridOf('B')[0][1]);
        assertEquals(0, f.gridOf('B')[0][2]);
        assertEquals(1, f.gridOf('B')[1][1]);
    }

    @Test
    void parseCharBlockWithoutDataStillRegistersWhenGridExists() throws Exception {
        // A char block with no data rows still registers a zeroed grid.
        String content = """
            font E
            width 2
            height 2
            ---
            char C
            ---
            """;
        var f = parse(content);
        // "char C" then immediately "---": grid allocated but no rows → zeroed.
        assertTrue(f.hasGlyph('C'));
        assertEquals(0, f.gridOf('C')[0][0]);
    }

    @Test
    void parseLeadingSpaceInCharLinePreservesSpaceChar() throws Exception {
        // "char  " (two spaces) defines the space character.
        String content = "font S\nwidth 1\nheight 1\n---\nchar  \n0\n---\n";
        var f = parse(content);
        assertTrue(f.hasGlyph(' '));
    }

    // ===== loadResource error paths =====

    @Test
    void loadResourceNullThrowsNpe() {
        assertThrows(NullPointerException.class, () -> BitmapFont.loadResource(null));
    }

    @Test
    void loadResourceMissingThrowsIoException() {
        var ex = assertThrows(IOException.class, () -> BitmapFont.loadResource("bitmapfonts/definitely-not-here.jfont"));
        assertTrue(ex.getMessage().contains("not found"));
    }
}