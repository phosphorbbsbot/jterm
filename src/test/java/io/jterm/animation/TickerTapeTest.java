package io.jterm.animation;

import io.jterm.core.TerminalSize;
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
        // new contract: tick() advances, renderFrame paints the current offset
        tape.tick(System.nanoTime());
        int afterFirst = tape.getScrollOffset();
        assertTrue(afterFirst > 0, "tick() should advance the scroll");

        tape.tick(System.nanoTime());
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
        tape.tick(System.nanoTime());
        tape.renderFrame(new TextGraphics(buffer1), size);

        var buffer2 = new ScreenBuffer(size);
        tape.tick(System.nanoTime());
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
