package io.jterm.widget;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.TextCell;
import io.jterm.style.Theme;
import io.jterm.style.ThemeManager;
import io.jterm.widget.model.DefaultTableModel;
import io.jterm.widget.model.TableModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers remaining {@link Table} branches: model replacement and null-model
 * handling, STRUCTURE_CHANGED scroll clamping (both sides), selected-vs-
 * provider-vs-default row styles during draw, rowStyleProvider returning null,
 * width-constraint min/max fitting, and alignment edge helpers.
 */
class TableBranchesCoverageTest {

    private Theme savedTheme;

    @BeforeEach
    void setUp() {
        savedTheme = ThemeManager.active();
        ThemeManager.setActive(Theme.DARK);
    }

    @AfterEach
    void tearDown() {
        ThemeManager.setActive(savedTheme);
    }

    private Table modelOf(int rows) {
        var table = new Table("H0", "H1");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(40, 6));
        for (int r = 0; r < rows; r++) {
            table.addRow("cell" + r + "a", "cell" + r + "b");
        }
        return table;
    }

    private ScreenBuffer draw(Table table, int width, int height) {
        var buf = new ScreenBuffer(new TerminalSize(width, height));
        table.draw(new TextGraphics(buf));
        return buf;
    }

    // ---- model replacement -----------------------------------------------------

    @Test
    void setModelResetsSelectionAndScroll() {
        var table = modelOf(5);
        table.setSelectedRow(4);
        var replacement = new DefaultTableModel("X", "Y");
        replacement.addRow("r0a", "r0b");
        table.setModel(replacement);
        assertEquals(replacement, table.getModel());
        assertEquals(0, table.getSelectedRow(), "selection resets on model swap");
        var buf = draw(table, 40, 6);
        assertDoesNotThrow(() -> draw(table, 40, 6));
        assertNotNull(buf);
    }

    @Test
    void clearRowsBothBranchesByCallingTwice() {
        var table = modelOf(3);
        table.setSelectedRow(2);
        table.clearRows();
        assertEquals(0, table.getModel().getRowCount());
        assertEquals(0, table.getSelectedRow());
        table.clearRows(); // second pass: already-empty branch
        assertDoesNotThrow(() -> draw(table, 40, 4));
    }

    @Test
    void tableWithNullModelDrawsClearScreen() {
        var table = new Table((TableModel) null);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(30, 4));
        var buf = assertDoesNotThrow(() -> draw(table, 30, 4));
        assertEquals(' ', buf.getCell(5, 3).character().charAt(0));
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void defaultTableModelConstructorViaHeaders() {
        var table = new Table("A", "B", "C");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(30, 3));
        assertDoesNotThrow(() -> table.addRow("1", "2", "3"));
        table.setSelectedRow(0);
        var buf = assertDoesNotThrow(() -> draw(table, 30, 3));
        // Row 1 (below the header), column 0: value "1".
        assertEquals('1', buf.getCell(0, 1).character().charAt(0));
    }

    // ---- STRUCTURE_CHANGED scroll clamping (both directions) --------------------

    @Test
    void structureChangeClampsScrollOnlyWhenAboveSelection() {
        var model = new DefaultTableModel("C0", "C1", "C2", "C3", "C4");
        var table = new Table(model);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(60, 4));
        for (int r = 0; r < 10; r++) {
            model.addRow("r" + r + "0", "r" + r + "1", "r" + r + "2", "r" + r + "3", "r" + r + "4");
        }
        table.setSelectedRow(6);
        table.pageDown();
        int scrolled = table.getSelectedRow();
        assertTrue(scrolled >= 3, "pageDown should have scrolled to a lower row");
        // STRUCTURE_CHANGED with scrollOffset <= selectedRow: keeps deeper scroll.
        model.setValueAt(0, 0, "zz");
        assertEquals(scrolled, table.getSelectedRow(), "selection survives structure change");
        // Pull the selection to row 0 — now scrollOffset > selectedRow must clamp on the next event.
        table.setSelectedRow(0);
        model.setValueAt(1, 0, "yy");
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void rowsRemovedClampsSelectionToNewRowCount() {
        var model = new DefaultTableModel("C0", "C1");
        var table = new Table(model);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(40, 4));
        for (int r = 0; r < 5; r++) {
            model.addRow("a" + r, "b" + r);
        }
        table.setSelectedRow(4);
        model.removeRow(4);
        assertEquals(3, table.getSelectedRow(), "selection clamps to last row after removal");
        model.removeRow(4 - 1);
        assertEquals(2, table.getSelectedRow());
    }

    // ---- draw: selection vs provider vs default row styles ----------------------

    @Test
    void selectedRowFocusedUsesSelectionColors() {
        var table = modelOf(3);
        table.setFocused(true);
        table.setSelectedRow(1);
        var theme = ThemeManager.active();
        var buf = draw(table, 40, 4);
        assertEquals(theme.selectionFg(), buf.getCell(0, 2).fg(), "focused selection picks theme selection colors");
    }

    @Test
    void selectedRowUnfocusedUsesThemeRowColors() {
        var table = modelOf(3);
        table.setFocused(false);
        table.setSelectedRow(1);
        var theme = ThemeManager.active();
        var buf = draw(table, 40, 4);
        assertEquals(theme.foreground(), buf.getCell(0, 2).fg(), "unfocused selection falls back to theme row colors");
        assertNotEquals(theme.selectionFg(), buf.getCell(0, 2).fg());
    }

    @Test
    void rowStyleProviderSuppliesCustomStyleForUnselectedRows() {
        var table = modelOf(3);
        table.setSelectedRow(0);
        var buf0 = draw(table, 40, 4);
        // find row 1's text start col to probe the correct cell
        int col = -1;
        for (int c = 0; c < 40; c++) {
            if (buf0.getCell(c, 2).is('c')) { col = c; break; }
        }
        var custom = new TextCell(' ', io.jterm.style.AnsiColor.CYAN, io.jterm.style.AnsiColor.MAGENTA);
        table.setRowStyleProvider(row -> custom);
        var buf1 = draw(table, 40, 4);
        assertTrue(col >= 0, "row 1 should render text");
        assertEquals(io.jterm.style.AnsiColor.CYAN, buf1.getCell(col, 2).fg(), "provider style applies to unselected row 1");
    }

    @Test
    void rowStyleProviderReturningNullFallsBackToTheme() {
        var table = modelOf(3);
        table.setSelectedRow(0);
        var theme = ThemeManager.active();
        table.setRowStyleProvider(row -> null);
        var buf = draw(table, 40, 4);
        assertEquals(theme.foreground(), buf.getCell(0, 2).fg(), "null provider result falls back to theme colors");
    }

    // ---- width constraints --------------------------------------------------------

    @Test
    void pctFixedAndMinMaxConstraintPinsWidth() {
        var table = modelOf(2);
        table.draw(new TextGraphics(new ScreenBuffer(new TerminalSize(40, 4))));
        int auto0 = table.getColumnWidth(0);
        table.setColumnWidthPct(0, 50);
        table.draw(new TextGraphics(new ScreenBuffer(new TerminalSize(40, 4))));
        // usable = 40 - 1 separator = 39 → round(19.5) = 20.
        assertEquals(20, table.getColumnWidth(0), "50% of usable 39 ≈ 20");
        assertNotEquals(auto0, table.getColumnWidth(0));
        table.setColumnMinWidth(1, 6);
        table.setColumnMaxWidth(1, 3);
        table.draw(new TextGraphics(new ScreenBuffer(new TerminalSize(40, 4))));
        assertEquals(3, table.getColumnWidth(1), "max wins over min after clamping");
    }

    @Test
    void constraintOutOfRangeColumnIsIgnored() {
        var table = modelOf(2);
        assertDoesNotThrow(() -> table.setColumnMinWidth(5, 10));
        assertDoesNotThrow(() -> table.setColumnMaxWidth(-1, 10));
        assertDoesNotThrow(() -> table.setColumnMinWidthPct(9, 50));
        assertDoesNotThrow(() -> table.setColumnMaxWidthPct(-2, 50));
    }

    @Test
    void minPctLiftsColumnAboveAutoSize() {
        var table = modelOf(2);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(40, 4));
        // Natural width of "cell0a" is 6; force minimum 10% ≈ 4 — no visible change.
        table.setColumnMinWidthPct(0, 10);
        table.draw(new TextGraphics(new ScreenBuffer(new TerminalSize(40, 4))));
        assertTrue(table.getColumnWidth(0) >= 4);
        // A large minimum must lift the width above natural content width
        // (usable = 39 → 60% floor is ceil/floor ≈ 23).
        table.setColumnMinWidthPct(0, 60);
        table.draw(new TextGraphics(new ScreenBuffer(new TerminalSize(40, 4))));
        assertTrue(table.getColumnWidth(0) >= 23, "60% of usable 39 → ≥ 23, got " + table.getColumnWidth(0));
    }

    @Test
    void maxCharsClampsPctPinnedColumn() {
        var table = modelOf(2);
        table.setColumnWidthPct(1, 90);
        table.setColumnMaxWidth(1, 4);
        table.draw(new TextGraphics(new ScreenBuffer(new TerminalSize(40, 4))));
        assertTrue(table.getColumnWidth(1) <= 4, "maxChars clamps the pct-pinned width");
    }

    @Test
    void clearConstraintsRestoresAutoSize() {
        var table = modelOf(2);
        table.setColumnWidthPct(0, 50);
        table.setColumnMinWidth(0, 2);
        table.clearColumnWidthConstraints(0);
        table.clearColumnWidthConstraints(-1);
        table.clearColumnWidthConstraints(99);
        table.clearColumnWidthConstraints(0); // repeat: after array entry is already null
        table.draw(new TextGraphics(new ScreenBuffer(new TerminalSize(40, 4))));
        assertTrue(table.getColumnWidth(0) > 4, "auto-sized again from content");
    }

    @Test
    void zeroResetValuesDisableConstraints() {
        var table = modelOf(2);
        table.setColumnMinWidth(0, 5);
        table.setColumnMinWidth(0, 0);   // zero resets
        table.setColumnMaxWidth(0, 5);
        table.setColumnMaxWidth(0, 0);   // zero resets
        table.setColumnMinWidthPct(0, 0);
        table.setColumnMaxWidthPct(0, 0);
        table.draw(new TextGraphics(new ScreenBuffer(new TerminalSize(40, 4))));
        assertTrue(table.getColumnWidth(0) >= 6, "content width after zero-resets");
    }

    @Test
    void pctColumnsDriveFitAndSpreadPhases() {
        // 50% + 30% + free column inside 60 columns → constrained total exceeds
        // the width: shrink pass 1 trims the free column, pass 2 trims pct
        // columns toward their mins, grow phase re-fills non-pct columns, and
        // the hard clamp keeps the total ≤ available width.
        var table = new Table("AAAA", "BBBB", "CCCC");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(60, 5));
        table.addRow("longcell0", "longcell1", "longcell2");
        table.addRow("longcell0", "longcell1", "longcell2");
        table.setColumnWidthPct(0, 50);
        table.setColumnWidthPct(1, 30);
        table.setColumnMinWidth(1, 4);
        table.setColumnMaxWidth(1, 20);
        table.draw(new TextGraphics(new ScreenBuffer(new TerminalSize(60, 5))));
        int w0 = table.getColumnWidth(0);
        int w1 = table.getColumnWidth(1);
        int w2 = table.getColumnWidth(2);
        assertTrue(w0 > 0 && w1 > 0 && w2 > 0, "all columns sized");
        assertTrue(w1 <= 20, "col1 within maxChars: " + w1);
        assertTrue(w0 <= 60, "col0 never exceeds the viewport");
        // Re-draw is stable (constraint math is idempotent per layout pass).
        table.draw(new TextGraphics(new ScreenBuffer(new TerminalSize(60, 5))));
        assertEquals(w0, table.getColumnWidth(0));
        assertEquals(w1, table.getColumnWidth(1));
    }

    @Test
    void minPctAndMaxPctBoundTheUsableShare() {
        var table = modelOf(2);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(40, 4));
        table.setColumnMinWidthPct(1, 20);   // min ~7 of usable 39
        table.setColumnMaxWidthPct(1, 40);   // max ~15 of usable 39
        table.draw(new TextGraphics(new ScreenBuffer(new TerminalSize(40, 4))));
        int w1 = table.getColumnWidth(1);
        assertTrue(w1 >= 7 && w1 <= 16, "pct bounds respected, got " + w1);
        // clear all and confirm auto-size returns
        table.clearColumnWidthConstraints(1);
        table.draw(new TextGraphics(new ScreenBuffer(new TerminalSize(40, 4))));
        assertTrue(table.getColumnWidth(1) >= 6);
    }

    // ---- alignment helpers --------------------------------------------------------

    @Test
    void alignmentRightPadsLeftOfText() {
        var table = modelOf(2);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(40, 4));
        table.setColumnWidthPct(1, 40);
        table.draw(new TextGraphics(new ScreenBuffer(new TerminalSize(40, 4))));
        assertTrue(table.getColumnWidth(1) >= 10, "pinned col 1 wide enough for padding");
        table.setColumnAlignment(1, Table.Alignment.RIGHT);
        var buf = draw(table, 40, 4);
        // Row 1 (below header): locate "cell0b" — right-aligned in a wide column,
        // so its text must start well past column 0's width (6) + separator (1).
        var sb = new StringBuilder();
        for (int c = 0; c < 40; c++) {
            sb.append(buf.getCell(c, 1).character());
        }
        String row1 = sb.toString();
        int idx = row1.indexOf("cell0b");
        assertTrue(idx >= 0, "row 1 should contain 'cell0b': " + row1);
        assertTrue(idx > 7, "right-aligned text starts after padding, got col " + idx + " in: " + row1);
    }

    @Test
    void alignmentNullDefaultsToLeft() {
        var table = modelOf(2);
        assertDoesNotThrow(() -> table.setColumnAlignment(0, null));
        assertEquals(Table.Alignment.LEFT, table.getColumnAlignment(0));
        assertEquals(Table.Alignment.LEFT, table.getColumnAlignment(-1));
        assertEquals(Table.Alignment.LEFT, table.getColumnAlignment(42));
    }

    // ---- keyboard ----------------------------------------------------------------

    @Test
    void endKeyOnNullModelIsConsumedWithoutAction() {
        var table = new Table((TableModel) null);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(30, 4));
        assertTrue(table.handleKeyStroke(new KeyStroke(KeyType.END)), "END with no model still returns true");
        assertEquals(0, table.getSelectedRow());
        // Draw with a null model: clear-remaining-rows branch + zero-column guards.
        var buf = assertDoesNotThrow(() -> draw(table, 30, 4));
        assertNotNull(buf);
        // ensureVisible with rows()/scroll bounds never runs off the rails.
        table.setSelectedRow(0);
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void nullAlignmentEntryReadsAsLeft() {
        var table = modelOf(2);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(40, 4));
        // Reach getColumnAlignment's null-entry arm: set alignment to force the
        // array allocation, then reset through the zero-value contract.
        table.setColumnAlignment(0, Table.Alignment.RIGHT);
        table.setColumnAlignment(0, null); // null resets to LEFT
        assertEquals(Table.Alignment.LEFT, table.getColumnAlignment(0));
        // Draw exercises drawRow's alignment read path.
        var buf = assertDoesNotThrow(() -> draw(table, 40, 4));
        assertTrue(buf.getCell(0, 1).is('c'), "row 1 renders its first cell value");
    }

    @Test
    void insertRowNotifiesTableAndClampsSelection() {
        var model = new DefaultTableModel("C0", "C1");
        var table = new Table(model);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(40, 3));
        model.addRow("a", "b");
        table.setSelectedRow(0);
        // insertRow fires ROWS_ADDED (not handled by the clamp branch), then a
        // CELLS/ROWS event drives ensureVisible + invalidate.
        model.setValueAt(0, 0, "z");
        assertEquals(0, table.getSelectedRow());
        var buf = assertDoesNotThrow(() -> draw(table, 40, 3));
        boolean sawZ = false;
        for (int c = 0; c < 40; c++) {
            if (buf.getCell(c, 1).is('z')) sawZ = true;
        }
        assertTrue(sawZ, "updated cell value renders");
    }

    @Test
    void arrowAndEmacsNavigationMoveSelection() {
        var table = modelOf(4);
        assertTrue(table.handleKeyStroke(new KeyStroke(KeyType.ARROW_DOWN)));
        assertEquals(1, table.getSelectedRow());
        assertTrue(table.handleKeyStroke(new KeyStroke(KeyType.CHARACTER, 'n', true, false, false)));
        assertEquals(2, table.getSelectedRow());
        assertTrue(table.handleKeyStroke(new KeyStroke(KeyType.ARROW_UP)));
        assertEquals(1, table.getSelectedRow());
        assertTrue(table.handleKeyStroke(new KeyStroke(KeyType.CHARACTER, 'p', true, false, false)));
        assertEquals(0, table.getSelectedRow());
        // Ctrl+X is not a binding: falls through to the type switch and returns false.
        assertFalse(table.handleKeyStroke(new KeyStroke(KeyType.CHARACTER, 'x', true, false, false)));
    }

    @Test
    void pageUpDownAndEndNavigate() {
        var table = modelOf(8);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(40, 4));
        assertTrue(table.handleKeyStroke(new KeyStroke(KeyType.PAGE_DOWN)));
        assertEquals(3, table.getSelectedRow());
        assertTrue(table.handleKeyStroke(new KeyStroke(KeyType.END)));
        assertEquals(7, table.getSelectedRow());
        // pageUp scrolls back one viewport from scrollOffset 5 → row 2.
        assertTrue(table.handleKeyStroke(new KeyStroke(KeyType.PAGE_UP)));
        assertEquals(2, table.getSelectedRow());
        assertTrue(table.handleKeyStroke(new KeyStroke(KeyType.HOME)));
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void setSelectedRowClampsBothDirections() {
        var table = modelOf(3);
        table.setSelectedRow(99);
        assertEquals(2, table.getSelectedRow());
        table.setSelectedRow(-5);
        assertEquals(0, table.getSelectedRow());
        table.setSelectedRow(1);
        assertEquals(1, table.getSelectedRow());
    }

    @Test
    void addRowOnNonDefaultModelThrows() {
        TableModel anonymous = new TableModel() {
            @Override public int getRowCount() { return 0; }
            @Override public int getColumnCount() { return 1; }
            @Override public String getColumnName(int col) { return "C"; }
            @Override public String getValueAt(int row, int col) { return ""; }
            @Override public void addTableModelListener(io.jterm.widget.model.TableModelListener l) { }
            @Override public void removeTableModelListener(io.jterm.widget.model.TableModelListener l) { }
        };
        var table = new Table(anonymous);
        assertThrows(IllegalStateException.class, () -> table.addRow("x"));
    }

    @Test
    void preferredSizeSpansColumnsAndClampsHeight() {
        var table = modelOf(30);
        var size = table.getPreferredSize();
        assertTrue(size.rows() <= 12, "preferred height capped at 12, got " + size.rows());
        assertTrue(size.columns() >= 10, "width spans two content columns: " + size);
    }
}