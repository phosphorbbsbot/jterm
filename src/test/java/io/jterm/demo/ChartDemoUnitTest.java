package io.jterm.demo;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ChartDemo's private static data-generation and view-switch
 * seams (reached via reflection — they're private by visibility, not by
 * design). Verifies the random-walk generators produce sane series and the
 * view switchers swap series without touching rendering.
 */
class ChartDemoUnitTest {

    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        var m = ChartDemo.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    @SuppressWarnings("unchecked")
    private static List<Double> generate(String name, Class<?>[] types, Object... args) throws Exception {
        return (List<Double>) invoke(name, types, args);
    }

    // ===== data generators =====

    @Test
    void generatePricesProducesRandomWalk() throws Exception {
        var prices = (List<Double>) invoke("generatePrices", new Class<?>[]{double.class, int.class}, 100.0, 50);
        assertEquals(50, prices.size());
        for (double p : prices) {
            assertTrue(p >= 130, "walk clamped to demo floor 130, got " + p);
            assertTrue(p <= 170, "walk clamped to demo ceiling 170, got " + p);
        }
        // rng is a shared static: successive calls continue the stream (not
        // reproducible across calls), but bounds still hold for a second run.
        var again = (List<Double>) invoke("generatePrices", new Class<?>[]{double.class, int.class}, 100.0, 50);
        assertEquals(50, again.size());
        for (double p : again) assertTrue(p >= 130 && p <= 170);
    }

    @Test
    void generateVolumesProducesPositiveCount() throws Exception {
        var vols = (List<Double>) invoke("generateVolumes", new Class<?>[]{int.class}, 20);
        assertEquals(20, vols.size());
        for (double v : vols) assertTrue(v >= 0, "volumes non-negative");
    }

    @Test
    void generateTicksStaysNearCenter() throws Exception {
        var ticks = (List<Double>) invoke("generateTicks", new Class<?>[]{double.class, int.class}, 50.0, 30);
        assertEquals(30, ticks.size());
        for (double t : ticks) {
            assertTrue(t >= 0, "tick prices non-negative");
            assertTrue(t < 200, "tick near center 50, got " + t);
        }
    }

    @Test
    void movingAverageSmoothsAndShortens() throws Exception {
        var data = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        var ma = (List<Double>) invoke("calculateMovingAverage",
            new Class<?>[]{List.class, int.class}, data, 3);
        assertNotNull(ma);
        // Same length as input; NaN-warmup replaced by the first valid value.
        assertEquals(5, ma.size(), "aligned to input length, NaNs backfilled");
        assertEquals(2.0, ma.get(2), 1e-9, "avg(1,2,3) at index 2");
        assertEquals(3.0, ma.get(3), 1e-9, "avg(2,3,4) at index 3");
        assertEquals(4.0, ma.get(4), 1e-9, "avg(3,4,5) at index 4");
        assertEquals(2.0, ma.get(0), 1e-9, "warmup backfilled with first valid");
    }

    // ===== view switchers =====

    @Test
    void showLineChartSwapsSeries() throws Exception {
        var chart = new io.jterm.widget.chart.Chart("t");
        var prices = List.of(1.0, 2.0, 3.0);
        invoke("showLineChart", new Class<?>[]{io.jterm.widget.chart.Chart.class, List.class}, chart, prices);
        assertEquals(1, chart.getSeries().size());
        assertEquals("Price", chart.getSeries().get(0).name());
    }

    @Test
    void showBarChartSwapsSeries() throws Exception {
        var chart = new io.jterm.widget.chart.Chart("t");
        invoke("showBarChart", new Class<?>[]{io.jterm.widget.chart.Chart.class, List.class},
            chart, List.of(5.0, 6.0));
        assertEquals(1, chart.getSeries().size());
        assertEquals("Volume", chart.getSeries().get(0).name());
    }

    @Test
    void showScatterChartSwapsSeries() throws Exception {
        var chart = new io.jterm.widget.chart.Chart("t");
        invoke("showScatterChart", new Class<?>[]{io.jterm.widget.chart.Chart.class, List.class},
            chart, List.of(9.0, 1.0));
        assertEquals("Ticks", chart.getSeries().get(0).name());
    }

    @Test
    void showMultiSeriesChartAddsTwo() throws Exception {
        var chart = new io.jterm.widget.chart.Chart("t");
        invoke("showMultiSeriesChart",
            new Class<?>[]{io.jterm.widget.chart.Chart.class, List.class, List.class},
            chart, List.of(1.0, 2.0), List.of(1.5, 1.8));
        assertEquals(2, chart.getSeries().size());
    }

    @Test
    void viewSwitchersClearPreviousSeries() throws Exception {
        var chart = new io.jterm.widget.chart.Chart("t");
        invoke("showLineChart", new Class<?>[]{io.jterm.widget.chart.Chart.class, List.class},
            chart, List.of(1.0));
        invoke("showBarChart", new Class<?>[]{io.jterm.widget.chart.Chart.class, List.class},
            chart, List.of(2.0, 3.0));
        assertEquals(1, chart.getSeries().size(), "previous view cleared");
        assertEquals("Volume", chart.getSeries().get(0).name());
    }
}