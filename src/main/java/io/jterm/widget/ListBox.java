package io.jterm.widget;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.graphics.TextGraphics;
import io.jterm.style.AnsiColor;
import io.jterm.style.SGR;
import io.jterm.style.TextCell;
import io.jterm.style.ThemeManager;
import io.jterm.util.TerminalTextUtils;
import io.jterm.widget.model.DefaultListModel;
import io.jterm.widget.model.ListDataEvent;
import io.jterm.widget.model.ListDataListener;
import io.jterm.widget.model.ListModel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Scrollable list of items backed by a {@link ListModel}.
 *
 * @param <T> the type of items displayed in the list
 */
public class ListBox<T> extends AbstractComponent implements ListDataListener {
    private ListModel<T> model;
    private volatile int selectedIndex = 0;
    private volatile int scrollOffset = 0;
    private volatile Function<T, String> renderer = Object::toString;
    private volatile RowStyle rowStyleProvider;

    /**
     * Per-row style override, mirroring {@link Table}'s RowStyle law: return a
     * TextCell whose character/fg/bg are placeholder values — only its SGR
     * modifiers are used (colors stay theme-driven). The selected row ignores
     * the provider so selection styling always wins. Null falls back to the
     * theme style.
     */
    public interface RowStyle {
        /**
         * Styles one list row.
         *
         * @param row the absolute model row index being rendered
         * @return the styled cell (only SGR modifiers are consumed), or
         *         {@code null} to fall back to the theme style entirely
         */
        io.jterm.style.TextCell apply(int row);
    }

    /**
     * Provider fg resolution law: a provider color that is NOT the
     * {@code DEFAULT} placeholder is consumed verbatim (so a viewer can pin
     * an explicit dark gray that survives terminals ignoring SGR 2); the
     * {@code DEFAULT} placeholder falls back to the theme foreground.
     */
    private static io.jterm.style.Color resolveProviderForeground(TextCell custom, io.jterm.style.Theme theme) {
        var fg = custom.fg();
        return fg == io.jterm.style.AnsiColor.DEFAULT ? theme.foreground() : fg;
    }

    /**
     * Provider bg resolution law — mirrors {@link #resolveProviderForeground}.
     */
    private static io.jterm.style.Color resolveProviderBackground(TextCell custom, io.jterm.style.Theme theme) {
        var bg = custom.bg();
        return bg == io.jterm.style.AnsiColor.DEFAULT ? theme.background() : bg;
    }

    /**
     * Installs a per-row style provider (e.g. dim bulletin-view author/time
     * header lines) or clears it with {@code null}.
     *
     * @param provider per-row style source, or {@code null} to clear
     */
    public void setRowStyleProvider(RowStyle provider) {
        this.rowStyleProvider = provider;
        invalidate();
    }

    /** Returns the installed row-style provider, or {@code null} when none.
     * @return the installed provider, or {@code null} when none is set */
    public RowStyle getRowStyleProvider() {
        return rowStyleProvider;
    }

    private volatile RowSegments rowSegmentsProvider;

    /**
     * Per-row character-range styling on top of the row style (Joe, round 3:
     * the author name inside a dimmed header line rendered in its own color).
     * Segments index CHARACTERS of the rendered row string; the LAST matching
     * segment wins (same law as {@code TextGraphics.drawStyledString}). The
     * row-style provider remains the base outside segments; segments are
     * IGNORED on the selected row (selection styling wins). A segment's
     * DEFAULT bg resolves to the row's base background.
     */
    public interface RowSegments {
        /**
         * Segments for one rendered row.
         *
         * @param row the absolute model row index being rendered
         * @return styled character ranges, or {@code null} when the row has none
         */
        java.util.List<io.jterm.style.StyledSegment> apply(int row);
    }

    /**
     * Installs a per-row segment styler or clears it with {@code null}.
     *
     * @param provider per-row segment source, or {@code null} to clear
     */
    public void setRowSegments(RowSegments provider) {
        this.rowSegmentsProvider = provider;
        invalidate();
    }

    /** Returns the installed row-segments provider, or {@code null} when none.
     * @return the installed provider, or {@code null} when none is set */
    public RowSegments getRowSegments() {
        return rowSegmentsProvider;
    }
    private final List<Runnable> selectionListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean autoScroll = false;
    private volatile boolean manualScroll = false;

    /** Creates a ListBox backed by an empty {@link DefaultListModel}. */
    public ListBox() {
        this(new DefaultListModel<>());
    }

    /**
     * Creates a ListBox that displays the supplied model.
     *
     * @param model the model to display
     */
    public ListBox(ListModel<T> model) {
        setModel(model);
    }

    /**
     * Replaces the model backing this list.
     *
     * <p>The list unregisters itself from the old model (if any), registers with
     * the new model, resets selection/scroll, and invalidates.
     *
     * @param model the new backing model
     */
    public void setModel(ListModel<T> model) {
        if (this.model != null) {
            this.model.removeListDataListener(this);
        }
        this.model = model;
        model.addListDataListener(this);
        selectedIndex = 0;
        scrollOffset = 0;
        manualScroll = false;
        invalidate();
    }

    /**
     * Returns the model currently backing this list.
     *
     * @return the backing model
     */
    public ListModel<T> getModel() {
        return model;
    }

    /**
     * Convenience: delegates to the model if it is a {@link DefaultListModel}.
     *
     * @param item the item to append
     */
    public void addItem(T item) {
        if (model instanceof DefaultListModel<T> defaultModel) {
            defaultModel.addElement(item);
        } else {
            throw new UnsupportedOperationException(
                    "addItem requires a DefaultListModel; current model is " + model.getClass().getName());
        }
    }

    /** Convenience: removes all items if the model is a {@link DefaultListModel}. */
    public void clearItems() {
        if (model instanceof DefaultListModel<T> defaultModel) {
            defaultModel.clear();
        } else {
            throw new UnsupportedOperationException(
                    "clearItems requires a DefaultListModel; current model is " + model.getClass().getName());
        }
    }

    /**
     * Enables or disables auto-scroll: when enabled, the list keeps the last
     * item visible and disables manual scroll.
     *
     * @param autoScroll {@code true} to keep the last item visible
     */
    public void setAutoScroll(boolean autoScroll) {
        this.autoScroll = autoScroll;
        if (autoScroll) {
            manualScroll = false;
            scrollToBottom();
        }
        invalidate();
    }

    /**
     * Returns whether auto-scroll is enabled.
     *
     * @return {@code true} if auto-scroll is enabled
     */
    public boolean isAutoScroll() {
        return autoScroll;
    }

    /** Scrolls the list so that the last item is visible. */
    public void scrollToBottom() {
        int rows = getSize().rows();
        int maxOffset = Math.max(0, model.getSize() - rows);
        scrollOffset = maxOffset;
        manualScroll = false;
        invalidate();
    }

    /**
     * Returns the current scroll offset (the index of the topmost visible item).
     *
     * @return the current scroll offset
     */
    public int getScrollOffset() {
        return scrollOffset;
    }

    /** Scroll down by one viewport height (page down). */
    public void pageDown() {
        int rows = getSize().rows();
        setScrollOffset(scrollOffset + rows);
        setSelectedIndex(scrollOffset + rows - 1);
    }

    /** Scroll up by one viewport height (page up). */
    public void pageUp() {
        int rows = getSize().rows();
        setScrollOffset(scrollOffset - rows);
        setSelectedIndex(scrollOffset);
    }

    /**
     * Sets the scroll offset, clamped to {@code [0, maxSize - rows]}. Manual
     * scroll is engaged when the offset is below the maximum.
     *
     * @param offset the desired scroll offset
     */
    public void setScrollOffset(int offset) {
        int rows = getSize().rows();
        int maxOffset = Math.max(0, model.getSize() - rows);
        this.scrollOffset = Math.max(0, Math.min(offset, maxOffset));
        this.manualScroll = this.scrollOffset < maxOffset;
        invalidate();
    }

    /**
     * Returns whether the last model item is currently visible.
     *
     * @return {@code true} if the last item is visible
     */
    public boolean isLastItemVisible() {
        int rows = getSize().rows();
        int size = model.getSize();
        if (size <= rows) return true;
        return scrollOffset + rows >= size;
    }

    /**
     * Sets the function used to render each model element to a display string.
     *
     * @param renderer the rendering function
     */
    public void setRenderer(Function<T, String> renderer) {
        this.renderer = renderer;
        invalidate();
    }

    /**
     * Returns the current rendering function.
     *
     * @return the rendering function
     */
    public Function<T, String> getRenderer() {
        return renderer;
    }

    /**
     * Adds a listener that is fired whenever the selection changes.
     *
     * @param listener the listener to add
     */
    public void addSelectionListener(Runnable listener) {
        selectionListeners.add(listener);
    }

    /**
     * Returns a defensive copy of the registered selection listeners.
     *
     * @return a copy of the registered listeners
     */
    public List<Runnable> getSelectionListeners() {
        return new ArrayList<>(selectionListeners);
    }

    /**
     * Returns the currently selected item, or {@code null} if the list is empty.
     *
     * @return the selected item, or {@code null}
     */
    public T getSelectedItem() {
        int size = model.getSize();
        if (size == 0 || selectedIndex < 0 || selectedIndex >= size) return null;
        return model.getElementAt(selectedIndex);
    }

    /**
     * Returns the index of the currently selected item.
     *
     * @return the selected index
     */
    public int getSelectedIndex() { return selectedIndex; }

    /**
     * Sets the selected index, clamped to the valid model range, and fires
     * selection listeners. When auto-scroll is not manually overridden the
     * selected item is kept visible.
     *
     * @param index the desired selection index
     */
    public void setSelectedIndex(int index) {
        int size = model.getSize();
        if (index < 0) index = 0;
        else if (size == 0) index = 0;
        else if (index >= size) index = size - 1;
        if (index == this.selectedIndex) return;
        this.selectedIndex = index;
        if (!manualScroll) {
            ensureVisible();
        }
        fireSelectionChanged();
        invalidate();
    }

    /**
     * Returns a list of all items currently in the model.
     *
     * @return a copy of the model's items
     */
    public List<T> getItems() {
        List<T> result = new ArrayList<>(model.getSize());
        for (int i = 0; i < model.getSize(); i++) {
            result.add(model.getElementAt(i));
        }
        return result;
    }

    private void ensureVisible() {
        var rows = getSize().rows();
        if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
        if (selectedIndex >= scrollOffset + rows) scrollOffset = selectedIndex - rows + 1;
        if (scrollOffset < 0) scrollOffset = 0;
        int maxOffset = Math.max(0, model.getSize() - rows);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
    }

    /**
     * Computes the preferred size based on the widest rendered item and the
     * model size (clamped to 10 rows).
     *
     * @return the preferred terminal size
     */
    @Override
    protected TerminalSize calculatePreferredSize() {
        int maxLen = 4;
        for (int i = 0; i < model.getSize(); i++) {
            T item = model.getElementAt(i);
            maxLen = Math.max(maxLen, TerminalTextUtils.getTrueWidth(renderer.apply(item)));
        }
        return new TerminalSize(maxLen, Math.max(2, Math.min(model.getSize(), 10)));
    }

    /**
     * Renders the visible portion of the list, highlighting the selected row.
     *
     * @param graphics the text-graphics target
     */
    @Override
    protected void drawComponent(TextGraphics graphics) {
        var size = getSize();
        var theme = ThemeManager.active();
        int modelSize = model.getSize();
        for (int r = 0; r < size.rows(); r++) {
            int idx = scrollOffset + r;
            if (idx >= modelSize) {
                graphics.fillRectangle(0, r, size.columns(), 1, new TextCell(' ', theme.foreground(), theme.background()));
                continue;
            }
            String text = renderer.apply(model.getElementAt(idx));
            boolean selected = idx == selectedIndex;
            TextCell style;
            if (selected) {
                // Selection always wins over the row-style provider (same law
                // as Table's RowStyle) — a provider like "dim header lines"
                // never restyles the cursor row.
                style = new TextCell(' ', theme.selectionFg(), theme.selectionBg());
            } else if (rowStyleProvider != null) {
                var custom = rowStyleProvider.apply(idx);
                style = custom != null
                    ? new TextCell(' ', resolveProviderForeground(custom, theme), resolveProviderBackground(custom, theme),
                        custom.modifiers().toArray(new SGR[0]))
                    : new TextCell(' ', theme.foreground(), theme.background());
            } else {
                style = new TextCell(' ', theme.foreground(), theme.background());
            }
            graphics.fillRectangle(0, r, size.columns(), 1, style);
            String drawn = TerminalTextUtils.truncate(text, size.columns());
            var segments = (!selected && rowSegmentsProvider != null) ? rowSegmentsProvider.apply(idx) : null;
            if (segments == null || segments.isEmpty()) {
                graphics.drawString(0, r, drawn, style);
            } else {
                // Segment law: LAST matching segment wins; outside segments the
                // row-style base applies; segment DEFAULT bg resolves to the
                // row's base bg (mirrors TextGraphics.drawStyledString).
                final TextCell baseStyle = style;
                graphics.drawStyledString(0, r, drawn, baseStyle, (charIdx, c, defStyle) -> {
                    io.jterm.style.StyledSegment matched = null;
                    for (io.jterm.style.StyledSegment seg : segments) {
                        if (seg.contains(charIdx)) {
                            matched = seg;
                        }
                    }
                    if (matched == null) {
                        return null;
                    }
                    return new TextCell(c, matched.fg(),
                        matched.bg() == io.jterm.style.AnsiColor.DEFAULT ? defStyle.bg() : matched.bg(),
                        matched.modifiers().toArray(new SGR[0]));
                });
            }
        }
    }

    /**
     * Handles Emacs-style and arrow navigation keys.
     *
     * @param keyStroke the keystroke to handle
     * @return {@code true} if the keystroke was consumed
     */
    @Override
    public boolean handleKeyStroke(KeyStroke keyStroke) {
        // Emacs-style key bindings (Ctrl+letter arrives as CHARACTER with ctrl=true)
        if (keyStroke.type() == KeyType.CHARACTER && keyStroke.ctrl()) {
            switch (keyStroke.character()) {
                case 'P', 'p' -> { setSelectedIndex(selectedIndex - 1); return true; }
                case 'N', 'n' -> { setSelectedIndex(selectedIndex + 1); return true; }
                case 'V', 'v' -> { pageDown(); return true; }
                default -> { return false; }  // Ignore other Ctrl+letter combos
            }
        }
        switch (keyStroke.type()) {
            case ARROW_UP -> { setSelectedIndex(selectedIndex - 1); return true; }
            case ARROW_DOWN -> { setSelectedIndex(selectedIndex + 1); return true; }
            case PAGE_UP -> { pageUp(); return true; }
            case PAGE_DOWN -> { pageDown(); return true; }
            case HOME -> { setSelectedIndex(0); return true; }
            case END -> { setSelectedIndex(model.getSize() - 1); return true; }
            case ENTER -> { fireSelectionChanged(); return true; }
            default -> { return false; }
        }
    }

    /**
     * Updates bounds and re-ensures the selected item is visible.
     *
     * @param position the new position
     * @param size     the new size
     */
    @Override
    public void setBounds(TerminalPosition position, TerminalSize size) {
        super.setBounds(position, size);
        ensureVisible();
    }

    /** {@inheritDoc} — keeps selection valid and scrolls when auto-scroll is on. */
    @Override
    public void intervalAdded(ListDataEvent e) {
        if (autoScroll) {
            scrollToBottom();
        }
        if (selectedIndex >= model.getSize()) {
            selectedIndex = Math.max(0, model.getSize() - 1);
        }
        invalidate();
    }

    /** {@inheritDoc} — keeps selection valid and re-ensures visibility. */
    @Override
    public void intervalRemoved(ListDataEvent e) {
        int oldSelected = selectedIndex;
        if (selectedIndex >= model.getSize()) {
            selectedIndex = Math.max(0, model.getSize() - 1);
        }
        if (selectedIndex != oldSelected) {
            fireSelectionChanged();
        }
        ensureVisible();
        invalidate();
    }

    /** {@inheritDoc} — clamps the selection and re-renders. */
    @Override
    public void contentsChanged(ListDataEvent e) {
        if (selectedIndex >= model.getSize()) {
            selectedIndex = Math.max(0, model.getSize() - 1);
        }
        ensureVisible();
        invalidate();
    }

    private void fireSelectionChanged() {
        for (var l : selectionListeners) l.run();
    }
}
