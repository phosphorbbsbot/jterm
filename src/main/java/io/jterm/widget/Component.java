package io.jterm.widget;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.layout.LayoutData;

/**
 * UI widget interface. Every interactive or display element in JTerm implements
 * this interface.
 */
public interface Component {
    /**
     * Current bounds size (after layout).
     *
     * @return current size in columns × rows
     */
    TerminalSize getSize();

    /**
     * Current position relative to parent (after layout).
     *
     * @return current top-left position relative to the parent
     */
    TerminalPosition getPosition();

    /**
     * Natural preferred size.
     *
     * @return the size this component would prefer, given its content
     */
    TerminalSize getPreferredSize();

    /**
     * Sets position and size, then triggers any internal layout.
     *
     * @param position new top-left position relative to the parent
     * @param size     new bounds size
     */
    void setBounds(TerminalPosition position, TerminalSize size);

    /**
     * Renders this component into the supplied graphics context.
     *
     * @param graphics graphics context sized to this component's bounds
     */
    void draw(TextGraphics graphics);

    /**
     * Marks this component's cached preferred size as stale.
     */
    void invalidate();

    /**
     * Whether the component should be drawn and laid out.
     *
     * @return true if visible
     */
    boolean isVisible();

    /**
     * Sets visibility.
     *
     * @param visible true to draw and lay out this component
     */
    void setVisible(boolean visible);

    /**
     * Layout-specific constraints, if any.
     *
     * @return the layout data, or {@code null} if none was set
     */
    LayoutData getLayoutData();

    /**
     * Sets layout constraints.
     *
     * @param data layout-specific constraints, or {@code null} to clear
     */
    void setLayoutData(LayoutData data);

    /**
     * Immediate parent container, or null if unattached.
     *
     * @return the parent, or {@code null} if this component has no parent
     */
    Component getParent();

    /**
     * Sets parent container.
     *
     * @param parent the new parent, or {@code null} when detached
     */
    void setParent(Component parent);

    /**
     * Called when this component receives a keyboard event.
     *
     * @param keyStroke the keystroke to handle
     * @return true if the keystroke was consumed, false to allow the window to handle it
     */
    boolean handleKeyStroke(io.jterm.core.input.KeyStroke keyStroke);

    /**
     * Whether this component currently has keyboard focus.
     *
     * @return true if focused
     */
    boolean isFocused();

    /**
     * Sets focused state.
     *
     * @param focused true to give this component keyboard focus
     */
    void setFocused(boolean focused);

    /**
     * Whether this component can receive keyboard focus via TAB traversal.
     *
     * @return true if focusable (default)
     */
    default boolean isFocusable() { return true; }

    /**
     * Sets whether this component can receive focus via TAB traversal.
     *
     * @param focusable true to allow TAB focus traversal
     */
    default void setFocusable(boolean focusable) {}

    /**
     * CURSOR-ANCHOR LAW (2026-11-13): the absolute screen cell where the text
     * cursor should be parked after a frame, while this component holds focus
     * and draws text. Reported DURING drawing (the widget knows its draw
     * origin); null when unfocused or not a text widget — the gui clears the
     * anchor every frame based on the focused component's report.
     *
     * @return the absolute caret cell (0-based), or null
     */
    default TerminalPosition getCursorCell() { return null; }
}
