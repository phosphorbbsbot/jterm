package io.jterm.ansifilm;

import io.jterm.style.AnsiColor;
import io.jterm.style.IndexedColor;
import io.jterm.style.RgbColor;
import io.jterm.style.SGR;
import io.jterm.style.TextCell;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * pfilm frame-pack parser laws (animation plan card 1, 2026-11-20):
 *
 * <ul>
 *   <li><b>HEADER LAW</b> — line 1 {@code FORMAT pfilm1}, line 2 {@code COUNT},
 *       line 3 {@code COLS/ROWS}; every frame body must stay inside the
 *       declared dims, and the actual frame count must equal COUNT.</li>
 *   <li><b>STRICT-DIRECTIVE LAW</b> — an unknown directive is an ERROR, never
 *       skipped silently.</li>
 *   <li><b>DURATION LAW</b> — frame durations are positive integers.</li>
 *   <li><b>PAINTER LAW</b> — frame bodies are painterly: SGR styling
 *       (truecolor {@code 38;2/48;2} included) and printable glyphs only;
 *       cursor-movement CSI and other control escapes are rejected.</li>
 *   <li><b>SUBCELL RESERVE</b> — the {@code SUBCELL} marker line is accepted
 *       right after a FRAME line and stored (reserved for the half-block
 *       card), nowhere else.</li>
 * </ul>
 */
class AnsiFilmTest {

    // ── happy path ─────────────────────────────────────────────────────

    @Test
    @DisplayName("happy path: two frames parse with durations, cells and dims")
    void happyPath() {
        String src = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 2",
                "COLS 4 ROWS 2",
                "FRAME 100",
                "AB",
                "CD",
                "END",
                "FRAME 250",
                "\u001B[38;2;255;214;71mX",
                "END");
        var film = AnsiFilm.parse(src);

        assertEquals(2, film.frameCount());
        assertEquals(4, film.size().columns());
        assertEquals(2, film.size().rows());

        var f0 = film.frame(0);
        assertEquals(100, f0.durationMs());
        assertEquals("A", f0.cells().getCell(0, 0).character());
        assertEquals("B", f0.cells().getCell(1, 0).character());
        assertEquals("C", f0.cells().getCell(0, 1).character());
        assertEquals("D", f0.cells().getCell(1, 1).character());
        // Cells the body never painted are default-empty grid.
        assertEquals(TextCell.EMPTY, f0.cells().getCell(2, 0));

        var f1 = film.frame(1);
        assertEquals(250, f1.durationMs());
        assertFalse(f1.subcell());
        var x = f1.cells().getCell(0, 0);
        assertEquals("X", x.character());
        assertEquals(new RgbColor(255, 214, 71), x.fg(), "truecolor 38;2 parses as RgbColor fg");
    }

    @Test
    @DisplayName("basic and indexed SGR parameter forms parse into library colors")
    void sgrParameterForms() {
        String src = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 10 ROWS 1",
                "FRAME 50",
                "\u001B[31mA\u001B[1mB\u001B[0mC\u001B[48;5;17mD\u001B[38;5;200mE",
                "END");
        var film = AnsiFilm.parse(src);
        var row = film.frame(0).cells();
        assertEquals(AnsiColor.RED, row.getCell(0, 0).fg(), "ESC[31m -> AnsiColor.RED");
        assertEquals(SGR.BOLD, row.getCell(1, 0).modifiers().iterator().next(), "ESC[1m adds BOLD");
        var resetCell = row.getCell(2, 0);
        assertEquals("C", resetCell.character());
        assertEquals(AnsiColor.DEFAULT, resetCell.fg(), "ESC[0m resets the brush to default");
        assertEquals(AnsiColor.DEFAULT, resetCell.bg(), "ESC[0m resets the brush to default");
        assertTrue(resetCell.modifiers().isEmpty(), "ESC[0m clears modifiers");
        assertEquals(new IndexedColor(17), row.getCell(3, 0).bg(), "ESC[48;5;17m -> IndexedColor bg");
        assertEquals(new IndexedColor(200), row.getCell(4, 0).fg(), "ESC[38;5;200m -> IndexedColor fg");
    }

    @Test
    @DisplayName("CR, CRLF and tab expansion all rasterize row-major")
    void controlCharacterLaws() {
        String src = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 9 ROWS 2",
                "FRAME 100",
                "AB\r\nC\u001B[41m X\tZ",
                "END");
        var film = AnsiFilm.parse(src);
        var cells = film.frame(0).cells();
        // "\r\n" is ONE line break (CR resets col, LF moves row)
        assertEquals("A", cells.getCell(0, 0).character());
        assertEquals("B", cells.getCell(1, 0).character());
        assertEquals("C", cells.getCell(0, 1).character());
        // After "C\u001B[41m " the cursor is on col 1; the tab jumps to col 8.
        assertEquals("Z", cells.getCell(8, 1).character(), "tab jumps to the next 8-column stop");
        var spaced = cells.getCell(1, 1);
        assertEquals(" ", spaced.character());
        assertEquals(AnsiColor.RED, spaced.bg(), "ESC[41m paints the space's bg red");
    }

    @Test
    @DisplayName("a body line may be blank: the cursor advances, nothing paints")
    void blankBodyLinesAdvanceRow() {
        String src = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 4 ROWS 4",
                "FRAME 100",
                "",
                "AB",
                "",
                "CD",
                "END");
        var film = AnsiFilm.parse(src);
        var cells = film.frame(0).cells();
        // "", "AB", "", "CD" body lines paint rows 0..3 one row per line
        assertEquals(TextCell.EMPTY, cells.getCell(0, 0), "row 0 stayed empty (the blank line)");
        assertEquals("A", cells.getCell(0, 1).character(), "row 1 holds AB");
        assertEquals("C", cells.getCell(0, 3).character(), "row 3 holds CD (two rows below AB)");
        assertEquals(TextCell.EMPTY, cells.getCell(0, 2), "row 2 stayed empty (the blank line)");
    }

    // ── header laws ─────────────────────────────────────────────────────

    @Test
    @DisplayName("COUNT mismatch is rejected: fewer frames actually present")
    void countMismatchFewer() {
        String src = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 3",
                "COLS 4 ROWS 2",
                "FRAME 100",
                "AB",
                "END");
        var e = assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(src));
        assertTrue(e.getMessage().contains("COUNT"), e.getMessage());
    }

    @Test
    @DisplayName("COUNT mismatch is rejected: more frames than declared")
    void countMismatchMore() {
        String src = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 4 ROWS 2",
                "FRAME 100",
                "AB",
                "END",
                "FRAME 100",
                "CD",
                "END");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(src));
    }

    @Test
    @DisplayName("dims mismatch is rejected: a paint past the declared COLS throws")
    void dimsOverflowColumns() {
        String src = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 2 ROWS 2",
                "FRAME 100",
                "ABC",
                "END");
        var e = assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(src));
        assertTrue(e.getMessage().toLowerCase().contains("dim") || e.getMessage().contains("column"),
                e.getMessage());
    }

    @Test
    @DisplayName("dims mismatch is rejected: a paint past the declared ROWS throws")
    void dimsOverflowRows() {
        String src = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 2 ROWS 1",
                "FRAME 100",
                "AB",
                "CD",
                "END");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(src));
    }

    @Test
    @DisplayName("a cursor walked out of bounds without painting still rejects the paint it lands on")
    void cursorOverflowOnlyRelevantOnPaint() {
        // The trailing tab walks the cursor past the margin; with nothing
        // painted there the frame is still lawful — the dims law binds
        // PAINTS, not cursor walks.
        String src = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 2 ROWS 1",
                "FRAME 100",
                "AB\t",
                "END");
        assertDoesNotThrow(() -> AnsiFilm.parse(src));
    }

    @Test
    @DisplayName("zero and negative durations are rejected")
    void durationLaws() {
        String zero = String.join("\n",
                "FORMAT pfilm1", "COUNT 1", "COLS 4 ROWS 2",
                "FRAME 0", "AB", "END");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(zero));

        String negative = String.join("\n",
                "FORMAT pfilm1", "COUNT 1", "COLS 4 ROWS 2",
                "FRAME -5", "AB", "END");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(negative));

        String garbage = String.join("\n",
                "FORMAT pfilm1", "COUNT 1", "COLS 4 ROWS 2",
                "FRAME soon", "AB", "END");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(garbage));
    }

    @Test
    @DisplayName("unknown directives are Errors, never skipped silently")
    void unknownDirectiveLaw() {
        String withComment = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 4 ROWS 2",
                "# a comment the format never had",
                "FRAME 100",
                "AB",
                "END");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(withComment));

        String inFrame = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 4 ROWS 2",
                "FRAME 100",
                "PAUSE 40",
                "AB",
                "END");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(inFrame));
    }

    @Test
    @DisplayName("bad header: wrong format tag, missing header lines, non-numeric dims all reject")
    void headerLaws() {
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(String.join("\n",
                "FORMAT pfilm2", "COUNT 1", "COLS 4 ROWS 2", "FRAME 100", "AB", "END")));

        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(String.join("\n",
                "COUNT 1", "COLS 4 ROWS 2", "FRAME 100", "AB", "END")));

        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(String.join("\n",
                "FORMAT pfilm1", "COUNT 1", "COLS 4 ROWS 2", "FRAME 100", "AB", "END", "FRAME X", "AB", "END")));

        // duplicate header directive mid-file: a stray COLS inside a frame body
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(String.join("\n",
                "FORMAT pfilm1", "COUNT 1", "COLS 4 ROWS 2",
                "FRAME 100", "COLS 8 ROWS 2", "AB", "END")));
    }

    @Test
    @DisplayName("a zero-frame film is lawful: COUNT 0 with no frames")
    void zeroFrameFilm() {
        var film = AnsiFilm.parse(String.join("\n",
                "FORMAT pfilm1", "COUNT 0", "COLS 40 ROWS 8"));
        assertEquals(0, film.frameCount());
    }

    @Test
    @DisplayName("COLS/ROWS must be positive and both present")
    void dimsLaws() {
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(String.join("\n",
                "FORMAT pfilm1", "COUNT 0", "COLS 40")));
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(String.join("\n",
                "FORMAT pfilm1", "COUNT 0", "COLS 0 ROWS 2")));
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(String.join("\n",
                "FORMAT pfilm1", "COUNT 0", "COLS 40 ROWS -1")));
        assertDoesNotThrow(() -> AnsiFilm.parse(String.join("\n",
                "FORMAT pfilm1", "COUNT 0", "COLS 40 ROWS 8")));
    }

    // ── SUBCELL reserve ─────────────────────────────────────────────────

    @Test
    @DisplayName("SUBCELL marker line right after FRAME is accepted and stored")
    void subcellReserve() {
        String src = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 2",
                "COLS 4 ROWS 2",
                "FRAME 100",
                "SUBCELL",
                "AB",
                "END",
                "FRAME 100",
                "CD",
                "END");
        var film = AnsiFilm.parse(src);
        assertTrue(film.frame(0).subcell(), "the SUBCELL flag rides frame 0");
        assertFalse(film.frame(1).subcell(), "frame 1 has no marker");
    }

    @Test
    @DisplayName("SUBCELL not immediately after FRAME is an unknown-directive error")
    void subcellOnlyAfterFrame() {
        String src = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 4 ROWS 2",
                "SUBCELL",
                "FRAME 100",
                "AB",
                "END");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(src));
    }

    // ── painter laws ────────────────────────────────────────────────────

    @Test
    @DisplayName("cursor-movement CSI inside a frame body is rejected, not skipped")
    void cursorMovementCsiRejected() {
        String src = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 4 ROWS 2",
                "FRAME 100",
                "\u001B[2;3HA",
                "END");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(src));
    }

    @Test
    @DisplayName("a lone ESC or truncated escape inside a frame body is rejected")
    void malformedEscapeRejected() {
        String lone = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 4 ROWS 2",
                "FRAME 100",
                "AB\u001B",
                "END");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(lone));

        String truncated = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 4 ROWS 2",
                "FRAME 100",
                "\u001B[38;2;1;2",
                "END");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(truncated));
    }

    @Test
    @DisplayName("truecolor background 48;2 parses into the cell's bg")
    void truecolorBg() {
        String src = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 4 ROWS 2",
                "FRAME 100",
                "\u001B[48;2;6;8;16m ",
                "END");
        var film = AnsiFilm.parse(src);
        var cell = film.frame(0).cells().getCell(0, 0);
        assertEquals(new RgbColor(6, 8, 16), cell.bg());
        assertEquals(" ", cell.character());
        // The Frame.cell() accessor rides the same grid.
        assertEquals(cell, film.frame(0).cell(0, 0));
    }

    // ── coverage-completion laws (every parser error arm is lawful) ─────

    @Test
    @DisplayName("a null pack source is a format error, not an NPE")
    void nullSourceRejected() {
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(null));
    }

    @Test
    @DisplayName("bare header keys (no argument) reject")
    void bareHeaderArgsRejected() {
        String bareFormat = String.join("\n",
                "FORMAT", "COUNT 1", "COLS 4 ROWS 2", "FRAME 100", "AB", "END");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(bareFormat));

        String bareCount = String.join("\n",
                "FORMAT pfilm1", "COUNT", "COLS 4 ROWS 2", "FRAME 100", "AB", "END");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(bareCount));

        String bareCols = String.join("\n",
                "FORMAT pfilm1", "COUNT 1", "COLS", "FRAME 100", "AB", "END");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(bareCols));
    }

    @Test
    @DisplayName("a bare FRAME (no duration) and a frame without an END both reject")
    void frameDirectiveMalformations() {
        String bareFrame = String.join("\n",
                "FORMAT pfilm1", "COUNT 1", "COLS 4 ROWS 2", "FRAME", "AB", "END");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(bareFrame));

        String unterminated = String.join("\n",
                "FORMAT pfilm1", "COUNT 1", "COLS 4 ROWS 2", "FRAME 100", "AB");
        var e = assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(unterminated));
        assertTrue(e.getMessage().contains("never ends"), e.getMessage());
    }

    @Test
    @DisplayName("non-painterly control characters and non-CSI escapes reject specifically")
    void painterlyRejections() {
        String control = String.join("\n",
                "FORMAT pfilm1", "COUNT 1", "COLS 4 ROWS 2", "FRAME 100", "A\u0001B", "END");
        var controlError = assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(control));
        assertTrue(controlError.getMessage().contains("not painterly"), controlError.getMessage());

        String loneEsc = String.join("\n",
                "FORMAT pfilm1", "COUNT 1", "COLS 4 ROWS 2", "FRAME 100", "A\u001BxB", "END");
        var loneError = assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(loneEsc));
        assertTrue(loneError.getMessage().contains("not painterly"), loneError.getMessage());
    }

    @Test
    @DisplayName("the full SGR styling table parses: modifiers, 39/49 defaults, the whole color matrix")
    void fullSgrTableLaw() {
        // Modifiers ride one after another, then disable in shared families.
        String mods = "\u001B[31mA"          // sets RED fg
                + "\u001B[2m\u001B[3m\u001B[4m\u001B[5m\u001B[7m\u001B[8m\u001B[9mB"
                + "\u001B[22m\u001B[23m\u001B[25m\u001B[27m\u001B[29mC"
                + "\u001B[1m\u001B[24m\u001B[28mD"
                + "\u001B[39mE\u001B[49mF";
        String src = bodyPack(20, mods);
        var cells = AnsiFilm.parse(src).frame(0).cells();
        var b = cells.getCell(1, 0);
        assertTrue(b.modifiers().containsAll(java.util.EnumSet.of(
                        SGR.DIM, SGR.ITALIC, SGR.UNDERLINE, SGR.BLINK, SGR.REVERSE, SGR.HIDDEN, SGR.STRIKETHROUGH)),
                "all modifier codes parse; got " + b.modifiers());
        var c = cells.getCell(2, 0);
        assertEquals(java.util.EnumSet.of(SGR.UNDERLINE, SGR.HIDDEN), c.modifiers(),
                "the shared disable families clear bold/dim/italic/blink/reverse/strike;"
                        + " underline and hidden wait for their own codes");
        var d = cells.getCell(3, 0);
        assertEquals(SGR.BOLD, d.modifiers().iterator().next());
        assertEquals(AnsiColor.RED, d.fg(), "earlier fg survives the modifier work");
        assertEquals(AnsiColor.DEFAULT, cells.getCell(4, 0).fg(), "39 resets fg to default");
        var f = cells.getCell(5, 0);
        assertEquals("F", f.character());
        assertEquals(AnsiColor.DEFAULT, f.bg(), "49 resets bg to default");
    }

    @Test
    @DisplayName("the 16-color matrix maps every fg and bg code onto its palette entry")
    void colorMatrixLaw() {
        StringBuilder body = new StringBuilder();
        int[] fgCodes = {30, 31, 32, 33, 34, 35, 36, 37, 90, 91, 92, 93, 94, 95, 96, 97};
        int[] bgCodes = {40, 41, 42, 43, 44, 45, 46, 47, 100, 101, 102, 103, 104, 105, 106, 107};
        for (int i = 0; i < 16; i++) {
            body.append("\u001B[").append(fgCodes[i]).append(';').append(bgCodes[i]).append('m')
                    .append((char) ('A' + i));
        }
        var cells = AnsiFilm.parse(bodyPack(16, body.toString())).frame(0).cells();
        for (int i = 0; i < 16; i++) {
            assertEquals(String.valueOf((char) ('A' + i)), cells.getCell(i, 0).character(),
                    "cell " + i);
            assertEquals(AnsiColor.values()[i], cells.getCell(i, 0).fg(),
                    "fg code " + fgCodes[i]);
            assertEquals(AnsiColor.values()[i], cells.getCell(i, 0).bg(),
                    "bg code " + bgCodes[i]);
        }
    }

    @Test
    @DisplayName("extended-color malformations reject: missing mode, out-of-range index, missing params")
    void extendedColorMalformations() {
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(
                bodyPack(2, "\u001B[38mX")));
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(
                bodyPack(2, "\u001B[38;5;300mX")));
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(
                bodyPack(2, "\u001B[38;2;1mX")));
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(
                bodyPack(2, "\u001B[38;7;1;2;3mX")));
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(
                bodyPack(2, "\u001B[48;9;1mX")));
    }

    @Test
    @DisplayName("unknown SGR codes and non-numeric parameters reject")
    void sgrMalformations() {
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(
                bodyPack(2, "\u001B[10mX")));
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(
                bodyPack(2, "\u001B[kmX")));
    }

    @Test
    @DisplayName("empty SGR forms are resets: ESC[m and an empty parameter slot")
    void emptySgrForms() {
        var cells = AnsiFilm.parse(bodyPack(4,
                "\u001B[31m\u001B[mX\u001B[32;;35mY")).frame(0).cells();
        assertEquals(AnsiColor.DEFAULT, cells.getCell(0, 0).fg(), "ESC[m is a full reset");
        var y = cells.getCell(1, 0);
        assertEquals("Y", y.character());
        assertEquals(AnsiColor.MAGENTA, y.fg(),
                "the empty parameter slot is a mid-sequence reset, so Y paints magenta");
        assertEquals(AnsiColor.DEFAULT, y.bg(), "the empty slot reset the background brush");
    }

    @Test
    @DisplayName("wide glyphs parse into double-width cells (the film delta skips their twin column)")
    void wideGlyphCellLaw() {
        var film = AnsiFilm.parse(bodyPack(2, "\u66F4\u65B0"));
        var wide = film.frame(0).cells().getCell(0, 0);
        assertTrue(wide.isDoubleWidth(), "CJK glyphs are double-width cells");
        assertEquals("\u66F4", wide.character());
    }

    @Test
    @DisplayName("mid-line CR overwrites the row from the left; extended-mode NFEs reject")
    void coverageCompletions() {
        // A mid-line CR: "AB\rCD" overwrites the row leftward from C.
        var cells = AnsiFilm.parse(bodyPack(2, "AB\rC")).frame(0).cells();
        assertEquals("C", cells.getCell(0, 0).character(), "the CR restarted the row");
        assertEquals("B", cells.getCell(1, 0).character(), "the right cell survived");

        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(
                bodyPack(2, "\u001B[38;kmX")), "the extended-color mode must be numeric");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(
                bodyPack(2, "\u001B[38;5;kmX")), "the 256-color index must be numeric");
    }

    @Test
    @DisplayName("DELT and non-digit SGR junk rejects; a late SUBCELL mid-body rejects")
    void coverageCompletions2() {
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(
                bodyPack(2, "\u0001X\u007F")), "control junk rejects");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(
                bodyPack(2, "\u001B[3!mX")), "SGR params must be numeric");
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(
                bodyPack(2, "\u001B[38;!mX")), "the extended mode must be numeric");
        // SUBCELL after the body started (not right after FRAME) is a reserve
        // violation: the marker is reserved, never silently skipped.
        String late = String.join("\n",
                "FORMAT pfilm1", "COUNT 1", "COLS 4 ROWS 2",
                "FRAME 100", "AB", "SUBCELL", "END");
        var e = assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(late));
        assertTrue(e.getMessage().contains("SUBCELL"), e.getMessage());
    }

    @Test
    @DisplayName("an empty source and a source ending at the header both reject as missing lines")
    void missingHeaderLines() {
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse(""));
        assertThrows(AnsiFilm.FormatException.class, () -> AnsiFilm.parse("FORMAT pfilm1\n"));
        assertThrows(AnsiFilm.FormatException.class, () ->
                AnsiFilm.parse("FORMAT pfilm1\nCOUNT 1\n"));
    }

    /** Wraps a body into a minimal lawful single-frame pack of the given cols. */
    private static String bodyPack(int cols, String body) {
        return String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS " + cols + " ROWS 1",
                "FRAME 100",
                body,
                "END");
    }
}
