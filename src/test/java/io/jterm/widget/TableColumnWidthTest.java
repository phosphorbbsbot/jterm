package io.jterm.widget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for per-column width constraints in {@link Table}: exact percentage,
 * minimum/maximum in characters or percent of available width.
 */
class TableColumnWidthTest {

    // ── helpers ────────────────────────────────────────────────────────

    private Table table(String[] headers, String[]... rows) {
        var model = new io.jterm.widget.model.DefaultTableModel(headers);
        for (String[] row : rows) {
            model.addRow(row);
        }
        return new Table(model);
    }

    private void draw(Table table, int width, int height) {
        var buffer = new io.jterm.screen.ScreenBuffer(new io.jterm.core.TerminalSize(width, height));
        table.setBounds(io.jterm.core.TerminalPosition.TOP_LEFT, new io.jterm.core.TerminalSize(width, height));
        table.draw(new io.jterm.graphics.TextGraphics(buffer));
    }

    // ── percentage width ───────────────────────────────────────────────

    @Test
    void percentWidthTakesExactShareOfAvailable() {
        Table t = table(new String[]{"A", "B"},
                new String[]{"xxxx", "yyyy"});
        t.setColumnWidthPct(0, 50.0);
        draw(t, 41, 5);
        // available = 41 - 1 separator = 40; 50% = 20 (rounded)
        assertEquals(20, t.getColumnWidth(0),
                "50% of 40 usable columns should be 20, got " + t.getColumnWidth(0));
    }

    @Test
    void percentWidthsSumToAvailable() {
        Table t = table(new String[]{"A", "B", "C"},
                new String[]{"xx", "yy", "zz"});
        t.setColumnWidthPct(0, 25.0);
        t.setColumnWidthPct(1, 50.0);
        t.setColumnWidthPct(2, 25.0);
        draw(t, 61, 5);
        int usable = 61 - 2; // two separators
        int total = t.getColumnWidth(0) + t.getColumnWidth(1) + t.getColumnWidth(2);
        assertTrue(total <= usable && total >= usable - 2,
                "pct columns should consume ~all usable width (" + total + " vs " + usable + ")");
    }

    // ── minimum constraints ────────────────────────────────────────────

    @Test
    void minWidthInCharsForcesWiderColumn() {
        Table t = table(new String[]{"ID", "NAME"},
                new String[]{"1", "bob"});
        t.setColumnMinWidth(0, 15);
        draw(t, 40, 5);
        assertTrue(t.getColumnWidth(0) >= 15,
                "min width 15 chars must hold, got " + t.getColumnWidth(0));
    }

    @Test
    void minWidthPctForcesWiderColumn() {
        Table t = table(new String[]{"ID", "NAME"},
                new String[]{"1", "bob"});
        t.setColumnMinWidthPct(0, 50.0);
        draw(t, 61, 5);
        int usable = 61 - 1;
        assertTrue(t.getColumnWidth(0) >= (int) Math.floor(usable * 0.50) - 1,
                "min 50% must hold, got " + t.getColumnWidth(0));
    }

    // ── maximum constraints ────────────────────────────────────────────

    @Test
    void maxWidthInCharsCapsLongContent() {
        String long1 = "x".repeat(60);
        Table t = table(new String[]{"H0", "H1"},
                new String[]{long1, "s"});
        t.setColumnMaxWidth(0, 10);
        draw(t, 80, 5);
        assertTrue(t.getColumnWidth(0) <= 10,
                "max width 10 must cap content, got " + t.getColumnWidth(0));
    }

    @Test
    void maxWidthPctCapsLongContent() {
        String long1 = "x".repeat(60);
        Table t = table(new String[]{"H0", "H1"},
                new String[]{long1, "s"});
        t.setColumnMaxWidthPct(0, 20.0);
        draw(t, 61, 5);
        int usable = 61 - 1;
        assertTrue(t.getColumnWidth(0) <= (int) Math.ceil(usable * 0.20) + 1,
                "max 20% must cap content, got " + t.getColumnWidth(0));
    }

    // ── overflow safety ────────────────────────────────────────────────

    @Test
    void overConstrainedColumnsStillFitTerminal() {
        // sum of pcts = 120% must degrade gracefully: total <= available
        Table t = table(new String[]{"A", "B", "C"},
                new String[]{"aaaaaaaaaa", "bbbbbbbbbb", "cccccccccc"});
        t.setColumnWidthPct(0, 80.0);
        t.setColumnWidthPct(1, 30.0);
        t.setColumnWidthPct(2, 40.0);
        draw(t, 41, 5);
        int sep = 2;
        int total = t.getColumnWidth(0) + t.getColumnWidth(1) + t.getColumnWidth(2) + sep;
        assertTrue(total <= 41,
                "over-constrained pct widths must not overflow terminal: " + total);
        for (int c = 0; c < 3; c++) {
            assertTrue(t.getColumnWidth(c) >= 1, "column " + c + " collapsed to 0");
        }
    }

    @Test
    void minAboveMaxClampsToMax() {
        Table t = table(new String[]{"H0"},
                new String[]{"abc"});
        t.setColumnMinWidth(0, 30);
        t.setColumnMaxWidth(0, 10);
        draw(t, 40, 5);
        assertTrue(t.getColumnWidth(0) <= 10, "max must win over min");
    }

    // ── invalid input / bounds safety ──────────────────────────────────

    @Test
    void invalidArgsAreIgnored() {
        Table t = table(new String[]{"H0", "H1"},
                new String[]{"a", "b"});
        assertDoesNotThrow(() -> t.setColumnWidthPct(-1, 50.0));
        assertDoesNotThrow(() -> t.setColumnWidthPct(0, -5.0));
        assertDoesNotThrow(() -> t.setColumnWidthPct(0, 0.0));
        assertDoesNotThrow(() -> t.setColumnWidthPct(0, 150.0));
        assertDoesNotThrow(() -> t.setColumnMinWidth(0, -3));
        assertDoesNotThrow(() -> t.setColumnMaxWidth(0, -3));
        assertDoesNotThrow(() -> t.setColumnMinWidthPct(0, -10.0));
        assertDoesNotThrow(() -> t.setColumnMaxWidthPct(0, 300.0));
        // out of bounds col index: silent no-op
        assertDoesNotThrow(() -> t.setColumnWidthPct(99, 50.0));
    }

    @Test
    void clearColumnWidthConstraintsRestoresAutoSizing() {
        String long1 = "x".repeat(60);
        Table t = table(new String[]{"H0", "H1"},
                new String[]{long1, "s"});
        t.setColumnMaxWidth(0, 8);
        draw(t, 100, 5);
        assertTrue(t.getColumnWidth(0) <= 8);
        t.clearColumnWidthConstraints(0);
        draw(t, 100, 5);
        // legacy even-share auto-sizing: floor 49, content 60 capped at 50
        assertEquals(50, t.getColumnWidth(0),
                "after clearing, legacy auto-sizing (even share) returns");
    }

    // ── interplay with content ─────────────────────────────────────────

    @Test
    void unconstrainedColumnsStillShrinkWhenSpaceRunsOut() {
        // col0 constrained to 50%; col1 long content, no constraint.
        // 41 cols, 1 sep -> 40 usable; col0 = 20, col1 must fit in 20.
        String long1 = "y".repeat(60);
        Table t = table(new String[]{"H0", "H1"},
                new String[]{long1, long1});
        t.setColumnWidthPct(0, 50.0);
        draw(t, 41, 5);
        int total = t.getColumnWidth(0) + t.getColumnWidth(1) + 1;
        assertTrue(total <= 41, "total " + total + " overflows");
        assertEquals(20, t.getColumnWidth(0));
        assertTrue(t.getColumnWidth(1) >= 1);
    }

    @Test
    void pctOverridesNaturalWidthEvenWhenContentShorter() {
        // content is 2 wide but pct demands 60% -> column expands, text left-aligned
        Table t = table(new String[]{"H0", "H1"},
                new String[]{"ab", "cd"});
        t.setColumnWidthPct(0, 60.0);
        draw(t, 41, 5);
        int usable = 40;
        assertEquals((int) Math.round(usable * 0.60), t.getColumnWidth(0),
                "pct width should force wider column than content");
    }
}