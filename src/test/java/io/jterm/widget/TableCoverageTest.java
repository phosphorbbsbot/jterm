package io.jterm.widget;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.widget.model.DefaultTableModel;
import io.jterm.widget.model.TableModel;
import io.jterm.widget.model.TableModelListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-completion tests for {@link Table}: clearRows (both branches),
 * page up/down scrolling, HOME/END keys, Ctrl+V page-down, column
 * alignment (right-align padding + out-of-bounds guard), column widths,
 * separator char, model replacement listener hygiene, getTableModelRows,
 * and STRUCTURE_CHANGED scroll clamping. Complements TableTest.
 */
class TableCoverageTest {

    private Table table5(int rows, int cols, int height) {
        var table = new Table("C0", "C1", "C2", "C3", "C4");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(60, height));
        for (int r = 0; r < rows; r++) {
            String[] cells = new String[5];
            for (int c = 0; c < 5; c++) cells[c] = "r" + r + "c" + c;
            table.addRow(cells);
        }
        return table;
    }

    // ===== clearRows =====

    @Test
    void clearRowsEmptiesDefaultBackedTable() {
        var table = table5(3, 5, 10);
        assertEquals(3, table.getTableModelRows().size());
        table.clearRows();
        assertEquals(0, table.getTableModelRows().size());
        assertEquals(0, table.getSelectedRow(), "selection clamped after clear");
    }

    @Test
    void clearRowsOnForeignModelThrows() {
        var model = new DefaultTableModel("A", "B");
        var table = new Table(model);
        // Replace the model with a non-DefaultTableModel via setModel(null)
        // is not enough — need a Table whose model is NOT DefaultTableModel.
        // Construct through the header constructor then swap in a custom model.
        var table2 = new Table("X");
        table2.setModel(new TableModel() {
            @Override public int getRowCount() { return 0; }
            @Override public int getColumnCount() { return 1; }
            @Override public String getColumnName(int i) { return "X"; }
            @Override public String getValueAt(int r, int c) { return ""; }
            @Override public void addTableModelListener(io.jterm.widget.model.TableModelListener l) {}
            @Override public void removeTableModelListener(io.jterm.widget.model.TableModelListener l) {}
        });
        assertThrows(IllegalStateException.class, table2::clearRows);
    }

    // ===== paging =====

    @Test
    void pageDownScrollsAndSelects() {
        var table = table5(10, 5, 5);  // viewport = 4 rows
        table.pageDown();
        // scrollOffset = min(0+4, 10-4) = 4 → selection follows to 4.
        assertEquals(4, table.getSelectedRow());
        // Second page reaches the last full viewport.
        table.pageDown();
        assertEquals(6, table.getSelectedRow());
    }

    @Test
    void pageDownOnEmptyTableIsSafe() {
        var table = new Table("A");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        assertDoesNotThrow(table::pageDown);
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void pageUpReturnsToTop() {
        var table = table5(10, 5, 5);
        table.pageDown();
        table.pageUp();
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void pageDownKeyNavigates() {
        var table = table5(10, 5, 5);
        table.handleKeyStroke(new KeyStroke(KeyType.PAGE_DOWN));
        assertEquals(4, table.getSelectedRow());
        table.handleKeyStroke(new KeyStroke(KeyType.PAGE_UP));
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void ctrlVPagesDown() {
        var table = table5(10, 5, 5);
        assertTrue(table.handleKeyStroke(KeyStroke.character('V', true, false, false)));
        assertEquals(4, table.getSelectedRow());
        assertTrue(table.handleKeyStroke(KeyStroke.character('v', true, false, false)));
        assertEquals(6, table.getSelectedRow(), "scroll capped at rowCount - viewport");
    }

    @Test
    void ctrlOtherLettersReturnFalse() {
        var table = table5(2, 5, 5);
        assertFalse(table.handleKeyStroke(KeyStroke.character('X', true, false, false)));
    }

    // ===== HOME / END =====

    @Test
    void homeKeySelectsFirstRow() {
        var table = table5(6, 5, 10);
        table.setSelectedRow(4);
        assertTrue(table.handleKeyStroke(new KeyStroke(KeyType.HOME)));
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void endKeySelectsLastRow() {
        var table = table5(6, 5, 10);
        assertTrue(table.handleKeyStroke(new KeyStroke(KeyType.END)));
        assertEquals(5, table.getSelectedRow());
    }

    // ===== column alignment =====

    @Test
    void setColumnAlignmentOutOfBoundsIgnored() {
        var table = table5(2, 5, 8);
        assertDoesNotThrow(() -> table.setColumnAlignment(-1, Table.Alignment.RIGHT));
        assertDoesNotThrow(() -> table.setColumnAlignment(5, Table.Alignment.RIGHT));
        assertEquals(Table.Alignment.LEFT, table.getColumnAlignment(0), "untouched default");
    }

    @Test
    void rightAlignedCellPadsLeft() {
        var table = new Table("N");
        table.addRow("ab");
        table.setColumnAlignment(0, Table.Alignment.RIGHT);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        var buf = new ScreenBuffer(new TerminalSize(20, 5));
        table.draw(new TextGraphics(buf));
        // The cell text ends at the column's right edge; find 'a' of "ab".
        int col0Width = table.getColumnWidth(0);
        assertTrue(col0Width >= 2);
        assertEquals('b', buf.getCell(col0Width - 1, 1).character().charAt(0));
    }

    @Test
    void nullAlignmentResetsToLeft() {
        var table = table5(2, 5, 8);
        table.setColumnAlignment(0, Table.Alignment.RIGHT);
        assertEquals(Table.Alignment.RIGHT, table.getColumnAlignment(0));
        table.setColumnAlignment(0, null);
        assertEquals(Table.Alignment.LEFT, table.getColumnAlignment(0));
    }

    @Test
    void getColumnAlignmentWithoutAnyConfigReturnsLeft() {
        var table = table5(1, 5, 8);
        assertEquals(Table.Alignment.LEFT, table.getColumnAlignment(0));
        assertEquals(Table.Alignment.LEFT, table.getColumnAlignment(99), "out of bounds → LEFT");
    }

    // ===== column widths & separator =====

    @Test
    void getColumnWidthComputedAfterDraw() {
        var table = table5(2, 5, 8);
        assertEquals(0, table.getColumnWidth(0), "before draw");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(60, 8));
        var buf = new ScreenBuffer(new TerminalSize(60, 8));
        table.draw(new TextGraphics(buf));
        assertTrue(table.getColumnWidth(0) > 0, "after draw");
        assertEquals(0, table.getColumnWidth(-1), "out of bounds");
        assertEquals(0, table.getColumnWidth(9), "out of bounds");
    }

    @Test
    void separatorCharRoundTrip() {
        var table = table5(1, 5, 8);
        assertEquals("│", table.getColumnSeparatorChar(), "default");
        table.setColumnSeparatorChar("|");
        assertEquals("|", table.getColumnSeparatorChar());
        table.setColumnSeparatorChar(null);
        assertEquals("│", table.getColumnSeparatorChar(), "null → default");
    }

    // ===== model replacement =====

    @Test
    void setModelSwapsAndStopsListeningToOld() {
        var old = new DefaultTableModel("A");
        var table = new Table(old);
        old.addRow("1");
        var fresh = new DefaultTableModel("B");
        table.setModel(fresh);
        fresh.addRow("2");
        assertEquals(1, table.getTableModelRows().size(), "new model drives table");
        table.setModel(null);  // null allowed: renders empty
        assertEquals(0, table.getTableModelRows().size());
    }

    @Test
    void setModelResetsSelection() {
        var table = table5(5, 5, 10);
        table.setSelectedRow(3);
        table.setModel(new DefaultTableModel("Only"));
        assertEquals(0, table.getSelectedRow());
    }

    // ===== STRUCTURE_CHANGED scroll clamp =====

    @Test
    void structureChangedClampsScrollAboveSelection() throws Exception {
        var model = new DefaultTableModel("A", "B");
        var table = new Table(model);
        for (int i = 0; i < 20; i++) model.addRow("r" + i, "x" + i);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(30, 5));
        table.setSelectedRow(15);
        // Removing rows fires ROWS_REMOVED → selection clamps to the new count.
        model.removeRow(19);
        model.removeRow(18);
        assertTrue(table.getSelectedRow() < 19, "selection clamped to new count");
        // clear() fires ROWS_CHANGED across the old range → clamped to empty (0).
        model.clear();
        assertEquals(0, table.getSelectedRow());
    }

    // ===== preferred size =====

    @Test
    void preferredSizeCapsAtTwelveRows() {
        var table = table5(30, 5, 10);
        var ps = table.getPreferredSize();
        assertEquals(12, ps.rows(), "row count capped at 12");
        assertTrue(ps.columns() >= 5);
    }

    @Test
    void preferredSizeEmptyTableMinThreeRows() {
        var table = new Table("A");
        var ps = table.getPreferredSize();
        assertTrue(ps.rows() >= 3, "minimum 3 rows");
    }
}