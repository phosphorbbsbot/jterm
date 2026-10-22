package io.jterm.animation;

import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.style.AnsiColor;
import io.jterm.style.SGR;
import io.jterm.style.TextCell;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Animated stock ticker tape background. Items scroll right-to-left across a
 * single row: symbol, latest close price, and day change percentage. Green for
 * up, red for down. Data is supplied by a {@code java.util.function.Supplier}
 * of items; the library is database-agnostic. When no supplier is given — or
 * it returns no usable data — the tape scrolls built-in decorative sample
 * data. Callers that want live quotes supply them via the two-argument
 * constructor.
 */
public class TickerTape implements AnimatedBackground {

    private static final int TARGET_FPS = 8;
    private static final double DEFAULT_SCROLL_SPEED_CELLS = 1.0;

    /**
     * Cells advanced per frame; fractional values slow the scroll below one
     * cell per frame by advancing only on frames where the accumulated
     * fraction crosses a cell boundary.
     */
    private double scrollSpeed = DEFAULT_SCROLL_SPEED_CELLS;
    private double scrollCarry;

    /**
     * Symbols scroll in SHUFFLED order (nothing adjacent alphabetizes by
     * accident). The order is recomputed only when the item SET changes —
     * mid-stream refreshes keep the existing sequence so the tape never
     * visually jumps. Default seed derives from identity hash; hosts may
     * pin a seed for deterministic renders (tests).
     */
    private final java.util.Random orderRandom = new java.util.Random();
    private volatile java.util.List<TickerItem> orderedItems = List.of();
    private volatile int lastOrderSignature = -1;
    private volatile boolean orderInitialized;
    private long shuffleSeed = -1;

    /** @param seed non-negative long to pin the shuffle (deterministic order); -1 = random */
    public void setShuffleSeed(long seed) {
        this.shuffleSeed = seed;
        // force re-derivation with the new seed, bypassing the sticky-subset
        // check: the ordered list must come from THIS seed alone
        if (items != null && !items.isEmpty()) {
            orderSymbols.clear();
            List<TickerItem> copy = new java.util.ArrayList<>(items);
            java.util.Random rnd = seed >= 0 ? new java.util.Random(seed) : orderRandom;
            java.util.Collections.shuffle(copy, rnd);
            for (TickerItem it : copy) {
                orderSymbols.add(it.symbol());
            }
            orderedItems = List.copyOf(copy);
            java.util.TreeSet<String> syms = new java.util.TreeSet<>();
            for (TickerItem it : orderedItems) {
                syms.add(it.symbol());
            }
            lastOrderSignature = syms.hashCode();
            orderInitialized = true;
        }
    }

    /** @param cellsPerFrame cells to advance per frame (e.g. 0.5 = half speed) */
    public void setScrollSpeed(double cellsPerFrame) {
        if (cellsPerFrame <= 0) {
            throw new IllegalArgumentException("scrollSpeed must be > 0, got " + cellsPerFrame);
        }
        this.scrollSpeed = cellsPerFrame;
    }    private static final String SEPARATOR = "  |  ";
    private static final long REFRESH_INTERVAL_MS = 60_000;

    // Built-in decorative data scrolled when no supplier provides items.
    private static final List<TickerItem> FALLBACK_ITEMS = List.of(
            new TickerItem("AAPL", 189.52, 186.00),
            new TickerItem("GOOGL", 141.80, 140.10),
            new TickerItem("MSFT", 378.91, 375.20),
            new TickerItem("AMZN", 178.35, 181.50),
            new TickerItem("NVDA", 875.28, 860.00),
            new TickerItem("TSLA", 242.10, 248.50),
            new TickerItem("META", 472.33, 465.00),
            new TickerItem("NFLX", 626.92, 615.00)
    );

    private final Supplier<List<TickerItem>> dataSupplier;

    /**
     * When true (default), an empty/erroring data supplier renders the built-in
     * demo items instead of a blank bar. Standalone/library demos look alive;
     * production hosts should paint NOTHING when their feed has no data.
     */
    private boolean demoFallback = true;

    /** @param demo false to paint a blank bar instead of demo items when the supplier has no data */
    public void setDemoFallback(boolean demo) {
        this.demoFallback = demo;
    }

    private volatile boolean running;
    private volatile TerminalSize lastSize;
    private volatile int scrollOffset;
    private volatile List<TickerItem> items;
    private volatile long lastRefreshMs;

    /**
     * Creates a ticker tape that scrolls built-in decorative sample data.
     * Pass a custom {@code Supplier} of items via the two-argument constructor
     * to render live quotes.
     *
     * @param preferredSize preferred terminal size
     */
    public TickerTape(TerminalSize preferredSize) {
        this(preferredSize, () -> null);
    }

    /**
     * Creates a ticker tape with a custom data supplier.
     *
     * @param preferredSize preferred terminal size
     * @param dataSupplier  supplies ticker items; falls back to sample data on error
     */
    public TickerTape(TerminalSize preferredSize, Supplier<List<TickerItem>> dataSupplier) {
        this.dataSupplier = Objects.requireNonNull(dataSupplier, "dataSupplier");
        onResize(preferredSize);
        refreshData();
    }

    @Override
    /**
     * Renders one frame of the animation into the given graphics buffer.
     * @param graphics the graphics
     * @param size the size
     */
    public void renderFrame(TextGraphics graphics, TerminalSize size) {
        lastSize = size;
        if (size.columns() <= 0 || size.rows() <= 0) {
            return;
        }

        // Periodically refresh prices from the data source
        if (System.currentTimeMillis() - lastRefreshMs >= REFRESH_INTERVAL_MS) {
            refreshData();
        }

        // Background.
        TextCell bg = new TextCell(' ', AnsiColor.BLACK, AnsiColor.BLACK);
        graphics.fillRectangle(0, 0, size.columns(), size.rows(), bg);

        if (items == null || items.isEmpty()) {
            return;
        }

        int cols = size.columns();
        int row = size.rows() - 1;

        StringBuilder builder = new StringBuilder();
        List<TickerItem> ordered = orderedItems.isEmpty() ? items : orderedItems;
        for (TickerItem item : ordered) {
            if (!builder.isEmpty()) {
                builder.append(SEPARATOR);
            }
            builder.append(item.renderString());
        }
        String text = builder.toString();
        if (text.isEmpty()) {
            return;
        }

        String padded = text + SEPARATOR;
        int totalWidth = padded.length();
        if (totalWidth <= 0) {
            return;
        }

        // Advance is driven by tick() only — hosts repaint independently of
        // the animation clock, and renderFrame must be idempotent per offset
        // (blitting a cached offset twice must produce the same row).

        // REAL TERMINAL LAW: writing a character into the last column of the
        // bottom row makes real terminals auto-wrap to the next line. The
        // tape lives on the bottom row, so it must never paint column
        // cols-1 — stop one column short.
        int paintCols = Math.max(0, cols - 1);

        int startIdx = scrollOffset;
        for (int col = 0; col < paintCols; col++) {
            int idx = (startIdx + col) % totalWidth;
            char ch = padded.charAt(idx);
            int effectiveCol = col;

            // Determine which item this character belongs to so we can color it.
            AnsiColor fg = AnsiColor.BRIGHT_WHITE;
            SGR sgr = SGR.BOLD;

            int itemStart = 0;
            for (TickerItem item : ordered) {
                String rendered = item.renderString();
                int itemEnd = itemStart + rendered.length();
                if (idx >= itemStart && idx < itemEnd) {
                    fg = item.change() >= 0.0 ? AnsiColor.BRIGHT_GREEN : AnsiColor.BRIGHT_RED;
                    break;
                }
                // Skip separator for the next item.
                itemStart = itemEnd + SEPARATOR.length();
            }
            // Separator characters keep bright white foreground.

            graphics.setCell(effectiveCol, row, new TextCell(ch, fg, AnsiColor.BLACK, sgr));
        }
    }

    /** Symbol cycle order, kept INCREMENTALLY: arrivals insert, nothing reshuffles. */
    private final java.util.ArrayList<String> orderSymbols = new java.util.ArrayList<>();

    /**
     * Maintains the cached scroll order for the given item set:
     * unknown symbols INSERT at random positions; previously-seen symbols
     * KEEP their cycle order exactly (a streaming universe — quotes landing
     * one at a time — must never swap the visible set, Joe 2026-10-25).
     * Prices refresh in place; symbols gone from the supplier fall out.
     */
    private void regenerateOrder(List<TickerItem> fresh) {
        if (fresh == null) {
            orderedItems = List.of();
            orderSymbols.clear();
            lastOrderSignature = -1;
            return;
        }
        java.util.Map<String, TickerItem> bySym = new java.util.LinkedHashMap<>();
        for (TickerItem it : fresh) {
            bySym.put(it.symbol(), it);
        }
        java.util.Random rnd = shuffleSeed >= 0 ? new java.util.Random(shuffleSeed) : orderRandom;
        if (!orderInitialized) {
            orderSymbols.clear();
            List<TickerItem> copy = new java.util.ArrayList<>(fresh);
            java.util.Collections.shuffle(copy, rnd);
            for (TickerItem it : copy) {
                orderSymbols.add(it.symbol());
            }
            orderInitialized = true;
        } else {
            // insert NEW symbols at random positions; keep all others fixed
            java.util.List<String> newSyms = new java.util.ArrayList<>();
            for (String sym : bySym.keySet()) {
                if (!orderSymbols.contains(sym)) {
                    newSyms.add(sym);
                }
            }
            for (String sym : newSyms) {
                orderSymbols.add(rnd.nextInt(orderSymbols.size() + 1), sym);
            }
            // drop symbols the supplier no longer provides
            orderSymbols.removeIf(sym -> !bySym.containsKey(sym));
        }
        List<TickerItem> ordered = new java.util.ArrayList<>();
        for (String sym : orderSymbols) {
            TickerItem it = bySym.get(sym);
            if (it != null) {
                ordered.add(it);
            }
        }
        orderedItems = List.copyOf(ordered);
        java.util.TreeSet<String> syms = new java.util.TreeSet<>(orderSymbols);
        lastOrderSignature = syms.hashCode();
    }

    /** Full cycle width for the given items (" + SEPARATOR " between rendered items). */
    private static int paddedWidth(List<TickerItem> current) {
        StringBuilder sb = new StringBuilder();
        for (TickerItem item : current) {
            if (!sb.isEmpty()) {
                sb.append(SEPARATOR);
            }
            sb.append(item.renderString());
        }
        return sb.isEmpty() ? 0 : sb.length() + SEPARATOR.length();
    }

    @Override
    /**
     * Advances the scroll state for one animation frame (scrollSpeed cells,
     * fractionally carried). The paint path ({@code renderFrame}) never
     * advances — hosts repaint independently of the animation clock.
     */
    public void tick(long nowNanos) {
        // Periodically refresh prices from the data source
        if (System.currentTimeMillis() - lastRefreshMs >= REFRESH_INTERVAL_MS) {
            refreshData();
        }
        List<TickerItem> current = items;
        if (current == null || current.isEmpty()) {
            return;
        }
        int totalWidth = paddedWidth(current);
        if (totalWidth <= 0) {
            return;
        }
        scrollCarry += scrollSpeed;
        int advance = (int) Math.floor(scrollCarry);
        scrollCarry -= advance;
        scrollOffset = (scrollOffset + advance) % totalWidth;
    }

    @Override
    /**
     * Reallocates internal buffers for the new terminal size.
     * @param newSize the new size
     */
    public void onResize(TerminalSize newSize) {
        this.lastSize = newSize;
    }

    @Override
    /**
     * Starts the animation.
     */
    public void start() {
        running = true;
    }

    @Override
    /**
     * Stops the animation.
     */
    public void stop() {
        running = false;
    }

    @Override
    /**
     * Returns whether the running flag is set.
     * @return the result
     */
    public boolean isRunning() {
        return running;
    }

    @Override
    /**
     * Returns the target frame rate in frames per second.
     * @return the result
     */
    public int targetFps() {
        return TARGET_FPS;
    }

    @Override
    /**
     * Returns the last terminal size the animation was rendered at.
     * @return the result
     */
    public TerminalSize lastSize() {
        return lastSize;
    }

    /**
     * Sets the scroll offset directly. Visible for tests.
     *
     * @param offset scroll offset in columns; values {@code <} 0 are clamped to 0
     */
    public void setScrollOffset(int offset) {
        this.scrollOffset = Math.max(0, offset);
    }

    /**
     * Returns the current scroll offset. Visible for tests.
     *
     * @return scroll offset in columns
     */
    public int getScrollOffset() {
        return scrollOffset;
    }

    /**
     * Returns the currently rendered items. Visible for tests.
     *
     * @return an unmodifiable copy of the current items; empty if none
     */
    public List<TickerItem> getItems() {
        return items == null ? List.of() : List.copyOf(items);
    }

    /** Visible for tests: force a data reload. */
    public void refreshData() {
        try {
            this.items = dataSupplier.get();
        } catch (Exception e) {
            this.items = null;
        }
        if (this.items == null || this.items.isEmpty()) {
            this.items = demoFallback ? FALLBACK_ITEMS : null;
        }
        regenerateOrder(this.items);
        lastRefreshMs = System.currentTimeMillis();
    }

    /** Immutable ticker item: symbol, latest close, and previous close. */
    public static final class TickerItem {
        private final String symbol;
        private final double close;
        private final double previousClose;

        /**
         * Constructs a new immutable TickerItem.
         *
         * @param symbol the ticker symbol
         * @param close the latest closing price
         * @param previousClose the previous closing price
         */
        public TickerItem(String symbol, double close, double previousClose) {
            this.symbol = Objects.requireNonNull(symbol, "symbol");
            this.close = close;
            this.previousClose = previousClose;
        }

        /**
         * Returns the ticker symbol.
         *
         * @return the symbol
         */
        public String symbol() { return symbol; }

        /**
         * Returns the latest closing price.
         *
         * @return the close price
         */
        public double close() { return close; }

        /**
         * Returns the previous closing price.
         *
         * @return the previous close price
         */
        public double previousClose() { return previousClose; }

        /**
         * Returns the percentage change from the previous close to the latest close.
         *
         * @return the percentage change, or 0.0 if the previous close was zero
         */
        public double change() {
            if (previousClose == 0.0) {
                return 0.0;
            }
            return (close - previousClose) / previousClose * 100.0;
        }

        /**
         * Renders this item as a single-line ticker string.
         *
         * @return the formatted ticker string
         */
        public String renderString() {
            String percent = String.format(java.util.Locale.US, "%.2f%%", change());
            return symbol + " " + String.format(java.util.Locale.US, "%.2f", close) + " " + percent;
        }

        /**
         * Returns the rendered ticker string for this item.
         *
         * @return the rendered ticker string
         */
        @Override
        public String toString() {
            return renderString();
        }
    }
}
