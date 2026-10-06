package io.jterm.widget.chart;

import io.jterm.style.AnsiColor;
import io.jterm.style.Color;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regression + ±Nσ channel series built from a plotted series (trend-channel). */
class RegressionSeriesTest {

    @Test
    void perfectLinearFitYieldsZeroSigmaAndCollocatedBands() {
        var src = new ChartSeries("Close", List.of(1.0, 3.0, 5.0, 7.0, 9.0),
            ChartType.LINE, AnsiColor.WHITE);
        List<ChartSeries> out = RegressionSeries.of(src, 1.5);
        assertEquals(3, out.size());
        assertEquals(List.of(1.0, 3.0, 5.0, 7.0, 9.0), out.get(0).values(),
            "regression reproduces an exact line");
        assertEquals(out.get(0).values(), out.get(1).values(), "+σ band == line (σ=0)");
        assertEquals(out.get(0).values(), out.get(2).values(), "−σ band == line (σ=0)");
    }

    @Test
    void sigmaBandsOffsetByOnePointHalfSigma() {
        // line y = x for x=0..3 plus a +2 outlier shift at the last point
        var src = new ChartSeries("Close", List.of(0.0, 1.0, 2.0, 4.0),
            ChartType.LINE, AnsiColor.WHITE);
        List<ChartSeries> out = RegressionSeries.of(src, 1.0);
        ChartSeries reg = out.get(0);
        ChartSeries upper = out.get(1);
        ChartSeries lower = out.get(2);
        // OLS through (0,0)(1,1)(2,2)(3,4): slope 1.3, intercept −0.2
        assertEquals(3.7, reg.values().get(3), 1e-9, "regression at x=3");
        // residuals 0.2, −0.1, −0.4, 0.3 → σ (population) = √0.075
        assertEquals(Math.sqrt(0.075), RegressionSeries.lastSigma(), 1e-9, "σ of residuals");
        assertEquals(3.7 + Math.sqrt(0.075), upper.values().get(3), 1e-9, "+1σ band at x=3");
        assertEquals(3.7 - Math.sqrt(0.075), lower.values().get(3), 1e-9, "−1σ band at x=3");
    }

    @Test
    void gapsAreExcludedFromFitButRegressionSpansAllIndexes() {
        var src = new ChartSeries("Close", Arrays.asList(1.0, null, 5.0, null, 9.0),
            ChartType.LINE, AnsiColor.WHITE);
        List<ChartSeries> out = RegressionSeries.of(src, 1.5);
        assertEquals(5, out.get(0).values().size(), "regression spans every x");
        // fit through (0,1)(2,5)(4,9): y = 2x + 1 exactly, σ = 0
        assertEquals(2.0 * 3 + 1, out.get(0).values().get(3), 1e-9,
            "fitted line is defined at gap indexes too");
        assertTrue(out.get(0).values().stream().allMatch(v -> v != null),
            "regression/channel lines have no gaps");
        // the source's nulls must NOT have been fitted through
        assertEquals(1.0, out.get(0).values().get(0), 1e-9);
        assertEquals(5.0, out.get(0).values().get(2), 1e-9);
    }

    @Test
    void negativeValuedSeriesRegressesLinearly() {
        var src = new ChartSeries("PnL", List.of(-10.0, -5.0, -2.0, 3.0),
            ChartType.LINE, AnsiColor.WHITE);
        List<ChartSeries> out = RegressionSeries.of(src, 1.5);
        assertTrue(out.get(0).values().stream().allMatch(v -> !Double.isNaN(v)),
            "linear regression handles negatives (no log)");
    }

    @Test
    void seriesMetaFollowsSource() {
        var src = new ChartSeries("Close", List.of(1.0, 2.0, 3.0),
            ChartType.LINE, AnsiColor.WHITE);
        List<ChartSeries> out = RegressionSeries.of(src, 1.5);
        assertEquals("Close reg", out.get(0).name());
        assertEquals("Close +1.5σ", out.get(1).name());
        assertEquals("Close −1.5σ", out.get(2).name());
        assertEquals(ChartType.LINE, out.get(0).type());
        Color regColor = out.get(0).color();
        assertNotEquals(src.color(), regColor, "regression uses its own color");
    }
}