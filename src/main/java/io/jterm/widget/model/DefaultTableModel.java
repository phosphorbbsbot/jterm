package io.jterm.widget.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default mutable implementation of {@link TableModel}.
 */
public class DefaultTableModel extends AbstractTableModel {

    private final List<String> headers;
    private final List<List<String>> rows = new CopyOnWriteArrayList<>();

    /**
     * Creates a model with the given column headers.
     *
     * @param headers the column headers
     */
    public DefaultTableModel(String... headers) {
        this.headers = new CopyOnWriteArrayList<>(Arrays.asList(headers));
    }

    /** {@inheritDoc} — returns the current row count. */
    @Override
    public int getRowCount() {
        return rows.size();
    }

    /** {@inheritDoc} — returns the column count from the headers. */
    @Override
    public int getColumnCount() {
        return headers.size();
    }

    /**
     * {@inheritDoc} — returns the header at the given index, or empty string if out of range.
     */
    @Override
    public String getColumnName(int col) {
        if (col < 0 || col >= headers.size()) return "";
        return headers.get(col);
    }

    /**
     * {@inheritDoc} — returns the cell value, or empty string if row/column is out of range.
     */
    @Override
    public String getValueAt(int row, int col) {
        if (row < 0 || row >= rows.size()) return "";
        List<String> cells = rows.get(row);
        if (col < 0 || col >= cells.size()) return "";
        return cells.get(col);
    }

    /**
     * Adds a row to the end of this model.
     *
     * @param cells the cell values
     */
    public void addRow(String... cells) {
        int index = rows.size();
        rows.add(new ArrayList<>(Arrays.asList(cells)));
        fireRowsAdded(index, index);
    }

    /**
     * Inserts a row at the specified index.
     *
     * @param index the index at which to insert
     * @param cells the cell values
     */
    public void insertRow(int index, String... cells) {
        rows.add(index, new ArrayList<>(Arrays.asList(cells)));
        fireRowsAdded(index, index);
    }

    /**
     * Replaces the value of a single cell.
     *
     * @param row the row index
     * @param col the column index
     * @param value the new value
     */
    public void setValueAt(int row, int col, String value) {
        rows.get(row).set(col, value);
        fireCellsChanged(row, row, col);
    }

    /**
     * Removes the row at the specified index.
     *
     * @param index the row index
     */
    public void removeRow(int index) {
        rows.remove(index);
        fireRowsRemoved(index, index);
    }

    /**
     * Removes all rows from this model.
     */
    public void clear() {
        int size = rows.size();
        rows.clear();
        fireRowsChanged(0, Math.max(0, size - 1));
    }

    /**
     * Replaces the model's contents with {@code newRows}, diffing old against
     * new and emitting the true structural events (ROWS_REMOVED for rows that
     * disappeared, ROWS_ADDED for rows that appeared) so listeners — and the
     * Table's content-anchored selection — follow the actual row movement
     * instead of surviving a wholesale structure change. Fires nothing when
     * the content is identical; at most ONE removal event and ONE addition
     * event otherwise (common prefix/suffix are left untouched).
     *
     * @param newRows the new row contents
     */
    public void replaceAll(List<List<String>> newRows) {
        List<List<String>> target = new ArrayList<>();
        for (List<String> row : newRows) {
            target.add(new ArrayList<>(row));
        }
        int oldSize = rows.size();
        int newSize = target.size();
        // Common prefix.
        int p = 0;
        while (p < oldSize && p < newSize && rows.get(p).equals(target.get(p))) {
            p++;
        }
        // Common suffix (never reaching into the prefix).
        int s = 0;
        while (s < oldSize - p && s < newSize - p
                && rows.get(oldSize - 1 - s).equals(target.get(newSize - 1 - s))) {
            s++;
        }
        int removedLast = oldSize - s - 1;   // inclusive; valid only if >= p
        int addedLast = newSize - s - 1;     // inclusive; valid only if >= p
        if (p <= removedLast && p <= addedLast && removedLast - p == addedLast - p) {
            // Same-size middle: an in-place edit / same-count replacement.
            // One ROWS_CHANGED keeps every listener's selection anchored on
            // the slot instead of double-moving through remove+add.
            for (int i = p; i <= removedLast; i++) {
                rows.set(i, new ArrayList<>(target.get(i)));
            }
            fireRowsChanged(p, removedLast);
        } else {
            if (p <= removedLast) {
                rows.subList(p, removedLast + 1).clear();
                fireRowsRemoved(p, removedLast);
            }
            if (p <= addedLast) {
                for (int i = p; i <= addedLast; i++) {
                    rows.add(i, new ArrayList<>(target.get(i)));
                }
                fireRowsAdded(p, addedLast);
            }
        }
    }
}
