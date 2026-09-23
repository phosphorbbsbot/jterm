package io.jterm.widget.model;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default mutable implementation of {@link GridModel}, backed by a
 * {@link CopyOnWriteArrayList} for thread-safe reads and writes.
 *
 * <p>All mutating methods fire {@link GridListener#gridChanged()} exactly
 * once per call (not once per element).
 *
 * @param <T> the row type
 */
public class DefaultGridModel<T> implements GridModel<T> {

    /** Creates an empty grid model with no rows and no listeners. */
    public DefaultGridModel() {}

    private final List<T> rows = new CopyOnWriteArrayList<>();
    private final List<GridListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Adds a single row to the end of this model and fires {@code gridChanged}.
     *
     * @param row the row to add
     */
    public void addRow(T row) {
        synchronized (rows) {
            rows.add(row);
        }
        fireGridChanged();
    }

    /**
     * Adds all rows from the given collection to the end of this model and
     * fires {@code gridChanged} exactly once.
     *
     * @param newRows the rows to add
     */
    public void addRows(Collection<T> newRows) {
        synchronized (rows) {
            rows.addAll(newRows);
        }
        fireGridChanged();
    }

    /**
     * Replaces all rows in this model with the given collection, firing
     * {@code gridChanged} exactly once. The clear+add sequence is atomic,
     * so concurrent {@code setRows} calls always leave the model holding
     * exactly one caller's rows (never a blend of two).
     *
     * @param newRows the new set of rows
     */
    public void setRows(Collection<T> newRows) {
        synchronized (rows) {
            rows.clear();
            rows.addAll(newRows);
        }
        fireGridChanged();
    }

    /**
     * Removes all rows from this model and fires {@code gridChanged}.
     */
    public void clear() {
        synchronized (rows) {
            rows.clear();
        }
        fireGridChanged();
    }

    /** {@inheritDoc} — returns the current row count. */
    @Override
    public int getRowCount() {
        return rows.size();
    }

    /**
     * {@inheritDoc} — returns the row at the given index.
     *
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    @Override
    public T getRow(int index) {
        return rows.get(index);  // CopyOnWriteArrayList.get throws IndexOutOfBoundsException for invalid index
    }

    /** {@inheritDoc} — adds a listener to be notified on model changes. */
    @Override
    public void addGridListener(GridListener listener) {
        listeners.add(listener);
    }

    /** {@inheritDoc} — removes a previously added listener. */
    @Override
    public void removeGridListener(GridListener listener) {
        listeners.remove(listener);
    }

    private void fireGridChanged() {
        for (GridListener listener : listeners) {
            listener.gridChanged();
        }
    }
}