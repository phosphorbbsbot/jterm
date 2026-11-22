package io.jterm.screen;

import io.jterm.core.ColorDepth;
import io.jterm.core.TerminalSize;
import io.jterm.style.AnsiColor;
import io.jterm.style.Color;
import io.jterm.style.TextCell;

/**
 * Sub-cell (2× vertical) resolution buffer: a (columns × 2*rows) color grid
 * that encodes each top/bottom half-pair into exactly one Unicode block
 * character — the chart renderer's ▀▄ trick, generalised, library-level
 * (2026-11-19 animation plan, card 4).
 *
 * <p>A sub-cell color of {@code null} is "transparent" (the terminal's
 * default background shows through). Per cell-pair (top, bottom) of the
 * MAPPED colors the encoding law is:</p>
 *
 * <ul>
 *   <li>{@code (null, null)} → space with default attrs ({@link TextCell#EMPTY}).</li>
 *   <li>{@code (C, C)} → U+2588 FULL BLOCK, fg = C, bg default — the byte law:
 *       one SGR change instead of the split's two, and the background never
 *       leaves default (verified against {@link DefaultScreen.SgrStateTracker},
 *       test-asserted: strictly fewer bytes in every SGR state).</li>
 *   <li>{@code (null, C)} → U+2584 BOTTOM HALF, fg = C, bg default.</li>
 *   <li>{@code (C, null)} → U+2580 TOP HALF, fg = C, bg default.</li>
 *   <li>{@code (C, D), C≠D} → U+2580 with fg = C, bg = D (the chart split).</li>
 * </ul>
 *
 * <p>Depth law: sub-cell colors map through the existing ColorDepth pipeline
 * ({@link DefaultScreen.SgrStateTracker#degradeForDepth}) BEFORE encode, so a
 * pair whose halves degrade to the same ANSI/C256 entry collapses to the
 * FULL BLOCK law exactly like the tracker collapses redundant sequences; the
 * emitted cells already carry MAPPED colors and degrade-for-depth is
 * idempotent (palette colors pass through the screen layer untouched).</p>
 *
 * <p>Delta law: the emitted cells feed the ordinary {@link ScreenBuffer} →
 * {@link DefaultScreen} machinery, so a later frame whose pair encodes to the
 * same cell yields an empty diff, and the SGR delta rides
 * {@link DefaultScreen.SgrStateTracker}'s MAPPED comparisons — two sub-cells
 * mapping to the same emitted pair emit zero redundant sequences, and a pair
 * collapsing to DEFAULT rides the single-reset shortcut.</p>
 *
 * <p>Grid laws (mirroring {@link ScreenBuffer}): out-of-bounds writes are
 * ignored and reads return null (space fill); a grid whose sub-row count is
 * odd keeps the trailing partnerless half as null, so the last row paints by
 * the top-half/space laws and never throws.</p>
 */
public final class HalfBlockBuffer {
    /** U+2588 — both halves painted (fg carries the shared color). */
    public static final char FULL_BLOCK = '\u2588';
    /** U+2580 — top half painted (or fg/bg split). */
    public static final char TOP_HALF_BLOCK = '\u2580';
    /** U+2584 — bottom half painted. */
    public static final char BOTTOM_HALF_BLOCK = '\u2584';

    private final int columns;
    /** Sub-rows the buffer holds: pairs stack two sub-rows per cell row. */
    private final int subRows;
    /** Flat sub-cell grid, row-major; null = default/transparent (space fill). */
    private final Color[] sub;

    /**
     * A buffer sized in terminal cells: every cell row holds a top and a
     * bottom half ({@code 2 * rows} sub-rows).
     *
     * @param columns grid width in columns
     * @param rows    grid height in cell rows (doubles the sub-cell rows)
     */
    public HalfBlockBuffer(int columns, int rows) {
        this(columns, 2 * positive(rows, "rows"), false);
    }

    private HalfBlockBuffer(int columns, int subRows, boolean subRowSized) {
        this.columns = positive(columns, "columns");
        this.subRows = positive(subRows, "sub-rows");
        this.sub = new Color[columns * subRows];
    }

    /**
     * A buffer sized directly in sub-rows. An ODD count keeps the trailing
     * partnerless sub-row (the space-fill law): its bottom half is null, so
     * the final row encodes by the top-half/space laws.
     *
     * @param columns grid width in columns
     * @param subRows sub-cell rows (odd tolerated; pairs are rounded up)
     */
    public static HalfBlockBuffer forSubRows(int columns, int subRows) {
        return new HalfBlockBuffer(columns, subRows, true);
    }

    private static int positive(int n, String what) {
        if (n <= 0) {
            throw new IllegalArgumentException(what + " must be positive, got: " + n);
        }
        return n;
    }

    /** @return grid width in columns. */
    public int columns() {
        return columns;
    }

    /** @return grid height in cell rows (the sub-rows paired up). */
    public int rows() {
        return (subRows + 1) / 2;
    }

    /** @return grid height in sub-rows (twice {@link #rows()} for even grids). */
    public int subRows() {
        return subRows;
    }

    /**
     * Paints one sub-cell. Out-of-bounds writes are ignored (space-fill law);
     * a null color clears the sub-cell to default.
     *
     * @param column zero-indexed column
     * @param subRow zero-indexed sub-row (0 = top half of row 0)
     * @param color  the color, or null to clear
     */
    public synchronized void setSubCell(int column, int subRow, Color color) {
        if (column < 0 || column >= columns || subRow < 0 || subRow >= subRows) {
            return;
        }
        sub[subRow * columns + column] = color;
    }

    /**
     * Returns one sub-cell color.
     *
     * @param column zero-indexed column
     * @param subRow zero-indexed sub-row
     * @return the color, or null when default/transparent or out of bounds
     */
    public synchronized Color getSubCell(int column, int subRow) {
        if (column < 0 || column >= columns || subRow < 0 || subRow >= subRows) {
            return null;
        }
        return sub[subRow * columns + column];
    }

    /**
     * Encodes one cell-pair by the law in the class javadoc. Colors first
     * degrade to the declared tier (the MAPPED colors — comparisons and
     * emission both ride them), then split/pair.
     *
     * @param top    the top half color, or null for default
     * @param bottom the bottom half color, or null for default
     * @param depth  the terminal tier to map to; never null
     * @return the encoded cell (never null)
     */
    public static TextCell encodeCellPair(Color top, Color bottom, ColorDepth depth) {
        java.util.Objects.requireNonNull(depth, "depth must not be null");
        Color mappedTop = DefaultScreen.SgrStateTracker.degradeForDepth(top, depth);
        Color mappedBottom = DefaultScreen.SgrStateTracker.degradeForDepth(bottom, depth);
        if (mappedTop == null && mappedBottom == null) {
            return TextCell.EMPTY;
        }
        if (mappedTop != null && mappedBottom != null) {
            if (mappedTop.equals(mappedBottom)) {
                // Byte law: FULL BLOCK costs one fg sequence where the split
                // costs fg + bg, and the background never leaves default.
                return new TextCell(FULL_BLOCK, mappedTop, AnsiColor.DEFAULT);
            }
            return new TextCell(TOP_HALF_BLOCK, mappedTop, mappedBottom);
        }
        if (mappedTop == null) {
            return new TextCell(BOTTOM_HALF_BLOCK, mappedBottom, AnsiColor.DEFAULT);
        }
        return new TextCell(TOP_HALF_BLOCK, mappedTop, AnsiColor.DEFAULT);
    }

    /**
     * Encodes the pair at a cell row from the grid's live colors.
     *
     * @param column zero-indexed column
     * @param row    zero-indexed cell row (pairs sub-rows 2r and 2r+1)
     * @param depth  the terminal tier to map to; never null
     * @return the encoded cell (never null); out-of-bounds pairs are EMPTY
     */
    public synchronized TextCell encodedCellPair(int column, int row, ColorDepth depth) {
        if (column < 0 || column >= columns || row < 0 || row >= rows()) {
            return TextCell.EMPTY;
        }
        return encodeCellPair(
                getSubCell(column, 2 * row),
                getSubCell(column, 2 * row + 1),
                depth);
    }

    /**
     * Encodes the whole grid into a fresh {@link ScreenBuffer} (the truecolor
     * law: colors pass through by identity, byte-identical for every existing
     * path — the unknown-depth law).
     *
     * @return the encoded screen buffer, sized columns × rows
     */
    public ScreenBuffer toScreenBuffer() {
        return toScreenBuffer(ColorDepth.TRUECOLOR);
    }

    /**
     * Encodes the whole grid into a fresh {@link ScreenBuffer}, mapping every
     * sub-cell through the declared tier before encode.
     *
     * @param depth the terminal tier to map to; never null
     * @return the encoded screen buffer, sized columns × rows
     */
    public synchronized ScreenBuffer toScreenBuffer(ColorDepth depth) {
        return toScreenBuffer(depth, null);
    }

    /**
     * Encodes the grid into the GIVEN buffer when its shape matches (GC law,
     * Joe 2026-11-21: film playback allocated a fresh HalfBlockBuffer AND a
     * fresh ScreenBuffer per frame — both are same-shape frame to frame, so
     * callers hold them and re-encode). Pass {@code null} to allocate.
     *
     * @param depth the terminal tier to map to; never null
     * @param out   a same-shape buffer to overwrite, or null to allocate
     * @return the encoded screen buffer, sized columns × rows
     */
    public synchronized ScreenBuffer toScreenBuffer(ColorDepth depth, ScreenBuffer out) {
        if (out == null || out.size().columns() != columns
                || out.size().rows() != rows()) {
            out = new ScreenBuffer(new TerminalSize(columns, rows()));
        }
        for (int r = 0; r < rows(); r++) {
            for (int c = 0; c < columns; c++) {
                Color top = getSubCell(c, 2 * r);
                Color bottom = 2 * r + 1 < subRows ? getSubCell(c, 2 * r + 1) : null;
                out.setCell(c, r, encodeCellPair(top, bottom, depth));
            }
        }
        return out;
    }
}
