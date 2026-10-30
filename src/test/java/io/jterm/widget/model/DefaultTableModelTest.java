package io.jterm.widget.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultTableModelTest {

    private static final class CapturingListener implements TableModelListener {
        private final List<TableModelEvent> events = new ArrayList<>();

        @Override
        public void tableChanged(TableModelEvent e) {
            events.add(e);
        }
    }

    @Test
    void addRowFiresRowsAdded() {
        DefaultTableModel model = new DefaultTableModel("A", "B");
        CapturingListener listener = new CapturingListener();
        model.addTableModelListener(listener);
        model.addRow("1", "2");
        assertEquals(1, listener.events.size());
        assertEquals(TableModelEventType.ROWS_ADDED, listener.events.get(0).type());
        assertEquals(0, listener.events.get(0).firstRow());
        assertEquals(0, listener.events.get(0).lastRow());
        assertEquals(-1, listener.events.get(0).column());
    }

    @Test
    void insertRowFiresRowsAdded() {
        DefaultTableModel model = new DefaultTableModel("A");
        model.addRow("a");
        model.addRow("c");
        CapturingListener listener = new CapturingListener();
        model.addTableModelListener(listener);
        model.insertRow(1, "b");
        assertEquals(1, listener.events.size());
        assertEquals(1, listener.events.get(0).firstRow());
        assertEquals(1, listener.events.get(0).lastRow());
        assertEquals("b", model.getValueAt(1, 0));
    }

    @Test
    void removeRowFiresRowsRemoved() {
        DefaultTableModel model = new DefaultTableModel("A");
        model.addRow("a");
        CapturingListener listener = new CapturingListener();
        model.addTableModelListener(listener);
        model.removeRow(0);
        assertEquals(1, listener.events.size());
        assertEquals(TableModelEventType.ROWS_REMOVED, listener.events.get(0).type());
        assertEquals(0, listener.events.get(0).firstRow());
        assertEquals(0, listener.events.get(0).lastRow());
        assertEquals(0, model.getRowCount());
    }

    @Test
    void setValueAtFiresCellsChanged() {
        DefaultTableModel model = new DefaultTableModel("A");
        model.addRow("old");
        CapturingListener listener = new CapturingListener();
        model.addTableModelListener(listener);
        model.setValueAt(0, 0, "new");
        assertEquals(1, listener.events.size());
        assertEquals(TableModelEventType.CELLS_CHANGED, listener.events.get(0).type());
        assertEquals(0, listener.events.get(0).firstRow());
        assertEquals(0, listener.events.get(0).lastRow());
        assertEquals(0, listener.events.get(0).column());
        assertEquals("new", model.getValueAt(0, 0));
    }

    @Test
    void clearFiresRowsChangedForFullRange() {
        DefaultTableModel model = new DefaultTableModel("A");
        model.addRow("a");
        model.addRow("b");
        model.addRow("c");
        CapturingListener listener = new CapturingListener();
        model.addTableModelListener(listener);
        model.clear();
        assertEquals(1, listener.events.size());
        assertEquals(TableModelEventType.ROWS_CHANGED, listener.events.get(0).type());
        assertEquals(0, listener.events.get(0).firstRow());
        assertEquals(2, listener.events.get(0).lastRow());
        assertEquals(0, model.getRowCount());
    }

    @Test
    void clearOnEmptyModelFiresZeroRange() {
        DefaultTableModel model = new DefaultTableModel("A");
        CapturingListener listener = new CapturingListener();
        model.addTableModelListener(listener);
        model.clear();
        assertEquals(1, listener.events.size());
        assertEquals(0, listener.events.get(0).firstRow());
        assertEquals(0, listener.events.get(0).lastRow());
    }

    @Test
    void addMultipleRowsFiresSequentialIndices() {
        DefaultTableModel model = new DefaultTableModel("A");
        CapturingListener listener = new CapturingListener();
        model.addTableModelListener(listener);
        model.addRow("a");
        model.addRow("b");
        model.addRow("c");
        assertEquals(3, listener.events.size());
        assertEquals(0, listener.events.get(0).firstRow());
        assertEquals(1, listener.events.get(1).firstRow());
        assertEquals(2, listener.events.get(2).firstRow());
    }

    @Test
    void outOfBoundsValueReturnsEmptyString() {
        DefaultTableModel model = new DefaultTableModel("A", "B");
        assertEquals("", model.getValueAt(0, 0));
        assertEquals("", model.getValueAt(-1, 0));
        assertEquals("", model.getValueAt(0, 5));
    }

    @Test
    void columnCountMatchesHeaders() {
        DefaultTableModel model = new DefaultTableModel("One", "Two", "Three");
        assertEquals(3, model.getColumnCount());
    }

    @Test
    void listenerRemovedDuringEventDoesNotThrow() {
        DefaultTableModel model = new DefaultTableModel("A");
        TableModelListener[] selfRemoving = new TableModelListener[1];
        selfRemoving[0] = e -> model.removeTableModelListener(selfRemoving[0]);
        model.addTableModelListener(selfRemoving[0]);
        model.addRow("x");
        model.addRow("y");
        assertEquals(2, model.getRowCount());
    }

    @Test
    void noEventsAfterListenerRemoved() {
        DefaultTableModel model = new DefaultTableModel("A");
        CapturingListener listener = new CapturingListener();
        model.addTableModelListener(listener);
        model.removeTableModelListener(listener);
        model.addRow("x");
        model.removeRow(0);
        model.clear();
        assertTrue(listener.events.isEmpty());
    }

    // ---- replaceAll: diff-based rebuild events -------------------------------

    @Test
    void replaceAllDeleteEmitsSingleRowsRemoved() {
        DefaultTableModel model = new DefaultTableModel("A");
        model.addRow("r1");
        model.addRow("r2");
        model.addRow("r3");
        CapturingListener listener = new CapturingListener();
        model.addTableModelListener(listener);
        model.replaceAll(List.of(List.of("r1"), List.of("r3")));
        assertEquals(1, listener.events.size(), "A middle-row delete must be ONE removal event");
        assertEquals(TableModelEventType.ROWS_REMOVED, listener.events.getFirst().type());
        assertEquals(1, listener.events.getFirst().firstRow());
        assertEquals(1, listener.events.getFirst().lastRow());
    }

    @Test
    void replaceAllAppendEmitsSingleRowsAdded() {
        DefaultTableModel model = new DefaultTableModel("A");
        model.addRow("r1");
        CapturingListener listener = new CapturingListener();
        model.addTableModelListener(listener);
        model.replaceAll(List.of(List.of("r1"), List.of("r2")));
        assertEquals(1, listener.events.size());
        assertEquals(TableModelEventType.ROWS_ADDED, listener.events.getFirst().type());
        assertEquals(1, listener.events.getFirst().firstRow());
    }

    @Test
    void replaceAllEditEmitsSingleRowsChangedAtSameSlot() {
        DefaultTableModel model = new DefaultTableModel("A");
        model.addRow("r1");
        model.addRow("r2");
        CapturingListener listener = new CapturingListener();
        model.addTableModelListener(listener);
        model.replaceAll(List.of(List.of("r1"), List.of("r2-edited")));
        assertEquals(1, listener.events.size(),
                "An in-place edit must be ONE rows-changed event — remove+add would double-move selections");
        assertEquals(TableModelEventType.ROWS_CHANGED, listener.events.getFirst().type());
        assertEquals(1, listener.events.getFirst().firstRow());
        assertEquals("r2-edited", model.getValueAt(1, 0));
    }

    @Test
    void replaceAllNoChangeFiresNothing() {
        DefaultTableModel model = new DefaultTableModel("A");
        model.addRow("r1");
        model.addRow("r2");
        CapturingListener listener = new CapturingListener();
        model.addTableModelListener(listener);
        model.replaceAll(List.of(List.of("r1"), List.of("r2")));
        assertEquals(0, listener.events.size(), "Identical content must not disturb the table");
    }

    @Test
    void replaceAllPrependEmitsAddedAtZero() {
        DefaultTableModel model = new DefaultTableModel("A");
        model.addRow("r1");
        CapturingListener listener = new CapturingListener();
        model.addTableModelListener(listener);
        model.replaceAll(List.of(List.of("r0"), List.of("r1")));
        assertEquals(1, listener.events.size());
        assertEquals(TableModelEventType.ROWS_ADDED, listener.events.getFirst().type());
        assertEquals(0, listener.events.getFirst().firstRow());
    }

    @Test
    void replaceAllFullReplaceFromEmpty() {
        DefaultTableModel model = new DefaultTableModel("A");
        CapturingListener listener = new CapturingListener();
        model.addTableModelListener(listener);
        model.replaceAll(List.of(List.of("r1"), List.of("r2")));
        assertEquals(2, model.getRowCount());
        assertEquals(1, listener.events.size());
        assertEquals(TableModelEventType.ROWS_ADDED, listener.events.getFirst().type());
        assertEquals(0, listener.events.getFirst().firstRow());
        assertEquals(1, listener.events.getFirst().lastRow());
    }
}
