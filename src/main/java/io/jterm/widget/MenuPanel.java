package io.jterm.widget;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.style.AnsiColor;
import io.jterm.style.Color;
import io.jterm.style.RgbColor;
import io.jterm.style.SGR;
import io.jterm.style.TextCell;
import io.jterm.style.ThemeManager;
import io.jterm.util.TerminalTextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * A read-only menu panel that displays shortcut keys in brackets and
 * left-aligns descriptions with padding.
 *
 * <p>Layout (each row has a leading and trailing space):
 * <pre>
 *   │  [M]     Message Boards  │
 *   │  [C]     Live Chat       │
 * </pre>
 * The key column is padded to the width of the widest key (including
 * brackets). A fixed gap separates the key column from the description.
 * Each row has 1 space of vertical padding above and below.
 */
public class MenuPanel extends AbstractComponent {

    /**
     * Immutable menu entry.
     *
     * @param key         shortcut key shown in brackets (e.g. {@code "M"} for {@code [M]}); may be empty
     * @param description menu item text shown right of the key column
     */
    public record MenuEntry(String key, String description) {}

    private static final int GAP = 5;
    private static final int MIN_DESC_WIDTH = 4;
    private static final int VERTICAL_PADDING = 1; // rows above and below items

    private final List<MenuEntry> items = new ArrayList<>();
    private int highlightedIndex = -1;
    private int columns = 1;

    /** Creates an empty MenuPanel with a single column. */
    public MenuPanel() {}

    /**
     * Returns the number of columns used to lay out items.
     * Default is 1 (single column). Values {@code <} 1 are clamped to 1.
     *
     * @return the number of layout columns
     */
    public int getColumns() { return columns; }

    /**
     * Sets the number of columns used to lay out items. When {@code > 1},
     * items flow column-major: the first ceil(N/columns) items fill column 0,
     * the next batch fills column 1, etc.
     *
     * @param columns number of layout columns; values {@code <} 1 are clamped to 1
     */
    public void setColumns(int columns) {
        this.columns = Math.max(1, columns);
        invalidate();
    }

    /**
     * Adds an item with a shortcut key and description.
     *
     * @param key         shortcut key shown in brackets; null treated as empty
     * @param description menu item text; null treated as empty
     */
    public void addItem(String key, String description) {
        if (key == null) key = "";
        if (description == null) description = "";
        items.add(new MenuEntry(key, description));
        invalidate();
    }

    /** Removes all items. */
    public void clearItems() {
        items.clear();
        highlightedIndex = -1;
        invalidate();
    }

    /**
     * Returns a copy of the current items.
     *
     * @return a copy of the item list, in insertion order
     */
    public List<MenuEntry> getItems() {
        return new ArrayList<>(items);
    }

    /**
     * Returns the index of the highlighted item.
     *
     * @return the highlighted index, or -1 if none
     */
    public int getHighlightedIndex() {
        return highlightedIndex;
    }

    /**
     * Highlights the item at the given index (rendered in reverse video).
     *
     * @param index item index to highlight; pass -1 to clear
     */
    public void setHighlightedIndex(int index) {
        this.highlightedIndex = Math.max(-1, Math.min(index, items.size() - 1));
        invalidate();
    }

    /**
     * Finds the index of the menu item whose key matches (case-insensitive).
     *
     * @param key key to search for; null returns -1
     * @return the matching index, or -1 if not found
     */
    public int indexOfKey(String key) {
        if (key == null) return -1;
        String upper = key.toUpperCase();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key().equalsIgnoreCase(upper)) return i;
        }
        return -1;
    }

    /**
     * Returns the preferred size based on the widest key/description and item count.
     *
     * @return the preferred terminal size
     */
    @Override
    protected TerminalSize calculatePreferredSize() {
        if (items.isEmpty()) {
            return new TerminalSize(2, VERTICAL_PADDING * 2);
        }
        int keyColWidth = keyColumnWidth();
        int maxDescWidth = 0;
        for (var item : items) {
            maxDescWidth = Math.max(maxDescWidth, TerminalTextUtils.getTrueWidth(item.description()));
        }
        // 1 leading space + keyColWidth + GAP + desc + 1 trailing space
        int colWidth = 1 + keyColWidth + GAP + Math.max(maxDescWidth, MIN_DESC_WIDTH) + 1;

        if (columns <= 1) {
            return new TerminalSize(colWidth, items.size() + VERTICAL_PADDING * 2);
        }

        // Multi-column: distribute items across columns
        int itemsPerColumn = (int) Math.ceil((double) items.size() / columns);
        int colGap = 2; // gap between columns
        int totalWidth = colWidth * columns + colGap * (columns - 1);
        int totalHeight = itemsPerColumn + VERTICAL_PADDING * 2;
        return new TerminalSize(totalWidth, totalHeight);
    }

    /**
     * Renders each menu entry's key (in brackets) and description, with
     * optional highlight for the selected entry.
     *
     * @param graphics the text-graphics target
     */
    @Override
    protected void drawComponent(TextGraphics graphics) {
        var size = getSize();
        var theme = ThemeManager.active();
        var normal = new TextCell(' ', theme.foreground(), theme.background());
        var bold = new TextCell(' ', theme.foreground(), theme.background(), SGR.BOLD);

        int keyColWidth = keyColumnWidth();
        int colWidth = size.columns(); // single-column: fill entire width
        int availableDescWidth = Math.max(0, size.columns() - 1 - keyColWidth - GAP - 1);

        // For multi-column, recalculate available desc width per column
        if (columns > 1) {
            int colGap = 2;
            // totalNonDesc = leading/key/gap/trailing per column + inter-column gaps
            int totalNonDesc = (1 + keyColWidth + GAP + 1) * columns + colGap * (columns - 1);
            availableDescWidth = Math.max(MIN_DESC_WIDTH, (size.columns() - totalNonDesc) / columns);
            colWidth = 1 + keyColWidth + GAP + availableDescWidth + 1;
        }

        graphics.fillRectangle(0, 0, size.columns(), size.rows(), normal);

        int itemsPerColumn = columns > 1 ? (int) Math.ceil((double) items.size() / columns) : items.size();
        int colGap = columns > 1 ? 2 : 0;
        // Single-column matches the original loop bound exactly; multi-column
        // caps at the total slots across all columns.
        int maxItems = columns > 1
                ? itemsPerColumn * columns
                : size.rows() - VERTICAL_PADDING;

        for (int i = 0; i < items.size() && i < maxItems; i++) {
            int col = i / itemsPerColumn;
            int rowInCol = i % itemsPerColumn;

            // Skip if this column+row is out of bounds
            int row = rowInCol + VERTICAL_PADDING;
            if (row >= size.rows() - VERTICAL_PADDING) continue;

            int colX = col * (colWidth + colGap);
            if (colX + colWidth > size.columns()) break;

            var item = items.get(i);
            boolean highlighted = (i == highlightedIndex);

            // Key in brackets: "[M]" — right-padded to keyColWidth. Drawn as
            // three colored segments (Joe 2026-11-27, per-key styling): DIM
            // brackets and the key characters bold in the brightened theme
            // foreground — the bright version of each theme's TEXT color
            // (Joe 11/27 refinement), NOT the accent slot.
            // Widths/padding are unchanged: "[M]" still measures 3 columns.
            String keyInBrackets = "[" + item.key() + "]";
            int keyWidth = TerminalTextUtils.getTrueWidth(keyInBrackets);
            int keyPadding = Math.max(0, keyColWidth - keyWidth);
            int bracketX = colX + 1 + keyPadding; // '[' after the right padding
            // Start at column 1 (leading space)
            // Highlighted cells swap fg/bg directly instead of using SGR.REVERSE.
            // Some CP437 clients (MuffinTerm) don't implement ESC[7m (REVERSE),
            // and on standard ANSI terminals REVERSE would undo the manual swap.
            // Explicit fg/bg color codes work on every terminal.
            var keyCell = highlighted
                    ? new TextCell(' ', theme.background(), theme.foreground(), SGR.BOLD)
                    : bold;
            // DIM on swapped (inverted) colors reads muddy — highlighted rows
            // keep the bare swapped pair so the row still reads as full inversion.
            var bracketCell = highlighted
                    ? new TextCell('[', theme.background(), theme.foreground())
                    : new TextCell('[', theme.foreground(), theme.background(), SGR.DIM);
            var keyCharCell = highlighted
                    ? new TextCell(' ', theme.background(), theme.foreground(), SGR.BOLD)
                    : new TextCell(' ', brighten(theme.foreground()), theme.background(), SGR.BOLD);
            graphics.drawString(colX + 1, row, " ".repeat(keyPadding), keyCell);
            graphics.drawString(bracketX, row, "[", bracketCell);
            // Every character of the key renders in the brightened foreground
            // (multi-char keys too); drawString handles per-char column
            // advance for double-width chars.
            graphics.drawString(bracketX + 1, row, item.key(), keyCharCell);
            graphics.drawString(bracketX + keyWidth - 1, row, "]", bracketCell);

            // Description: left-aligned after the gap
            String desc = TerminalTextUtils.truncate(item.description(), availableDescWidth);
            int descX = colX + 1 + keyColWidth + GAP;
            var descCell = highlighted
                    ? new TextCell(' ', theme.background(), theme.foreground())
                    : normal;
            graphics.drawString(descX, row, desc, descCell);

            // If highlighted, fill the rest of the row with inverted spaces
            if (highlighted) {
                int descEnd = descX + TerminalTextUtils.getTrueWidth(desc);
                int rowEnd = colX + colWidth - 1; // leave 1 trailing space
                if (descEnd < rowEnd) {
                    var fillCell = new TextCell(' ', theme.background(), theme.foreground());
                    graphics.fillRectangle(descEnd, row, rowEnd - descEnd, 1, fillCell);
                }
            }
        }
    }

    private int keyColumnWidth() {
        int max = 0;
        for (var item : items) {
            // Width includes brackets: "[M]" = 3
            int w = TerminalTextUtils.getTrueWidth(item.key()) + 2;
            max = Math.max(max, w);
        }
        return max;
    }

    /**
     * Returns the bright variant of a foreground color for menu-key emphasis
     * (Joe 2026-11-27: key chars pop in the bright version of each theme's
     * TEXT color, not the accent slot).
     *
     * <p>ANSI colors map to their BRIGHT_ counterpart (BLACK→BRIGHT_BLACK,
     * …, WHITE→BRIGHT_WHITE); {@link AnsiColor#DEFAULT} and already-bright
     * colors pass through unchanged. RGB colors scale each channel toward
     * 255 by ×1.4, clamped at 255.
     */
    private static Color brighten(Color c) {
        if (c instanceof AnsiColor a) {
            return switch (a) {
                case BLACK -> AnsiColor.BRIGHT_BLACK;
                case RED -> AnsiColor.BRIGHT_RED;
                case GREEN -> AnsiColor.BRIGHT_GREEN;
                case YELLOW -> AnsiColor.BRIGHT_YELLOW;
                case BLUE -> AnsiColor.BRIGHT_BLUE;
                case MAGENTA -> AnsiColor.BRIGHT_MAGENTA;
                case CYAN -> AnsiColor.BRIGHT_CYAN;
                case WHITE -> AnsiColor.BRIGHT_WHITE;
                default -> a; // DEFAULT and BRIGHT_* pass through unchanged
            };
        }
        if (c instanceof RgbColor rgb) {
            return new RgbColor(
                    (int) Math.min(255, Math.round(rgb.r() * 1.4)),
                    (int) Math.min(255, Math.round(rgb.g() * 1.4)),
                    (int) Math.min(255, Math.round(rgb.b() * 1.4)));
        }
        return c;
    }
}
