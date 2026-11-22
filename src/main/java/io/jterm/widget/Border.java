package io.jterm.widget;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.style.AnsiColor;
import io.jterm.style.SGR;
import io.jterm.style.TextCell;
import io.jterm.style.ThemeManager;
import io.jterm.util.Symbols;

/** Decorative border wrapping a component. */
public class Border extends AbstractContainer {
    private final BorderStyle style;
    private String title;

    /**
     * Creates a border with the default single-line style wrapping the given component.
     *
     * @param contents the component to wrap
     */
    public Border(Component contents) { this(contents, BorderStyle.SINGLE_LINE); }

    /**
     * Creates a border with the given style wrapping the given component.
     *
     * @param contents the component to wrap
     * @param style    the border style
     */
    public Border(Component contents, BorderStyle style) {
        this.style = style;
        addComponent(contents);
    }

    /**
     * Sets the title shown in the top border line.
     *
     * @param title the title text
     */
    public void setTitle(String title) { this.title = title; }

    /**
     * Returns the preferred size of the wrapped component plus 2 columns/rows
     * for the border (or unchanged if the style is empty).
     *
     * @return the preferred terminal size
     */
    @Override
    protected TerminalSize calculatePreferredSize() {
        var kids = childrenView();
        var ps = kids.get(0).getPreferredSize();
        if (style.isEmpty()) return ps;
        return new TerminalSize(ps.columns() + 2, ps.rows() + 2);
    }

    /**
     * Lays out the wrapped component inside the border (with 1-cell insets when a border is drawn).
     *
     * @param position the new position
     * @param size     the new size
     */
    @Override
    public void setBounds(io.jterm.core.TerminalPosition position, TerminalSize size) {
        super.setBounds(position, size);
        var kids = childrenView();
        if (!kids.isEmpty()) {
            if (style.isEmpty()) {
                kids.get(0).setBounds(new TerminalPosition(0, 0), new TerminalSize(size.columns(), size.rows()));
            } else {
                kids.get(0).setBounds(new TerminalPosition(1, 1), new TerminalSize(size.columns() - 2, size.rows() - 2));
            }
        }
    }

    /**
     * Draws the border frame, optional title, and the wrapped component inside.
     *
     * @param graphics the text-graphics target
     */
    @Override
    protected void drawComponent(TextGraphics graphics) {
        if (style.isEmpty()) {
            super.drawComponent(graphics);
            return;
        }
        var size = getSize();
        var theme = ThemeManager.active();
        // Fill the border's interior with the theme background so lower-z-order
        // windows (e.g. an animated background) don't bleed through.
        var bgCell = new TextCell(' ', theme.foreground(), theme.background());
        graphics.fillRectangle(0, 0, size.columns(), size.rows(), bgCell);
        var borderCell = new TextCell(' ', theme.border(), theme.background());
        // top
        graphics.drawString(0, 0, style.tl + repeat(style.h, size.columns() - 2) + style.tr, borderCell);
        // sides
        for (int r = 1; r < size.rows() - 1; r++) {
            graphics.drawString(0, r, style.v, borderCell);
            graphics.drawString(size.columns() - 1, r, style.v, borderCell);
        }
        // bottom
        graphics.drawString(0, size.rows() - 1, style.bl + repeat(style.h, size.columns() - 2) + style.br, borderCell);
        if (title != null && !title.isEmpty() && size.columns() > title.length() + 4) {
            graphics.drawString(2, 0, " " + title + " ", new TextCell(' ', theme.foreground(), theme.background()));
        }
        super.drawComponent(graphics);
    }

    private String repeat(String s, int n) {
        return n <= 0 ? "" : s.repeat(n);
    }

    /**
     * Border character styles, each defining corner and edge glyphs
     * (typically CP437 box-drawing characters).
     */
    public enum BorderStyle {
        /** Single-line box-drawing border: ├──┤ │ ─. */
        SINGLE_LINE(Symbols.TL_CORNER, Symbols.TR_CORNER, Symbols.BL_CORNER, Symbols.BR_CORNER, Symbols.H_LINE, Symbols.V_LINE),
        /** Double-line box-drawing border: ╔══╗ ║. */
        DOUBLE_LINE(Symbols.TL_DOUBLE, Symbols.TR_DOUBLE, Symbols.BL_DOUBLE, Symbols.BR_DOUBLE, Symbols.H_DOUBLE, Symbols.V_DOUBLE),
        /** Rounded corner border: ╭─╮ ╵. */
        ROUNDED(Symbols.TL_ROUNDED, Symbols.TR_ROUNDED, Symbols.BL_ROUNDED, Symbols.BR_ROUNDED, Symbols.H_LINE, Symbols.V_LINE),
        /** Bevel border: double top, single bottom. */
        BEVEL(Symbols.TL_DOUBLE, Symbols.TR_CORNER, Symbols.BL_CORNER, Symbols.BR_DOUBLE, Symbols.H_DOUBLE, Symbols.V_DOUBLE),
        /** Invisible border: no glyphs drawn. */
        EMPTY("", "", "", "", "", "");

        final String tl, tr, bl, br, h, v;
        BorderStyle(String tl, String tr, String bl, String br, String h, String v) {
            this.tl = tl; this.tr = tr; this.bl = bl; this.br = br; this.h = h; this.v = v;
        }

        boolean isEmpty() { return this == EMPTY; }

        /**
         * Top-left corner character.
         *
         * @return the top-left corner glyph
         */
        public String topLeft() { return tl; }
        /**
         * Top-right corner character.
         *
         * @return the top-right corner glyph
         */
        public String topRight() { return tr; }
        /**
         * Bottom-left corner character.
         *
         * @return the bottom-left corner glyph
         */
        public String bottomLeft() { return bl; }
        /**
         * Bottom-right corner character.
         *
         * @return the bottom-right corner glyph
         */
        public String bottomRight() { return br; }
        /**
         * Horizontal edge character.
         *
         * @return the horizontal edge glyph
         */
        public String horizontal() { return h; }
        /**
         * Vertical edge character.
         *
         * @return the vertical edge glyph
         */
        public String vertical() { return v; }
    }
}
