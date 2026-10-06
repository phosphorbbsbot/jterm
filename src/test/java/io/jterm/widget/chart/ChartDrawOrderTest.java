package io.jterm.widget.chart;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Later series draw over earlier ones (price on top of its regression). */
class ChartDrawOrderTest {

    @Test
    void laterSeriesOwnCollidingCells() {
        var a = new ChartSeries("a", List.of(1.0, 2.0, 3.0), ChartType.LINE, AnsiColor.GREEN);
        var b = new ChartSeries("b", List.of(1.0, 2.0, 3.0), ChartType.LINE, AnsiColor.RED);
        var chart = new Chart("order");
        chart.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(40, 12));
        chart.addSeries(a);
        chart.addSeries(b);
        var buf = new ScreenBuffer(new TerminalSize(40, 12));
        chart.draw(new TextGraphics(buf));
        int red = 0;
        int green = 0;
        for (int r = 0; r < 12; r++) {
            for (int c = 0; c < 40; c++) {
                var cell = buf.getCell(c, r);
                if (cell.fg() == AnsiColor.GREEN) {
                    green++;
                } else if (cell.fg() == AnsiColor.RED) {
                    red++;
                }
            }
        }
        assertTrue(red > 0, "second series renders");
        assertTrue(green == 0 || red > green,
            "colliding cells belong to the later (top) series; green=" + green + " red=" + red);
        assertTrue(green <= 2,
            "later series owns colliding cells (≤2 half-cell strays); got " + green);
    }
}