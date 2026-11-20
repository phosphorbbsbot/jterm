package io.jterm.screen;

import io.jterm.core.ColorDepth;
import io.jterm.core.TerminalSize;
import io.jterm.style.AnsiColor;
import io.jterm.style.Color;
import io.jterm.style.IndexedColor;
import io.jterm.style.RgbColor;
import io.jterm.style.TextCell;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Laws for {@link HalfBlockBuffer} — sub-cell (2× vertical) resolution via
 * Unicode half blocks (2026-11-19 animation plan, card 4).
 *
 * <p>Each cell-pair (top, bottom) encodes to exactly one block character:
 * FULL BLOCK when the halves agree, a lone half block when one half is
 * default/transparent, the classic chart-renderer split ▀ (fg=top, bg=bottom)
 * when they differ (the chart renderer's ▀▄ trick, generalised). Colors ride
 * the MAPPED tier (like {@link DefaultScreen.SgrStateTracker}) so the encode
 * and the delta law both see the colors the wire will carry.</p>
 */
class HalfBlockBufferTest {

    private static final RgbColor GOLD = new RgbColor(255, 214, 71);
    private static final RgbColor RED_DEEP = new RgbColor(170, 0, 0);
    /** A red that is NOT RED_DEEP on the wire but maps to the same ANSI-16 entry. */
    private static final RgbColor RED_NEAR = new RgbColor(250, 0, 0);

    private static String bytes(byte[] out) {
        return new String(out, StandardCharsets.UTF_8);
    }

    private static DefaultScreen.SgrStateTracker trackerAt(ColorDepth depth) {
        return new DefaultScreen.SgrStateTracker(() -> depth);
    }

    // ------------------------------------------------------------------
    // Construction laws.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a zero or negative dimension is rejected (fail loudly)")
    void rejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException.class, () -> new HalfBlockBuffer(0, 8));
        assertThrows(IllegalArgumentException.class, () -> new HalfBlockBuffer(40, 0));
        assertThrows(IllegalArgumentException.class, () -> new HalfBlockBuffer(-1, -1));
        assertThrows(IllegalArgumentException.class, () -> HalfBlockBuffer.forSubRows(40, 0));
        assertThrows(IllegalArgumentException.class, () -> HalfBlockBuffer.forSubRows(0, 5));
    }

    @Test
    @DisplayName("a cell-space buffer holds cols x 2*rows sub-cells; forSubRows rounds a half up")
    void gridDimensions() {
        var even = new HalfBlockBuffer(40, 12);
        assertEquals(40, even.columns());
        assertEquals(12, even.rows());
        assertEquals(24, even.subRows());

        var odd = HalfBlockBuffer.forSubRows(40, 25);
        assertEquals(40, odd.columns());
        assertEquals(13, odd.rows());
        assertEquals(25, odd.subRows());
    }

    @Test
    @DisplayName("a null depth is rejected (fail loudly; use the no-arg toScreenBuffer for the truecolor law)")
    void rejectsNullDepth() {
        var buf = new HalfBlockBuffer(2, 1);
        assertThrows(NullPointerException.class, () -> buf.toScreenBuffer((ColorDepth) null));
        assertThrows(NullPointerException.class,
                () -> HalfBlockBuffer.encodeCellPair(AnsiColor.RED, AnsiColor.BLUE, null));
    }

    // ------------------------------------------------------------------
    // Encode table: every combination → exact char + fg/bg (golden).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("encode table: (null, null) → space with default attrs (TextCell.EMPTY)")
    void encodeNullPair() {
        var cell = HalfBlockBuffer.encodeCellPair(null, null, ColorDepth.TRUECOLOR);
        assertEquals(TextCell.EMPTY, cell);
        assertSame(TextCell.EMPTY, cell, "the empty pair is the shared EMPTY cell");
    }

    @Test
    @DisplayName("encode table: (C, C) → FULL BLOCK, fg = C, bg default (byte law)")
    void encodeSameColorPair() {
        var cell = HalfBlockBuffer.encodeCellPair(GOLD, GOLD, ColorDepth.TRUECOLOR);
        assertFullBlock(cell, GOLD, "identical halves share one fg sequence instead of fg+bg");
    }

    @Test
    @DisplayName("encode table: (null, C) → ▄, fg = C, bg default")
    void encodeBottomOnlyPair() {
        var cell = HalfBlockBuffer.encodeCellPair(null, GOLD, ColorDepth.TRUECOLOR);
        assertEquals("\u2584", cell.character());
        assertSame(GOLD, cell.fg(), "bottom-only paints the bottom color as fg");
        assertEquals(AnsiColor.DEFAULT, cell.bg(), "bg stays default");
        assertTrue(cell.modifiers().isEmpty());
    }

    @Test
    @DisplayName("encode table: (C, null) → ▀, fg = C, bg default")
    void encodeTopOnlyPair() {
        var cell = HalfBlockBuffer.encodeCellPair(GOLD, null, ColorDepth.TRUECOLOR);
        assertEquals("\u2580", cell.character());
        assertSame(GOLD, cell.fg(), "top-only paints the top color as fg");
        assertEquals(AnsiColor.DEFAULT, cell.bg(), "bg stays default");
        assertTrue(cell.modifiers().isEmpty());
    }

    @Test
    @DisplayName("encode table: (C, D), C≠D → ▀, fg = C, bg = D (the chart-split)")
    void encodeMixedPair() {
        var cell = HalfBlockBuffer.encodeCellPair(GOLD, new RgbColor(0, 64, 192), ColorDepth.TRUECOLOR);
        assertEquals("\u2580", cell.character());
        assertSame(GOLD, cell.fg(), "top half rides fg");
        assertEquals(new RgbColor(0, 64, 192), cell.bg(), "bottom half rides bg");
        assertTrue(cell.modifiers().isEmpty());
    }

    @Test
    @DisplayName("encode table: the byte law prefers FULL BLOCK over the fg+bg split when halves agree")
    void byteLawPrefersFullBlock() {
        var chosenTracker = trackerAt(ColorDepth.TRUECOLOR);
        chosenTracker.transitionTo(TextCell.EMPTY);
        byte[] chosen = chosenTracker.transitionTo(
                HalfBlockBuffer.encodeCellPair(GOLD, GOLD, ColorDepth.TRUECOLOR));
        assertEquals("\u001b[38;2;255;214;71m", bytes(chosen),
                "one sequence: fg only (bg already default, never re-emitted)");

        var splitTracker = trackerAt(ColorDepth.TRUECOLOR);
        splitTracker.transitionTo(TextCell.EMPTY);
        String split = bytes(splitTracker.transitionTo(
                new TextCell('\u2580', GOLD, GOLD)));
        assertTrue(split.length() > chosen.length,
                "the ▀-with-split-bg alternative costs a second sequence: " + split);
        assertEquals(2, split.chars().filter(c -> c == 'm').count(),
                "split alternative emits fg AND bg");

        assertEquals(0L, chosenTracker.transitionTo(
                HalfBlockBuffer.encodeCellPair(GOLD, GOLD, ColorDepth.TRUECOLOR)).length,
                "adjacent same-color full blocks ride the carried state — zero bytes");
    }

    // ------------------------------------------------------------------
    // Depth laws: sub-cell colors map through the existing pipeline BEFORE
    // encode — mapped equality collapses the pair.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ANSI16: distinct RgbColor reds mapping to the same palette entry collapse to FULL BLOCK")
    void ansi16MappedCollapse() {
        var cell = HalfBlockBuffer.encodeCellPair(RED_NEAR, RED_DEEP, ColorDepth.ANSI16);
        assertFullBlock(cell, AnsiColor.RED, "both halves map to RED — one block, one sequence");
    }

    @Test
    @DisplayName("C256: mixed pair encodes ▀ with the mapped cube/grid indices")
    void c256MixedPairUsesMappedColors() {
        var cell = HalfBlockBuffer.encodeCellPair(
                new RgbColor(255, 0, 0), new RgbColor(0, 0, 255), ColorDepth.C256);
        assertEquals("\u2580", cell.character());
        assertEquals(new IndexedColor(196), cell.fg(), "pure red → cube index 196");
        assertEquals(new IndexedColor(21), cell.bg(), "pure blue → cube index 21");
    }

    @Test
    @DisplayName("ANSI16: half pairs carry the mapped palette entry (gold → BRIGHT_YELLOW)")
    void ansi16HalfPairsCarryMappedColor() {
        var top = HalfBlockBuffer.encodeCellPair(GOLD, null, ColorDepth.ANSI16);
        assertEquals("\u2580", top.character());
        assertEquals(AnsiColor.BRIGHT_YELLOW, top.fg(), "gold maps to BRIGHT_YELLOW before encode");
        assertEquals(AnsiColor.DEFAULT, top.bg());

        var bottom = HalfBlockBuffer.encodeCellPair(null, GOLD, ColorDepth.ANSI16);
        assertEquals("\u2584", bottom.character());
        assertEquals(AnsiColor.BRIGHT_YELLOW, bottom.fg());
        assertEquals(AnsiColor.DEFAULT, bottom.bg());
    }

    @Test
    @DisplayName("TRUECOLOR: colors pass through by identity — mapped unchanged (the byte-identical law)")
    void truecolorPassesThroughByIdentity() {
        var top = new RgbColor(255, 214, 71);
        var bottom = new RgbColor(0, 64, 192);
        var cell = HalfBlockBuffer.encodeCellPair(top, bottom, ColorDepth.TRUECOLOR);
        assertSame(top, cell.fg(), "TRUECOLOR mapping is the identity");
        assertSame(bottom, cell.bg());
    }

    @Test
    @DisplayName("pre-painted palette colors ride untouched at every tier (non-RgbColor passthrough)")
    void prePaintedColorsPassThrough() {
        var a16 = HalfBlockBuffer.encodeCellPair(AnsiColor.RED, AnsiColor.BLUE, ColorDepth.ANSI16);
        assertEquals("\u2580", a16.character());
        assertSame(AnsiColor.RED, a16.fg());
        assertSame(AnsiColor.BLUE, a16.bg());

        var c256 = HalfBlockBuffer.encodeCellPair(AnsiColor.RED, new IndexedColor(21), ColorDepth.C256);
        assertSame(AnsiColor.RED, c256.fg(), "AnsiColor is never degraded again");
        assertEquals(new IndexedColor(21), c256.bg());
    }

    // ------------------------------------------------------------------
    // Grid laws: out-of-bounds is space-fill; default grid encodes empty.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a fresh grid is all default: null reads and EMPTY encoded cells")
    void freshGridIsAllDefault() {
        var buf = new HalfBlockBuffer(4, 3);
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 3; r++) {
                assertNull(buf.getSubCell(c, 2 * r), "top half defaults null");
                assertNull(buf.getSubCell(c, 2 * r + 1), "bottom half defaults null");
            }
        }
        var screen = buf.toScreenBuffer();
        assertEquals(new TerminalSize(4, 3), screen.size());
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 3; r++) {
                assertEquals(TextCell.EMPTY, screen.getCell(c, r));
            }
        }
    }

    @Test
    @DisplayName("set/get round-trip; out-of-bounds writes are ignored and reads are null (space-fill law)")
    void gridBoundsLaws() {
        var buf = new HalfBlockBuffer(4, 3);
        buf.setSubCell(1, 2, GOLD);
        assertSame(GOLD, buf.getSubCell(1, 2));
        buf.setSubCell(1, 2, null);
        assertNull(buf.getSubCell(1, 2), "null clears the sub-cell");

        buf.setSubCell(-1, 0, GOLD);
        buf.setSubCell(4, 0, GOLD);
        buf.setSubCell(0, -1, GOLD);
        buf.setSubCell(0, 6, GOLD);
        assertNull(buf.getSubCell(0, 0), "out-of-bounds writes are ignored");
        assertNull(buf.getSubCell(-1, 0), "out-of-bounds reads are null");
        assertNull(buf.getSubCell(4, 0), "out-of-bounds reads are null");
        assertNull(buf.getSubCell(0, -1), "out-of-bounds reads are null");
        assertNull(buf.getSubCell(0, 6), "out-of-bounds reads are null");
    }

    @Test
    @DisplayName("odd sub-cell heights: the partnerless bottom half is space-fill — ▀ or space, never an exception")
    void oddSubRowLaw() {
        var buf = HalfBlockBuffer.forSubRows(3, 5);
        buf.setSubCell(1, 4, GOLD);          // trailing sub-row = top of the last (half-empty) pair

        var screen = buf.toScreenBuffer();
        assertEquals("\u2580", screen.getCell(1, 2).character(),
                "the odd trailing sub-row paints as its own top half");
        assertSame(GOLD, screen.getCell(1, 2).fg());
        assertEquals(AnsiColor.DEFAULT, screen.getCell(1, 2).bg());
        assertEquals(TextCell.EMPTY, screen.getCell(0, 2), "an all-null odd pair stays space");

        assertNull(buf.getSubCell(0, 5), "the absent bottom half reads null");
        assertNull(buf.getSubCell(0, 6), "beyond the grid reads null");
        buf.setSubCell(2, 5, RED_DEEP);      // below the sub-grid: must be ignored, not stored
        var after = buf.toScreenBuffer();
        assertEquals(TextCell.EMPTY, after.getCell(2, 2),
                "the ignored sub-cell never reaches the encode");
    }

    @Test
    @DisplayName("out-of-bounds pair reads: encodedCellPair is EMPTY off the grid (buffer-level space fill)")
    void encodedCellPairOutOfBoundsIsSpace() {
        var buf = new HalfBlockBuffer(2, 2);
        assertEquals(TextCell.EMPTY, buf.encodedCellPair(0, -1, ColorDepth.TRUECOLOR));
        assertEquals(TextCell.EMPTY, buf.encodedCellPair(-1, 0, ColorDepth.TRUECOLOR));
        assertEquals(TextCell.EMPTY, buf.encodedCellPair(2, 1, ColorDepth.TRUECOLOR));
        assertEquals(TextCell.EMPTY, buf.encodedCellPair(0, 2, ColorDepth.TRUECOLOR));
        buf.setSubCell(0, 0, GOLD);
        assertEquals("\u2580", buf.encodedCellPair(0, 0, ColorDepth.TRUECOLOR).character(),
                "in-bounds reads still encode");
    }

    // ------------------------------------------------------------------
    // Delta law: MAPPED comparisons — a pair that maps identically emits
    // zero redundant sequences on the next frame.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("delta (ANSI16): swapped raw reds map to the same pair — the re-encode emits zero bytes")
    void ansi16DeltaLaw() {
        var buf = new HalfBlockBuffer(2, 2);
        buf.setSubCell(0, 0, RED_NEAR);
        buf.setSubCell(0, 1, RED_DEEP);
        var cellA = buf.encodedCellPair(0, 0, ColorDepth.ANSI16);

        buf.setSubCell(0, 0, RED_DEEP);      // swap the two raws
        buf.setSubCell(0, 1, RED_NEAR);
        var cellB = buf.encodedCellPair(0, 0, ColorDepth.ANSI16);

        assertFullBlock(cellA, AnsiColor.RED, "first frame: FULL BLOCK of mapped RED");
        assertFullBlock(cellB, AnsiColor.RED, "second frame: MAPPED pair identical");

        var tracker = trackerAt(ColorDepth.ANSI16);
        tracker.transitionTo(TextCell.EMPTY);
        tracker.transitionTo(cellA);
        assertEquals(0, tracker.transitionTo(cellB).length,
                "mapped-identical pair emits zero redundant sequences");
    }

    @Test
    @DisplayName("delta (C256): a later frame's sub-cells inside the same 256 bucket emit zero bytes")
    void c256DeltaLaw() {
        var tracker = trackerAt(ColorDepth.C256);
        tracker.transitionTo(TextCell.EMPTY);
        var first = HalfBlockBuffer.encodeCellPair(new RgbColor(255, 255, 255), null, ColorDepth.C256);
        assertEquals("\u001b[38;5;231m", bytes(tracker.transitionTo(first)));
        var second = HalfBlockBuffer.encodeCellPair(new RgbColor(255, 254, 254), null, ColorDepth.C256);
        assertEquals(0, tracker.transitionTo(second).length,
                "RGB(255,255,255) and RGB(255,254,254) both land on cube 231 — zero redundant bytes");
    }

    @Test
    @DisplayName("delta (TRUECOLOR): byte-identical frames emit zero sequences")
    void truecolorDeltaLaw() {
        var tracker = trackerAt(ColorDepth.TRUECOLOR);
        tracker.transitionTo(TextCell.EMPTY);
        var first = HalfBlockBuffer.encodeCellPair(GOLD, new RgbColor(0, 64, 192), ColorDepth.TRUECOLOR);
        assertEquals("\u001b[38;2;255;214;71m\u001b[48;2;0;64;192m", bytes(tracker.transitionTo(first)));
        var second = HalfBlockBuffer.encodeCellPair(GOLD, new RgbColor(0, 64, 192), ColorDepth.TRUECOLOR);
        assertEquals(0, tracker.transitionTo(second).length,
                "identical pairs ride the carried SGR state — zero bytes");
    }

    @Test
    @DisplayName("frame diff: re-encoding an unchanged grid yields an empty ScreenBuffer diff")
    void reencodeIsDiffFree() {
        var buf = new HalfBlockBuffer(6, 4);
        buf.setSubCell(0, 0, GOLD);
        buf.setSubCell(0, 1, GOLD);
        buf.setSubCell(1, 3, RED_DEEP);
        var screenA = buf.toScreenBuffer();
        var screenB = buf.toScreenBuffer();
        assertTrue(screenA.diffFrom(screenB).isEmpty(),
                "the same grid encodes to the same screen buffer");
    }

    @Test
    @DisplayName("default-reset shortcut preserved: a colored pair collapsing to EMPTY emits the single reset")
    void defaultResetShortcutPreserved() {
        var buf = new HalfBlockBuffer(1, 1);
        buf.setSubCell(0, 0, GOLD);
        buf.setSubCell(0, 1, new RgbColor(0, 64, 192));
        var colored = buf.encodedCellPair(0, 0, ColorDepth.TRUECOLOR);

        buf.setSubCell(0, 0, null);          // frame two clears the pair
        buf.setSubCell(0, 1, null);
        var cleared = buf.encodedCellPair(0, 0, ColorDepth.TRUECOLOR);
        assertSame(TextCell.EMPTY, cleared, "the cleared pair encodes to EMPTY");

        var tracker = trackerAt(ColorDepth.TRUECOLOR);
        tracker.transitionTo(TextCell.EMPTY);
        tracker.transitionTo(colored);
        assertEquals("\u001b[0m", bytes(tracker.transitionTo(cleared)),
                "the null law rides the single SGR reset shortcut");
    }

    // ------------------------------------------------------------------
    // Screen-level integration: an encoded buffer paints through
    // DefaultScreen on every tier (film card's SUBCELL smoke stand-in).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DefaultScreen paints the encoded buffer in golden truecolor at default depth")
    void screenPaintsEncodedBufferTruecolor() throws Exception {
        var buf = new HalfBlockBuffer(2, 2);
        buf.setSubCell(0, 0, GOLD);                       // (0,0): (gold, null)      → ▀
        buf.setSubCell(1, 1, new RgbColor(0, 64, 192));   // (1,0): (null, blue)      → ▄
        buf.setSubCell(0, 2, GOLD);                       // (0,1): (gold, blue)      → ▀ with split bg
        buf.setSubCell(0, 3, new RgbColor(0, 64, 192));
        var screen = buf.toScreenBuffer();

        var terminal = new io.jterm.core.MockTerminal(new io.jterm.core.TerminalSize(80, 24));
        var view = new DefaultScreen(terminal);
        view.startScreen();
        for (int c = 0; c < 2; c++) {
            for (int r = 0; r < 2; r++) {
                view.setCell(c, r, screen.getCell(c, r));
            }
        }
        view.refresh();
        String full = terminal.getOutput();

        assertTrue(full.contains("\u2580"), "top half block reaches the wire: " + full);
        assertTrue(full.contains("\u2584"), "bottom half block reaches the wire: " + full);
        assertTrue(full.contains("\u001b[38;2;255;214;71m"), "golden truecolor fg: " + full);
        assertTrue(full.contains("\u001b[38;2;0;64;192m"), "bottom-half truecolor fg: " + full);
        assertTrue(full.contains("\u001b[48;2;0;64;192m"), "split-pair truecolor bg (fg null side skipped): " + full);
    }

    @Test
    @DisplayName("DefaultScreen degrades the encoded buffer on a C256 tier — no truecolor bytes leak")
    void screenPaintsEncodedBufferC256() throws Exception {
        var buf = new HalfBlockBuffer(2, 2);
        buf.setSubCell(0, 0, GOLD);
        buf.setSubCell(0, 1, GOLD);
        var screen = buf.toScreenBuffer(ColorDepth.C256);

        var terminal = new io.jterm.core.MockTerminal(new io.jterm.core.TerminalSize(80, 24));
        terminal.setColorDepth(ColorDepth.C256);
        var view = new DefaultScreen(terminal);
        view.startScreen();
        for (int c = 0; c < 2; c++) {
            for (int r = 0; r < 2; r++) {
                view.setCell(c, r, screen.getCell(c, r));
            }
        }
        view.refresh();
        String full = terminal.getOutput();

        assertTrue(full.contains("\u001b[38;5;221m"), "mapped cube gold (fg only): " + full);
        assertFalse(full.contains("38;2;"), "no truecolor bytes below the declared tier: " + full);
        assertEquals("\u2588", findCellChar(full), "the FULL BLOCK char reaches the wire");
    }

    /** The first non-escape character in the terminal stream (single-cell paint). */
    private static String findCellChar(String wire) {
        int i = 0;
        while (i < wire.length()) {
            char c = wire.charAt(i);
            if (c == '\u001b') { while (i < wire.length() && wire.charAt(i) != 'm') i++; i++; continue; }
            return String.valueOf(c);
        }
        return null;
    }

    private static void assertFullBlock(TextCell cell, Color expectedFg, String why) {
        assertEquals("\u2588", cell.character(), why);
        assertEquals(expectedFg, cell.fg(), why);
        assertEquals(AnsiColor.DEFAULT, cell.bg(), why);
        assertTrue(cell.modifiers().isEmpty(), why);
    }
}
