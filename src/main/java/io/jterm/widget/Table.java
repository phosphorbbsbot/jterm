package io.jterm.widget;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyType;
import io.jterm.graphics.TextGraphics;
import io.jterm.style.AnsiColor;
import io.jterm.style.SGR;
import io.jterm.style.TextCell;
import io.jterm.style.ThemeManager;
import io.jterm.util.Symbols;
import io.jterm.util.TerminalTextUtils;
import io.jterm.widget.model.DefaultTableModel;
import io.jterm.widget.model.TableModel;
import io.jterm.widget.model.TableModelEvent;
import io.jterm.widget.model.TableModelEventType;
import io.jterm.widget.model.TableModelListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Column/row table with headers and scrolling. */
public class Table extends AbstractComponent implements TableModelListener {
    private TableModel model;
    private volatile int selectedRow = 0;
    private volatile int scrollOffset = 0;
    private volatile int[] columnWidths;

    /**
     * Per-column width constraints. All fields optional ({@code null} = unset);
     * when set they override the content-based auto-sizing for that column.
     *
     * @param pct    exact width as percent of usable width (0 exclusive, 100 inclusive)
     * @param minChars absolute minimum width in characters
     * @param minPct   minimum width as percent of usable width
     * @param maxChars absolute maximum width in characters
     * @param maxPct   maximum width as percent of usable width
     */
    private record WidthConstraint(Double pct, Integer minChars, Double minPct,
                                   Integer maxChars, Double maxPct) {
        boolean isEmpty() {
            return pct == null && minChars == null && minPct == null
                    && maxChars == null && maxPct == null;
        }
    }

    /** Per-column width constraints; null entries or null array = auto-sizing. */
    private volatile WidthConstraint[] columnWidthConstraints;

    /** Per-column alignment array; defaults to {@code LEFT} for all columns. */
    private Alignment[] columnAlignments;

    /** Character drawn between columns. Defaults to {@code "│"}. Use {@code " "} for no visible separator. */
    private String columnSeparatorChar = "│";

    /**
     * Per-column text alignment for table cells.
     * <ul>
     *   <li>{@link #LEFT} — text starts at the left edge of the column (default)</li>
     *   <li>{@link #RIGHT} — text ends at the right edge of the column, padded on the left</li>
     * </ul>
     */
    public enum Alignment {
        /** Left-aligned: text at the left edge, padding on the right. */
        LEFT,
        /** Right-aligned: text at the right edge, padding on the left. */
        RIGHT
    }

    /**
     * Creates a table backed by the provided model.
     *
     * @param model the backing table model
     */
    public Table(TableModel model) {
        setModel(model);
    }

    /**
     * Creates a table with the given headers backed by a {@link DefaultTableModel}.
     *
     * @param headers the column headers
     */
    public Table(String... headers) {
        this(new DefaultTableModel(headers));
    }

    /**
     * Sets the backing model and attaches this table as a listener.
     *
     * @param model the new backing model
     */
    public void setModel(TableModel model) {
        if (this.model != null) {
            this.model.removeTableModelListener(this);
        }
        this.model = model;
        if (model != null) {
            model.addTableModelListener(this);
        }
        selectedRow = 0;
        scrollOffset = 0;
        invalidate();
    }

    /**
     * Returns the backing model.
     *
     * @return the current model
     */
    public TableModel getModel() {
        return model;
    }

    /** {@inheritDoc} — clamps selection/scroll on model changes. */
    @Override
    public void tableChanged(TableModelEvent e) {
        if (e.type() == TableModelEventType.ROWS_REMOVED
                || e.type() == TableModelEventType.ROWS_CHANGED
                || e.type() == TableModelEventType.STRUCTURE_CHANGED) {
            if (model != null && selectedRow >= model.getRowCount()) {
                selectedRow = Math.max(0, model.getRowCount() - 1);
            }
        }
        // On STRUCTURE_CHANGED, clamp selection to valid range (already done
        // above) but do NOT reset to row 0 — that discards the user's selection
        // on every model refresh. Only reset scroll if the selection is now
        // above the viewport.
        if (e.type() == TableModelEventType.STRUCTURE_CHANGED) {
            if (scrollOffset > selectedRow) {
                scrollOffset = selectedRow;
            }
        }
        ensureVisible();
        invalidate();
    }

    /**
     * Convenience: appends a row if the backing model is a {@link DefaultTableModel}.
     *
     * @param cells the row cell values
     * @throws IllegalStateException if the model is not a DefaultTableModel
     */
    public void addRow(String... cells) {
        if (model instanceof DefaultTableModel dtm) {
            dtm.addRow(cells);
        } else {
            throw new IllegalStateException("Table was not created with a DefaultTableModel; add rows via the model");
        }
    }

    /**
     * Removes all rows from the backing model (if it is a {@link DefaultTableModel}).
     *
     * <p>This is the correct way to clear a table's rows. Calling
     * {@code getTableModelRows().clear()} does <strong>not</strong> work because
     * {@link #getTableModelRows()} returns a defensive copy of the row data.</p>
     */
    public void clearRows() {
        if (model instanceof DefaultTableModel dtm) {
            dtm.clear();
        } else {
            throw new IllegalStateException("Table was not created with a DefaultTableModel; clear rows via the model");
        }
    }

    /**
     * Sets the selected row, clamped to the valid range.
     *
     * @param index the desired row index
     */
    public void setSelectedRow(int index) {
        int count = model == null ? 0 : model.getRowCount();
        this.selectedRow = Math.max(0, Math.min(count - 1, index));
        ensureVisible();
        invalidate();
    }

    /**
     * Returns the index of the currently selected row.
     *
     * @return the selected row index
     */
    public int getSelectedRow() {
        return selectedRow;
    }

    /**
     * Computes the preferred size based on column widths and row count.
     *
     * @return the preferred terminal size
     */
    @Override
    protected TerminalSize calculatePreferredSize() {
        int totalWidth = model == null ? 1 : model.getColumnCount() + 1;
        if (model != null) {
            for (int i = 0; i < model.getColumnCount(); i++) {
                int max = TerminalTextUtils.getTrueWidth(model.getColumnName(i));
                int rowCount = model.getRowCount();
                for (int r = 0; r < rowCount; r++) {
                    max = Math.max(max, TerminalTextUtils.getTrueWidth(model.getValueAt(r, i)));
                }
                totalWidth += max;
            }
        }
        int rowCount = model == null ? 0 : model.getRowCount();
        return new TerminalSize(totalWidth, Math.max(3, Math.min(rowCount + 1, 12)));
    }

    /**
     * Renders the header row and the visible data rows with separators.
     *
     * @param graphics the text-graphics target
     */
    @Override
    protected void drawComponent(TextGraphics graphics) {
        var size = getSize();
        var theme = ThemeManager.active();
        updateColumnWidths(size.columns());
        int y = 0;
        int columnCount = model == null ? 0 : model.getColumnCount();
        // Header
        List<String> headerCells = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) {
            headerCells.add(model.getColumnName(i));
        }
        drawRow(graphics, y++, headerCells, new TextCell(' ', theme.headerFg(), theme.headerBg(), SGR.BOLD), true);
        // Rows
        int rowCount = model == null ? 0 : model.getRowCount();
        for (int i = scrollOffset; i < rowCount && y < size.rows(); i++) {
            boolean selected = i == selectedRow;
            var style = (selected && isFocused())
                    ? new TextCell(' ', theme.selectionFg(), theme.selectionBg())
                    : new TextCell(' ', theme.foreground(), theme.background());
            List<String> rowCells = new ArrayList<>();
            for (int c = 0; c < columnCount; c++) {
                rowCells.add(model.getValueAt(i, c));
            }
            drawRow(graphics, y++, rowCells, style, false);
        }
        // Clear remaining rows
        while (y < size.rows()) {
            graphics.fillRectangle(0, y++, size.columns(), 1, new TextCell(' ', theme.foreground(), theme.background()));
        }
    }

    private void updateColumnWidths(int availableWidth) {
        int columnCount = model == null ? 0 : model.getColumnCount();
        if (columnWidths == null || columnWidths.length != columnCount) {
            columnWidths = new int[columnCount];
        }
        if (columnCount == 0) {
            return;
        }
        // Usable width for text = terminal width minus separators. Percentages
        // are relative to this usable width, so "50%" + "50%" fills the table.
        int sepTotal = 0;
        for (int i = 0; i < columnCount - 1; i++) {
            sepTotal += TerminalTextUtils.getTrueWidth(columnSeparatorChar);
        }
        int usable = Math.max(columnCount, availableWidth - sepTotal);

        // Natural (content-based) width per column, as before.
        int[] natural = new int[columnCount];
        for (int i = 0; i < columnCount; i++) {
            int max = TerminalTextUtils.getTrueWidth(model.getColumnName(i));
            int rowCount = model.getRowCount();
            for (int r = 0; r < rowCount; r++) {
                max = Math.max(max, TerminalTextUtils.getTrueWidth(model.getValueAt(r, i)));
            }
            natural[i] = max;
        }

        // Constraint targets per column (null = no constraint).
        WidthConstraint[] cons = effectiveConstraints(columnCount);
        int[] width = new int[columnCount];
        int pinnedCount = 0;
        for (int i = 0; i < columnCount; i++) {
            WidthConstraint c = cons[i];
            if (c != null && c.pct() != null) {
                double target = usable * c.pct() / 100.0;
                width[i] = clampConstraint((int) Math.round(target), c, usable);
                pinnedCount++;
            } else {
                width[i] = -1;   // unresolved
            }
        }
        // Unconstrained columns. If NO column is pct-pinned, keep the legacy
        // even-share auto-sizing (min = available/columns, cap = content and
        // terminal share) so existing screens look identical. If any column
        // IS pct-pinned, unconstrained columns size to content (clamped by
        // their min/max) and the spread phase fills leftovers.
        boolean anyPct = false;
        for (int i = 0; i < columnCount; i++) {
            if (cons[i] != null && cons[i].pct() != null) {
                anyPct = true;
                break;
            }
        }
        // Legacy math used the RAW available width (separators included in
        // the division), so reproduce it exactly for byte-identical layouts.
        int legacyFloor = Math.max(3, availableWidth / columnCount - 1);
        int legacyCap = Math.max(1, availableWidth / columnCount);
        for (int i = 0; i < columnCount; i++) {
            if (width[i] == -1) {
                WidthConstraint c = cons[i];
                int w = anyPct ? natural[i]
                        : Math.max(legacyFloor, Math.min(natural[i], legacyCap));
                if (c != null) {
                    if (c.minChars() != null) {
                        w = Math.max(w, c.minChars());
                    }
                    if (c.minPct() != null) {
                        w = Math.max(w, (int) Math.floor(usable * c.minPct() / 100.0));
                    }
                    if (c.maxChars() != null) {
                        w = Math.min(w, c.maxChars());
                    }
                    if (c.maxPct() != null) {
                        w = Math.min(w, (int) Math.ceil(usable * c.maxPct() / 100.0));
                    }
                }
                width[i] = Math.max(1, w);
            }
        }
        // Pure legacy case (no constraints anywhere): widths already honor
        // usable/nCols, nothing to fit or spread — done.
        boolean anyConstraint = false;
        for (WidthConstraint c : cons) {
            if (c != null) {
                anyConstraint = true;
                break;
            }
        }
        if (!anyConstraint) {
            columnWidths = width;
            return;
        }
        // Fit phase: shrink until total (with separators) fits availableWidth.
        // Pass 1: shrink unconstrained columns proportionally to excess.
        int total = totalWithSeparators(width, sepTotal);
        for (int i = 0; i < columnCount && total > availableWidth; i++) {
            if (cons[i] == null || cons[i].pct() == null) {
                int floor = minWidthOf(cons[i], usable, 1);
                int excess = total - availableWidth;
                int cut = Math.min(excess, Math.max(0, width[i] - floor));
                width[i] -= cut;
                total -= cut;
            }
        }
        // Pass 2: shrink pct columns proportionally, respecting their mins.
        while (total > availableWidth) {
            int before = total;
            for (int i = 0; i < columnCount && total > availableWidth; i++) {
                if (cons[i] != null && cons[i].pct() != null && width[i] > 1) {
                    int floor = minWidthOf(cons[i], usable, 1);
                    int cut = Math.max(1, (total - availableWidth) / Math.max(1, pinnedCount));
                    cut = Math.min(cut, width[i] - Math.max(floor, 1));
                    if (cut > 0) {
                        width[i] -= cut;
                        total -= cut;
                    }
                }
            }
            if (total == before) {
                break;   // everything at floor; hard clip below
            }
        }
        // Grow phase: if pct columns under-filled and room remains, expand
        // unconstrained columns up to their natural width first, then evenly.
        total = totalWithSeparators(width, sepTotal);
        if (total < availableWidth) {
            for (int i = 0; i < columnCount && total < availableWidth; i++) {
                if (cons[i] == null || cons[i].pct() == null) {
                    int maxW = Math.min(natural[i], maxWidthOf(cons[i], usable, Integer.MAX_VALUE));
                    if (width[i] < maxW) {
                        int add = Math.min(availableWidth - total, maxW - width[i]);
                        width[i] += add;
                        total += add;
                    }
                }
            }
            // Spread remaining space over non-pct columns only, respecting
            // both maxes and natural width (unconstrained columns never grow
            // past their content; leftover space stays blank, matching the
            // old auto-sizing look).
            while (total < availableWidth) {
                boolean grew = false;
                for (int i = 0; i < columnCount && total < availableWidth; i++) {
                    if (cons[i] != null && cons[i].pct() != null) {
                        continue;   // pct columns keep their exact share
                    }
                    int maxW = Math.min(natural[i], maxWidthOf(cons[i], usable, Integer.MAX_VALUE));
                    if (width[i] < maxW) {
                        width[i]++;
                        total++;
                        grew = true;
                    }
                }
                if (!grew) {
                    break;   // every column at natural/max
                }
            }
        }
        // Final clamp: never exceed availableWidth. Hard-clip widest columns.
        while (totalWithSeparators(width, sepTotal) > availableWidth) {
            int widest = 0;
            for (int i = 1; i < columnCount; i++) {
                if (width[i] > width[widest]) {
                    widest = i;
                }
            }
            if (width[widest] <= 1) {
                break;
            }
            width[widest]--;
        }
        columnWidths = width;
    }

    /** Sum of all column widths plus separator widths. */
    private int totalWithSeparators(int[] width, int sepTotal) {
        int total = sepTotal;
        for (int w : width) {
            total += w;
        }
        return total;
    }

    /** Effective minimum for column i: minChars/minPct, floor of {@code fallback}. */
    private int minWidthOf(WidthConstraint c, int usable, int fallback) {
        if (c == null) {
            return fallback;
        }
        int m = fallback;
        if (c.minChars() != null) {
            m = Math.max(m, c.minChars());
        }
        if (c.minPct() != null) {
            m = Math.max(m, (int) Math.floor(usable * c.minPct() / 100.0));
        }
        return m;
    }

    /** Effective maximum for column i: minChars/minPct, cap of {@code cap}. */
    private int maxWidthOf(WidthConstraint c, int usable, int cap) {
        if (c == null) {
            return cap;
        }
        int m = cap;
        if (c.maxChars() != null) {
            m = Math.min(m, c.maxChars());
        }
        if (c.maxPct() != null) {
            m = Math.min(m, (int) Math.ceil(usable * c.maxPct() / 100.0));
        }
        return m;
    }

    /** Clamp a pct-driven width with the constraint's min/max (chars and pct). */
    private int clampConstraint(int raw, WidthConstraint c, int usable) {
        int w = raw;
        if (c.minChars() != null) {
            w = Math.max(w, c.minChars());
        }
        if (c.minPct() != null) {
            w = Math.max(w, (int) Math.floor(usable * c.minPct() / 100.0));
        }
        if (c.maxChars() != null) {
            w = Math.min(w, c.maxChars());
        }
        if (c.maxPct() != null) {
            w = Math.min(w, (int) Math.ceil(usable * c.maxPct() / 100.0));
        }
        return Math.max(1, w);
    }

    /** Constraint array sized to columnCount, or all-null if none set. */
    private WidthConstraint[] effectiveConstraints(int columnCount) {
        if (columnWidthConstraints == null || columnWidthConstraints.length != columnCount) {
            return new WidthConstraint[columnCount];
        }
        return columnWidthConstraints;
    }

    /**
     * Sets an exact width for a column as a percentage of the usable width
     * (terminal width minus column separators). Valid range is (0, 100].
     * Invalid or out-of-bounds values are silently ignored.
     *
     * @param col the column index (0-based)
     * @param pct percentage of usable width (e.g. 50.0 = half the table width)
     */
    public void setColumnWidthPct(int col, double pct) {
        setConstraintField(col, c -> new WidthConstraint(pct, c.minChars(), c.minPct(),
                c.maxChars(), c.maxPct()));
    }

    /**
     * Sets an absolute minimum width in characters for a column.
     * Negative values are ignored; zero resets the constraint.
     *
     * @param col the column index (0-based)
     * @param chars minimum width in terminal characters (0 = unset)
     */
    public void setColumnMinWidth(int col, int chars) {
        setConstraintField(col, c -> new WidthConstraint(c == null ? null : c.pct(),
                chars > 0 ? chars : null, c == null ? null : c.minPct(),
                c == null ? null : c.maxChars(), c == null ? null : c.maxPct()));
    }

    /**
     * Sets a minimum width for a column as a percentage of usable width.
     * Valid range is [0, 100); 0 resets the constraint.
     *
     * @param col the column index (0-based)
     * @param pct minimum percentage of usable width (0 = unset)
     */
    public void setColumnMinWidthPct(int col, double pct) {
        setConstraintField(col, c -> new WidthConstraint(c == null ? null : c.pct(),
                c == null ? null : c.minChars(), pct > 0 ? pct : null,
                c == null ? null : c.maxChars(), c == null ? null : c.maxPct()));
    }

    /**
     * Sets an absolute maximum width in characters for a column.
     * Negative values are ignored; zero resets the constraint.
     *
     * @param col the column index (0-based)
     * @param chars maximum width in terminal characters (0 = unset)
     */
    public void setColumnMaxWidth(int col, int chars) {
        setConstraintField(col, c -> new WidthConstraint(c == null ? null : c.pct(),
                c == null ? null : c.minChars(), c == null ? null : c.minPct(),
                chars > 0 ? chars : null, c == null ? null : c.maxPct()));
    }

    /**
     * Sets a maximum width for a column as a percentage of usable width.
     * Valid range is (0, 100]; 0 resets the constraint.
     *
     * @param col the column index (0-based)
     * @param pct maximum percentage of usable width (0 = unset)
     */
    public void setColumnMaxWidthPct(int col, double pct) {
        setConstraintField(col, c -> new WidthConstraint(c == null ? null : c.pct(),
                c == null ? null : c.minChars(), c == null ? null : c.minPct(),
                c == null ? null : c.maxChars(), pct > 0 ? pct : null));
    }

    /**
     * Removes all width constraints from a column, restoring auto-sizing.
     *
     * @param col the column index (0-based); out-of-bounds is silently ignored
     */
    public void clearColumnWidthConstraints(int col) {
        if (columnWidthConstraints == null || col < 0
                || col >= columnWidthConstraints.length) {
            return;
        }
        columnWidthConstraints[col] = null;
        invalidate();
    }

    /** Functional mutation of one column's constraint record. */
    private interface ConstraintMutator {
        WidthConstraint apply(WidthConstraint existing);
    }

    private void setConstraintField(int col, ConstraintMutator mutator) {
        int columnCount = model == null ? 0 : model.getColumnCount();
        if (col < 0 || col >= columnCount) {
            return;
        }
        if (columnWidthConstraints == null || columnWidthConstraints.length != columnCount) {
            columnWidthConstraints = new WidthConstraint[columnCount];
        }
        WidthConstraint existing = columnWidthConstraints[col] != null
                ? columnWidthConstraints[col] : new WidthConstraint(null, null, null, null, null);
        WidthConstraint updated = mutator.apply(existing);
        columnWidthConstraints[col] = (updated == null || updated.isEmpty()) ? null : updated;
        invalidate();
    }

    private void drawRow(TextGraphics graphics, int y, List<String> cells, TextCell style, boolean isHeader) {
        int x = 0;
        int columnCount = model == null ? 0 : model.getColumnCount();
        for (int i = 0; i < columnCount; i++) {
            String text = i < cells.size() ? cells.get(i) : "";
            String truncated = TerminalTextUtils.truncate(text, columnWidths[i]);
            int textWidth = TerminalTextUtils.getTrueWidth(truncated);
            Alignment alignment = getColumnAlignment(i);
            if (alignment == Alignment.RIGHT) {
                int pad = columnWidths[i] - textWidth;
                if (pad > 0) {
                    graphics.fillRectangle(x, y, pad, 1, style);
                }
                graphics.drawString(x + pad, y, truncated, style);
            } else {
                graphics.drawString(x, y, truncated, style);
                int pad = columnWidths[i] - textWidth;
                if (pad > 0) {
                    graphics.fillRectangle(x + textWidth, y, pad, 1, style);
                }
            }
            x += columnWidths[i];
            if (i < columnCount - 1) {
                graphics.drawString(x, y, columnSeparatorChar, style);
                x += TerminalTextUtils.getTrueWidth(columnSeparatorChar);
            }
        }
    }

    /**
     * Handles arrow, page, home/end, and Emacs-style row navigation keys.
     *
     * @param keyStroke the keystroke to handle
     * @return {@code true} if the keystroke was consumed
     */
    @Override
    public boolean handleKeyStroke(io.jterm.core.input.KeyStroke keyStroke) {
        // Emacs-style key bindings (Ctrl+letter arrives as CHARACTER with ctrl=true)
        if (keyStroke.type() == KeyType.CHARACTER && keyStroke.ctrl()) {
            switch (keyStroke.character()) {
                case 'P', 'p' -> { setSelectedRow(selectedRow - 1); return true; }
                case 'N', 'n' -> { setSelectedRow(selectedRow + 1); return true; }
                case 'V', 'v' -> { pageDown(); return true; }
                default -> { return false; }  // Ignore other Ctrl+letter combos
            }
        }
        switch (keyStroke.type()) {
            case ARROW_UP -> { setSelectedRow(selectedRow - 1); return true; }
            case ARROW_DOWN -> { setSelectedRow(selectedRow + 1); return true; }
            case PAGE_UP -> { pageUp(); return true; }
            case PAGE_DOWN -> { pageDown(); return true; }
            case HOME -> { setSelectedRow(0); return true; }
            case END -> { if (model != null) setSelectedRow(model.getRowCount() - 1); return true; }
            default -> { return false; }
        }
    }

    private void ensureVisible() {
        var visibleRows = Math.max(1, getSize().rows() - 1);
        if (selectedRow < scrollOffset) scrollOffset = selectedRow;
        if (selectedRow >= scrollOffset + visibleRows) scrollOffset = selectedRow - visibleRows + 1;
        if (scrollOffset < 0) scrollOffset = 0;
    }

    /** Scroll down by one viewport height (page down). */
    public void pageDown() {
        int visibleRows = Math.max(1, getSize().rows() - 1);
        int rowCount = model == null ? 0 : model.getRowCount();
        scrollOffset = Math.min(scrollOffset + visibleRows, Math.max(0, rowCount - visibleRows));
        setSelectedRow(scrollOffset);
    }

    /** Scroll up by one viewport height (page up). */
    public void pageUp() {
        int visibleRows = Math.max(1, getSize().rows() - 1);
        scrollOffset = Math.max(0, scrollOffset - visibleRows);
        setSelectedRow(scrollOffset);
    }

    /**
     * Updates bounds and re-ensures the selected row is visible.
     *
     * @param position the new position
     * @param size     the new size
     */
    @Override
    public void setBounds(TerminalPosition position, TerminalSize size) {
        super.setBounds(position, size);
        ensureVisible();
    }

    /**
     * Sets the alignment for a specific column. Out-of-bounds column indices
     * are silently ignored (no exception thrown).
     *
     * @param col       the column index (0-based)
     * @param alignment the alignment to set; if {@code null}, defaults to {@link Alignment#LEFT}
     */
    public void setColumnAlignment(int col, Alignment alignment) {
        int columnCount = model == null ? 0 : model.getColumnCount();
        if (col < 0 || col >= columnCount) {
            return; // silently ignore out-of-bounds
        }
        if (columnAlignments == null || columnAlignments.length != columnCount) {
            columnAlignments = new Alignment[columnCount];
            Arrays.fill(columnAlignments, Alignment.LEFT);
        }
        columnAlignments[col] = (alignment == null) ? Alignment.LEFT : alignment;
        invalidate();
    }

    /**
     * Returns the alignment for the given column. Returns {@link Alignment#LEFT}
     * for out-of-bounds indices or unset columns.
     *
     * @param col the column index (0-based)
     * @return the alignment, or {@code LEFT} if not set or out of bounds
     */
    public Alignment getColumnAlignment(int col) {
        if (columnAlignments == null || col < 0 || col >= columnAlignments.length) {
            return Alignment.LEFT;
        }
        Alignment a = columnAlignments[col];
        return a == null ? Alignment.LEFT : a;
    }

    /**
     * Returns the computed width of the given column (in terminal columns).
     * This is the actual width used during rendering, which may differ from
     * the preferred width depending on available space.
     *
     * @param col the column index (0-based)
     * @return the column width in terminal columns, or 0 if not yet computed or out of bounds
     */
    public int getColumnWidth(int col) {
        if (columnWidths == null || col < 0 || col >= columnWidths.length) {
            return 0;
        }
        return columnWidths[col];
    }

    /**
     * Sets the character drawn between columns. Defaults to {@code "│"}.
     * Use {@code ":"} for label-value config screens, or {@code " "} for
     * no visible separator.
     *
     * @param separator the separator character to draw between columns
     */
    public void setColumnSeparatorChar(String separator) {
        this.columnSeparatorChar = separator != null ? separator : "│";
        invalidate();
    }

    /**
     * Returns the current column separator character.
     *
     * @return the separator character drawn between columns
     */
    public String getColumnSeparatorChar() {
        return columnSeparatorChar;
    }

    /**
     * Returns a defensive copy of all rows currently in the backing model.
     *
     * @return list of rows; each row is a list of cell strings
     */
    public List<List<String>> getTableModelRows() {
        int rowCount = model == null ? 0 : model.getRowCount();
        int columnCount = model == null ? 0 : model.getColumnCount();
        List<List<String>> result = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < columnCount; c++) {
                cells.add(model.getValueAt(r, c));
            }
            result.add(cells);
        }
        return result;
    }
}
