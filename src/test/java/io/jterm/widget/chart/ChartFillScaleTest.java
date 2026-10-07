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
 * Joe's fill-scale law (2026-10-07): with fill-all enabled, EVERY secondary
 * series maps its own [min,max] onto the full plot height so differently
 * scaled units (earnings $ vs PE ratio) are both visible; the primary series
 * keeps the shared scale and the grid labels stay the primary's units.
 */
class ChartFillScaleTest {

    private static final int W = 40;
    private static final int H = 14;

    private int[] verticalExtent(ScreenBuffer buf, io.jterm.style.Color fg) {
        // Count per row; rows with ≤2 cells are legend/swatch noise, not a line.
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (int r = 0; r < H; r++) {
            int cells = 0;
            for (int c = 0; c < W; c++) {
                if (buf.getCell(c, r).fg() == fg) cells++;
            }
            if (cells > 2) {
                top = Math.min(top, r);
                bottom = Math.max(bottom, r);
            }
        }
        return new int[]{top, bottom == Integer.MIN_VALUE ? top : bottom};
    }

    private Chart chart(boolean fillAll) {
        // big: 0..10 (primary), tiny: 41..42 (flat line under shared scale),
        // huge: 100..200. Units a decade apart — the Joe earnings-vs-PE case.
        // Different phases so filled series don't coincide cell-for-cell
        // (identical shapes would legally overpaint each other — z-order).
        var a = new ChartSeries("big", List.of(0.0, 5.0, 10.0, 5.0), ChartType.LINE, AnsiColor.GREEN);
        var b = new ChartSeries("tiny", List.of(41.0, 42.0, 41.5, 41.0), ChartType.LINE, AnsiColor.RED);
        var c = new ChartSeries("huge", List.of(200.0, 100.0, 150.0, 200.0), ChartType.LINE, AnsiColor.CYAN);
        var chart = new Chart("fill");
        chart.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(W, H));
        chart.setYAxisConfig(ChartAxisConfig.auto());
        if (fillAll) chart.setFillAllSeries(true);
        chart.addSeries(a);
        chart.addSeries(b);
        chart.addSeries(c);
        return chart;
    }

    @Test
    void fillAllSecondarySeriesSpanFullPlotHeight() {
        var buf = new ScreenBuffer(new TerminalSize(W, H));
        chart(true).draw(new TextGraphics(buf));

        int[] primary = verticalExtent(buf, AnsiColor.GREEN);
        int[] tiny = verticalExtent(buf, AnsiColor.RED);
        int[] huge = verticalExtent(buf, AnsiColor.CYAN);

        assertTrue(tiny[0] <= primary[0] + 1,
            "secondary 'tiny' reaches the top of the plot like the primary; tiny=" + tiny[0] + " primary=" + primary[0]);
        assertTrue(tiny[1] >= primary[1] - 1,
            "secondary 'tiny' reaches the bottom of the plot like the primary; tiny=" + tiny[1] + " primary=" + primary[1]);
        assertTrue(huge[0] <= primary[0] + 1,
            "secondary 'huge' reaches the top of the plot; huge=" + huge[0] + " primary=" + primary[0]);
        assertTrue(huge[1] >= primary[1] - 1,
            "secondary 'huge' reaches the bottom of the plot; huge=" + huge[1] + " primary=" + primary[1]);
    }

    @Test
    void sharedScaleLeavesTinySeriesFlat() {
        var buf = new ScreenBuffer(new TerminalSize(W, H));
        chart(false).draw(new TextGraphics(buf));

        int[] tiny = verticalExtent(buf, AnsiColor.RED);
        int[] huge = verticalExtent(buf, AnsiColor.CYAN);
        // On the shared 0..200 scale, tiny (41..42) must render within 2 rows
        // of its band — flat and invisible, the bug this mode exists to fix.
        assertTrue(tiny[1] - tiny[0] <= 2,
            "'tiny' is effectively flat under shared scale; extent=" + (tiny[1] - tiny[0]));
        assertTrue(huge[1] - huge[0] >= tiny[1] - tiny[0]);
    }

    @Test
    void primaryFillsItsOwnRangeWhenFillAll() {
        // v2 law (Joe correction 2026-10-07): the PRIMARY fills the screen
        // too — the axis becomes the primary's own range, so the primary's
        // geometry spans the plot height (it was squashed one-flat-row under
        // the shared scale with differently-scaled secondaries).
        var bufOn = new ScreenBuffer(new TerminalSize(W, H));
        chart(true).draw(new TextGraphics(bufOn));
        int[] primaryOn = verticalExtent(bufOn, AnsiColor.GREEN);
        assertTrue(primaryOn[1] - primaryOn[0] >= 4,
            "primary spans the plot height in fill mode; extent=" + (primaryOn[1] - primaryOn[0]));
    }

    @Test
    void gridLabelsSpeakPrimaryUnitsWhenFillAll() {
        // v2 law: labels come from the primary's own range — e.g. plotting
        // Earnings + P/E shows grid ticks in the PRIMARY's units, not a
        // range stretched across both series' units.
        var on = chart(true);
        var bufOn = new ScreenBuffer(new TerminalSize(W, H));
        on.draw(new TextGraphics(bufOn));
        // Primary = "big" 0..10 (5% pad → top tick ≈ 10): the gutter must
        // show a single-digit order of magnitude, NOT the shared 0..200 axis.
        String gutter = "";
        for (int r = 1; r < H - 1; r++) {
            StringBuilder row = new StringBuilder();
            for (int c = 1; c < 6; c++) row.append(bufOn.getCell(c, r).character());
            gutter += row.toString().trim() + "\n";
        }
        assertFalse(gutter.contains("100"), "no shared-scale 100 tick: " + gutter);
        assertTrue(gutter.lines().anyMatch(l -> l.matches(".*\\d+.*")), "ticks render");
    }

    @Test
    void flatSeriesRendersAsMidHeightDashedLine() {
        // min == max cannot be range-mapped — rule: mid-height dashed line.
        var a = new ChartSeries("pp", List.of(0.0, 5.0, 10.0), ChartType.LINE, AnsiColor.GREEN);
        var cash = new ChartSeries("prcxx", List.of(1.0, 1.0, 1.0, 1.0), ChartType.LINE, AnsiColor.RED);
        var chart = new Chart("flat");
        chart.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(W, H));
        chart.setFillAllSeries(true);
        chart.addSeries(a);
        chart.addSeries(cash);
        var buf = new ScreenBuffer(new TerminalSize(W, H));
        chart.draw(new TextGraphics(buf));
        // Flat series = RED cells; primary = GREEN. The flat line must sit
        // mid-plot (not clamp to top/bottom) and be visible as a line.
        int[] flat = verticalExtent(buf, AnsiColor.RED);
        assertTrue(flat[0] != Integer.MAX_VALUE, "flat series renders");
        assertTrue(flat[1] - flat[0] <= 2,
            "flat series is a thin line; extent=" + (flat[1] - flat[0]));
        int mid = H / 2;
        assertTrue(flat[0] >= mid - 3 && flat[0] <= mid + 3,
            "flat series sits mid-plot; top=" + flat[0]);
    }

    private String bufText(ScreenBuffer buf) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) sb.append(buf.getCell(c, r).character());
            sb.append('\n');
        }
        return sb.toString();
    }

    @Test
    void joeCase_primaryPeWithSparseEpsSecondary_logAxis() {
        // Real AAPL 1Y: PE tight 36.9..37.4 with nulls, EPS quarterly sparse.
        // PE is PRIMARY: its own range defines the axis + labels; EPS (secondary)
        // fills its own range. NEITHER may pin flat at an edge.
        var pe = new ChartSeries("P/E", java.util.Arrays.asList(37.40, null, 37.32, 37.41, 37.03, null, 37.33, 36.93), ChartType.LINE, AnsiColor.GREEN);
        var eps = new ChartSeries("EPS", java.util.Arrays.asList(1.20, null, null, null, 2.03, null, null, 2.85), ChartType.LINE, AnsiColor.RED);
        var chart = new Chart("joe");
        chart.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(W, H));
        chart.setYAxisConfig(ChartAxisConfig.logAuto());
        chart.setFillAllSeries(true);
        chart.addSeries(pe);   // primary
        chart.addSeries(eps);  // secondary
        var buf = new ScreenBuffer(new TerminalSize(W, H));
        chart.draw(new TextGraphics(buf));

        int[] peE = verticalExtent(buf, AnsiColor.GREEN);
        int[] epsE = verticalExtent(buf, AnsiColor.RED);
        assertTrue(peE[0] != Integer.MAX_VALUE, "PE renders");
        assertTrue(epsE[0] != Integer.MAX_VALUE, "EPS renders (not swallowed by null gaps)");
        assertTrue(peE[1] - peE[0] >= 4, "PE fills the plot height (primary too); extent=" + (peE[1] - peE[0]));
        assertTrue(epsE[1] - epsE[0] >= 4, "EPS fills the plot height; extent=" + (epsE[1] - epsE[0]));
    }
}