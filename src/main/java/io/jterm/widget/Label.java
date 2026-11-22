package io.jterm.widget;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.style.AnsiColor;
import io.jterm.style.SGR;
import io.jterm.style.TextCell;
import io.jterm.style.ThemeManager;
import io.jterm.util.TerminalTextUtils;

/** Displays styled text. */
public class Label extends AbstractComponent {
    private String text;
    private TextCell style;
    private HorizontalAlignment horizontalAlignment = HorizontalAlignment.CENTER;

    /**
     * Horizontal alignment of text within the label.
     */
    public enum HorizontalAlignment {
        /** Align text to the left edge. */
        LEFT,
        /** Center text horizontally. */
        CENTER,
        /** Align text to the right edge. */
        RIGHT
    }

    /**
     * Creates a label with the given text using default colors.
     *
     * @param text the label text
     */
    public Label(String text) {
        this(text, AnsiColor.DEFAULT, AnsiColor.DEFAULT);
    }

    /**
     * Creates a label with the given text and foreground/background colors.
     *
     * @param text the label text
     * @param fg   the foreground color
     * @param bg   the background color
     */
    public Label(String text, io.jterm.style.Color fg, io.jterm.style.Color bg) {
        this.text = text;
        this.style = new TextCell(' ', fg, bg);
    }

    /**
     * Creates a label with the given text and cell style.
     *
     * @param text  the label text
     * @param style the cell style (foreground, background, character)
     */
    public Label(String text, TextCell style) {
        this.text = text;
        this.style = style;
    }

    /**
     * Sets the label text and invalidates the component.
     *
     * @param text the new text
     */
    public void setText(String text) {
        this.text = text;
        cachedRows = null; // GC law (2026-11-21): the paint cache is content-bound
        invalidate();
    }

    /**
     * Returns the current label text.
     *
     * @return the text
     */
    public String getText() { return text; }

    /**
     * Sets the foreground color and invalidates the component.
     *
     * @param color the new foreground color
     */
    public void setForeground(io.jterm.style.Color color) {
        this.style = style.withForeground(color);
        invalidate();
    }

    /**
     * Sets the background color and invalidates the component.
     *
     * @param color the new background color
     */
    public void setBackground(io.jterm.style.Color color) {
        this.style = style.withBackground(color);
        invalidate();
    }

    /**
     * Sets the full cell style and invalidates the component.
     *
     * @param style the new cell style
     */
    public void setStyle(TextCell style) {
        this.style = style;
        invalidate();
    }

    /**
     * Sets horizontal alignment of text within the label's bounds.
     *
     * @param alignment the new horizontal alignment
     */
    public void setHorizontalAlignment(HorizontalAlignment alignment) {
        this.horizontalAlignment = alignment;
        invalidate();
    }

    /** Computes preferred size from the widest line and line count. */
    @Override
    protected TerminalSize calculatePreferredSize() {
        if (text == null || text.isEmpty()) return new TerminalSize(1, 1);
        int max = 0;
        for (var line : text.split("\n", -1)) {
            max = Math.max(max, TerminalTextUtils.getTrueWidth(line));
        }
        return new TerminalSize(max, text.split("\n", -1).length);
    }

    /** Renders each line of text with horizontal alignment, falling back to theme colors when defaults are used. */
    @Override
    protected void drawComponent(TextGraphics graphics) {
        var size = getSize();
        // If label uses DEFAULT colors, fall back to theme fg/bg so the
        // label inherits the themed background instead of terminal default.
        var effectiveStyle = style;
        boolean themed = false;
        if (style.fg() == AnsiColor.DEFAULT && style.bg() == AnsiColor.DEFAULT) {
            var theme = ThemeManager.active();
            effectiveStyle = new TextCell(' ', theme.foreground(), theme.background());
            themed = true;
        }

        /* GC law (Joe, 2026-11-21): every frame re-split the text AND
         * re-created one TextCell record (plus its shared char String) per
         * painted character — the biggest single allocation source on the
         * paint loop. Labels paint UN changing content frame after frame, so
         * cache the per-row lines + the built cell rows and re-draw them;
         * invalidate on every text/style/alignment mutation (setText etc.
         * already call invalidate() → the cache is rebuilt there). Theme-
         * resolved labels key the cache on the theme instance, so a theme
         * switch rebuilds it. Per-character allocation only happens once
         * per (row, character); every later frame reuses the SAME cell refs,
         * which also lets ScreenBuffer.diffFrom short-circuit identical
         * cells by reference. */
        if (cachedStyle != effectiveStyle || cachedRows == null
                || (themed && cachedTheme != ThemeManager.active())) {
            cachedStyle = effectiveStyle;
            cachedTheme = themed ? ThemeManager.active() : null;
            cachedLines = text == null ? new String[0] : text.split("\n", -1);
            cachedRows = new TextCell[cachedLines.length][];
            for (int i = 0; i < cachedLines.length; i++) {
                var line = cachedLines[i];
                var row = new TextCell[line.length()];
                for (int j = 0; j < line.length(); j++) {
                    row[j] = effectiveStyle.withCharacter(line.charAt(j));
                }
                cachedRows[i] = row;
            }
        }
        if (cachedRows == null) return;

        for (int i = 0; i < cachedLines.length && i < size.rows(); i++) {
            var line = cachedLines[i];
            int width = TerminalTextUtils.getTrueWidth(line);
            int x = 0;
            if (width < size.columns()) {
                switch (horizontalAlignment) {
                    case CENTER -> x = (size.columns() - width) / 2;
                    case RIGHT  -> x = size.columns() - width;
                    case LEFT   -> x = 0;
                }
            }
            var row = cachedRows[i];
            for (int j = 0; j < row.length; j++) {
                graphics.setCell(x + j, i, row[j]);
            }
        }
    }

    /** Cached paint rows (GC law, 2026-11-21) — rebuilt on mutation/theme change. */
    private String[] cachedLines;
    private TextCell[][] cachedRows;
    private TextCell cachedStyle;
    private Object cachedTheme;
}
