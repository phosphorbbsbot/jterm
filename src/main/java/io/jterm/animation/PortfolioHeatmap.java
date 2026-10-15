package io.jterm.animation;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.style.AnsiColor;
import io.jterm.style.SGR;
import io.jterm.style.TextCell;

import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Animated background that renders a heatmap grid of portfolio positions.
 * Each cell shows a ticker symbol, sized roughly by position weight. Cell
 * color reflects daily unrealized P&amp;L: green for gains, red for losses,
 * with brightness proportional to magnitude.
 *
 * <p>Data is supplied by a {@code java.util.function.Supplier} of positions;
 * the library is database-agnostic. When no supplier is given — or it returns
 * no usable data — the heatmap renders built-in decorative sample data.
 * Callers that want live positions supply them via the two-argument
 * constructor.</p>
 */
public class PortfolioHeatmap implements AnimatedBackground {

    private static final int TARGET_FPS = 4;

    // Built-in decorative data rendered when no supplier provides positions.
    private static final List<Position> FALLBACK_POSITIONS = List.of(
            new Position("AAPL", 100, 185.00, 189.52),
            new Position("GOOGL", 50, 138.20, 141.80),
            new Position("MSFT", 75, 370.00, 378.91),
            new Position("AMZN", 120, 181.50, 178.35),
            new Position("NVDA", 40, 860.00, 875.28),
            new Position("TSLA", 90, 248.50, 242.10),
            new Position("META", 30, 465.00, 472.33),
            new Position("NFLX", 55, 615.00, 626.92),
            new Position("SPY", 200, 520.00, 524.50),
            new Position("QQQ", 80, 430.00, 428.20),
            new Position("IWM", 150, 195.00, 197.80),
            new Position("BND", 300, 70.00, 70.15)
    );

    private final Supplier<List<Position>> dataSupplier;
    private final Random random = new Random();

    private volatile boolean running;
    private volatile TerminalSize lastSize;
    private volatile List<Position> positions;

    /**
     * Creates a portfolio heatmap that renders built-in decorative sample
     * data. Pass a custom {@code Supplier} of positions via the two-argument
     * constructor to render live data.
     *
     * @param preferredSize the preferred terminal size
     */
    public PortfolioHeatmap(TerminalSize preferredSize) {
        this(preferredSize, () -> null);
    }

    /**
     * Creates a portfolio heatmap with a custom data supplier.
     *
     * @param preferredSize the preferred terminal size
     * @param dataSupplier  supplier of portfolio positions used each refresh
     */
    public PortfolioHeatmap(TerminalSize preferredSize, Supplier<List<Position>> dataSupplier) {
        this.dataSupplier = Objects.requireNonNull(dataSupplier, "dataSupplier");
        onResize(preferredSize);
        refreshData();
    }

    @Override
    /**
     * Renders one frame of the animation into the given graphics buffer.
     * @param graphics the graphics
     * @param size the size
     */
    public void renderFrame(TextGraphics graphics, TerminalSize size) {
        lastSize = size;
        if (size.columns() <= 0 || size.rows() <= 0) {
            return;
        }

        // Black background.
        TextCell bg = new TextCell(' ', AnsiColor.BLACK, AnsiColor.BLACK);
        graphics.fillRectangle(0, 0, size.columns(), size.rows(), bg);

        // Reserve top/bottom margin rows so the heatmap never touches the border.
        int marginTop = 1;
        int marginBottom = 1;
        int marginLeft = 0;
        int marginRight = 0;

        List<Position> active = positions == null ? List.of() : positions;

        int cols = size.columns() - marginLeft - marginRight;
        int rows = size.rows() - marginTop - marginBottom;
        if (cols <= 0 || rows <= 0) {
            return;
        }

        if (active.isEmpty()) {
            active = FALLBACK_POSITIONS;
        }

        // Compute weights from market value; tiny positions still get a cell.
        double totalValue = active.stream().mapToDouble(Position::marketValue).sum();
        final double sizeShare = totalValue > 0 ? 0.0 : 1.0 / active.size();
        double[] weights = active.stream()
                .mapToDouble(p -> totalValue > 0 ? p.marketValue() / totalValue : sizeShare)
                .toArray();

        // Cap weights so no single position can swallow the whole screen; this
        // keeps a visible grid even when one position dominates everything else.
        double cappedTotal = 0.0;
        double cap = 0.35;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] > cap) weights[i] = cap;
            cappedTotal += weights[i];
        }
        if (cappedTotal > 0) {
            for (int i = 0; i < weights.length; i++) {
                weights[i] /= cappedTotal;
            }
        }

        // Compute per-cell geometry.
        final int[] cellWidths = new int[active.size()];
        final int[] cellHeights = new int[active.size()];
        final int[] cellRows = new int[active.size()];
        final int[] cellCols = new int[active.size()];

        layoutTreemap(active.size(), weights, cols, rows, cellWidths, cellHeights, cellRows, cellCols);

        for (int i = 0; i < active.size(); i++) {
            Position pos = active.get(i);
            int x = marginLeft + cellCols[i];
            int y = marginTop + cellRows[i];
            int w = cellWidths[i];
            int h = cellHeights[i];
            if (w <= 0 || h <= 0 || x + w <= 0 || y + h <= 0) {
                continue;
            }
            drawCell(graphics, pos, x, y, w, h);
        }
    }

    /**
     * Treemap-ish layout: positions are arranged in a single horizontal band
     * when they fit, otherwise wrapped. Each position receives a width
     * proportional to weight and a height that grows with weight.
     */
    private void layoutTreemap(int n, double[] weights, int cols, int rows,
                               int[] widths, int[] heights, int[] outRows, int[] outCols) {
        // Minimum width so a ticker label is readable.
        int minWidth = 5;
        int maxHeight = Math.max(1, rows / 2 + 1);
        double maxWeight = 0.0;
        for (double w : weights) {
            if (w > maxWeight) maxWeight = w;
        }

        // First pass: compute raw proportional widths and minimum heights.
        int[] rawWidths = new int[n];
        for (int i = 0; i < n; i++) {
            double share = weights[i];
            rawWidths[i] = (int) Math.round(cols * share);
            if (rawWidths[i] < minWidth) {
                rawWidths[i] = minWidth;
            }
            // Height grows with relative weight but is at least 1 and at most maxHeight.
            double weightRatio = maxWeight > 0 ? share / maxWeight : 1.0;
            heights[i] = Math.max(1, (int) Math.round(1 + (maxHeight - 1) * weightRatio));
        }

        // Normalize widths so they sum to cols.
        int totalRaw = 0;
        for (int w : rawWidths) totalRaw += w;
        if (totalRaw > 0) {
            int remaining = cols;
            for (int i = 0; i < n; i++) {
                widths[i] = Math.max(minWidth, (int) Math.round(rawWidths[i] * cols / (double) totalRaw));
                remaining -= widths[i];
            }
            // Distribute rounding remainder to avoid empty columns.
            for (int i = 0; i < n && remaining != 0; i++) {
                if (remaining > 0) {
                    widths[i]++;
                    remaining--;
                } else if (widths[i] > minWidth) {
                    widths[i]--;
                    remaining++;
                }
            }
        } else {
            for (int i = 0; i < n; i++) widths[i] = minWidth;
        }

        int currentRow = 0;
        int currentCol = 0;
        int rowHeight = 1;
        int consumed = 0;
        for (int i = 0; i < n; i++) {
            int w = widths[i];
            int h = heights[i];
            if (currentCol + w > cols && w <= cols) {
                currentRow += rowHeight;
                currentCol = 0;
                rowHeight = h;
            }
            outRows[i] = currentRow;
            outCols[i] = currentCol;
            currentCol += w;
            consumed += w;
            rowHeight = Math.max(rowHeight, h);
            if (currentCol >= cols || consumed >= cols) {
                currentRow += rowHeight;
                currentCol = 0;
                consumed = 0;
                rowHeight = 1;
            }
        }
    }

    private void drawCell(TextGraphics graphics, Position pos, int x, int y, int w, int h) {
        double dailyPnl = pos.dailyPnlPct();
        AnsiColor bg = pickHeatColor(dailyPnl);

        // Treat tiny cells as background-only.
        if (w <= 1 || h <= 1) {
            TextCell fill = new TextCell(' ', AnsiColor.WHITE, bg);
            for (int r = y; r < y + h && r < graphics.getSize().rows(); r++) {
                for (int c = x; c < x + w && c < graphics.getSize().columns(); c++) {
                    graphics.setCell(c, r, fill);
                }
            }
            return;
        }

        TextCell fill = new TextCell(' ', AnsiColor.WHITE, bg);
        TextCell border = new TextCell(' ', AnsiColor.WHITE, bg, SGR.BOLD);

        // Fill the cell.
        for (int r = y; r < y + h && r < graphics.getSize().rows(); r++) {
            for (int c = x; c < x + w && c < graphics.getSize().columns(); c++) {
                graphics.setCell(c, r, fill);
            }
        }

        // Center the ticker symbol in the cell.
        String symbol = pos.symbol();
        if (symbol.length() > w) {
            symbol = symbol.substring(0, Math.max(1, w));
        }
        int textRow = y + h / 2;
        int textCol = x + Math.max(0, (w - symbol.length()) / 2);

        TextCell textCell = new TextCell(' ', AnsiColor.WHITE, bg, SGR.BOLD);
        for (int i = 0; i < symbol.length(); i++) {
            int col = textCol + i;
            int row = textRow;
            if (col >= x && col < x + w && row >= y && row < y + h
                    && col < graphics.getSize().columns() && row < graphics.getSize().rows()) {
                graphics.setCell(col, row, textCell.withCharacter(symbol.charAt(i)));
            }
        }

        // Optionally render a small P&amp;L percent in the bottom-right corner.
        String pct = String.format(java.util.Locale.US, "%.1f%%", dailyPnl);
        if (pct.length() <= w && h >= 2) {
            int pctCol = x + w - pct.length();
            int pctRow = y + h - 1;
            for (int i = 0; i < pct.length(); i++) {
                int col = pctCol + i;
                if (col >= x && col < graphics.getSize().columns() && pctRow < graphics.getSize().rows()) {
                    graphics.setCell(col, pctRow, textCell.withCharacter(pct.charAt(i)));
                }
            }
        }
    }

    /**
     * Pick a heat color based on daily P&amp;L percentage. Gains are green, losses
     * are red, and the brightness/intensity scales with magnitude.
     */
    private AnsiColor pickHeatColor(double dailyPnlPct) {
        double magnitude = Math.abs(dailyPnlPct);
        boolean gain = dailyPnlPct >= 0;

        // ANSI palette only has 4 intensity levels for each hue. Map magnitude
        // to nearest intensity: 0-2%, 2-5%, 5-10%, >10%.
        AnsiColor[] greens = {AnsiColor.GREEN, AnsiColor.BRIGHT_GREEN, AnsiColor.BRIGHT_GREEN, AnsiColor.BRIGHT_GREEN};
        AnsiColor[] reds = {AnsiColor.RED, AnsiColor.BRIGHT_RED, AnsiColor.BRIGHT_RED, AnsiColor.BRIGHT_RED};

        int bucket;
        if (magnitude < 2.0) bucket = 0;
        else if (magnitude < 5.0) bucket = 1;
        else if (magnitude < 10.0) bucket = 2;
        else bucket = 3;

        return gain ? greens[bucket] : reds[bucket];
    }

    @Override
    /**
     * Reallocates internal buffers for the new terminal size.
     * @param newSize the new size
     */
    public void onResize(TerminalSize newSize) {
        this.lastSize = newSize;
    }

    @Override
    /**
     * Starts the animation.
     */
    public void start() {
        running = true;
    }

    @Override
    /**
     * Stops the animation.
     */
    public void stop() {
        running = false;
    }

    @Override
    /**
     * Returns whether the running flag is set.
     * @return the result
     */
    public boolean isRunning() {
        return running;
    }

    @Override
    /**
     * Returns the target frame rate in frames per second.
     * @return the result
     */
    public int targetFps() {
        return TARGET_FPS;
    }

    @Override
    /**
     * Returns the last terminal size the animation was rendered at.
     * @return the result
     */
    public TerminalSize lastSize() {
        return lastSize;
    }

    /**
     * Returns the currently rendered positions.
     *
     * @return a copy of the current positions
     */
    public List<Position> getPositions() {
        return positions == null ? List.of() : List.copyOf(positions);
    }

    /**
     * Forces a data reload from the supplier.
     */
    public void refreshData() {
        try {
            this.positions = dataSupplier.get();
        } catch (Exception e) {
            this.positions = FALLBACK_POSITIONS;
        }
        if (this.positions == null || this.positions.isEmpty()) {
            this.positions = FALLBACK_POSITIONS;
        }
    }

    /** Immutable portfolio position: symbol, quantity, cost basis, latest close. */
    public static final class Position {
        private final String symbol;
        private final int quantity;
        private final double costBasis;
        private final double latestClose;

        /**
         * Constructs a new immutable Position.
         *
         * @param symbol the ticker symbol
         * @param quantity the number of shares held
         * @param costBasis the average cost basis per share
         * @param latestClose the latest closing price per share
         */
        public Position(String symbol, int quantity, double costBasis, double latestClose) {
            this.symbol = Objects.requireNonNull(symbol, "symbol");
            this.quantity = quantity;
            this.costBasis = costBasis;
            this.latestClose = latestClose;
        }

        /**
         * Returns the stock symbol.
         *
         * @return the symbol
         */
        public String symbol() { return symbol; }

        /**
         * Returns the number of shares held.
         *
         * @return the quantity
         */
        public int quantity() { return quantity; }

        /**
         * Returns the average cost basis per share.
         *
         * @return the cost basis
         */
        public double costBasis() { return costBasis; }

        /**
         * Returns the latest closing price.
         *
         * @return the latest close
         */
        public double latestClose() { return latestClose; }

        /**
         * Returns the current market value of the position.
         *
         * @return quantity multiplied by latestClose
         */
        public double marketValue() {
            return quantity * latestClose;
        }

        /**
         * Returns the daily P&amp;L percentage relative to the cost basis.
         *
         * @return the percentage gain or loss, or 0.0 if either basis is non-positive
         */
        public double dailyPnlPct() {
            if (costBasis <= 0.0 || latestClose <= 0.0) {
                return 0.0;
            }
            return (latestClose - costBasis) / costBasis * 100.0;
        }

        /**
         * Returns a short string representation of this position.
         *
         * @return the symbol and daily P&amp;L percentage
         */
        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "%s %.2f%%", symbol, dailyPnlPct());
        }
    }
}
