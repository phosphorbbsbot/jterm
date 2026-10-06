package io.jterm.widget.chart;

import io.jterm.style.AnsiColor;
import io.jterm.style.Color;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-point color palette on {@link ChartSeries}: an optional parallel list
 * of per-point color overrides; base color stays the legend/segment default.
 */
class ChartSeriesPointColorsTest {

    @Test
    void pointColorsDefaultToNullOnExistingConstructors() {
        var s = new ChartSeries("AAPL", List.of(1.0, 2.0), AnsiColor.GREEN);
        assertNull(s.pointColors(), "4-arg + 3-arg ctors keep null pointColors");
    }

    @Test
    void pointColorsStoredUnmodifiable() {
        var s = new ChartSeries("AAPL", List.of(1.0, 2.0), ChartType.LINE, AnsiColor.GREEN,
            java.util.Arrays.asList(AnsiColor.RED, (Color) null));
        assertEquals(2, s.pointColors().size());
        assertThrows(UnsupportedOperationException.class,
            () -> s.pointColors().add(AnsiColor.BLUE));
        assertNull(s.pointColors().get(1), "null in the palette = use base color at that point");
    }

    @Test
    void nullPaletteStaysNull() {
        var s = new ChartSeries("AAPL", List.of(1.0), ChartType.LINE, AnsiColor.GREEN, null);
        assertNull(s.pointColors());
        assertEquals(AnsiColor.GREEN, s.colorAt(0));
    }

    @Test
    void paletteLongerThanValuesThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> new ChartSeries("AAPL", List.of(1.0), ChartType.LINE, AnsiColor.GREEN,
                List.of(AnsiColor.RED, AnsiColor.BLUE)),
            "palette may be shorter (trailing base) but never longer than values");
    }

    @Test
    void colorAtReturnsOverrideOrBase() {
        var s = new ChartSeries("AAPL", List.of(1.0, 2.0, 3.0), ChartType.LINE, AnsiColor.GREEN,
            java.util.Arrays.asList(AnsiColor.RED, (Color) null));
        assertEquals(AnsiColor.RED, s.colorAt(0), "override at 0");
        assertEquals(AnsiColor.GREEN, s.colorAt(1), "null palette entry → base color");
        assertEquals(AnsiColor.GREEN, s.colorAt(2), "beyond palette → base color");
    }

    @Test
    void baseColorRemainsTheLegendColor() {
        var s = new ChartSeries("AAPL", List.of(1.0), ChartType.LINE, AnsiColor.GREEN,
            List.of(AnsiColor.RED));
        assertEquals(AnsiColor.GREEN, s.color(), "base color untouched for legend");
    }
}