package io.jterm.widget.chart;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.style.AnsiColor;
import io.jterm.style.Color;
import io.jterm.style.SGR;
import io.jterm.style.TextCell;
import io.jterm.style.ThemeManager;
import io.jterm.util.Symbols;
import io.jterm.widget.AbstractComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * A terminal-based chart widget that renders one or more {@link ChartSeries}
 * to a character grid. Supports line, bar, and scatter plots.
 *
 * <p><b>Features</b></p>
 * <ul>
 *   <li>Automatic or fixed Y-axis scaling</li>
 *   <li>Y-axis labels with configurable formatting</li>
 *   <li>Horizontal grid lines (dashed)</li>
 *   <li>Legend showing series name and color</li>
 *   <li>Title display</li>
 *   <li>Box border around plot area</li>
 * </ul>
 *
 * <p><b>Usage</b></p>
 * <pre>{@code
 * var chart = new Chart("AAPL — 30 Day");
 * chart.addSeries(new ChartSeries("Close", prices, ChartType.LINE, AnsiColor.GREEN));
 * chart.setYAxisConfig(ChartAxisConfig.fixed(140, 160, "$%.0f"));
 * panel.addComponent(chart);
 * }</pre>
 *
 * <p><b>Layout</b></p>
 * <pre>
 *  ┌─ Title ──────────────────────┐
 *  │ 160 ┤                        │
 *  │     │        ╭─╮             │
 *  │ 150 ┤    ╭───╯ ╰──╮          │
 *  │     │  ──╯        ╰──        │
 *  │ 140 ┤                        │
 *  └─────┴────────────────────────┘
 * </pre>
 */
public class Chart extends AbstractComponent {

    private String title = "";
    private final List<ChartSeries> seriesList = new ArrayList<>();
    private ChartAxisConfig yAxisConfig = ChartAxisConfig.auto();
    /**
     * Fill scale mode (Joe, 2026-10-07): when true, every NON-primary series
     * maps its own [min,max] onto the full plot height so differently scaled
     * units (earnings $ vs PE ratio) are both visible. The PRIMARY series
     * keeps the shared scale and the grid labels stay in the primary's
     * units. Off by default — without it all series share one Y scale.
     */
    private boolean fillAllSeries;
    /** Shared Y range from the latest render — primary/empty series fallback. */
    private double[] latestYRange = new double[]{0.0, 1.0};

    private List<String> xAxisLabels = new ArrayList<>();
    private boolean showGrid = true;
    private boolean showLegend = true;
    private boolean showBorder = true;
    private int yLabelWidth = 6;
    private Color borderColor = AnsiColor.BRIGHT_BLACK;
    private Color gridColor = AnsiColor.BRIGHT_BLACK;
    private Color axisLabelColor = AnsiColor.WHITE;

    // Layout constants
    private static final int Y_AXIS_WIDTH = 1;   // the ┤ column
    private static final int X_AXIS_HEIGHT = 1;  // the ┴ row

    /** Creates a chart with no title. */
    public Chart() {}

    /**
     * Creates a chart with the given title displayed above the plot area.
     *
     * @param title the chart title; {@code null} is treated as empty
     */
    public Chart(String title) {
        this.title = title;
    }

    // ── Configuration ──────────────────────────────────────────

    /**
     * Sets the chart title shown above the plot area.
     *
     * @param title the new title; {@code null} is treated as empty
     * @return this chart, for method chaining
     */
    public Chart setTitle(String title) {
        this.title = title == null ? "" : title;
        invalidate();
        return this;
    }

    /**
     * Returns the current chart title.
     *
     * @return the current chart title
     */
    public String getTitle() { return title; }

    /**
     * Adds a data series to the chart.
     *
     * @param series the series to add
     * @return this chart, for method chaining
     */
    public Chart addSeries(ChartSeries series) {
        seriesList.add(series);
        invalidate();
        return this;
    }

    /**
     * Removes the first series whose name matches the given value.
     *
     * @param name the series name to remove
     * @return this chart, for method chaining
     */
    public Chart removeSeries(String name) {
        seriesList.removeIf(s -> s.name().equals(name));
        invalidate();
        return this;
    }

    /**
     * Returns a defensive copy of the series list.
     *
     * @return a defensive copy of the series list
     */
    public List<ChartSeries> getSeries() { return new ArrayList<>(seriesList); }

    /**
     * Returns the number of series currently attached to the chart.
     *
     * @return the number of series currently attached to the chart
     */
    public int getSeriesCount() { return seriesList.size(); }

    /**
     * Sets the Y-axis configuration (range, scale, label format).
     *
     * @param config the new axis configuration
     * @return this chart, for method chaining
     */
    public Chart setYAxisConfig(ChartAxisConfig config) {
        this.yAxisConfig = config;
        invalidate();
        return this;
    }

    /**
     * Returns the current Y-axis configuration.
     *
     * @return the current Y-axis configuration
     */
    public ChartAxisConfig getYAxisConfig() { return yAxisConfig; }

    /**
     * Convenience method: switches the Y-axis between linear and logarithmic
     * scale while preserving the current range and label format. If
     * currently auto-scale, stays auto-scale; if fixed, stays fixed.
     *
     * @param logarithmic {@code true} for a logarithmic Y-axis,
     *                    {@code false} for linear
     * @return this chart, for method chaining
     */
    public Chart setYAxisLogarithmic(boolean logarithmic) {
        if (yAxisConfig.logarithmic() == logarithmic) return this;
        if (logarithmic) {
            if (yAxisConfig.autoScale()) {
                this.yAxisConfig = ChartAxisConfig.logAuto(yAxisConfig.labelFormat());
            } else {
                this.yAxisConfig = ChartAxisConfig.logFixed(
                        yAxisConfig.min(), yAxisConfig.max(), yAxisConfig.labelFormat());
            }
        } else {
            // Back to linear
            if (yAxisConfig.autoScale()) {
                this.yAxisConfig = new ChartAxisConfig(0, 0, true, yAxisConfig.labelFormat(), false);
            } else {
                this.yAxisConfig = new ChartAxisConfig(
                        yAxisConfig.min(), yAxisConfig.max(), false, yAxisConfig.labelFormat(), false);
            }
        }
        invalidate();
        return this;
    }

    /**
     * Enable per-series fill scale: every non-primary series spans the full
     * plot height using its own min/max; grid labels remain the primary
     * series' axis (native units). Regression series follow their source
     * series' scale. A perfectly flat series (min == max) renders as a
     * mid-height dashed line.
     *
     * @param on true to fill every non-primary series to the plot height
     */
    public void setFillAllSeries(boolean on) {
        this.fillAllSeries = on;
    }

    /**
     * @return whether per-series fill-scale mode is enabled
     */
    public boolean isFillAllSeries() {
        return fillAllSeries;
    }

    /**
     * Returns whether the Y-axis uses logarithmic scale.
     *
     * @return {@code true} if the Y-axis uses a logarithmic scale
     */
    public boolean isYAxisLogarithmic() { return yAxisConfig.logarithmic(); }

    /**
     * Sets X-axis labels (e.g. date strings). Labels are evenly distributed
     * across the plot width — typically 3-5 labels (start, middle, end).
     * Pass an empty list to hide X-axis labels.
     *
     * @param labels the X-axis labels; {@code null} clears them
     * @return this chart, for method chaining
     */
    public Chart setXAxisLabels(List<String> labels) {
        this.xAxisLabels = labels == null ? new ArrayList<>() : new ArrayList<>(labels);
        invalidate();
        return this;
    }

    /**
     * Returns a defensive copy of the current X-axis labels.
     *
     * @return a defensive copy of the X-axis labels
     */
    public List<String> getXAxisLabels() { return new ArrayList<>(xAxisLabels); }

    /**
     * Enables or disables dashed horizontal grid lines.
     *
     * @param show {@code true} to show grid lines
     * @return this chart, for method chaining
     */
    public Chart setShowGrid(boolean show) {
        this.showGrid = show;
        invalidate();
        return this;
    }

    /**
     * Returns whether horizontal grid lines are drawn.
     *
     * @return {@code true} if horizontal grid lines are drawn
     */
    public boolean isShowGrid() { return showGrid; }

    /**
     * Enables or disables the legend row below the plot.
     *
     * @param show {@code true} to show the legend
     * @return this chart, for method chaining
     */
    public Chart setShowLegend(boolean show) {
        this.showLegend = show;
        invalidate();
        return this;
    }

    /**
     * Returns whether the legend is shown.
     *
     * @return {@code true} if the legend is shown
     */
    public boolean isShowLegend() { return showLegend; }

    /**
     * Enables or disables the box border around the plot area.
     *
     * @param show {@code true} to draw the border
     * @return this chart, for method chaining
     */
    public Chart setShowBorder(boolean show) {
        this.showBorder = show;
        invalidate();
        return this;
    }

    /**
     * Returns whether the border is drawn around the plot area.
     *
     * @return {@code true} if the border is drawn around the plot area
     */
    public boolean isShowBorder() { return showBorder; }

    /**
     * Sets the width in columns reserved for Y-axis labels.
     *
     * @param width the label column width; clamped to a minimum of 2
     * @return this chart, for method chaining
     */
    public Chart setYLabelWidth(int width) {
        this.yLabelWidth = Math.max(2, width);
        invalidate();
        return this;
    }

    /**
     * Sets the color used for the plot border.
     *
     * @param color the new border color
     * @return this chart, for method chaining
     */
    public Chart setBorderColor(Color color) {
        this.borderColor = color;
        invalidate();
        return this;
    }

    /**
     * Sets the color used for grid lines.
     *
     * @param color the new grid color
     * @return this chart, for method chaining
     */
    public Chart setGridColor(Color color) {
        this.gridColor = color;
        invalidate();
        return this;
    }

    // ── Layout calculations ────────────────────────────────────

    /**
     * Returns the default preferred size of {@code 40×12} columns.
     *
     * @return a 40-column by 12-row terminal size
     */
    @Override
    protected TerminalSize calculatePreferredSize() {
        // Default preferred size: 40×12 (enough for a useful chart)
        return new TerminalSize(40, 12);
    }

    /**
     * Computes the effective plot area bounds within the widget.
     * Returns [x0, y0, width, height] for the inner plotting region.
     */
    private int[] computePlotArea(int totalCols, int totalRows) {
        int topMargin = 0;
        int bottomMargin = 0;
        int leftMargin = yLabelWidth + Y_AXIS_WIDTH;
        int rightMargin = 0;

        if (!title.isEmpty()) topMargin += 1;
        if (showLegend) bottomMargin += 1;
        if (!xAxisLabels.isEmpty()) bottomMargin += 1; // reserve a row for x-axis labels

        int plotX = leftMargin;
        int plotY = topMargin;
        int plotW = totalCols - leftMargin - rightMargin;
        int plotH = totalRows - topMargin - bottomMargin;

        if (showBorder) {
            plotX += 1;
            plotY += 1;
            plotW -= 2;
            plotH -= 2;
        }

        // Clamp
        plotW = Math.max(1, plotW);
        plotH = Math.max(1, plotH);

        return new int[]{plotX, plotY, plotW, plotH};
    }

    /**
     * Computes the effective Y-axis range from config or data.
     */
    private double[] computeYRange() {
        if (!yAxisConfig.autoScale()) {
            double min = yAxisConfig.min();
            double max = yAxisConfig.max();
            if (yAxisConfig.logarithmic()) {
                // Ensure positive for log scale
                min = Math.max(min, Math.nextUp(0));
                // Only inflate max if it's not above min after the min clamp
                if (max <= min) {
                    max = Math.nextUp(min) * 2;
                }
            }
            return new double[]{min, max};
        }
        double dataMin = Double.MAX_VALUE;
        double dataMax = Double.MIN_VALUE;
        for (var s : seriesList) {
            if (s.isEmpty()) continue;
            dataMin = Math.min(dataMin, s.min());
            dataMax = Math.max(dataMax, s.max());
        }
        if (dataMin == Double.MAX_VALUE) {
            dataMin = 0;
            dataMax = 1;
        }
        if (yAxisConfig.logarithmic()) {
            // For log scale, clamp min to a small positive value
            if (dataMin <= 0) dataMin = Math.nextUp(0);
            if (dataMax <= dataMin) dataMax = dataMin * 2;
            // No linear padding — log space is already visually balanced
            return new double[]{dataMin, dataMax};
        }
        // Add 5% padding
        double range = dataMax - dataMin;
        if (range == 0) range = 1;
        double pad = range * 0.05;
        return new double[]{dataMin - pad, dataMax + pad};
    }

    // ── Rendering ──────────────────────────────────────────────

    /**
     * Renders the chart: border, title, axes, grid, series, and legend.
     *
     * @param graphics the text-graphics target for the current component bounds
     */
    @Override
    protected void drawComponent(TextGraphics graphics) {
        var size = getSize();
        int cols = size.columns();
        int rows = size.rows();
        var theme = ThemeManager.active();

        // Fill entire chart area with theme background so no DEFAULT cells remain
        graphics.fillRectangle(0, 0, cols, rows,
                new TextCell(' ', theme.foreground(), theme.background()));

        var plotArea = computePlotArea(cols, rows);
        int px = plotArea[0], py = plotArea[1], pw = plotArea[2], ph = plotArea[3];

        var yRange = computeYRange();
        double yMin = yRange[0], yMax = yRange[1];
        this.latestYRange = yRange; // recorded for per-series effective ranges

        // Draw border if enabled
        if (showBorder) {
            drawChartBorder(graphics, cols, rows, theme.background());
        }

        // Draw title
        if (!title.isEmpty()) {
            int titleY = showBorder ? 0 : 0;
            int titleX = showBorder ? 2 : yLabelWidth;
            graphics.drawString(titleX, titleY, truncate(title, cols - titleX - 1),
                    new TextCell(' ', AnsiColor.BRIGHT_WHITE, theme.background(), SGR.BOLD));
        }

        // Draw Y-axis labels and grid
        drawYAxis(graphics, px, py, pw, ph, yMin, yMax, theme.background());

        // Draw X-axis baseline
        drawXAxis(graphics, px, py + ph - 1, pw, theme.background());

        // Draw each series. In fill mode the order INVERTS (primary draws
        // LAST/on top): every series spans the full height so their extreme
        // rows always collide — the primary (the labeled axis) must own them.
        if (fillAllSeries) {
            var reversed = new ArrayList<>(seriesList);
            java.util.Collections.reverse(reversed);
            for (var series : reversed) {
                if (series.isEmpty()) continue;
                drawSeries(graphics, series, px, py, pw, ph, yMin, yMax, theme.background());
            }
        } else {
            for (var series : seriesList) {
                if (series.isEmpty()) continue;
                drawSeries(graphics, series, px, py, pw, ph, yMin, yMax, theme.background());
            }
        }

        // Draw legend
        if (showLegend && !seriesList.isEmpty()) {
            drawLegend(graphics, cols, rows, theme.background());
        }
    }

    private void drawChartBorder(TextGraphics g, int cols, int rows, Color bg) {
        var borderCell = new TextCell(' ', borderColor, bg);

        // Top
        for (int c = 0; c < cols; c++) g.setCell(c, 0, borderCell.withCharacter(getBorderChar(c, 0, cols, rows, 'h')));
        // Bottom
        for (int c = 0; c < cols; c++) g.setCell(c, rows - 1, borderCell.withCharacter(getBorderChar(c, rows - 1, cols, rows, 'h')));
        // Left
        for (int r = 0; r < rows; r++) g.setCell(0, r, borderCell.withCharacter(getBorderChar(0, r, cols, rows, 'v')));
        // Right
        for (int r = 0; r < rows; r++) g.setCell(cols - 1, r, borderCell.withCharacter(getBorderChar(cols - 1, r, cols, rows, 'v')));

        // Corners
        g.setCell(0, 0, borderCell.withCharacter('┌'));
        g.setCell(cols - 1, 0, borderCell.withCharacter('┐'));
        g.setCell(0, rows - 1, borderCell.withCharacter('└'));
        g.setCell(cols - 1, rows - 1, borderCell.withCharacter('┘'));
    }

    private char getBorderChar(int c, int r, int cols, int rows, char orient) {
        if (c == 0 || c == cols - 1) return '│';
        if (r == 0 || r == rows - 1) return '─';
        return ' ';
    }

    private void drawYAxis(TextGraphics g, int px, int py, int pw, int ph, double yMin, double yMax, Color bg) {
        var labelCell = new TextCell(' ', axisLabelColor, bg);
        var gridCell = new TextCell(' ', gridColor, bg);
        var axisCell = new TextCell(' ', borderColor, bg);

        // Compute tick values: log or linear
        double[] ticks;
        if (yAxisConfig.logarithmic()) {
            ticks = logTicks(yMin, yMax, Math.max(2, Math.min(8, ph)));
            // Fall back to linear ticks when the log range is too narrow
            // (less than one decade) or logTicks produced too few ticks.
            if (ticks.length < 2 || (yMin > 0 && yMax / yMin < 10)) {
                ticks = niceTicks(yMin, yMax, Math.max(2, Math.min(5, ph / 2)));
            }
        } else {
            ticks = niceTicks(yMin, yMax, Math.max(2, Math.min(5, ph / 2)));
        }

        for (double value : ticks) {
            // Map value to screen Y (inverted: yMax at top, yMin at bottom)
            double yFraction = valueToYFraction(value, yMin, yMax);
            int y = py + (int) Math.round((1.0 - yFraction) * (ph - 1));
            if (y < py || y >= py + ph) continue;

            // Draw label (right-aligned in label area)
            String label = yAxisConfig.format(value);
            int labelStart = px - Y_AXIS_WIDTH - label.length();
            if (labelStart < 0) labelStart = 0;
            g.drawString(labelStart, y, label, labelCell);

            // Draw axis tick
            g.setCell(px - 1, y, axisCell.withCharacter('┤'));

            // Draw grid line (dashed) — skip top and bottom edges
            if (showGrid && value != ticks[0] && value != ticks[ticks.length - 1]) {
                for (int c = px; c < px + pw; c++) {
                    if ((c - px) % 2 == 0) {
                        g.setCell(c, y, gridCell.withCharacter('·'));
                    }
                }
            }
        }
    }

    /**
     * Maps a data value to a Y-axis fraction (0 = bottom, 1 = top),
     * using linear or logarithmic mapping depending on the axis config.
     */
    private double valueToYFraction(double val, double yMin, double yMax) {
        if (yAxisConfig.logarithmic()) {
            // Guard against non-positive values
            double logMin = Math.log(yMin);
            double logMax = Math.log(yMax);
            double logVal = Math.log(val);
            if (logMax == logMin) return 0.5;
            return (logVal - logMin) / (logMax - logMin);
        }
        double range = yMax - yMin;
        if (range == 0) return 0.5;
        return (val - yMin) / range;
    }

    /**
     * Generates "nice" logarithmic tick values at 1× and 2× and 5× powers
     * of 10 within [yMin, yMax]. Produces ticks like: 1, 2, 5, 10, 20, 50,
     * 100, 200, 500, ... which are standard for log-scale axes.
     */
    private static double[] logTicks(double yMin, double yMax, int maxTicks) {
        if (yMax <= yMin || yMin <= 0) {
            return new double[]{Math.max(yMin, 1)};
        }

        // Determine the power-of-10 range
        int loExp = (int) Math.floor(Math.log10(yMin));
        int hiExp = (int) Math.ceil(Math.log10(yMax));

        // Multipliers for each decade: 1×, 2×, 5×
        double[] mults = {1, 2, 5};

        java.util.List<Double> tickValues = new java.util.ArrayList<>();
        for (int exp = loExp; exp <= hiExp; exp++) {
            double base = Math.pow(10, exp);
            for (double m : mults) {
                double v = m * base;
                if (v >= yMin * 0.999 && v <= yMax * 1.001) {
                    tickValues.add(v);
                }
            }
        }

        if (tickValues.isEmpty()) {
            tickValues.add(yMin);
            tickValues.add(yMax);
        }

        // If too many ticks, subsample (keep every 2nd or 3rd)
        if (tickValues.size() > maxTicks) {
            int step = (int) Math.ceil((double) tickValues.size() / maxTicks);
            java.util.List<Double> sampled = new java.util.ArrayList<>();
            for (int i = 0; i < tickValues.size(); i += step) {
                sampled.add(tickValues.get(i));
            }
            // Ensure the last tick is included
            double last = tickValues.get(tickValues.size() - 1);
            if (sampled.get(sampled.size() - 1) != last) {
                sampled.add(last);
            }
            tickValues = sampled;
        }

        return tickValues.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /**
     * Computes "nice" round tick values for a Y-axis range.
     * Picks a step size from {1, 2, 5, 10, 25, 50, 100, 250, 500, 1000, ...}
     * that yields 3-6 ticks, then generates ticks at multiples of that step
     * that fall within [yMin, yMax].
     */
    private static double[] niceTicks(double yMin, double yMax, int targetTicks) {
        if (yMax <= yMin) return new double[]{yMin};

        double range = yMax - yMin;
        // Candidate step sizes — covers all scales from pennies to thousands
        double[] steps = {1, 2, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000,
                          10_000, 25_000, 50_000, 100_000, 250_000, 500_000, 1_000_000};

        // Also handle sub-1 ranges (penny stocks etc.) with fractional steps
        double[] fracSteps = {0.01, 0.02, 0.05, 0.1, 0.25, 0.5};
        java.util.List<Double> allSteps = new java.util.ArrayList<>();
        for (double s : fracSteps) allSteps.add(s);
        for (double s : steps) allSteps.add(s);

        // Find the step that gives the closest number of ticks to target
        double bestStep = allSteps.get(0);
        int bestDiff = Integer.MAX_VALUE;
        for (double step : allSteps) {
            int numTicks = (int) Math.floor(yMax / step) - (int) Math.ceil(yMin / step) + 1;
            if (numTicks < 2) continue;
            int diff = Math.abs(numTicks - targetTicks);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestStep = step;
            }
        }

        // Generate ticks at multiples of bestStep within [yMin, yMax]
        java.util.List<Double> tickValues = new java.util.ArrayList<>();
        long first = (long) Math.ceil(yMin / bestStep);
        long last = (long) Math.floor(yMax / bestStep);
        for (long m = first; m <= last; m++) {
            double v = m * bestStep;
            if (v >= yMin - 0.001 && v <= yMax + 0.001) {
                tickValues.add(v);
            }
        }

        if (tickValues.isEmpty()) {
            tickValues.add(yMin);
            tickValues.add(yMax);
        }
        return tickValues.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private void drawXAxis(TextGraphics g, int px, int pyBottom, int pw, Color bg) {
        var axisCell = new TextCell(' ', borderColor, bg);
        for (int c = px; c < px + pw; c++) {
            g.setCell(c, pyBottom, axisCell.withCharacter('─'));
        }
        g.setCell(px - 1, pyBottom, axisCell.withCharacter('┴'));

        // Draw X-axis labels below the baseline, evenly distributed
        if (!xAxisLabels.isEmpty()) {
            int labelRow = pyBottom + 1;
            var labelCell = new TextCell(' ', axisLabelColor, bg);
            int n = xAxisLabels.size();
            for (int i = 0; i < n; i++) {
                String label = xAxisLabels.get(i);
                // Position: evenly spread across plot width
                double fraction = n == 1 ? 0.5 : (double) i / (n - 1);
                int x = px + (int) (fraction * (pw - 1));
                // Center the label at x, clamping to plot area
                int labelStart = x - label.length() / 2;
                int labelEnd = labelStart + label.length();
                // Clamp left
                if (labelStart < px) labelStart = px;
                // Clamp right
                if (labelEnd > px + pw) {
                    labelStart = px + pw - label.length();
                }
                String clipped = label;
                if (labelStart + label.length() > px + pw) {
                    clipped = label.substring(0, px + pw - labelStart);
                }
                g.drawString(Math.max(px, labelStart), labelRow, clipped, labelCell);
            }
        }
    }

    /**
     * Effective [min,max] used to map THIS series' values onto the plot:
     * the primary series always uses the shared range (its geometry must
     * agree with the grid labels); in fill-all mode every other series uses
     * its own non-null-value range. A flat series (min == max) gets a range
     * of ±1 so it renders as a thin mid-height line (dashes survive as the
     * line is drawn flat across the plot).
     */
    private double[] effectiveSeriesRange(ChartSeries series) {
        if (!fillAllSeries || seriesList.isEmpty() || seriesList.get(0) == series) {
            return new double[]{yAxisMin(), yAxisMax()};
        }
        double mn = Double.MAX_VALUE;
        double mx = -Double.MAX_VALUE;
        for (Double v : series.values()) {
            if (v == null) continue;
            mn = Math.min(mn, v);
            mx = Math.max(mx, v);
        }
        if (mn == Double.MAX_VALUE) {
            return new double[]{yAxisMin(), yAxisMax()}; // empty → shared
        }
        if (mx == mn) {
            // Flat series (min == max, e.g. cash sweep at $1): straddle the
            // value ±1 so it renders as a thin MID-height line.
            return new double[]{mn - 1.0, mn + 1.0};
        }
        // Match the shared-range padding so filled series keep margin at the edges
        double range = mx - mn;
        double pad = range * 0.05;
        return new double[]{mn - pad, mx + pad};
    }

    private double yAxisMin() {
        return latestYRange[0];
    }

    private double yAxisMax() {
        return latestYRange[1];
    }

    private void drawSeries(TextGraphics g, ChartSeries series, int px, int py, int pw, int ph, double yMin, double yMax, Color bg) {
        int n = series.size();
        double[] span = effectiveSeriesRange(series);
        yMin = span[0];
        yMax = span[1];
        double yRange = yMax - yMin;
        if (yRange == 0) yRange = 1;

        // Map data index → column
        // If we have more data points than columns, we sample; if fewer, we spread
        var valueCell = new TextCell(' ', series.color(), bg, SGR.BOLD);

        switch (series.type()) {
            case LINE -> drawLineSeries(g, series, px, py, pw, ph, yMin, yMax, valueCell);
            case BAR -> drawBarSeries(g, series, px, py, pw, ph, yMin, yMax, valueCell);
            case SCATTER -> drawScatterSeries(g, series, px, py, pw, ph, yMin, yMax, valueCell);
        }
    }

    /** Same cell with the foreground swapped to {@code color} (no-op when equal). */
    private static TextCell recolored(TextCell cell, Color color) {
        return java.util.Objects.equals(color, cell.fg())
            ? cell
            : new TextCell(cell.character(), color, cell.bg(), cell.modifiers());
    }

    private void drawLineSeries(TextGraphics g, ChartSeries series, int px, int py, int pw, int ph, double yMin, double yMax, TextCell cell) {
        int n = series.size();
        // Use sub-cell (half-block) Y resolution: 2 sub-rows per terminal row.
        // subY = 0 → top of py, subY = 2*ph-1 → bottom of py+ph-1
        int[] screenX = new int[n];
        int[] subY = new int[n];
        boolean[] gap = new boolean[n];

        for (int i = 0; i < n; i++) {
            double xFraction = n == 1 ? 0.5 : (double) i / (n - 1);
            screenX[i] = px + (int) (xFraction * (pw - 1));

            Double boxed = series.values().get(i);
            if (boxed == null) {
                gap[i] = true; // null = absent point: no marker, no segment
                continue;
            }
            double yFraction = valueToYFraction(boxed, yMin, yMax);
            // Invert: yFraction=1 (max) → top, yFraction=0 (min) → bottom
            double exactSubY = (1.0 - yFraction) * (2.0 * ph - 1);
            subY[i] = (int) Math.round(exactSubY);
            subY[i] = Math.max(0, Math.min(2 * ph - 1, subY[i]));
        }

        // Draw line segments using sub-cell Bresenham — segments never cross a gap.
        // A segment leaving point i renders in point i's effective color (per-point
        // overrides recolor "the point at i", including the segment leaving it).
        for (int i = 0; i < n - 1; i++) {
            if (gap[i] || gap[i + 1]) {
                continue;
            }
            var segCell = recolored(cell, series.colorAt(i));
            drawPlotLineSubCell(g, screenX[i], subY[i], screenX[i + 1], subY[i + 1], py, segCell);
        }

        // Draw markers at data points, merging with existing line characters.
        // A marker should never DOWNGRADE a full block (█) to a half block (▀/▄).
        // If the line already drew the other half in the same cell, upgrade to █.
        for (int i = 0; i < n; i++) {
            if (gap[i]) {
                continue;
            }
            var pointCell = recolored(cell, series.colorAt(i));
            int row = py + subY[i] / 2;
            int half = subY[i] % 2;  // 0 = upper half, 1 = lower half
            char marker = half == 0 ? '▀' : '▄';
            var existing = g.getCell(screenX[i], row);
            char existingChar = existing.character().length() == 1 ? existing.character().charAt(0) : ' ';
            char merged;
            if (existingChar == '█') {
                // Already a full block — keep it
                merged = '█';
            } else if (existingChar == '▀' && marker == '▄') {
                // Line drew upper half, marker is lower half → merge to full
                merged = '█';
            } else if (existingChar == '▄' && marker == '▀') {
                // Line drew lower half, marker is upper half → merge to full
                merged = '█';
            } else if (existingChar == '─') {
                // Horizontal line char — marker gives more precise vertical position
                merged = marker;
            } else {
                // No meaningful line char or same half — just use marker
                merged = marker;
            }
            g.setCell(screenX[i], row, pointCell.withCharacter(merged));
        }
    }

    private void drawBarSeries(TextGraphics g, ChartSeries series, int px, int py, int pw, int ph, double yMin, double yMax, TextCell cell) {
        int n = series.size();
        int barWidth = Math.max(1, pw / n - 1);
        int baselineY = py + ph - 1;

        for (int i = 0; i < n; i++) {
            Double boxed = series.values().get(i);
            if (boxed == null) {
                continue; // gap: no bar
            }
            double xFraction = n == 1 ? 0.5 : (double) i / (n - 1);
            int x = px + (int) (xFraction * (pw - 1));

            double yFraction = valueToYFraction(boxed, yMin, yMax);
            int barHeight = (int) (yFraction * (ph - 1));
            barHeight = Math.max(0, Math.min(ph - 1, barHeight));

            var barCell = recolored(cell, series.colorAt(i));
            for (int dy = 0; dy <= barHeight; dy++) {
                for (int dx = 0; dx < barWidth && x + dx < px + pw; dx++) {
                    char ch = dy == barHeight ? '▀' : '█';
                    g.setCell(x + dx, baselineY - dy, barCell.withCharacter(ch));
                }
            }
        }
    }

    private void drawScatterSeries(TextGraphics g, ChartSeries series, int px, int py, int pw, int ph, double yMin, double yMax, TextCell cell) {
        int n = series.size();
        for (int i = 0; i < n; i++) {
            Double boxed = series.values().get(i);
            if (boxed == null) {
                continue; // gap: no dot
            }
            double xFraction = n == 1 ? 0.5 : (double) i / (n - 1);
            int x = px + (int) (xFraction * (pw - 1));

            double yFraction = valueToYFraction(boxed, yMin, yMax);
            int y = py + (int) ((1.0 - yFraction) * (ph - 1));
            y = Math.max(py, Math.min(py + ph - 1, y));

            Color pointColor = series.colorAt(i);
            TextCell pointCell = pointColor == series.color()
                ? cell
                : new TextCell(cell.character(), pointColor, cell.bg(), cell.modifiers());
            g.setCell(x, y, pointCell.withCharacter('●'));
        }
    }

    /**
     * Draws a line between two points using sub-cell (half-block) resolution.
     *
     * Coordinates: x0/x1 are absolute screen columns; subY0/subY1 are sub-cell
     * rows relative to the plot area top (py). subY 0 = top of py,
     * subY 1 = bottom of py row 0, subY 2 = top of py+1, etc.
     *
     * Each terminal cell can show:
     * - ▀ (upper half) — line passes through the top half
     * - ▄ (lower half) — line passes through the bottom half
     * - █ (full)       — line passes through both halves (vertical segment)
     * - ─ (horizontal) — flat segment at a sub-row boundary
     *
     * When two sub-points fall in different halves of the same cell, we merge
     * them into a full block (█) for continuity.
     */
    private void drawPlotLineSubCell(TextGraphics g, int x0, int subY0, int x1, int subY1,
                                      int py, TextCell cell) {
        // Bresenham in sub-cell space
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(subY1 - subY0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = subY0 < subY1 ? 1 : -1;
        int err = dx - dy;

        // Track which half of the current cell has been painted, so we can
        // merge upper+lower into a full block when both are visited.
        int prevCellX = -1;
        int prevCellRow = -1;
        boolean paintedUpper = false;
        boolean paintedLower = false;

        while (true) {
            int cellRow = py + subY0 / 2;  // absolute screen row
            boolean isUpper = (subY0 % 2) == 0;

            if (cellRow == prevCellRow && x0 == prevCellX) {
                // Same cell as previous point — mark the other half
                if (isUpper) paintedUpper = true;
                else paintedLower = true;
            } else {
                // New cell — flush previous cell
                if (prevCellX >= 0) {
                    char ch = mergeHalves(paintedUpper, paintedLower);
                    g.setCell(prevCellX, prevCellRow, cell.withCharacter(ch));
                }
                prevCellX = x0;
                prevCellRow = cellRow;
                paintedUpper = isUpper;
                paintedLower = !isUpper;
            }

            if (x0 == x1 && subY0 == subY1) break;
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                subY0 += sy;
            }
        }

        // Flush last cell
        if (prevCellX >= 0) {
            char ch = mergeHalves(paintedUpper, paintedLower);
            g.setCell(prevCellX, prevCellRow, cell.withCharacter(ch));
        }
    }

    /** Merge upper/lower half flags into a single character. */
    private char mergeHalves(boolean upper, boolean lower) {
        if (upper && lower) return '█';
        if (upper) return '▀';
        if (lower) return '▄';
        return '─';
    }

    private void drawLegend(TextGraphics g, int cols, int rows, Color bg) {
        int legendY = rows - 1;
        int x = showBorder ? 2 : 0;

        var labelCell = new TextCell(' ', AnsiColor.WHITE, bg);

        for (int i = 0; i < seriesList.size(); i++) {
            var s = seriesList.get(i);
            String entry = " " + s.name() + " ";
            // Draw color marker
            var markerCell = new TextCell(' ', s.color(), s.color());
            g.setCell(x, legendY, markerCell.withCharacter('█'));
            g.drawString(x + 1, legendY, entry, labelCell);
            x += 1 + entry.length() + 1;

            if (x >= cols - 2) break; // truncate if too many series
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, Math.max(0, maxLen - 1)) + "…";
    }
}