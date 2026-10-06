package io.jterm.widget.chart;

import io.jterm.style.AnsiColor;
import io.jterm.style.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * Trend-channel regression for chart series: an OLS line through the plotted
 * values plus ±Nσ bands, mirroring the trend-channel analysis (TrendScanner
 * σ=1.5 default). Linear in value space — safe for any series (prices,
 * P/E, P&L), unlike the log-space variant used for report charts.
 *
 * <p>Only non-null source points feed the fit (nulls are gaps); the fitted
 * line and bands are defined at every index so they render as continuous
 * lines spanning gaps. A single observed point yields a degenerate flat
 * fit at that value with σ=0; no points yields nothing.</p>
 */
public final class RegressionSeries {

    /** σ of the last fit — exposed for tests. */
    private static double lastSigma;

    private RegressionSeries() { }

    /** σ (population) of residuals from the most recent {@link #of} call. */
    public static double lastSigma() {
        return lastSigma;
    }

    /**
     * Builds {@code [regression, +Nσ, −Nσ]} series over the source's index
     * grid.
     *
     * @param src   the plotted series to regress
     * @param sigmaMultiple band width in multiples of the residual σ
     * @return three LINE series (may be empty when the source has no points)
     */
    public static List<ChartSeries> of(ChartSeries src, double sigmaMultiple) {
        List<Double> values = src.values();
        int n = values.size();
        List<ChartSeries> out = new ArrayList<>();
        if (n == 0) {
            return out;
        }

        // OLS through the observed (index, value) pairs
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        int m = 0;
        for (int i = 0; i < n; i++) {
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
        if (m == 0) {
            return out;
        }
        double denom = m * sumX2 - sumX * sumX;
        double slope;
        double intercept;
        if (denom == 0) { // single observed point → flat fit at its value
            slope = 0;
            intercept = sumY / m;
        } else {
            slope = (m * sumXY - sumX * sumY) / denom;
            intercept = (sumY - slope * sumX) / m;
        }

        // residual σ (population, over observed points)
        double ss = 0;
        for (int i = 0; i < n; i++) {
            Double v = values.get(i);
            if (v == null) {
                continue;
            }
            double r = v - (slope * i + intercept);
            ss += r * r;
        }
        double sigma = m <= 1 ? 0 : Math.sqrt(ss / m);
        lastSigma = sigma;

        double band = sigmaMultiple * sigma;
        Color base = src.color() != null ? src.color() : AnsiColor.WHITE;
        List<Double> reg = new ArrayList<>(n);
        List<Double> up = new ArrayList<>(n);
        List<Double> lo = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double fitted = slope * i + intercept;
            reg.add(fitted);
            up.add(fitted + band);
            lo.add(fitted - band);
        }
        String name = src.name() != null ? src.name() : "series";
        out.add(new ChartSeries(name + " reg", reg, ChartType.LINE, AnsiColor.MAGENTA));
        out.add(new ChartSeries(name + " +" + bandLabel(sigmaMultiple) + "σ", up,
            ChartType.LINE, AnsiColor.BRIGHT_BLACK));
        out.add(new ChartSeries(name + " −" + bandLabel(sigmaMultiple) + "σ", lo,
            ChartType.LINE, AnsiColor.BRIGHT_BLACK));
        return out;
    }

    private static String bandLabel(double sigmaMultiple) {
        return sigmaMultiple == Math.floor(sigmaMultiple)
            ? String.valueOf((int) sigmaMultiple)
            : String.valueOf(sigmaMultiple);
    }
}