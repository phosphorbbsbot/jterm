package io.jterm.screen;

import io.jterm.core.ColorDepth;
import io.jterm.style.AnsiColor;
import io.jterm.style.Color;
import io.jterm.style.IndexedColor;
import io.jterm.style.RgbColor;
import io.jterm.screen.DefaultScreen.SgrStateTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GC law (Joe, 2026-11-21): the RGB→palette degradation memoizes per tier —
 * the nearest-palette search + fresh IndexedColor ran PER CELL on
 * non-truecolor clients. Keys are by value (RgbColor record equals), so
 * equal-but-distinct color instances share one mapped object.
 */
class DegradeRgbCacheLawTest {

    @Test
    void repeatedMappingReturnsTheSameInstancePerTier() {
        var rgb = new RgbColor(200, 91, 12);
        var c256a = SgrStateTracker.degradeForDepth(rgb, ColorDepth.C256);
        var c256b = SgrStateTracker.degradeForDepth(rgb, ColorDepth.C256);
        assertSame(c256a, c256b, "C256 mapping is memoized: one search, one object");
        assertInstanceOf(IndexedColor.class, c256a);

        var a16a = SgrStateTracker.degradeForDepth(rgb, ColorDepth.ANSI16);
        var a16b = SgrStateTracker.degradeForDepth(rgb, ColorDepth.ANSI16);
        assertSame(a16a, a16b, "ANSI16 mapping is memoized");
        assertInstanceOf(AnsiColor.class, a16a);
    }

    @Test
    void equalValueDistinctInstancesShareTheMemoEntry() {
        var a = new RgbColor(10, 200, 30);
        var b = new RgbColor(10, 200, 30); // equal by value, distinct instance
        var mappedA = SgrStateTracker.degradeForDepth(a, ColorDepth.C256);
        var mappedB = SgrStateTracker.degradeForDepth(b, ColorDepth.C256);
        assertSame(mappedA, mappedB,
                "the memo keys on VALUE — equal colors dedupe to one mapped object");
    }

    @Test
    void truecolorShortCircuitsToIdentity() {
        var rgb = new RgbColor(1, 2, 3);
        assertSame(rgb, SgrStateTracker.degradeForDepth(rgb, ColorDepth.TRUECOLOR),
                "truecolor tier never degrades");
    }

    @Test
    void nullAndNullsStayNullSafe() {
        assertNull(SgrStateTracker.degradeForDepth(null, ColorDepth.C256),
                "API-legal null colors pass through on every tier");
        assertSame(AnsiColor.DEFAULT, SgrStateTracker.degradeForDepth(AnsiColor.DEFAULT, ColorDepth.ANSI16),
                "non-RgbColor colors pass through untouched");
    }
}
