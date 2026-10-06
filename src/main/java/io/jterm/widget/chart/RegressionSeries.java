package io.jterm.widget.chart;

import io.jterm.style.AnsiColor;
import io.jterm.style.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Trend-channel regression for chart series: an OLS line through the plotted
 * values plus ±Nσ bands, mirroring the trend-channel analysis (TrendScanner
 * σ=1.5 default).
 *
 * <p>Two spaces, matched to the plot scale:</p>
 * <ul>
 *   <li>{@link #of(ChartSeries, double)} — linear OLS in value space; the
 *       line is straight on a linear axis. Works for any series, including
 *       negatives (P&amp;L).</li>
 *   <li>{@link #of(ChartSeries, double, boolean)} with {@code logSpace=true}
 *       — OLS in log space, {@code exp()}ed back to price space; the line is
 *       straight on a LOG axis (trend-channel style). Falls back to the
 *       linear fit when any observed value is ≤ 0.</li>
 * </ul>
 *
 * <p>Only non-null source points feed the fit (nulls are gaps); the fitted
 * line and bands are defined at every index so they render as continuous
 * lines spanning gaps. A single observed point yields a degenerate flat
 * fit at that value with σ=0; no points yields nothing.</p>
 */
public final class RegressionSeries {

    /** σ (in fit space) of the last fit. */
    private static double lastSigma;

    private RegressionSeries() { }

    /** σ (population, fit space) of residuals from the most recent {@code of} call. */
    public static Optional<Double> lastSigmaLog() {
        return Optional.of(lastSigma);
    }

    /** σ of the last fit; {@code NaN} when no fit has run yet. */
    public static double lastSigma() {
        return Double.isNaN(lastSigma) ? Double.NaN : lastSigma;
    }

    /** Linear-space regression + bands (straight on a linear axis). */
    public static List<ChartSeries> of(ChartSeries src, double sigmaMultiple) {
        return of(src, sigmaMultiple, false);
    }

    /**
     * Regression + bands in the requested space.
     *
     * @param src           the plotted series to regress
     * @param sigmaMultiple band width in multiples of the residual σ
     * @param logSpace      fit in log space (straight on a log-Y axis); falls
     *                      back to linear space if any observed value ≤ 0
     * @return three LINE series (may be empty when the source has no points)
     */
    public static List<ChartSeries> of(ChartSeries src, double sigmaMultiple, boolean logSpace) {
        lastSigma = Double.NaN;
        List<Double> values = src.values();
        if (values.isEmpty()) {
            return List.of();
        }
        List<Double> fitInput = values;
        boolean inLog = logSpace && values.stream().anyMatch(v -> v != null)
            && values.stream().allMatch(v -> v == null || v > 0);
        if (inLog) {
            fitInput = values.stream()
                .map(v -> v == null ? null : Math.log(v))
                .toList();
        }

        double[] s = sums(fitInput);
        int m = (int) s[4];
        if (m == 0) {
            return List.of();
        }
        double denom = m * s[2] - s[0] * s[0];
        double slope;
        double intercept;
        if (denom == 0) { // single observed point → flat fit at its value
            slope = 0;
            intercept = s[1] / m;
        } else {
            slope = (m * s[3] - s[0] * s[1]) / denom;
            intercept = (s[1] - slope * s[0]) / m;
        }

        double sigma = sigmaOf(fitInput, slope, intercept, m);
        lastSigma = sigma;

        double band = sigmaMultiple * sigma;
        // back to value space: log-space fit is exp()ed; linear passes through
        var fittedAt = inLog
            ? (java.util.function.IntToDoubleFunction) i -> Math.exp(slope * i + intercept)
            : (java.util.function.IntToDoubleFunction) i -> slope * i + intercept;
        var bandAt = inLog
            ? (java.util.function.IntToDoubleFunction) i ->
                Math.exp(slope * i + intercept + band)
            : (java.util.function.IntToDoubleFunction) i -> slope * i + intercept + band;
        var bandLowAt = inLog
            ? (java.util.function.IntToDoubleFunction) i ->
                Math.exp(slope * i + intercept - band)
            : (java.util.function.IntToDoubleFunction) i -> slope * i + intercept - band;

        int n = values.size();
        List<Double> reg = new ArrayList<>(n);
        List<Double> up = new ArrayList<>(n);
        List<Double> lo = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            reg.add(fittedAt.applyAsDouble(i));
            up.add(bandAt.applyAsDouble(i));
            lo.add(bandLowAt.applyAsDouble(i));
        }
        String name = src.name() != null ? src.name() : "series";
        return List.of(
            new ChartSeries(name + " reg", reg, ChartType.LINE, AnsiColor.MAGENTA),
            new ChartSeries(name + " +" + bandLabel(sigmaMultiple) + "σ", up,
                ChartType.LINE, AnsiColor.BRIGHT_BLACK),
            new ChartSeries(name + " −" + bandLabel(sigmaMultiple) + "σ", lo,
                ChartType.LINE, AnsiColor.BRIGHT_BLACK));
    }

    /** Σx, Σy, Σx², Σxy, count over the observed points. */
    private static double[] sums(List<Double> values) {
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        int m = 0;
        for (int i = 0; i < values.size(); i++) {
            Double v = values.get(i);
            if (v == null) {
                continue;
            }
            sumX += i;
            sumY += v;
            sumXY += i * v;
            sumX2 += (double) i * i;
            m++;
        }
        return new double[]{sumX, sumY, sumX2, sumXY, m};
    }

    private static double sigmaOf(List<Double> values, double slope, double intercept, int m) {
        if (m <= 1) {
            return 0;
        }
        double ss = 0;
        for (int i = 0; i < values.size(); i++) {
            Double v = values.get(i);
            if (v == null) {
                continue;
            }
            double r = v - (slope * i + intercept);
            ss += r * r;
        }
        return Math.sqrt(ss / m);
    }

    private static String bandLabel(double sigmaMultiple) {
        return sigmaMultiple == Math.floor(sigmaMultiple)
            ? String.valueOf((int) sigmaMultiple)
            : String.valueOf(sigmaMultiple);
    }
}