package io.jterm.widget.model;

/**
 * Functional interface for listeners that want to be notified of changes
 * to a {@link GridModel}.
 */
@FunctionalInterface
public interface GridListener {

    /**
     * Called when the grid model has changed (rows added, removed, replaced, or cleared).
     */
    void gridChanged();

    /**
     * Called with a payload describing WHAT changed and WHERE. Listeners that
     * only care that "something changed" get this routed to {@link #gridChanged()}
     * by the default implementation, so existing lambdas and implementors keep
     * working. Widget-level listeners (e.g. DataGrid) override this to keep the
     * selection anchored to its content across structural edits: rows shifted at
     * or above the selection shift the selection with them.
     *
     * @param event the change description
     */
    default void gridChanged(GridModelEvent event) {
        gridChanged();
    }
}