package io.jterm.screen;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import io.jterm.style.IndexedColor;
import io.jterm.style.SGR;
import io.jterm.style.TextCell;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-completion tests for {@link AnsiArtRenderer}: the full SGR code
 * table (mods, bright colors, extended colors, defaults), cursor-movement
 * escapes (CUP, CUU/CUD/CUF/CUB), control characters (tab), clipping, and
 * the file loader. Complements AnsiArtRendererTest (basic rendering paths).
 */
class AnsiArtRendererCoverageTest {

    private record Buf(ScreenBuffer sb, TextGraphics g) {
        static Buf of(int w, int h) {
            var sb = new ScreenBuffer(new TerminalSize(w, h));
            return new Buf(sb, new TextGraphics(sb));
        }
    }

    private static TextCell cell(Buf b, int col, int row) {
        return b.sb().getCell(col, row);
    }

    // ===== SGR modifier codes =====

    @Test
    void sgrBoldAndReset() {
        var b = Buf.of(20, 2);
        AnsiArtRenderer.render(b.g(), "\u001B[1mAB\u001B[0mC");
        assertTrue(cell(b, 0, 0).modifiers().contains(SGR.BOLD));
        assertTrue(cell(b, 1, 0).modifiers().contains(SGR.BOLD));
        assertFalse(cell(b, 2, 0).modifiers().contains(SGR.BOLD), "after reset");
        assertEquals(AnsiColor.DEFAULT, cell(b, 2, 0).fg(), "reset restores default fg");
    }

    @Test
    void sgrDimItalicUnderlineBlink() {
        var b = Buf.of(20, 2);
        AnsiArtRenderer.render(b.g(), "\u001B[2;3;4;5mA\u001B[22;23;24;25mB");
        var on = cell(b, 0, 0).modifiers();
        assertTrue(on.contains(SGR.DIM) && on.contains(SGR.ITALIC)
            && on.contains(SGR.UNDERLINE) && on.contains(SGR.BLINK));
        var off = cell(b, 1, 0).modifiers();
        assertFalse(off.contains(SGR.DIM));
        assertFalse(off.contains(SGR.ITALIC));
        assertFalse(off.contains(SGR.UNDERLINE));
        assertFalse(off.contains(SGR.BLINK));
    }

    @Test
    void sgrReverseHiddenStrikethroughAndRemovals() {
        var b = Buf.of(20, 2);
        AnsiArtRenderer.render(b.g(), "\u001B[7;8;9mA\u001B[27;28;29mB");
        assertTrue(cell(b, 0, 0).modifiers().containsAll(java.util.EnumSet.of(SGR.REVERSE, SGR.HIDDEN, SGR.STRIKETHROUGH)));
        var after = cell(b, 1, 0).modifiers();
        assertFalse(after.contains(SGR.REVERSE));
        assertFalse(after.contains(SGR.HIDDEN));
        assertFalse(after.contains(SGR.STRIKETHROUGH));
    }

    @Test
    void sgrItalic() {
        var b = Buf.of(20, 2);
        AnsiArtRenderer.render(b.g(), "\u001B[3mi\u001B[23mn");
        assertTrue(cell(b, 0, 0).modifiers().contains(SGR.ITALIC));
        assertFalse(cell(b, 1, 0).modifiers().contains(SGR.ITALIC));
    }

    // ===== Basic + bright 16-color codes =====

    @Test
    void sgrBasicAndDefaultFgBg() {
        var b = Buf.of(20, 2);
        // 31=red fg, 44=blue bg; 39/49 restore defaults.
        AnsiArtRenderer.render(b.g(), "\u001B[31;44mX\u001B[39;49mY");
        assertEquals(AnsiColor.RED, cell(b, 0, 0).fg());
        assertEquals(AnsiColor.BLUE, cell(b, 0, 0).bg());
        assertEquals(AnsiColor.DEFAULT, cell(b, 1, 0).fg());
        assertEquals(AnsiColor.DEFAULT, cell(b, 1, 0).bg());
    }

    @Test
    void sgrBrightFgBg() {
        var b = Buf.of(20, 2);
        // 90-97 bright fg (palette 8-15), 100-107 bright bg (palette 8-15).
        AnsiArtRenderer.render(b.g(), "\u001B[91mF\u001B[101mB");
        assertEquals(AnsiColor.BRIGHT_RED, cell(b, 0, 0).fg());   // 91 → index 9
        assertEquals(AnsiColor.BRIGHT_RED, cell(b, 1, 0).bg());   // 101 → index 9
    }

    @Test
    void sgrEmptyParamsTreatedAsReset() {
        var b = Buf.of(20, 2);
        AnsiArtRenderer.render(b.g(), "\u001B[31mX\u001B[mY");
        assertEquals(AnsiColor.DEFAULT, cell(b, 1, 0).fg(), "bare ESC[m resets");
    }

    // ===== Extended colors (256 + true color), fg and bg =====

    @Test
    void sgr256FgAndBgIndexedColor() {
        var b = Buf.of(20, 2);
        AnsiArtRenderer.render(b.g(), "\u001B[38;5;196mF\u001B[48;5;21mB");
        var fg = cell(b, 0, 0).fg();
        var bg = cell(b, 1, 0).bg();
        assertTrue(fg instanceof IndexedColor && ((IndexedColor) fg).index() == 196,
            "fg should be IndexedColor(196), got " + fg);
        assertTrue(bg instanceof IndexedColor && ((IndexedColor) bg).index() == 21,
            "bg should be IndexedColor(21), got " + bg);
    }

    @Test
    void sgrTrueColorFgAndBg() {
        var b = Buf.of(20, 2);
        AnsiArtRenderer.render(b.g(), "\u001B[38;2;12;34;56mF\u001B[48;2;200;100;50mB");
        var fg = cell(b, 0, 0).fg();
        var bg = cell(b, 1, 0).bg();
        assertTrue(fg instanceof io.jterm.style.RgbColor, "fg truecolor, got " + fg);
        assertTrue(bg instanceof io.jterm.style.RgbColor, "bg truecolor, got " + bg);
        assertEquals(12, ((io.jterm.style.RgbColor) fg).r());
        assertEquals(200, ((io.jterm.style.RgbColor) bg).r());
    }

    // ===== Cursor movement escapes =====

    @Test
    void cursorHomeResetsPosition() {
        var b = Buf.of(20, 4);
        AnsiArtRenderer.render(b.g(), "abc\u001B[Hxyz");
        // After home, 'x','y','z' overwrite (0,0),(1,0),(2,0).
        assertEquals('x', cell(b, 0, 0).character().charAt(0));
        assertEquals('y', cell(b, 1, 0).character().charAt(0));
        assertEquals('z', cell(b, 2, 0).character().charAt(0));
    }

    @Test
    void cursorPositionAbsolute() {
        var b = Buf.of(20, 6);
        // CUP uses final byte 'H' (an 'm' final byte would be SGR!).
        AnsiArtRenderer.render(b.g(), "\u001B[3;5HQ");
        // 1-based ESC[<row>;<col>H → (row-1, col-1) = (2, 4).
        assertEquals('Q', cell(b, 4, 2).character().charAt(0));
    }

    @Test
    void cursorPositionFVariant() {
        var b = Buf.of(20, 6);
        // 'f' is the alternate CUP final byte.
        AnsiArtRenderer.render(b.g(), "\u001B[2;3fMQ");
        // 1-based → (row-1, col-1) = (1, 2): 'M' there, 'Q' right after.
        assertEquals('M', cell(b, 2, 1).character().charAt(0));
        assertEquals('Q', cell(b, 3, 1).character().charAt(0));
    }

    @Test
    void cursorRelativeMoves() {
        var b = Buf.of(30, 10);
        // Cursor semantics: a char renders at (col,row), then col advances.
        // Start (0,0): CUF 2 → col 2, 'a' at (2,0), col→3. CUD 1 → row 1,
        // col 3, 'b' at (3,1), col→4. CUB 1 → col 3, 'c' OVERWRITES 'b' at
        // (3,1), col→4. CUU 1 → row 0, 'd' at (4,0).
        AnsiArtRenderer.render(b.g(), "\u001B[2Ca\u001B[1Bb\u001B[1Dc\u001B[1Ad");
        assertEquals('a', cell(b, 2, 0).character().charAt(0));
        assertEquals('c', cell(b, 3, 1).character().charAt(0), "c overwrites b");
        assertEquals('d', cell(b, 4, 0).character().charAt(0));
    }

    @Test
    void cursorMovementWithoutParamMovesOne() {
        var b = Buf.of(20, 6);
        // 'a' at (0,0), col→1. ESC[B (no param) → row 1, col 1: 'b' at (1,1).
        AnsiArtRenderer.render(b.g(), "a\u001B[Bb");
        assertEquals('a', cell(b, 0, 0).character().charAt(0));
        assertEquals('b', cell(b, 1, 1).character().charAt(0));
    }

    @Test
    void eraseDisplay2JResetsCursor() {
        var b = Buf.of(20, 4);
        AnsiArtRenderer.render(b.g(), "abc\u001B[2Jxyz");
        assertEquals('x', cell(b, 0, 0).character().charAt(0));
        assertEquals('y', cell(b, 1, 0).character().charAt(0));
        // c was overwritten by x,y,z sequence after reset: col0=x col1=y col2=z
        assertEquals('z', cell(b, 2, 0).character().charAt(0));
    }

    @Test
    void eraseLineKIsSkippedWithoutSideEffects() {
        var b = Buf.of(20, 4);
        AnsiArtRenderer.render(b.g(), "ab\u001B[Kc");
        // ESC[K skipped; 'c' continues right after 'b'.
        assertEquals('c', cell(b, 2, 0).character().charAt(0));
    }

    @Test
    void nonSgrEscapeSequenceIsSkipped() {
        var b = Buf.of(20, 4);
        // Device-status etc: final byte not m/H/J/K/A-D — skipped entirely.
        AnsiArtRenderer.render(b.g(), "a\u001B[?25hb");
        assertEquals('a', cell(b, 0, 0).character().charAt(0));
        assertEquals('b', cell(b, 1, 0).character().charAt(0));
    }

    // ===== Control characters =====

    @Test
    void tabAdvancesToNextTabStop() {
        var b = Buf.of(40, 2);
        AnsiArtRenderer.render(b.g(), "a\tb");
        // 'a' at col 0; tab → col 8; 'b' at col 8.
        assertEquals('b', cell(b, 8, 0).character().charAt(0));
    }

    @Test
    void tabFromMidColumnSnapsForward() {
        var b = Buf.of(40, 2);
        AnsiArtRenderer.render(b.g(), "abc\tb");
        // col 3 → next multiple of 8 = 8.
        assertEquals('b', cell(b, 8, 0).character().charAt(0));
    }

    @Test
    void loneEscIsSkipped() {
        var b = Buf.of(20, 2);
        AnsiArtRenderer.render(b.g(), "a\u001Bb");
        assertEquals('b', cell(b, 1, 0).character().charAt(0));
    }

    @Test
    void incompleteEscapeAtEndIsIgnored() {
        var b = Buf.of(20, 2);
        AnsiArtRenderer.render(b.g(), "ab\u001B[3");
        // String ends mid-escape: renderer stops, 'a','b' still drawn.
        assertEquals('a', cell(b, 0, 0).character().charAt(0));
        assertEquals('b', cell(b, 1, 0).character().charAt(0));
    }

    // ===== Clipping =====

    @Test
    void renderClipsToBufferBounds() {
        var b = Buf.of(3, 2);
        // No newline and no autowrap: chars past col 3 are clipped, cursor
        // keeps advancing — only the first 3 cells fill.
        AnsiArtRenderer.render(b.g(), "abcdefghij");
        assertEquals('a', cell(b, 0, 0).character().charAt(0));
        assertEquals('c', cell(b, 2, 0).character().charAt(0));
        assertEquals(' ', cell(b, 0, 1).character().charAt(0), "no autowrap: row 1 untouched");
    }

    @Test
    void renderAtOffsetPlacesCells() {
        var b = Buf.of(20, 6);
        AnsiArtRenderer.render(b.g(), "hi", 3, 2);
        assertEquals('h', cell(b, 3, 2).character().charAt(0));
        assertEquals('i', cell(b, 4, 2).character().charAt(0));
    }

    @Test
    void emptyAndNullContentRenderNothing() {
        var b = Buf.of(10, 2);
        AnsiArtRenderer.render(b.g(), "");
        AnsiArtRenderer.render(b.g(), null);
        assertEquals(' ', cell(b, 0, 0).character().charAt(0));
    }

    @Test
    void unknownSgrCodeIgnored() {
        var b = Buf.of(20, 2);
        // 77 is not a standard SGR — silently ignored, text still renders.
        AnsiArtRenderer.render(b.g(), "\u001B[77mok");
        assertEquals('o', cell(b, 0, 0).character().charAt(0));
        assertEquals('k', cell(b, 1, 0).character().charAt(0));
    }

    // ===== fromFile =====

    @Test
    void fromFileReadsAnsiContent() throws Exception {
        var path = Files.createTempFile("jterm-ansi", ".ans");
        Files.writeString(path, "\u001B[31mRED");
        try {
            assertEquals("\u001B[31mRED", AnsiArtRenderer.fromFile(path));
        } finally {
            Files.deleteIfExists(path);
        }
    }
}