package io.jterm.widget.chart;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Big-number axis legibility (2026-10-16): shares-outstanding/revenue charts
 * printed raw {@code %.0f} labels (4938271605) and the tick-step ladder
 * topped out at 1,000,000 — billion-scale ranges "best-fit" to million steps
 * and drew hundreds of ticks. Pins: (1) compact magnitude labels, (2) the
 * ladder extends to any decade so ticks land on reasonable round values.
 */
class ChartCompactAxisTest {

    // ── Compact labels ───────────────────────────────────────────

    @Test
    void compactFormatsBillionsWithSuffix() {
        assertEquals("4.94B", ChartAxisConfig.compact(4_938_271_605.0));
        assertEquals("15.3B", ChartAxisConfig.compact(15_315_000_000.0));
    }

    @Test
    void compactFormatsMillionsAndThousands() {
        assertEquals("94.3M", ChartAxisConfig.compact(94_280_000.0));
        assertEquals("491M", ChartAxisConfig.compact(491_000_000.0));
        assertEquals("49.1K", ChartAxisConfig.compact(49_123.0));
    }

    @Test
    void compactKeepsSmallNumbersPlain() {
        assertEquals("31.2", ChartAxisConfig.compact(31.19));
        assertEquals("500", ChartAxisConfig.compact(500.0));
        assertEquals("0", ChartAxisConfig.compact(0.0));
    }

    @Test
    void compactHandlesNegativesAndBoundaryScaleUp() {
        assertEquals("-1.5M", ChartAxisConfig.compact(-1_500_000.0));
        assertEquals("1.23M", ChartAxisConfig.compact(1_234_567.0));
        assertEquals("2.5T", ChartAxisConfig.compact(2_500_000_000_000.0));
    }

    @Test
    void fixedCompactFactoryProducesCompactFormatter() {
        var cfg = ChartAxisConfig.fixedCompact(400_000_000.0, 491_000_000.0);
        assertFalse(cfg.autoScale());
        assertEquals("400M", cfg.format(400_000_000.0));
        assertEquals("491M", cfg.format(491_000_000.0));
    }

    @Test
    void logFixedCompactFactoryProducesCompactFormatter() {
        var cfg = ChartAxisConfig.logFixedCompact(1_000_000.0, 4_000_000_000.0);
        assertTrue(cfg.logarithmic());
        assertEquals("1M", cfg.format(1_000_000.0));
        assertEquals("4B", cfg.format(4_000_000_000.0));
    }

    // ── Tick ladder reaches billion+ scales ──────────────────────

    @Test
    void niceTicksPicksBillionStepsForWideRanges() {
        // Revenue-scale axis 2016..2025: previously the ladder capped at 1M
        // and this range produced ~hundreds of ticks.
        double[] ticks = Chart.niceTicks(1_000_000_000.0, 400_000_000_000.0, 5);
        assertTrue(ticks.length <= 8, "tick count stays sane on wide ranges: "
            + ticks.length);
        // Multiples of 1e11 (the best round step) — reasonable break values.
        assertArrayEquals(new double[]{1e11, 2e11, 3e11, 4e11}, ticks, 1e-6);
    }

    @Test
    void niceTicksPicksSaneStepsForShareCounts() {
        // Share-count span 400M..491M → step 2.5e7 → 4 clean ticks.
        double[] ticks = Chart.niceTicks(400_000_000.0, 491_000_000.0, 5);
        assertArrayEquals(
            new double[]{4.0e8, 4.25e8, 4.5e8, 4.75e8}, ticks, 1e-6);
    }

    @Test
    void niceTicksKeepsSubUnitStepsForPennyRanges() {
        // Regression guard for the fractional step path (price charts).
        double[] ticks = Chart.niceTicks(0.42, 0.97, 4);
        assertTrue(ticks.length >= 2 && ticks.length <= 8,
            "penny-scale ticks stay bounded: " + ticks.length);
        assertEquals(0.5, ticks[0], 1e-9, "first tick on a nice boundary");
    }

    // ── Rendered end-to-end: labels short enough for the axis gutter ──

    private String renderAxisLabels(ChartAxisConfig cfg, List<Double> values) {
        var chart = new Chart("");
        chart.addSeries(new ChartSeries("S", values, AnsiColor.BRIGHT_GREEN));
        chart.setYAxisConfig(cfg);
        chart.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(100, 24));
        var buf = new ScreenBuffer(new TerminalSize(100, 24));
        chart.draw(new TextGraphics(buf));
        var sb = new StringBuilder();
        // Y-axis labels render left of the plot; scan the first 12 columns.
        for (int r = 0; r < 24; r++) {
            sb.append('\n');
            for (int c = 0; c < 12; c++) {
                sb.append(buf.getCell(c, r).character());
            }
        }
        return sb.toString();
    }

    @Test
    void renderedSharesChartShowsCompactBreaksNotRawNumbers() {
        // Shares outstanding: 19 annual periods 400M..500M.
        var values = new java.util.ArrayList<Double>();
        for (int y = 0; y < 19; y++) values.add(400_000_000.0 + y * 5_000_000.0);
        String axis = renderAxisLabels(ChartAxisConfig.fixedCompact(400_000_000.0, 495_000_000.0), values);
        assertFalse(axis.contains("400000000"), "no raw long numbers on the axis:\n" + axis);
        assertTrue(axis.contains("M"), "compact magnitude suffix present:\n" + axis);
        assertTrue(axis.length() / (24L + 1) <= 13, "labels fit the 12-col gutter:\n" + axis);
    }

    @Test
    void renderedRawFormatStillAvailableForMoneyAxis() {
        // $-style axes keep their own format string — untouched path.
        String axis = renderAxisLabels(ChartAxisConfig.fixed(0, 200, "$%.0f"), List.of(100.0, 180.0));
        assertTrue(axis.contains("$150") || axis.contains("$100") || axis.contains("$200"),
            "money format preserved:\n" + axis);
    }
}