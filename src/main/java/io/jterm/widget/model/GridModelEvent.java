package io.jterm.widget.model;

/**
 * Event describing a change to a {@link GridModel}.
 *
 * @param type     the kind of change
 * @param firstRow first affected row, inclusive
 * @param lastRow  last affected row, inclusive
 */
public record GridModelEvent(GridModelEventType type, int firstRow, int lastRow) {
}