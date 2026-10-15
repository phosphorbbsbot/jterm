package io.jterm.animation;

import io.jterm.core.TerminalSize;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the default data suppliers of {@link TickerTape} and
 * {@link PortfolioHeatmap} against an in-memory H2 database so the JDBC code
 * paths (screener and portfolio schemas) run for real, including their
 * empty-result fallbacks and the no-URL fallback path. The suppliers read the
 * {@code screener.url} / {@code portfolio.url} system properties, so each test
 * installs one and clears it afterwards.
 */
class AnimationDataBuildersTest {

    private static final String URL =
            "jdbc:h2:mem:animationdata;DB_CLOSE_DELAY=-1;NON_KEYWORDS=DATE";

    @BeforeEach
    void installUrl() {
        System.setProperty("screener.url", URL);
        System.setProperty("portfolio.url", URL);
    }

    @AfterEach
    void clearUrl() {
        System.clearProperty("screener.url");
        System.clearProperty("portfolio.url");
    }

    private static void resetDatabase() throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, "", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP ALL OBJECTS");
            // H2 2.x does not auto-create schemas referenced by CREATE TABLE.
            stmt.execute("CREATE SCHEMA IF NOT EXISTS screener");
            stmt.execute("CREATE SCHEMA IF NOT EXISTS portfolio");
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, "", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    // ------------------------------------------------------------------
    // TickerTape default supplier (screener.daily_prices, LAG window fn)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TickerTape default supplier loads latest close and previous close from the screener database")
    void tickerTapeLoadsFromScreenerDatabase() throws Exception {
        resetDatabase();
        execute("CREATE TABLE screener.daily_prices (ticker VARCHAR(10), date TIMESTAMP, close DOUBLE PRECISION)");
        execute("INSERT INTO screener.daily_prices VALUES ('AAA', '2026-10-13 00:00:00', 100.0)");
        execute("INSERT INTO screener.daily_prices VALUES ('AAA', '2026-10-14 00:00:00', 110.0)");
        execute("INSERT INTO screener.daily_prices VALUES ('BBB', '2026-10-13 00:00:00', 50.0)");
        execute("INSERT INTO screener.daily_prices VALUES ('BBB', '2026-10-14 00:00:00', 45.0)");
        // Single date row: no previous close, must be excluded by the query.
        execute("INSERT INTO screener.daily_prices VALUES ('CCC', '2026-10-14 00:00:00', 7.0)");

        var tape = new TickerTape(new TerminalSize(80, 24));
        List<TickerTape.TickerItem> items = tape.getItems();

        assertEquals(2, items.size(), "one item per ticker that has a previous close");
        Set<String> symbols = items.stream().map(TickerTape.TickerItem::symbol).collect(Collectors.toSet());
        assertEquals(Set.of("AAA", "BBB"), symbols);

        TickerTape.TickerItem aaa = items.stream()
                .filter(i -> i.symbol().equals("AAA")).findFirst().orElseThrow();
        assertEquals(110.0, aaa.close(), 0.0001);
        assertEquals(100.0, aaa.previousClose(), 0.0001);
        assertEquals(10.0, aaa.change(), 0.001);
        assertTrue(aaa.renderString().contains("110.00"));

        TickerTape.TickerItem bbb = items.stream()
                .filter(i -> i.symbol().equals("BBB")).findFirst().orElseThrow();
        assertEquals(-10.0, bbb.change(), 0.001, "drop from 50 to 45 is -10%");

        // Sanity: a fresh render frame consumes the DB-loaded items.
        var buffer = new io.jterm.screen.ScreenBuffer(new TerminalSize(80, 24));
        assertDoesNotThrow(() -> tape.renderFrame(new io.jterm.graphics.TextGraphics(buffer), new TerminalSize(80, 24)));
    }

    @Test
    @DisplayName("TickerTape default supplier falls back to sample data when the screener tables are empty")
    void tickerTapeFallsBackWhenScreenerEmpty() throws Exception {
        resetDatabase();
        execute("CREATE TABLE screener.daily_prices (ticker VARCHAR(10), date TIMESTAMP, close DOUBLE PRECISION)");

        var tape = new TickerTape(new TerminalSize(80, 24));
        List<TickerTape.TickerItem> items = tape.getItems();

        assertEquals(8, items.size(), "empty query result must yield the built-in fallback items");
        assertEquals(Set.of("AAPL", "GOOGL", "MSFT", "AMZN", "NVDA", "TSLA", "META", "NFLX"),
                items.stream().map(TickerTape.TickerItem::symbol).collect(Collectors.toSet()));
        // Fallback entries carry both closes, so changes are non-trivial.
        for (TickerTape.TickerItem item : items) {
            assertTrue(item.previousClose() > 0.0, "fallback item needs a previous close: " + item.symbol());
        }
    }

    @Test
    @DisplayName("TickerTape without a configured URL uses the built-in sample items")
    void tickerTapeWithoutUrlUsesSampleItems() {
        System.clearProperty("screener.url"); // no URL at all -> first guard in defaultSupplier

        var tape = new TickerTape(new TerminalSize(80, 24));
        List<TickerTape.TickerItem> items = tape.getItems();

        assertEquals(8, items.size());
        assertTrue(items.stream().anyMatch(i -> i.symbol().equals("AAPL")));
        assertEquals(189.52, items.stream().filter(i -> i.symbol().equals("AAPL"))
                .findFirst().orElseThrow().close(), 0.0001);
    }

    @Test
    @DisplayName("TickerTape change() guards a zero previous close")
    void tickerItemZeroPreviousClose() {
        var item = new TickerTape.TickerItem("ZERO", 5.0, 0.0);
        assertEquals(0.0, item.change(), 0.0);
        assertTrue(item.renderString().contains("0.00%"));
    }

    @Test
    @DisplayName("refreshData restores fallback items when the supplier throws")
    void tickerTapeRefreshDataFallsBackOnSupplierThrow() {
        var items = List.of(new TickerTape.TickerItem("ONE", 1.0, 1.0));
        var tape = new TickerTape(new TerminalSize(80, 24), () -> items);
        assertEquals(1, tape.getItems().size());

        var broken = new TickerTape(new TerminalSize(80, 24), () -> { throw new IllegalStateException("boom"); });
        broken.refreshData();
        assertEquals(8, broken.getItems().size(), "supplier failure must fall back to sample data");
    }

    // ------------------------------------------------------------------
    // PortfolioHeatmap default supplier (portfolio.* / screener fallback)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PortfolioHeatmap default supplier loads active positions with quotes")
    void heatmapLoadsFromPortfolioQuotes() throws Exception {
        resetDatabase();
        execute("CREATE TABLE portfolio.positions (symbol VARCHAR(10), quantity INT, cost_basis DOUBLE PRECISION, status VARCHAR(10))");
        execute("CREATE TABLE portfolio.latest_quotes (ticker VARCHAR(10), close DOUBLE PRECISION)");
        execute("INSERT INTO portfolio.positions VALUES ('AAA', 10, 100.0, 'active')");
        execute("INSERT INTO portfolio.positions VALUES ('BBB', 5, 200.0, 'active')");
        execute("INSERT INTO portfolio.positions VALUES ('ZZZ', 3, 300.0, 'inactive')"); // filtered by status
        execute("INSERT INTO portfolio.positions VALUES ('QQQ', 3, 300.0, 'active')");   // no quote row
        execute("INSERT INTO portfolio.latest_quotes VALUES ('AAA', 110.0)");
        execute("INSERT INTO portfolio.latest_quotes VALUES ('BBB', 220.0)");

        var heatmap = new PortfolioHeatmap(new TerminalSize(80, 24));
        List<PortfolioHeatmap.Position> positions = heatmap.getPositions();

        assertEquals(2, positions.size(), "only active positions with a quote qualify");
        Set<String> symbols = positions.stream().map(PortfolioHeatmap.Position::symbol).collect(Collectors.toSet());
        assertEquals(Set.of("AAA", "BBB"), symbols);

        PortfolioHeatmap.Position aaa = positions.stream()
                .filter(p -> p.symbol().equals("AAA")).findFirst().orElseThrow();
        assertEquals(10, aaa.quantity());
        assertEquals(100.0, aaa.costBasis(), 0.0001);
        assertEquals(110.0, aaa.latestClose(), 0.0001);
        assertEquals(1100.0, aaa.marketValue(), 0.0001);
        assertEquals(10.0, aaa.dailyPnlPct(), 0.001);
        assertEquals("AAA 10.00%", aaa.toString());

        PortfolioHeatmap.Position bbb = positions.stream()
                .filter(p -> p.symbol().equals("BBB")).findFirst().orElseThrow();
        assertEquals(1100.0, bbb.marketValue(), 0.0001);
        assertEquals(10.0, bbb.dailyPnlPct(), 0.001);
    }

    @Test
    @DisplayName("PortfolioHeatmap default supplier falls back to screener daily prices when quotes are empty")
    void heatmapFallsBackToDailyPrices() throws Exception {
        resetDatabase();
        execute("CREATE TABLE portfolio.positions (symbol VARCHAR(10), quantity INT, cost_basis DOUBLE PRECISION, status VARCHAR(10))");
        execute("CREATE TABLE portfolio.latest_quotes (ticker VARCHAR(10), close DOUBLE PRECISION)"); // exists but empty
        execute("CREATE TABLE screener.daily_prices (ticker VARCHAR(10), date TIMESTAMP, close DOUBLE PRECISION)");
        execute("INSERT INTO portfolio.positions VALUES ('ZZZ', 7, 10.0, 'active')");
        execute("INSERT INTO screener.daily_prices VALUES ('ZZZ', '2026-10-13 00:00:00', 10.0)");
        execute("INSERT INTO screener.daily_prices VALUES ('ZZZ', '2026-10-14 00:00:00', 12.0)");

        var heatmap = new PortfolioHeatmap(new TerminalSize(80, 24));
        List<PortfolioHeatmap.Position> positions = heatmap.getPositions();

        assertEquals(1, positions.size(), "empty quotes must route to the screener daily_prices query");
        PortfolioHeatmap.Position zzz = positions.get(0);
        assertEquals("ZZZ", zzz.symbol());
        assertEquals(7, zzz.quantity());
        assertEquals(10.0, zzz.costBasis(), 0.0001);
        assertEquals(12.0, zzz.latestClose(), 0.0001, "latest daily price row must be used");
        assertEquals(84.0, zzz.marketValue(), 0.0001);
        assertEquals(20.0, zzz.dailyPnlPct(), 0.001);
    }

    @Test
    @DisplayName("PortfolioHeatmap default supplier falls back to sample positions when everything is empty")
    void heatmapFallsBackWhenEmpty() throws Exception {
        resetDatabase();
        execute("CREATE TABLE portfolio.positions (symbol VARCHAR(10), quantity INT, cost_basis DOUBLE PRECISION, status VARCHAR(10))");
        execute("CREATE TABLE portfolio.latest_quotes (ticker VARCHAR(10), close DOUBLE PRECISION)");
        execute("CREATE TABLE screener.daily_prices (ticker VARCHAR(10), date TIMESTAMP, close DOUBLE PRECISION)");

        var heatmap = new PortfolioHeatmap(new TerminalSize(80, 24));
        List<PortfolioHeatmap.Position> positions = heatmap.getPositions();

        assertEquals(12, positions.size(), "empty result must yield the built-in fallback positions");
        Set<String> symbols = positions.stream().map(PortfolioHeatmap.Position::symbol).collect(Collectors.toSet());
        assertTrue(symbols.contains("AAPL"));
        assertTrue(symbols.contains("BND"));
        assertTrue(symbols.contains("SPY"));
        for (PortfolioHeatmap.Position p : positions) {
            assertTrue(p.latestClose() > 0.0, "fallback positions have a price");
        }
    }

    @Test
    @DisplayName("Position dailyPnlPct guards non-positive basis and close")
    void positionPnlGuards() {
        assertEquals(0.0, new PortfolioHeatmap.Position("N1", 1, 0.0, 50.0).dailyPnlPct(), 0.0,
                "non-positive cost basis must yield 0");
        assertEquals(0.0, new PortfolioHeatmap.Position("N2", 1, 50.0, 0.0).dailyPnlPct(), 0.0,
                "non-positive close must yield 0");
    }

    @Test
    @DisplayName("refreshData restores fallback positions when the supplier throws")
    void heatmapRefreshDataFallsBackOnSupplierThrow() {
        var heatmap = new PortfolioHeatmap(new TerminalSize(80, 24),
                () -> List.of(new PortfolioHeatmap.Position("ONE", 1, 1.0, 1.0)));
        assertEquals(1, heatmap.getPositions().size());

        var broken = new PortfolioHeatmap(new TerminalSize(80, 24),
                () -> { throw new IllegalStateException("boom"); });
        broken.refreshData();
        assertEquals(12, broken.getPositions().size(), "supplier failure must fall back to sample positions");
    }

    // ------------------------------------------------------------------
    // Periodic refresh + remaining render guards
    // ------------------------------------------------------------------

    @Test
    @DisplayName("renderFrame after 60s re-queries the data source (periodic refresh)")
    void tickerTapePeriodicRefresh() throws Exception {
        var tape = new TickerTape(new TerminalSize(80, 24));

        var lastRefreshField = TickerTape.class.getDeclaredField("lastRefreshMs");
        lastRefreshField.setAccessible(true);
        // Age the refresh timestamp so the next renderFrame re-queries: the default
        // supplier reads screener.url (set by @BeforeEach) and hits the (empty)
        // database, which exercises the DB-read path from renderFrame.
        lastRefreshField.setLong(tape, System.currentTimeMillis() - 61_000);

        var buffer = new io.jterm.screen.ScreenBuffer(new TerminalSize(80, 24));
        tape.renderFrame(new io.jterm.graphics.TextGraphics(buffer),
                new TerminalSize(80, 24));
        long lastRefresh = (Long) lastRefreshField.get(tape);
        assertTrue(Math.abs(System.currentTimeMillis() - lastRefresh) < 5_000,
                "renderFrame must have re-run refreshData (lastRefreshMs updated)");
        // The fresh (aged) query against an empty DB yields the fallback items.
        assertEquals(8, tape.getItems().size(), "periodic refresh falls back to sample items on empty DB");
    }

    @Test
    @DisplayName("empty supplier list is normalized to the built-in sample tape")
    void tickerTapeNormalizesEmptySupplier() {
        var tape = new TickerTape(new TerminalSize(80, 24), java.util.List::of);
        assertEquals(8, tape.getItems().size(),
                "empty supplier output must normalize to the sample items");

        // The normalized tape paints glyphs on the bottom row.
        var size = new TerminalSize(40, 4);
        var buffer = new io.jterm.screen.ScreenBuffer(size);
        assertDoesNotThrow(() -> tape.renderFrame(new io.jterm.graphics.TextGraphics(buffer), size));
        boolean glyph = false;
        for (int c = 0; c < size.columns(); c++) {
            if (buffer.getCell(c, size.rows() - 1).character().charAt(0) != ' ') glyph = true;
        }
        assertTrue(glyph, "normalized sample tape must paint the bottom row");
    }

    @Test
    @DisplayName("PortfolioHeatmap without a configured URL uses the built-in sample positions")
    void heatmapWithoutUrlUsesSamplePositions() {
        System.clearProperty("portfolio.url");

        var heatmap = new PortfolioHeatmap(new TerminalSize(80, 24));
        assertEquals(12, heatmap.getPositions().size());
    }

    @Test
    @DisplayName("an unresolvable JDBC URL falls back to the sample tape (driver unavailable)")
    void tickerTapeWithBadDriverUrlFallsBack() {
        System.setProperty("screener.url", "jdbc:nonexistentdriver://host/db");

        var tape = new TickerTape(new TerminalSize(80, 24));
        assertEquals(8, tape.getItems().size(),
                "Connection failure must fall back to the built-in sample items");
    }

    @Test
    @DisplayName("an unresolvable JDBC URL falls back to the sample positions (driver unavailable)")
    void heatmapWithBadDriverUrlFallsBack() {
        System.setProperty("portfolio.url", "jdbc:nonexistentdriver://host/db");

        var heatmap = new PortfolioHeatmap(new TerminalSize(80, 24));
        assertEquals(12, heatmap.getPositions().size(),
                "Connection failure must fall back to the built-in sample positions");
    }
}