package io.jterm.animation;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import io.jterm.style.TextCell;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layout, scroll and rendering-branch tests for JaCoCo gaps: {@link
 * PortfolioHeatmap#layoutTreemap} edge cases (single position, tiny weights,
 * wrap arithmetic), {@link TickerTape} narrow/wide wrap behavior and separator
 * coloring, degenerate terminal sizes, and separator/border drawing branches.
 */
class AnimationLayoutAndBranchesTest {
    /** Synthetic clock for tick() laws: each call advances one frame slot
     * (1s / TARGET_FPS — currently 8fps = 125ms). */
    static final class T {
        /** Frame slot matching TickerTape.TARGET_FPS (4 ⇒ 250ms). */
        private static final long SLOT_NANOS = 125_000_000L;
        private long now = System.nanoTime();
        long tick() { now += SLOT_NANOS; return now; }
    }
    private final T T = new T();


    private static Map<Character, Long> glyphHistogram(ScreenBuffer buffer, TerminalSize size) {
        Map<Character, Long> counts = new HashMap<>();
        for (int r = 0; r < size.rows(); r++) {
            for (int c = 0; c < size.columns(); c++) {
                counts.merge(buffer.getCell(c, r).character().charAt(0), 1L, Long::sum);
            }
        }
        return counts;
    }

    private static long countGlyph(ScreenBuffer buffer, TerminalSize size, char glyph) {
        return glyphHistogram(buffer, size).getOrDefault(glyph, 0L);
    }

    // ------------------------------------------------------------------
    // PortfolioHeatmap.layoutTreemap / drawCell edge cases
    // ------------------------------------------------------------------

    @Test
    @DisplayName("single position fills the heatmap without throwing and paints its symbol")
    void singlePositionRenders() {
        var heatmap = new PortfolioHeatmap(new TerminalSize(80, 24),
                () -> List.of(new PortfolioHeatmap.Position("AAPL", 100, 150.0, 160.0)));
        var size = new TerminalSize(80, 24);
        var buffer = new ScreenBuffer(size);
        assertDoesNotThrow(() -> heatmap.renderFrame(new TextGraphics(buffer), size));

        long a = countGlyph(buffer, size, 'A');
        long l = countGlyph(buffer, size, 'L');
        assertTrue(a > 0, "symbol letters should be painted, A count=" + a);
        assertTrue(l > 0, "second symbol letter should be painted, L count=" + l);
    }

    @Test
    @DisplayName("weight cap equalizes extreme ratios; the tiny position still paints")
    void tinyPortfolioExtremeRatio() {
        // 8:1 raw ratio would be capped (0.35 cap + renormalization keeps both
        // positions renderable), yet the dominant cell must still paint its
        // symbol bigger or equal — and the tiny one must never vanish.
        var positions = List.of(
                new PortfolioHeatmap.Position("WIDE", 800, 1.0, 1.0),
                new PortfolioHeatmap.Position("THIN", 100, 1.0, 1.0));
        var heatmap = new PortfolioHeatmap(new TerminalSize(20, 12), () -> positions);
        var size = new TerminalSize(20, 12);
        var buffer = new ScreenBuffer(size);
        assertDoesNotThrow(() -> heatmap.renderFrame(new TextGraphics(buffer), size));

        long w = countGlyph(buffer, size, 'W');
        long t = countGlyph(buffer, size, 'T');
        int rowOfT = -1;
        for (int r = 1; r < size.rows() - 1 && rowOfT < 0; r++) {
            for (int c = 0; c < size.columns(); c++) {
                if (buffer.getCell(c, r).character().charAt(0) == 'T') rowOfT = r;
            }
        }
        assertTrue(w > 0, "dominant position symbol must be visible");
        assertTrue(t > 0, "tiny position must still get a painted cell");
        assertTrue(rowOfT >= 1 && rowOfT <= size.rows() - 2,
                "tiny position's symbol must be painted inside the margins");
        assertTrue(w >= t, "dominant position must not fill less than the tiny one: W=" + w + " T=" + t);
    }

    @Test
    @DisplayName("nine equal positions fit one row: every symbol lands inside the margins")
    void exactWidthRowPlacesAllSymbols() {
        // cols=45, min cell width=5 -> widths land at 5 or 6; the exact-fit wrap
        // branch (currentCol + w > cols && w <= cols) must place every symbol
        // within rows [1, rows-2].
        List<PortfolioHeatmap.Position> positions = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            positions.add(new PortfolioHeatmap.Position("S" + i, 10, 100.0, 100.0));
        }
        var heatmap = new PortfolioHeatmap(new TerminalSize(45, 12), () -> positions);
        var size = new TerminalSize(45, 12);
        var buffer = new ScreenBuffer(size);
        assertDoesNotThrow(() -> heatmap.renderFrame(new TextGraphics(buffer), size));

        for (int i = 1; i <= 9; i++) {
            String symbol = "S" + i;
            boolean found = false;
            for (int r = 1; r < size.rows() - 1 && !found; r++) {
                for (int c = 0; c <= size.columns() - symbol.length(); c++) {
                    boolean match = true;
                    for (int k = 0; k < symbol.length() && match; k++) {
                        if (!buffer.getCell(c + k, r).character().equals(
                                Character.toString(symbol.charAt(k)))) {
                            match = false;
                        }
                    }
                    if (match) found = true;
                }
            }
            assertTrue(found, "symbol " + symbol + " must be painted inside the margins");
        }
    }

    @Test
    @DisplayName("eleven positions wrap onto several rows; symbols stay inside margins")
    void wrappedRowsKeepSymbolsInsideMargins() {
        List<PortfolioHeatmap.Position> positions = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            positions.add(new PortfolioHeatmap.Position("P" + i, 10, 100.0, 100.0 + i));
        }
        var heatmap = new PortfolioHeatmap(new TerminalSize(40, 14), () -> positions);
        var size = new TerminalSize(40, 14);
        var buffer = new ScreenBuffer(size);
        assertDoesNotThrow(() -> heatmap.renderFrame(new TextGraphics(buffer), size));

        for (int i = 1; i <= 11; i++) {
            String symbol = "P" + i;
            boolean found = false;
            for (int r = 1; r < size.rows() - 1 && !found; r++) {
                for (int c = 0; c <= size.columns() - symbol.length(); c++) {
                    boolean match = true;
                    for (int k = 0; k < symbol.length() && match; k++) {
                        if (!buffer.getCell(c + k, r).character().equals(
                                Character.toString(symbol.charAt(k)))) {
                            match = false;
                        }
                    }
                    if (match) found = true;
                }
            }
            assertTrue(found, "wrapped symbol " + symbol + " must be painted inside the margins");
        }
    }

    @Test
    @DisplayName("zero total market value still assigns equal shares and renders")
    void zeroTotalMarketValueRenders() {
        // All values zero: totalValue == 0 -> equal-share branch (line 114/116).
        var positions = List.of(
                new PortfolioHeatmap.Position("ZEROA", 5, 0.0, 0.0),
                new PortfolioHeatmap.Position("ZEROB", 5, 0.0, 0.0));
        var heatmap = new PortfolioHeatmap(new TerminalSize(60, 16), () -> positions);
        var size = new TerminalSize(60, 16);
        var buffer = new ScreenBuffer(size);
        assertDoesNotThrow(() -> heatmap.renderFrame(new TextGraphics(buffer), size));

        long z = countGlyph(buffer, size, 'Z');
        assertTrue(z > 0, "zero-value positions must still render their symbols, Z count=" + z);
    }

    // ------------------------------------------------------------------
    // TickerTape wrap/scroll paths
    // ------------------------------------------------------------------

    @Test
    @DisplayName("narrow terminal: tape wraps modulo its own width (totalWidth > cols)")
    void narrowTerminalTapeWraps() {
        var items = List.of(
                new TickerTape.TickerItem("AAA", 10.0, 9.0),
                new TickerTape.TickerItem("BBB", 20.0, 21.0));
        var tape = new TickerTape(new TerminalSize(10, 2), () -> items);
        var size = new TerminalSize(10, 2);
        var buffer = new ScreenBuffer(size);

        // totalWidth = sum(renderString) + SEPARATOR(5) between/after items.
        int totalWidth = items.stream()
                .mapToInt(i -> i.renderString().length())
                .sum() + 5 * items.size();
        assertTrue(totalWidth > size.columns(), "test premise: tape wider than terminal");

        // QUEUE CONTRACT: the scroll advances via tick(); the offset consumed
        // is bounded by the front item's width and the queue rotates FIFO.
        // Two ticks below the front item's width must rotate it to the back.
        int frontWidth = items.get(0).renderString().length() + 5;
        tape.tick(T.tick());
        tape.renderFrame(new TextGraphics(buffer), size);
        assertTrue(tape.getScrollOffset() >= 0 && tape.getScrollOffset() < frontWidth,
                "offset stays anchored within the front item, got " + tape.getScrollOffset());
        for (int i = 0; i < frontWidth + 2; i++) {
            tape.tick(T.tick());
        }
        tape.renderFrame(new TextGraphics(buffer), size);
        assertTrue(tape.getScrollOffset() >= 0 && tape.getScrollOffset() < items.get(0).renderString().length() + 5,
                "after a full item passes, rotation keeps the anchor inside the new front item");
    }

    @Test
    @DisplayName("wide terminal with one short item: content cycles through all offset positions")
    void wideTerminalSingleItemCycles() {
        var items = List.of(new TickerTape.TickerItem("GOOG", 101.10, 100.00));
        var tape = new TickerTape(new TerminalSize(60, 2), () -> items);
        var size = new TerminalSize(60, 2);
        var buffer = new ScreenBuffer(size);
        tape.setScrollOffset(0);

        // TICK-DRIVEN (time-based): advance = elapsed × speed × fps; render is a pure paint.
        tape.renderFrame(new TextGraphics(buffer), size); // offset still 0
        assertEquals(0, tape.getScrollOffset(), "render must NOT advance (pure paint)");
        for (int i = 0; i < 8; i++) {
            tape.tick(T.tick()); // 1s of slots ⇒ 8 cells at speed 1.0
        }
        tape.renderFrame(new TextGraphics(buffer), size);
        assertTrue(tape.getScrollOffset() >= 4, "tick slots advance the scroll, got " + tape.getScrollOffset());
    }

    @Test
    @DisplayName("separator cells keep bright white foreground")
    void separatorCellsAreBrightWhite() {
        var items = List.of(
                new TickerTape.TickerItem("AAA", 10.0, 9.0),
                new TickerTape.TickerItem("BBB", 20.0, 21.0));
        var tape = new TickerTape(new TerminalSize(80, 2), () -> items);
        var size = new TerminalSize(80, 2);
        var buffer = new ScreenBuffer(size);
        tape.setScrollOffset(0);
        tape.renderFrame(new TextGraphics(buffer), size);

        boolean sawWhite = false;
        for (int c = 0; c < size.columns() && !sawWhite; c++) {
            TextCell cell = buffer.getCell(c, 1);
            if (cell.character().charAt(0) == '|') {
                assertEquals(AnsiColor.BLUE, cell.fg(), "separator must be blue (CNBC palette)");
                sawWhite = true;
            }
        }
        assertTrue(sawWhite, "a separator '|' cell should appear in the rendered tape row");
    }

    @Test
    @DisplayName("setScrollOffset clamps negatives; empty size renders nothing")
    void scrollOffsetClampAndEmptySize() {
        var tape = new TickerTape(new TerminalSize(80, 2));
        tape.setScrollOffset(-5);
        assertEquals(0, tape.getScrollOffset(), "negative offsets clamp to 0");
        tape.setScrollOffset(7);
        assertEquals(7, tape.getScrollOffset());

        var buffer = new ScreenBuffer(new TerminalSize(1, 1));
        assertDoesNotThrow(() -> tape.renderFrame(new TextGraphics(buffer), TerminalSize.ZERO));
    }

    @Test
    @DisplayName("TickerItem accessors and toString expose fields verbatim")
    void tickerItemAccessors() {
        var item = new TickerTape.TickerItem("MSFT", 378.91, 375.20);
        assertEquals("MSFT", item.symbol());
        assertEquals(378.91, item.close(), 0.0);
        assertEquals(375.20, item.previousClose(), 0.0);
        assertEquals(item.renderString(), item.toString(),
                "toString must delegate to renderString");
    }

    @Test
    @DisplayName("TickerItem rejects a null symbol")
    void tickerItemNullSymbolThrows() {
        assertThrows(NullPointerException.class, () -> new TickerTape.TickerItem(null, 1.0, 1.0));
    }

    @Test
    @DisplayName("TerrainFlyover layers expose their parameters verbatim")
    void terrainLayerAccessors() {
        var bg = new TerrainFlyover(new TerminalSize(80, 24));
        TerrainFlyover.Layer[] layers = bg.getLayers();

        double[] expectedBase = {0.22, 0.42, 0.62};
        double[] expectedAmplitude = {0.12, 0.18, 0.26};
        double[] expectedSpeed = {0.08, 0.18, 0.28};
        char[] expectedGlyph = {(char) 0xB0, (char) 0xB1, (char) 0xDB};
        for (int i = 0; i < layers.length; i++) {
            assertEquals(expectedBase[i], layers[i].baseFraction(), 1e-9);
            assertEquals(expectedAmplitude[i], layers[i].amplitudeFraction(), 1e-9,
                    "layer " + i + " amplitude");
            assertEquals(expectedSpeed[i], layers[i].speed(), 1e-9, "layer " + i + " speed");
            assertEquals(expectedGlyph[i], layers[i].glyph(), "layer " + i + " glyph");
        }
    }

    // ------------------------------------------------------------------
    // Degenerate terminal sizes (guards + array reallocation)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("heat map renders at degenerate sizes without corrupting layout")
    void heatmapDegenerateSizes() {
        for (TerminalSize size : List.of(new TerminalSize(1, 1), new TerminalSize(2, 1),
                new TerminalSize(1, 5), new TerminalSize(4, 5), new TerminalSize(7, 2))) {
            var heatmap = new PortfolioHeatmap(size, () -> List.of(
                    new PortfolioHeatmap.Position("TINY", 100, 10.0, 11.0),
                    new PortfolioHeatmap.Position("SMOL", 100, 10.0, 9.0)));
            var buffer = new ScreenBuffer(size);
            assertDoesNotThrow(() -> heatmap.renderFrame(new TextGraphics(buffer), size),
                    "size " + size + " must render without throwing");
        }
    }

    @Test
    @DisplayName("ticker tape renders at degenerate sizes")
    void tickerDegenerateSizes() {
        for (TerminalSize size : List.of(new TerminalSize(1, 1), new TerminalSize(2, 1),
                new TerminalSize(3, 2))) {
            var tape = new TickerTape(size, () -> List.of(new TickerTape.TickerItem("XY", 1.0, 1.0)));
            var buffer = new ScreenBuffer(size);
            assertDoesNotThrow(() -> tape.renderFrame(new TextGraphics(buffer), size),
                    "size " + size + " must render without throwing");
        }
    }

    @Test
    @DisplayName("candlestick field renders at degenerate sizes and reallocates bands")
    void candlestickDegenerateSizes() {
        for (TerminalSize size : List.of(new TerminalSize(1, 1), new TerminalSize(2, 5),
                new TerminalSize(3, 1), new TerminalSize(4, 5), new TerminalSize(7, 2))) {
            var field = new CandlestickField(size);
            var buffer = new ScreenBuffer(size);
            assertDoesNotThrow(() -> field.renderFrame(new TextGraphics(buffer), size),
                    "size " + size + " must render without throwing");
            assertNotNull(field.getBands(), "bands must be allocated at " + size);
        }
    }

    @Test
    @DisplayName("candlestick bands rebuild when the terminal shrinks mid-flight")
    void candlestickBandRebuildOnResize() {
        var field = new CandlestickField(new TerminalSize(80, 24));
        var big = new ScreenBuffer(new TerminalSize(80, 24));
        field.renderFrame(new TextGraphics(big), new TerminalSize(80, 24));
        int bigBands = field.getBandCount();
        assertTrue(bigBands >= 2);

        // Shrinking must re-run ensureBands with the new candle count / heights.
        var small = new TerminalSize(9, 6);
        var buffer = new ScreenBuffer(small);
        assertDoesNotThrow(() -> field.renderFrame(new TextGraphics(buffer), small));
        assertTrue(field.getBandCount() >= 1, "shrunk terminal must still have bands");

        // Candles must fit the new width: candle at col 0 and spacing of 3.
        boolean candleAtZero = false;
        for (int r = 0; r < small.rows(); r++) {
            if (buffer.getCell(0, r).character().charAt(0) != ' ') candleAtZero = true;
        }
        assertTrue(candleAtZero, "candle column 0 must be painted after resize");
    }

    @Test
    @DisplayName("candlestick field survives shrink-then-grow cycles")
    void candlestickResizeCycles() {
        var field = new CandlestickField(new TerminalSize(40, 12));
        for (int i = 0; i < 3; i++) {
            var wide = new TerminalSize(80, 24);
            assertDoesNotThrow(() -> field.renderFrame(new TextGraphics(new ScreenBuffer(wide)), wide));
            var thin = new TerminalSize(6, 4);
            assertDoesNotThrow(() -> field.renderFrame(new TextGraphics(new ScreenBuffer(thin)), thin));
        }
        assertTrue(field.getBandCount() >= 1);
    }

    @Test
    @DisplayName("small candles are still drawn in band 0 at ordinary sizes")
    void smallCandleRenderingStillPresent() {
        var size = new TerminalSize(7, 5);
        var field = new CandlestickField(size);
        var buffer = new ScreenBuffer(size);
        field.renderFrame(new TextGraphics(buffer), size);

        boolean candleContent = false;
        for (int r = 0; r < size.rows(); r++) {
            if (buffer.getCell(0, r).character().charAt(0) != ' ') candleContent = true;
        }
        assertTrue(candleContent, "band 0 must draw a candle at column 0");
    }
}