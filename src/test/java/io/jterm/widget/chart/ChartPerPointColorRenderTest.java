package io.jterm.widget.chart;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import io.jterm.style.Color;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** LINE and BAR rendering must honor per-point color overrides — not just scatter. */
class ChartPerPointColorRenderTest {

    private String drawAndGetColors(ChartType type, List<Double> values, List<Color> palette) {
        var series = new ChartSeries("s", values, type, AnsiColor.WHITE, palette);
        var chart = new Chart("render");
        chart.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(60, 20));
        chart.addSeries(series);
        var buf = new ScreenBuffer(new TerminalSize(60, 20));
        chart.draw(new TextGraphics(buf));
        var seen = new java.util.LinkedHashMap<String, Integer>();
        for (int r = 0; r < 20; r++) {
            for (int c = 0; c < 60; c++) {
                var cell = buf.getCell(c, r);
                if (!" ".equals(cell.character())) {
                    seen.merge(cell.fg().toString(), 1, Integer::sum);
                }
            }
        }
        return String.join("|", seen.keySet());
    }

    @Test
    void lineRendersSegmentInPerPointColor() {
        // three flat-ish points: white base, green override on point 1
        var colors = drawAndGetColors(ChartType.LINE,
            List.of(1.0, 1.0, 2.0),
            List.of(AnsiColor.WHITE, AnsiColor.GREEN, AnsiColor.WHITE));
        assertTrue(colors.contains(AnsiColor.GREEN.toString()),
            "line cells must use the per-point color; got: " + colors);
    }

    @Test
    void barRendersInPerPointColor() {
        var colors = drawAndGetColors(ChartType.BAR,
            List.of(1.0, 2.0),
            List.of(AnsiColor.WHITE, AnsiColor.GREEN));
        assertTrue(colors.contains(AnsiColor.GREEN.toString()),
            "bar cells must use the per-point color; got: " + colors);
    }
}