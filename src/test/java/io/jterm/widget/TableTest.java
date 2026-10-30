package io.jterm.widget;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.widget.model.DefaultTableModel;
import io.jterm.widget.model.TableModelEvent;
import io.jterm.widget.model.TableModelEventType;
import io.jterm.widget.model.TableModelListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableTest {

    private static final class CapturingListener implements TableModelListener {
        private final List<TableModelEvent> events = new ArrayList<>();

        @Override
        public void tableChanged(TableModelEvent e) {
            events.add(e);
        }
    }

    @Test
    void constructorStoresHeaders() {
        var table = new Table("Name", "Age");
        var ps = table.getPreferredSize();
        assertTrue(ps.columns() >= 9);
        assertTrue(ps.rows() >= 3);
    }

    @Test
    void constructorWithModelUsesModelHeaders() {
        DefaultTableModel model = new DefaultTableModel("Ticker", "Price");
        var table = new Table(model);
        assertEquals(model, table.getModel());
    }

    @Test
    void addRowUpdatesPreferredSize() {
        var table = new Table("A");
        table.addRow("Alpha");
        table.addRow("Beta");
        assertTrue(table.getPreferredSize().rows() >= 3);
    }

    @Test
    void addRowPopulatesBackingModel() {
        var table = new Table("A");
        table.addRow("Alpha");
        assertEquals(1, table.getModel().getRowCount());
        assertEquals("Alpha", table.getModel().getValueAt(0, 0));
    }

    @Test
    void setSelectedRowClampsToRange() {
        var table = new Table("A");
        table.addRow("1");
        table.addRow("2");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        table.setSelectedRow(5);
        assertEquals(1, table.getSelectedRow());
    }

    @Test
    void setSelectedRowClampsToZeroWhenEmpty() {
        var table = new Table("A");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        table.setSelectedRow(5);
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void arrowDownSelectsNextRow() {
        var table = new Table("A");
        table.addRow("1");
        table.addRow("2");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        table.handleKeyStroke(new KeyStroke(KeyType.ARROW_DOWN));
        assertEquals(1, table.getSelectedRow());
    }

    @Test
    void arrowUpStopsAtTop() {
        var table = new Table("A");
        table.addRow("1");
        table.addRow("2");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        table.setSelectedRow(1);
        table.handleKeyStroke(new KeyStroke(KeyType.ARROW_UP));
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void ctrlPUppercaseMovesSelectionUp() {
        var table = new Table("A");
        table.addRow("1");
        table.addRow("2");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        table.setSelectedRow(1);
        table.handleKeyStroke(KeyStroke.character('P', true, false, false));
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void ctrlPLowercaseMovesSelectionUp() {
        var table = new Table("A");
        table.addRow("1");
        table.addRow("2");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        table.setSelectedRow(1);
        table.handleKeyStroke(KeyStroke.character('p', true, false, false));
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void ctrlNUppercaseMovesSelectionDown() {
        var table = new Table("A");
        table.addRow("1");
        table.addRow("2");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        table.handleKeyStroke(KeyStroke.character('N', true, false, false));
        assertEquals(1, table.getSelectedRow());
    }

    @Test
    void ctrlNLowercaseMovesSelectionDown() {
        var table = new Table("A");
        table.addRow("1");
        table.addRow("2");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        table.handleKeyStroke(KeyStroke.character('n', true, false, false));
        assertEquals(1, table.getSelectedRow());
    }

    @Test
    void ctrlNStopsAtBottom() {
        var table = new Table("A");
        table.addRow("1");
        table.addRow("2");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        table.setSelectedRow(1);
        table.handleKeyStroke(KeyStroke.character('N', true, false, false));
        assertEquals(1, table.getSelectedRow());
    }

    @Test
    void ctrlPStopsAtTop() {
        var table = new Table("A");
        table.addRow("1");
        table.addRow("2");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        table.handleKeyStroke(KeyStroke.character('P', true, false, false));
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void drawsHeaderRow() {
        var buffer = new ScreenBuffer(new TerminalSize(20, 5));
        var table = new Table("Name", "Age");
        table.addRow("Alice", "30");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        table.draw(new TextGraphics(buffer));
        assertEquals('N', buffer.getCell(0, 0).character().charAt(0));
    }

    @Test
    void drawComponentReadsFromModel() {
        DefaultTableModel model = new DefaultTableModel("Name", "Age");
        model.addRow("Bob", "25");
        var table = new Table(model);
        var buffer = new ScreenBuffer(new TerminalSize(20, 5));
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        table.draw(new TextGraphics(buffer));
        assertEquals('B', buffer.getCell(0, 1).character().charAt(0));
    }

    @Test
    void emptyTableRendersHeader() {
        var buffer = new ScreenBuffer(new TerminalSize(10, 3));
        var table = new Table("A");
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 3));
        table.draw(new TextGraphics(buffer));
        assertEquals('A', buffer.getCell(0, 0).character().charAt(0));
    }

    @Test
    void modelDrivenUpdateReflectsNewRow() {
        DefaultTableModel model = new DefaultTableModel("A");
        var table = new Table(model);
        model.addRow("added");
        assertEquals(1, table.getModel().getRowCount());
        assertEquals("added", table.getModel().getValueAt(0, 0));
    }

    @Test
    void rowsRemovedClampsSelection() {
        DefaultTableModel model = new DefaultTableModel("A");
        var table = new Table(model);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        model.addRow("a");
        model.addRow("b");
        model.addRow("c");
        table.setSelectedRow(2);
        model.removeRow(2);
        assertEquals(1, table.getSelectedRow());
    }

    @Test
    void setModelSwitchesCleanly() {
        DefaultTableModel first = new DefaultTableModel("A");
        first.addRow("first");
        var table = new Table(first);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        DefaultTableModel second = new DefaultTableModel("B");
        table.setModel(second);
        assertEquals(second, table.getModel());
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void oldModelStopsNotifyingAfterSetModel() {
        DefaultTableModel first = new DefaultTableModel("A");
        var table = new Table(first);
        DefaultTableModel second = new DefaultTableModel("B");
        table.setModel(second);
        CapturingListener tableListener = new CapturingListener();
        second.addTableModelListener(tableListener);
        first.addRow("x");
        assertTrue(tableListener.events.isEmpty());
    }

    @Test
    void structureChangedClampsSelectionToValidRange() {
        DefaultTableModel model = new DefaultTableModel("A");
        var table = new Table(model);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        for (int i = 0; i < 20; i++) {
            model.addRow("row" + i);
        }
        table.setSelectedRow(19);
        model.clear();
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void structureChangedPreservesSelectionWhenRowCountSame() {
        // Simulates UserTableModel.refresh(): the model is rebuilt in-place
        // (clear + reload) then fires a single STRUCTURE_CHANGED event.
        // The table should NOT reset selectedRow to 0 — it should preserve
        // the user's selection.
        var model = new io.jterm.widget.model.AbstractTableModel() {
            private final java.util.List<String> rows = new java.util.ArrayList<>();
            @Override public int getRowCount() { return rows.size(); }
            @Override public int getColumnCount() { return 1; }
            @Override public String getColumnName(int col) { return "A"; }
            @Override public String getValueAt(int row, int col) { return rows.get(row); }
            public void rebuild(java.util.List<String> newRows) {
                rows.clear();
                rows.addAll(newRows);
                fireStructureChanged();
            }
        };
        var table = new Table(model);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        // Populate model
        model.rebuild(java.util.List.of("row0", "row1", "row2", "row3", "row4"));
        table.setSelectedRow(3);
        // Simulate refresh: rebuild with same data
        model.rebuild(java.util.List.of("row0", "row1", "row2", "row3", "row4"));
        // The selection should still be at row 3
        assertEquals(3, table.getSelectedRow());
    }

    @Test
    void cellsChangedInvalidatesTable() {
        DefaultTableModel model = new DefaultTableModel("A");
        var table = new Table(model);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        model.addRow("old");
        var buffer = new ScreenBuffer(new TerminalSize(10, 3));
        table.draw(new TextGraphics(buffer));
        model.setValueAt(0, 0, "new");
        buffer = new ScreenBuffer(new TerminalSize(10, 3));
        table.draw(new TextGraphics(buffer));
        assertEquals('n', buffer.getCell(0, 1).character().charAt(0));
    }

    @Test
    void rowsChangedClampsSelection() {
        DefaultTableModel model = new DefaultTableModel("A");
        var table = new Table(model);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        model.addRow("a");
        model.addRow("b");
        table.setSelectedRow(1);
        model.clear();
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void rowsAddedBeforeSelectionShiftsSelectionDown() {
        // Law: the selection anchors to its CONTENT, not to the index. Inserting
        // a row at or above the selected index slides the selected row down; the
        // highlight must ride along with it.
        DefaultTableModel model = new DefaultTableModel("A");
        var table = new Table(model);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        model.addRow("row0");
        model.addRow("row1");
        model.addRow("row2");
        table.setSelectedRow(2);
        model.insertRow(2, "NEW");
        // old row2 ("row2") is now row3 — the selection must follow it
        assertEquals(3, table.getSelectedRow());
        assertEquals("row2", model.getValueAt(table.getSelectedRow(), 0));
    }

    @Test
    void rowsAddedAtSelectionTopShiftsSelectionDown() {
        DefaultTableModel model = new DefaultTableModel("A");
        var table = new Table(model);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        model.addRow("row0");
        model.addRow("row1");
        table.setSelectedRow(1);
        model.insertRow(1, "NEW");
        assertEquals(2, table.getSelectedRow());
        assertEquals("row1", model.getValueAt(table.getSelectedRow(), 0));
    }

    @Test
    void rowsAddedAfterSelectionDoesNotMoveSelection() {
        // Appending below the selection changes nothing — the selected content
        // never moved.
        DefaultTableModel model = new DefaultTableModel("A");
        var table = new Table(model);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        model.addRow("row0");
        model.addRow("row1");
        table.setSelectedRow(0);
        model.insertRow(2, "row2");
        model.addRow("row3");
        assertEquals(0, table.getSelectedRow());
        assertEquals("row0", model.getValueAt(table.getSelectedRow(), 0));
    }

    @Test
    void rowsMultiAddedBeforeSelectionShiftsSelectionDown() {
        // A custom model can fire one ROWS_ADDED event for a multi-row block:
        // the selection must slide down by the full block length.
        var model = new io.jterm.widget.model.AbstractTableModel() {
            private final List<String> rows = new ArrayList<>();
            @Override public int getRowCount() { return rows.size(); }
            @Override public int getColumnCount() { return 1; }
            @Override public String getColumnName(int col) { return "A"; }
            @Override public String getValueAt(int row, int col) { return rows.get(row); }
            void insertBlock(int index, int count) {
                for (int i = 0; i < count; i++) rows.add(index, "NEW" + i);
                fireRowsAdded(index, index + count - 1);
            }
        };
        var table = new Table(model);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        model.rows.add("row0");
        model.rows.add("row1");
        table.setSelectedRow(0);
        model.insertBlock(0, 2);
        assertEquals(2, table.getSelectedRow());
        assertEquals("row0", table.getModel().getValueAt(table.getSelectedRow(), 0));
    }

    @Test
    void rowsRemovedAboveSelectionShiftsSelectionUp() {
        DefaultTableModel model = new DefaultTableModel("A");
        var table = new Table(model);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        model.addRow("row0");
        model.addRow("row1");
        model.addRow("row2");
        table.setSelectedRow(2);
        model.removeRow(0);
        assertEquals(1, table.getSelectedRow());
        assertEquals("row2", model.getValueAt(table.getSelectedRow(), 0));
    }

    @Test
    void rowsRemovedAtSelectionAnchorsToRemovedSlot() {
        DefaultTableModel model = new DefaultTableModel("A");
        var table = new Table(model);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        model.addRow("row0");
        model.addRow("row1");
        model.addRow("row2");
        table.setSelectedRow(1);
        model.removeRow(1);
        // removed the selected row — selection lands on the content that follows
        assertEquals(1, table.getSelectedRow());
        assertEquals("row2", model.getValueAt(table.getSelectedRow(), 0));
    }

    @Test
    void rowsRemovedBelowSelectionDoesNotMoveSelection() {
        DefaultTableModel model = new DefaultTableModel("A");
        var table = new Table(model);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        model.addRow("row0");
        model.addRow("row1");
        table.setSelectedRow(0);
        model.removeRow(1);
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void rowsRemovedClampsWhenTableEmpties() {
        DefaultTableModel model = new DefaultTableModel("A");
        var table = new Table(model);
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        model.addRow("row0");
        table.setSelectedRow(0);
        model.removeRow(0);
        assertEquals(0, table.getSelectedRow());
    }

    @Test
    void getTableModelRowsReturnsCopy() {
        var table = new Table("A");
        table.addRow("x");
        List<List<String>> rows = table.getTableModelRows();
        assertEquals(1, rows.size());
        assertEquals("x", rows.get(0).get(0));
    }

    @Test
    void clearRowsRemovesAllRowsFromModel() {
        var table = new Table("A", "B");
        table.addRow("1", "2");
        table.addRow("3", "4");
        assertEquals(2, table.getModel().getRowCount());
        table.clearRows();
        assertEquals(0, table.getModel().getRowCount());
        assertTrue(table.getTableModelRows().isEmpty());
    }

    @Test
    void clearRowsAllowsAddingNewRowsAfterwards() {
        var table = new Table("A");
        table.addRow("old");
        table.clearRows();
        table.addRow("new");
        assertEquals(1, table.getModel().getRowCount());
        assertEquals("new", table.getModel().getValueAt(0, 0));
    }

    @Test
    void addRowThrowsWhenModelIsNotDefault() {
        var table = new Table(new io.jterm.widget.model.AbstractTableModel() {
            @Override public int getRowCount() { return 0; }
            @Override public int getColumnCount() { return 1; }
            @Override public String getColumnName(int col) { return "C"; }
            @Override public String getValueAt(int row, int col) { return ""; }
        });
        assertThrows(IllegalStateException.class, () -> table.addRow("x"));
    }

    @Test
    void drawWithCustomModelReturnsValues() {
        var table = new Table(new io.jterm.widget.model.AbstractTableModel() {
            @Override public int getRowCount() { return 2; }
            @Override public int getColumnCount() { return 1; }
            @Override public String getColumnName(int col) { return "H"; }
            @Override public String getValueAt(int row, int col) { return row == 0 ? "One" : "Two"; }
        });
        var buffer = new ScreenBuffer(new TerminalSize(10, 4));
        table.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 4));
        table.draw(new TextGraphics(buffer));
        assertEquals('H', buffer.getCell(0, 0).character().charAt(0));
        assertEquals('O', buffer.getCell(0, 1).character().charAt(0));
        assertEquals('T', buffer.getCell(0, 2).character().charAt(0));
    }

    @Test
    void preferredSizeReflectsModelColumnCount() {
        DefaultTableModel model = new DefaultTableModel("One", "Two", "Three");
        var table = new Table(model);
        assertTrue(table.getPreferredSize().columns() >= 3);
    }
}
