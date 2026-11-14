package io.jterm.widget;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.layout.LayoutData;
import io.jterm.layout.LayoutManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Base container implementation with child list and layout delegation.
 *
 * {@link #calculatePreferredSize()} delegates to the layout manager, and
 * {@link #setBounds} triggers re-layout of children.
 */
public abstract class AbstractContainer extends AbstractComponent implements Container {
    private final List<Component> children = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile LayoutManager layoutManager;

    /** Creates a container with no layout manager. */
    public AbstractContainer() {}

    /**
     * Creates a container with the specified layout manager.
     *
     * @param layoutManager layout manager to arrange children with, or {@code null} for none
     */
    public AbstractContainer(LayoutManager layoutManager) {
        this.layoutManager = layoutManager;
    }

    /** {@inheritDoc} */
    @Override
    public List<Component> getChildren() { return new ArrayList<>(children); }

    /**
     * CURSOR-ANCHOR LAW forwarding: containers report the caret cell of their
     * first child that has one, so a focused PANEL wrapping a text widget
     * (the common dialog structure — the window focuses the root panel)
     * still surfaces the widget's caret to the gui. Unfocused text widgets
     * report null, so only the focused one wins.
     *
     * @return the caret cell of the first reporting child, or null
     */
    @Override
    public io.jterm.core.TerminalPosition getCursorCell() {
        for (Component child : children) {
            var cell = child.getCursorCell();
            if (cell != null) {
                return cell;
            }
        }
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public void addComponent(Component component) {
        children.add(component);
        component.setParent(this);
        invalidate();
    }

    /** {@inheritDoc} */
    @Override
    public void addComponent(Component component, Object layoutData) {
        if (layoutData instanceof LayoutData ld) {
            component.setLayoutData(ld);
        }
        addComponent(component);
    }

    /** {@inheritDoc} */
    @Override
    public void removeComponent(Component component) {
        if (children.remove(component)) {
            component.setParent(null);
            invalidate();
        }
    }

    /** {@inheritDoc} */
    @Override
    public LayoutManager getLayoutManager() { return layoutManager; }

    /** {@inheritDoc} */
    @Override
    public void setLayoutManager(LayoutManager layoutManager) {
        this.layoutManager = layoutManager;
        invalidate();
    }

    /** Computes the preferred size by delegating to the layout manager. */
    @Override
    protected TerminalSize calculatePreferredSize() {
        if (layoutManager == null) return new TerminalSize(1, 1);
        return layoutManager.getPreferredSize(children);
    }

    /** {@inheritDoc} */
    @Override
    public void setBounds(TerminalPosition position, TerminalSize size) {
        super.setBounds(position, size);
        if (layoutManager != null) {
            layoutManager.doLayout(size, children);
        }
    }

    /** Renders all visible children into sub-graphics contexts. */
    @Override
    protected void drawComponent(TextGraphics graphics) {
        for (var child : children) {
            if (!child.isVisible()) continue;
            var pos = child.getPosition();
            var size = child.getSize();
            if (size.columns() <= 0 || size.rows() <= 0) continue;
            var sub = io.jterm.graphics.TextGraphicsExtensions.subGraphics(graphics, pos, size);
            child.draw(sub);
        }
    }
}
