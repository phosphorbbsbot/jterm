package io.jterm.animation;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TickerTape}: AnimatedBackground contract, scrolling
 * behavior, green/red coloring, fallback data rendering, and edge cases.
 */
class TickerTapeTest {
    /** Synthetic clock for tick() laws: each call advances one frame slot
     * (1s / TARGET_FPS — currently 8fps = 125ms). */
    static final class T {
        /** Frame slot matching TickerTape.TARGET_FPS (4 ⇒ 250ms). */
        private static final long SLOT_NANOS = 125_000_000L;
        private long now = System.nanoTime();
        long tick() { now += SLOT_NANOS; return now; }
    }
    private final T T = new T();


    @Test
    @DisplayName("implements AnimatedBackground contract")
    void implementsAnimatedBackground() {
        var tape = new TickerTape(new TerminalSize(80, 24));
        assertTrue(tape instanceof AnimatedBackground);
        assertEquals(8, tape.targetFps());
        tape.start();
        assertTrue(tape.isRunning());
        tape.stop();
        assertFalse(tape.isRunning());
    }

    @Test
    @DisplayName("the bar advances visually through the separator pass — no dead frames at item exit")
    void separatorCrossingNeverFreezes() {
        // Joe 2026-10-22: 'as a symbol disappears, it pauses ~1.5s, then jumps
        // to catch up' — at 4 cells/s, a 3-char separator crossing that fails
        // to crop leaves the bar STATIC for its whole pass, then the rotation
        // snaps it. The law: every frame of the crossing renders DIFFERENT
        // row content, and the rotation frame continues the motion.
        var item = new TickerTape.TickerItem("ABCD", 100.00, 90.00);
        var tape = new TickerTape(new TerminalSize(40, 3), () -> List.of(item));
        tape.setScrollSpeed(1.0); // 1 cell/tick: every frame must move
        tape.tick(T.tick());
        var screen = new ScreenBuffer(new TerminalSize(40, 3));
        var g = new TextGraphics(screen);
        List<String> frames = new java.util.ArrayList<>();
        // ~24 ticks spans the full item text (18 chars) + separator (3)
        for (int i = 0; i < 24; i++) {
            tape.renderFrame(g, new TerminalSize(40, 3));
            var sb = new StringBuilder();
            for (int c = 0; c < 40; c++) {
                sb.append(screen.getCell(c, 2).character());
            }
            frames.add(sb.toString());
            tape.tick(T.tick());
        }
        for (int i = 1; i < frames.size(); i++) {
            assertNotEquals(frames.get(i - 1), frames.get(i),
                    "consecutive frames identical → the bar froze; frame " + i
                            + " and " + (i - 1) + " both:\"" + frames.get(i) + "\"");
        }
    }

    @Test
    @DisplayName("default supplier provides fallback ticker items")
    void defaultSupplierProvidesFallback() {
        var tape = new TickerTape(new TerminalSize(80, 24));
        assertFalse(tape.getItems().isEmpty(), "expected fallback ticker items");
    }

    @Test
    @DisplayName("custom supplier overrides fallback data")
    void customSupplierOverridesFallback() {
        var item = new TickerTape.TickerItem("TEST", 100.0, 95.0);
        var tape = new TickerTape(new TerminalSize(80, 24), () -> List.of(item));
        assertEquals(List.of(item), tape.getItems());
    }

    @Test
    @DisplayName("renderFrame clears background to black")
    void clearsBackgroundToBlack() {
        var tape = new TickerTape(new TerminalSize(80, 24));
        var size = new TerminalSize(80, 24);
        var buffer = new ScreenBuffer(size);
        tape.renderFrame(new TextGraphics(buffer), size);
        assertEquals(AnsiColor.BRIGHT_WHITE, buffer.getCell(0, 0).bg());
        assertEquals(AnsiColor.BRIGHT_WHITE, buffer.getCell(79, 23).bg());
    }

    @Test
    @DisplayName("renderFrame produces non-blank output")
    void rendersNonBlankOutput() {
        var tape = new TickerTape(new TerminalSize(80, 24));
        var size = new TerminalSize(80, 24);
        var buffer = new ScreenBuffer(size);
        tape.renderFrame(new TextGraphics(buffer), size);

        int nonBlank = 0;
        for (int r = 0; r < size.rows(); r++) {
            for (int c = 0; c < size.columns(); c++) {
                if (buffer.getCell(c, r).character().charAt(0) != ' ') {
                    nonBlank++;
                }
            }
        }
        assertTrue(nonBlank > 0, "expected non-blank rendered output");
    }

    @Test
    @DisplayName("scroll offset advances each frame")
    void scrollOffsetAdvances() {
        var tape = new TickerTape(new TerminalSize(80, 24));
        var size = new TerminalSize(80, 24);
        tape.setScrollOffset(0);
        var buffer = new ScreenBuffer(size);
        // new contract: tick() advances (time-based), renderFrame paints
        for (int i = 0; i < 4; i++) {
            tape.tick(T.tick());
        }
        int afterFirst = tape.getScrollOffset();
        assertTrue(afterFirst > 0, "tick() should advance the scroll, got " + afterFirst);

        tape.tick(T.tick());
        int afterSecond = tape.getScrollOffset();
        assertTrue(afterSecond > afterFirst, "tick() should keep advancing");

        int beforePaint = tape.getScrollOffset();
        tape.renderFrame(new TextGraphics(buffer), size);
        assertEquals(beforePaint, tape.getScrollOffset(),
                "renderFrame must be a pure paint (no advance)");
    }

    @Test
    @DisplayName("two consecutive frames differ")
    void framesDiffer() {
        var tape = new TickerTape(new TerminalSize(80, 24));
        var size = new TerminalSize(80, 24);
        tape.setScrollOffset(0);
        var buffer1 = new ScreenBuffer(size);
        tape.tick(T.tick());
        tape.renderFrame(new TextGraphics(buffer1), size);

        var buffer2 = new ScreenBuffer(size);
        tape.tick(T.tick());
        tape.renderFrame(new TextGraphics(buffer2), size);

        boolean anyDifferent = false;
        for (int r = 0; r < size.rows() && !anyDifferent; r++) {
            for (int c = 0; c < size.columns(); c++) {
                if (!buffer1.getCell(c, r).equals(buffer2.getCell(c, r))) {
                    anyDifferent = true;
                    break;
                }
            }
        }
        assertTrue(anyDifferent, "expected consecutive frames to differ");
    }

    @Test
    @DisplayName("up tickers rendered green, down tickers rendered red")
    void colorsUpGreenDownRed() {
        var items = List.of(
                new TickerTape.TickerItem("UP", 110.0, 100.0),
                new TickerTape.TickerItem("DOWN", 90.0, 100.0)
        );
        var tape = new TickerTape(new TerminalSize(80, 24), () -> items);
        var size = new TerminalSize(80, 24);
        var buffer = new ScreenBuffer(size);
        tape.setScrollOffset(0);
        tape.renderFrame(new TextGraphics(buffer), size);

        Set<AnsiColor> found = new HashSet<>();
        for (int r = 0; r < size.rows(); r++) {
            for (int c = 0; c < size.columns(); c++) {
                found.add((AnsiColor) buffer.getCell(c, r).fg());
            }
        }
        assertTrue(found.contains(AnsiColor.BRIGHT_GREEN), "expected green for up ticker");
        assertTrue(found.contains(AnsiColor.BRIGHT_RED), "expected red for down ticker");
    }

    @Test
    @DisplayName("renderFrame does not throw on empty size")
    void emptySizeDoesNotCrash() {
        var tape = new TickerTape(new TerminalSize(0, 0));
        var buffer = new ScreenBuffer(new TerminalSize(0, 0));
        assertDoesNotThrow(() -> tape.renderFrame(new TextGraphics(buffer), new TerminalSize(0, 0)));
    }

    @Test
    @DisplayName("small terminal sizes render without throwing")
    void smallTerminalSizes() {
        var tape = new TickerTape(new TerminalSize(10, 3));
        var size = new TerminalSize(10, 3);
        var buffer = new ScreenBuffer(size);
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 10; i++) {
                tape.renderFrame(new TextGraphics(buffer), size);
            }
        });
    }

    @Test
    @DisplayName("onResize stores the new size")
    void onResizeStoresSize() {
        var tape = new TickerTape(new TerminalSize(80, 24));
        var size = new TerminalSize(100, 40);
        tape.onResize(size);
        assertEquals(size, tape.lastSize());
    }

    @Test
    @DisplayName("ticker item change percentage is computed correctly")
    void changePercentage() {
        var up = new TickerTape.TickerItem("UP", 110.0, 100.0);
        assertEquals(10.0, up.change(), 0.001);

        var down = new TickerTape.TickerItem("DOWN", 90.0, 100.0);
        assertEquals(-10.0, down.change(), 0.001);

        var flat = new TickerTape.TickerItem("FLAT", 100.0, 100.0);
        assertEquals(0.0, flat.change(), 0.001);
    }

    @Test
    @DisplayName("ticker item render string contains symbol, price, and percent")
    void renderStringContainsFields() {
        var item = new TickerTape.TickerItem("SPY", 500.0, 495.0);
        String rendered = item.renderString();
        assertTrue(rendered.contains("SPY"), "render string should contain symbol");
        assertTrue(rendered.contains("500.00"), "render string should contain price");
        assertTrue(rendered.contains("1.01%"), "render string should contain percent change");
    }

    @Test
    @DisplayName("CNBC palette: white bar, blue symbol, green/red price by sign")
    void cnbcPalette() {
        var tape = new TickerTape(new TerminalSize(80, 1), () -> List.of(
            new TickerTape.TickerItem("AAPL", 100.0, 90.0)));  // +11% → green price
        tape.setDemoFallback(false);
        var buffer = new ScreenBuffer(new TerminalSize(80, 1));
        tape.renderFrame(new TextGraphics(buffer), new TerminalSize(80, 1));
        assertEquals(AnsiColor.BLUE, buffer.getCell(0, 0).fg(), "symbol must be blue");
        assertEquals(AnsiColor.BRIGHT_GREEN, buffer.getCell(5, 0).fg(), "price must be green when up");
        boolean sawWhiteBg = false;
        for (int c = 0; c < 80; c++) {
            if (buffer.getCell(c, 0).bg() == AnsiColor.BRIGHT_WHITE) { sawWhiteBg = true; break; }
        }
        assertTrue(sawWhiteBg, "bar background must be white");
        // down tape
        var down = new TickerTape(new TerminalSize(80, 1), () -> List.of(
            new TickerTape.TickerItem("AAPL", 90.0, 100.0)));  // -10% → red price
        down.setDemoFallback(false);
        var b2 = new ScreenBuffer(new TerminalSize(80, 1));
        down.renderFrame(new TextGraphics(b2), new TerminalSize(80, 1));
        assertEquals(AnsiColor.BLUE, b2.getCell(0, 0).fg());
        assertEquals(AnsiColor.BRIGHT_RED, b2.getCell(5, 0).fg(), "price must be red when down");
    }
}
