package io.jterm.widget.chart;

/**
 * Axis configuration for a {@link Chart}. Controls the value range, label
 * formatting, tick count, and scale type.
 *
 * <p>When {@code autoScale} is true (default), the chart recomputes min/max
 * from the data on each render. Set explicit min/max via {@link #fixed} for
 * a locked scale (e.g. $0–$200 for a stock chart).
 *
 * <p>When {@code logarithmic} is true, the Y-axis uses a logarithmic scale:
 * ticks are placed at "nice" log intervals (1, 2, 5, 10, 20, 50, 100, ...)
 * and value-to-pixel mapping uses log scale. The data range must be
 * strictly positive. Linear is the default.
 *
 * @param min         fixed minimum value when {@code autoScale} is false (ignored otherwise)
 * @param max         fixed maximum value when {@code autoScale} is false (ignored otherwise)
 * @param autoScale   true to recompute min/max from the data on each render
 * @param labelFormat a {@link String#format(String, Object...)} pattern such as {@code "%.0f"} for tick labels
 * @param logarithmic true for a logarithmic Y-axis (data must be strictly positive)
 */
public record ChartAxisConfig(double min, double max, boolean autoScale, String labelFormat, boolean logarithmic) {

    /**
     * Default: auto-scale, "%.0f" format, linear.
     *
     * @return an auto-scaling, linear axis configuration
     */
    public static ChartAxisConfig auto() {
        return new ChartAxisConfig(0, 0, true, "%.0f", false);
    }

    /**
     * Fixed range with custom format, linear.
     *
     * @param min         fixed minimum value of the axis
     * @param max         fixed maximum value of the axis
     * @param labelFormat {@link String#format(String, Object...)} pattern for tick labels
     * @return a fixed-range, linear axis configuration
     */
    public static ChartAxisConfig fixed(double min, double max, String labelFormat) {
        return new ChartAxisConfig(min, max, false, labelFormat, false);
    }

    /**
     * Fixed range with default format, linear.
     *
     * @param min fixed minimum value of the axis
     * @param max fixed maximum value of the axis
     * @return a fixed-range, linear axis configuration using the "%.0f" format
     */
    public static ChartAxisConfig fixed(double min, double max) {
        return new ChartAxisConfig(min, max, false, "%.0f", false);
    }

    /**
     * Auto-scale with logarithmic Y-axis. Data must be strictly positive.
     *
     * @return an auto-scaling, logarithmic axis configuration
     */
    public static ChartAxisConfig logAuto() {
        return new ChartAxisConfig(0, 0, true, "%.0f", true);
    }

    /**
     * Auto-scale with logarithmic Y-axis and custom label format.
     *
     * @param labelFormat {@link String#format(String, Object...)} pattern for tick labels
     * @return an auto-scaling, logarithmic axis configuration
     */
    public static ChartAxisConfig logAuto(String labelFormat) {
        return new ChartAxisConfig(0, 0, true, labelFormat, true);
    }

    /**
     * Fixed range with logarithmic Y-axis and custom label format.
     *
     * @param min         fixed minimum value of the axis (must be positive)
     * @param max         fixed maximum value of the axis
     * @param labelFormat {@link String#format(String, Object...)} pattern for tick labels
     * @return a fixed-range, logarithmic axis configuration
     */
    public static ChartAxisConfig logFixed(double min, double max, String labelFormat) {
        return new ChartAxisConfig(min, max, false, labelFormat, true);
    }

    /**
     * Fixed range with logarithmic Y-axis and default format.
     *
     * @param min fixed minimum value of the axis (must be positive)
     * @param max fixed maximum value of the axis
     * @return a fixed-range, logarithmic axis configuration using the "%.0f" format
     */
    public static ChartAxisConfig logFixed(double min, double max) {
        return new ChartAxisConfig(min, max, false, "%.0f", true);
    }

    /**
     * Format a value for axis display. Configs built by
     * {@link #fixedCompact} / {@link #logFixedCompact} render compact
     * magnitude labels ({@code 4.94B}, {@code 491M}); other configs apply
     * their {@link String#format} pattern.
     *
     * @param value the data value to format
     * @return the formatted label produced by this config
     */
    public String format(double value) {
        return isCompactFormat() ? compact(value) : labelFormat.formatted(value);
    }

    // ── Compact magnitude labels (2026-10-16) ────────────────────────

    /**
     * Formats a value with a compact magnitude suffix — the axis-label form
     * for big financial numbers: {@code 4.94B} instead of {@code 4938271605}.
     * Values under 10,000 render with up to two decimals un-suffixed
     * (margins, EPS, prices); larger values get one suffix tier above 100
     * (K, M, B, T) so labels stay ≤6 characters.
     *
     * @param value the data value to format
     * @return the compact label, never null
     */
    public static String compact(double value) {
        double abs = Math.abs(value);
        if (abs >= 1e12) return trimZeros(value / 1e12, "T");
        if (abs >= 1e9)  return trimZeros(value / 1e9, "B");
        if (abs >= 1e6)  return trimZeros(value / 1e6, "M");
        if (abs >= 10_000) return trimZeros(value / 1e3, "K");
        return trimZeros(value, "");
    }

    /** Formats the scaled value to ≤3 significant digits, dropping zero tails. */
    private static String trimZeros(double scaled, String suffix) {
        boolean negative = scaled < 0;
        double absScaled = Math.abs(scaled);
        String body;
        if (absScaled >= 100) {
            body = String.format("%.0f", absScaled);
        } else if (absScaled >= 10) {
            body = String.format("%.1f", absScaled);
        } else {
            body = String.format("%.2f", absScaled);
        }
        // Strip trailing zeros of the decimal part ("94.30" → "94.3", "2.50" → "2.5")
        if (body.contains(".") && body.endsWith("0")) {
            body = body.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return (negative ? "-" : "") + body + suffix;
    }

    /**
     * Fixed-range linear axis with the compact magnitude formatter.
     *
     * @param min fixed minimum value of the axis
     * @param max fixed maximum value of the axis
     * @return a fixed-range axis producing labels like {@code 400M}, {@code 4.94B}
     */
    public static ChartAxisConfig fixedCompact(double min, double max) {
        return new ChartAxisConfig(min, max, false, "COMPACT", false);
    }

    /**
     * Fixed-range logarithmic axis with the compact magnitude formatter.
     * Data must be strictly positive.
     *
     * @param min fixed minimum value of the axis (must be positive)
     * @param max fixed maximum value of the axis
     * @return a log axis producing labels like {@code 1M}, {@code 4B}
     */
    public static ChartAxisConfig logFixedCompact(double min, double max) {
        return new ChartAxisConfig(min, max, false, "COMPACT", true);
    }

    /**
     * True when this config uses the built-in compact magnitude formatter
     * instead of a {@link String#format} pattern.
     *
     * @return true for the {@code compact} label mode
     */
    public boolean isCompactFormat() {
        return "COMPACT".equals(labelFormat);
    }
}