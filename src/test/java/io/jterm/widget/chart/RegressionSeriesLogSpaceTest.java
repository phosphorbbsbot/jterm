package io.jterm.widget.chart;

import io.jterm.style.AnsiColor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Log-space regression: straight lines on a log Y axis (trend-channel style). */
class RegressionSeriesLogSpaceTest {

    @Test
    void perfectGeometricFitYieldsTheSeriesBack() {
        // exact doubling: log-linear OLS reproduces v_i = 2^i exactly, σ = 0
        var src = new ChartSeries("Close", List.of(1.0, 2.0, 4.0, 8.0),
            ChartType.LINE, AnsiColor.WHITE);
        List<ChartSeries> out = RegressionSeries.of(src, 1.5, true);
        List<Double> expected = List.of(1.0, 2.0, 4.0, 8.0);
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), out.get(0).values().get(i), 1e-9,
                "exp(OLS(log v)) == v for a perfect geometric series @ " + i);
        }
        assertEquals(0.0, RegressionSeries.lastSigmaLog().orElseThrow(), 1e-12, "σ of residuals");
        for (int i = 0; i < 4; i++) {
            assertEquals(out.get(0).values().get(i), out.get(1).values().get(i), 1e-9,
                "+σ collapses to the line @ " + i);
            assertEquals(out.get(0).values().get(i), out.get(2).values().get(i), 1e-9,
                "−σ collapses to the line @ " + i);
        }
    }

    @Test
    void bandsAreSymmetricInLogSpace() {
        // log-space fit through (0,0)(1,.2)(2,.4)(3,.8): slope .26, intercept −.04
        // residuals −.04,−.02,0,+.06 → σ_pop = √.0014
        var src = new ChartSeries("Close", List.of(1.0, Math.exp(.2), Math.exp(.4), Math.exp(.8)),
            ChartType.LINE, AnsiColor.WHITE);
        List<ChartSeries> out = RegressionSeries.of(src, 1.0, true);
        double sigma = 0.0547722557505166; // √(0.012/4), residuals .04 −.02 −.08 .06
        assertEquals(Math.exp(0.74 + sigma), out.get(1).values().get(3), 1e-9,
            "+1σ band = exp(fit + σ) in LOG space");
        assertEquals(Math.exp(0.74 - sigma), out.get(2).values().get(3), 1e-9,
            "−1σ band = exp(fit − σ) in LOG space");
        assertEquals(Math.exp(0.74), out.get(0).values().get(3), 1e-9, "fit at x=3");
    }

    @Test
    void gapsExcludedFromLogFitButLineSpansAllIndexes() {
        // (0,1),(2,4),(3,8): all on v = 2^x → fit is exact despite the null at 1
        var src = new ChartSeries("Close", Arrays.asList(1.0, null, 4.0, 8.0),
            ChartType.LINE, AnsiColor.WHITE);
        List<ChartSeries> out = RegressionSeries.of(src, 1.5, true);
        List<Double> expected = List.of(1.0, 2.0, 4.0, 8.0);
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), out.get(0).values().get(i), 1e-9,
                "fitted line spans the gap with the right value @ " + i);
        }
    }

    @Test
    void nonPositiveValuesFallBackToLinearFit() {
        var src = new ChartSeries("PnL", List.of(-10.0, -5.0, -2.0, 3.0),
            ChartType.LINE, AnsiColor.WHITE);
        var logAsked = RegressionSeries.of(src, 1.5, true);
        var linear = RegressionSeries.of(src, 1.5);
        assertEquals(linear.get(0).values(), logAsked.get(0).values(),
            "log fit impossible with ≤0 values — falls back to the linear-space fit");
    }
}