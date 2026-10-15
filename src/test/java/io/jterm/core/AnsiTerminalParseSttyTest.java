package io.jterm.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@link AnsiTerminal#parseSttySize(String)} pure helper extracted
 * from the stty code path: parses two-integer "rows cols" output, rejects
 * null/blank/garbage, and swaps the order (stty prints ROWS then COLUMNS,
 * TerminalSize is constructed as columns-then-rows).
 */
class AnsiTerminalParseSttyTest {

    @Test
    void parsesRowsThenColumnsIntoSize() {
        var size = AnsiTerminal.parseSttySize("24 80\n");
        assertEquals(80, size.columns());
        assertEquals(24, size.rows());
    }

    @Test
    void parsesCRLFAndExtraWhitespace() {
        var size = AnsiTerminal.parseSttySize("  50  120  \r\n");
        assertEquals(120, size.columns());
        assertEquals(50, size.rows());
    }

    @Test
    void blankOutputReturnsNull() {
        assertNull(AnsiTerminal.parseSttySize("   "));
        assertNull(AnsiTerminal.parseSttySize(""));
    }

    @Test
    void nullOutputReturnsNull() {
        assertNull(AnsiTerminal.parseSttySize(null));
    }

    @Test
    void singleFieldReturnsNull() {
        assertNull(AnsiTerminal.parseSttySize("24"));
    }

    @Test
    void threeFieldsReturnNull() {
        assertNull(AnsiTerminal.parseSttySize("24 80 1"));
    }

    @Test
    void nonNumericFieldsReturnNull() {
        assertNull(AnsiTerminal.parseSttySize("rows cols"));
        assertNull(AnsiTerminal.parseSttySize("24 eighty"));
    }

    @Test
    void overflowValuesReturnNull() {
        assertNull(AnsiTerminal.parseSttySize("24 99999999999999"));
    }
}