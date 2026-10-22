package io.jterm.animation;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire-fit laws (2026-10-22): real terminals auto-wrap when a character is
 * written to the last column of the bottom row, so the tape must never paint
 * column cols-1. Scroll advance must be configurable so hosts can halve it.
 */
class TickerTapeWireFitTest {

    private static TickerTape liveTape() {
        var tape = new TickerTape(new TerminalSize(80, 1), () -> List.of(
                new TickerTape.TickerItem("AAPL", 100.0, 90.0),
                new TickerTape.TickerItem("MSFT", 200.0, 190.0)));
        tape.setDemoFallback(false);
        return tape;
    }

    @Test
    @DisplayName("never paints the last column (terminals wrap on bottom-right writes)")
    void neverPaintsTheLastColumn() {
        var tape = liveTape();
        var buffer = new ScreenBuffer(new TerminalSize(80, 1));
        tape.renderFrame(new TextGraphics(buffer), new TerminalSize(80, 1));
        for (int scroll = 0; scroll < 300; scroll++) {
            // only the LAST column auto-wraps on real terminals
            var cell = buffer.getCell(79, 0);
            assertEquals(" ", cell.character(),
                    "column 79 painted after " + scroll + " scrolls — would wrap on real terminals");
            tape.renderFrame(new TextGraphics(buffer), new TerminalSize(80, 1));
        }
    }

    @Test
    @DisplayName("setScrollSpeed halves the advance")
    void scrollSpeedIsConfigurable() {
        var fast = liveTape();
        var slow = liveTape();
        slow.setScrollSpeed(0.5); // half the default 1.0 cell/frame
        // drive both N frames: slow must have advanced roughly half as far.
        // Measure via internal behaviour: after 10 frames the slow tape's
        // content start index differs from fast's. Compare rendered content
        // directly: fast starts cycling per frame, slow every other frame.
        var fastBuf = new ScreenBuffer(new TerminalSize(80, 1));
        var slowBuf = new ScreenBuffer(new TerminalSize(80, 1));
        for (int i = 0; i < 10; i++) {
            fast.renderFrame(new TextGraphics(fastBuf), new TerminalSize(80, 1));
            slow.renderFrame(new TextGraphics(slowBuf), new TerminalSize(80, 1));
        }
        String fastRender = rowText(fastBuf, 0);
        String slowRender = rowText(slowBuf, 0);
        assertNotEquals(fastRender, slowRender, "speeds identical — setScrollSpeed had no effect");
        // slow must lag fast by exactly 5 cell-steps after 10 frames (half speed)
        var mid = liveTape();
        var midBuf = new ScreenBuffer(new TerminalSize(80, 1));
        for (int i = 0; i < 5; i++) {
            mid.renderFrame(new TextGraphics(midBuf), new TerminalSize(80, 1));
        }
        assertEquals(rowText(midBuf, 0), slowRender,
                "slow tape after 10 frames should equal default tape after 5");
    }

    private static String rowText(ScreenBuffer buf, int row) {
        var sb = new StringBuilder();
        for (int c = 0; c < 80; c++) {
            sb.append(buf.getCell(c, row).character());
        }
        return sb.toString();
    }
}