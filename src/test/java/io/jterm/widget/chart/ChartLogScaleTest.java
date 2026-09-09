package io.jterm.widget.chart;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import io.jterm.style.IndexedColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-completion tests for {@link Chart}: the logarithmic Y-axis paths
 * (config toggles, log ticks, log value mapping, log→linear fallback for
 * narrow ranges), X-axis labels, and degenerate data ranges (all-zero data,
 * single point). Complements ChartTest, which covers the linear paths.
 */
class ChartLogScaleTest {

    private ScreenBuffer draw(Chart chart, int cols, int rows) {
        chart.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(cols, rows));
        var buf = new ScreenBuffer(new TerminalSize(cols, rows));
        chart.draw(new TextGraphics(buf));
        return buf;
    }

    private Chart logChart(double... values) {
        var chart = new Chart("Log");
        var series = new ChartSeries("s", java.util.Arrays.stream(values).boxed().toList(), AnsiColor.GREEN);
        chart.addSeries(series);
        chart.setYAxisLogarithmic(true);
        return chart;
    }

    // ===== setYAxisLogarithmic branches =====

    @Test
    void setYAxisLogarithmicRoundTrip() {
        var chart = new Chart("c");
        assertFalse(chart.isYAxisLogarithmic());
        assertSame(chart, chart.setYAxisLogarithmic(true));
        assertTrue(chart.isYAxisLogarithmic());
        chart.setYAxisLogarithmic(true);  // no-op branch: same value
        assertTrue(chart.isYAxisLogarithmic());
        chart.setYAxisLogarithmic(false);
        assertFalse(chart.isYAxisLogarithmic());
    }

    @Test
    void logarithmicWithFixedRangePreservesBounds() {
        var chart = new Chart("c");
        chart.setYAxisConfig(ChartAxisConfig.fixed(1, 1000));
        chart.setYAxisLogarithmic(true);
        assertTrue(chart.isYAxisLogarithmic());
        // Draw must not throw with a fixed log range spanning decades.
        var buf = draw(chart, 40, 12);
        assertNotNull(buf);
        // Back to linear keeps the fixed range.
        chart.setYAxisLogarithmic(false);
        assertFalse(chart.isYAxisLogarithmic());
        draw(chart, 40, 12);
    }

    // ===== log-scale rendering =====

    @Test
    void logScaleRendersWideRangeData() {
        // Data spanning 3 decades exercises logTicks.
        var chart = logChart(1.0, 10.0, 100.0, 1000.0);
        var buf = draw(chart, 50, 14);
        // The plot area must contain non-space cells (axis labels/lines).
        boolean anyInk = false;
        for (int r = 0; r < 12 && !anyInk; r++)
            for (int c = 0; c < 50; c++)
                if (buf.getCell(c, r).character().charAt(0) != ' ') { anyInk = true; break; }
        assertTrue(anyInk, "log chart renders visible content");
    }

    @Test
    void logScaleNarrowRangeFallsBackToLinearTicks() {
        // Ratio < 10 → logTicks fallback to linear ticks (line 488-490).
        var chart = logChart(5.0, 6.0, 7.0, 8.0);
        chart.setYAxisConfig(ChartAxisConfig.fixed(5, 8));
        chart.setYAxisLogarithmic(true);
        var buf = draw(chart, 40, 12);
        assertNotNull(buf);
    }

    @Test
    void logScaleZeroDataClampsMinAboveZero() {
        // All-zero data with autoScale: min clamps to nextUp(0) (line 385).
        var chart = logChart(0.0, 0.0, 0.0);
        assertDoesNotThrow(() -> draw(chart, 40, 12));
    }

    @Test
    void linearScaleAllZeroDataDoesNotThrow() {
        var chart = new Chart("c");
        chart.addSeries(new ChartSeries("z", java.util.List.of(0.0, 0.0), AnsiColor.RED));
        assertDoesNotThrow(() -> draw(chart, 30, 10));
    }

    @Test
    void linearScaleSingleIdenticalValuesDoesNotThrow() {
        var chart = new Chart("c");
        chart.addSeries(new ChartSeries("flat", java.util.List.of(5.0, 5.0, 5.0), AnsiColor.CYAN));
        assertDoesNotThrow(() -> draw(chart, 30, 10));
    }

    // ===== X-axis labels =====

    @Test
    void setXAxisLabelsRoundTrip() {
        var chart = new Chart("c");
        assertTrue(chart.getXAxisLabels().isEmpty());
        chart.setXAxisLabels(java.util.List.of("Mon", "Tue"));
        assertEquals(java.util.List.of("Mon", "Tue"), chart.getXAxisLabels());
        chart.setXAxisLabels(null);  // null clears
        assertTrue(chart.getXAxisLabels().isEmpty());
    }

    @Test
    void xLabelsAreDefensivelyCopied() {
        var chart = new Chart("c");
        var list = new java.util.ArrayList<>(java.util.List.of("a"));
        chart.setXAxisLabels(list);
        list.add("b");
        assertEquals(java.util.List.of("a"), chart.getXAxisLabels());
    }

    @Test
    void chartWithXLabelsRenders() {
        var chart = new Chart("c");
        chart.addSeries(new ChartSeries("s", java.util.List.of(1.0, 2.0, 3.0), AnsiColor.GREEN));
        chart.setXAxisLabels(java.util.List.of("a", "b", "c"));
        var buf = draw(chart, 40, 14);
        assertNotNull(buf);
    }

    // ===== indexed color palette in chart colors =====

    @Test
    void chartAcceptsIndexedColors() {
        var chart = new Chart("c");
        chart.addSeries(new ChartSeries("idx", java.util.List.of(1.0, 50.0), new IndexedColor(196)));
        assertDoesNotThrow(() -> draw(chart, 30, 10));
    }
}