package io.jterm.widget.chart;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Null values in a series are GAPS: stats and drawing must tolerate them. */
class ChartSeriesNullGapTest {

    private ChartSeries series(List<Double> values) {
        return new ChartSeries("s", values, ChartType.LINE, AnsiColor.WHITE, null);
    }

    @Test
    void minAndMaxIgnoreNullGaps() {
        var s = series(Arrays.asList(3.0, null, 9.0, null, 5.0));
        assertEquals(3.0, s.min());
        assertEquals(9.0, s.max());
    }

    @Test
    void allNullSeriesYieldsNaNStats() {
        var s = series(Arrays.asList(null, null));
        assertTrue(Double.isNaN(s.min()));
        assertTrue(Double.isNaN(s.max()));
        assertFalse(s.isEmpty(), "null cells are positions, not absence of the series");
    }

    @Test
    void drawingDoesNotThrowOnNullGaps() {
        // Production-shape: a sparse metric series beside daily closes. computeYRange
        // min()/max() consumed the nulls and threw NPE inside the GUI loop (live BBS).
        var sparse = series(Arrays.asList(null, null, 25.0, null, 28.0, null));
        var dense = series(Arrays.asList(100.0, 101.0, 102.0, 103.0, 104.0, 105.0));
        var chart = new Chart("gaps");
        chart.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(60, 20));
        chart.addSeries(sparse);
        chart.addSeries(dense);
        var buf = new ScreenBuffer(new TerminalSize(60, 20));
        var g = new TextGraphics(buf);
        assertDoesNotThrow(() -> chart.draw(g));
        // dense series still renders
        boolean drewSomething = false;
        for (int r = 0; r < 20 && !drewSomething; r++) {
            for (int c = 0; c < 60; c++) {
                if (!" ".equals(buf.getCell(c, r).character())) { drewSomething = true; break; }
            }
        }
        assertTrue(drewSomething, "chart renders despite the sparse series' gaps");
    }
}