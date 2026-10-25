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


    /**
     * Tape repaint ceiling. Advance is TIME-BASED (elapsed), so the cadence
     * only changes how often the client repaints, not the motion itself
     * (scroll speed lives in DEFAULT_SCROLL_SPEED_CELLS / setScrollSpeed).
     * 8 fps repaints the full-width bar 8×/s — on slower renderers
     * (phosphor-ios relay, tmux) the ROTATION frame rewrites the entire bar
     * row with its SGR runs, which Joe saw as a 1-1.5s freeze at every
     * left-edge exit (2026-10-22). 8 fps with the paused-clock/rotation fix
     * (2f7017e, Joe-confirmed clean) renders without the freeze; the value
     * doubles the pre-fix cadence (Joe 10/22: "double the speed").
     */
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
    private long lastTickNanos;
    private static final double MAX_CATCHUP_CELLS = 4.0; // retained: belt & braces
    /** Elapsed gaps longer than this are stalls — credit not accumulated. */
    private static final long MAX_CREDITED_ELAPSED_NANOS = 1_000_000_000L;
    /** Credit handed to a stalled tick: one beat of motion, not the backlog. */
    private static final long RESUME_CREDIT_NANOS = 250_000_000L;

    /** Pins the scroll-order shuffle for determinism.
     * @param seed non-negative long to pin the shuffle (deterministic order); -1 = random */
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

    /** Sets the scroll speed of the tape.
     * @param cellsPerFrame cells to advance per frame (e.g. 0.5 = half speed) */
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

    /** Controls whether the built-in demo items render when the feed is empty.
     * @param demo false to paint a blank bar instead of demo items when the supplier has no data */
    public void setDemoFallback(boolean demo) {
        this.demoFallback = demo;
    }

    private volatile boolean running;
    private volatile TerminalSize lastSize;
    private volatile int scrollOffset;          // window phase within the CURRENT cycle (tests)
    private volatile long absoluteOffsetCells;  // Joe's queue law: cells consumed since open — never % totalWidth
    /**
     * Guards (absoluteOffsetCells, orderedItems/scrollOffset) pairs: the
     * animation clock rotates the queue [offset -= frontWidth, items rotate]
     * while the paint thread reads [offset, items] every frame at 8fps. Those
     * two steps are NOT atomic separately — a paint between them saw the NEW
     * front with the OLD (huge) offset, clamped startChar to the item's full
     * length and painted the new symbol as ZERO cells + separator = the bar
     * visibly swallowed the arriving symbol for 1-2 frames at every
     * rotation beat (~5s at 4 cells/s per ~22-cell item) — exactly Joe's
     * "pause every 5 seconds, then the next symbol starts" (2026-10-26).
     * Both rotation and render snapshot under this lock; no I/O inside.
     */
    private final Object stateLock = new Object();
    private volatile List<TickerItem> lastCycleItems = List.of(); // the cycle whose widths anchor absoluteOffset
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

        // Background. CNBC palette: white bar.
        TextCell bg = new TextCell(' ', AnsiColor.BRIGHT_WHITE, AnsiColor.BRIGHT_WHITE);
        graphics.fillRectangle(0, 0, size.columns(), size.rows(), bg);

        if (items == null || items.isEmpty()) {
            return;
        }

        int cols = size.columns();
        int row = size.rows() - 1;
        // REAL TERMINAL LAW: writing into the last column of the bottom row
        // auto-wraps — the tape never paints column cols-1.
        int paintCols = Math.min(cols - 1, Math.max(0, cols - 1));

        // QUEUE RENDER (Joe): items render in FIFO order from the queue
        // front; `absoluteOffsetCells` counts cells consumed since open and
        // anchors the window to the FRONT item — universe changes (arrivals,
        // flaps, removals) never shift visible content; when an item scrolls
        // off the left, tick() rotates it to the back.
        // ATOMIC SNAPSHOT (see stateLock): offset and queue must come from
        // the same rotation state, else the front item races its offset.
        long offsetCells;
        List<TickerItem> ordered;
        synchronized (stateLock) {
            offsetCells = absoluteOffsetCells;
            ordered = (orderedItems.isEmpty() && items != null && !items.isEmpty()) ? items : orderedItems;
        }
        if (ordered == null || ordered.isEmpty()) {
            return;
        }
        int cellsIntoFront = (int) Math.min(offsetCells, Integer.MAX_VALUE);
        int col = 0;
        int idx = 0;
        while (col < paintCols) {
            if (idx >= ordered.size()) {
                break;
            }
            TickerItem item = ordered.get(idx);
            String rendered = item.renderString();
            int symLen = item.symbol().length();
            AnsiColor signColor = item.change() >= 0.0 ? AnsiColor.BRIGHT_GREEN : AnsiColor.BRIGHT_RED;
            // SMOOTH-SEPARATOR LAW (Joe 2026-10-22: the bar froze ~1.5s at
            // every left-edge exit, then jumped): the scroll crop must pass
            // THROUGH the front item's separator, not stop at its text end —
            // the old code painted the whole separator static while
            // cellsIntoFront crossed [len, len+sep), freezing the row for the
            // separator's pass (~0.75-1.5s at tape speed) and jumping at the
            // rotation. The front unit = item text + separator, cropped on
            // CHAR-BASIS with the separator's chars styled like the text tail.
            String frontUnit = (idx == 0) ? rendered + SEPARATOR : rendered + SEPARATOR;
            int startChar = (idx == 0) ? Math.min(cellsIntoFront, frontUnit.length()) : 0;
            for (int k = startChar; k < frontUnit.length() && col < paintCols; k++, col++) {
                char ch = frontUnit.charAt(k);
                boolean inText = k < symLen;
                boolean inSep = k >= rendered.length();
                AnsiColor fg = inSep ? AnsiColor.BLUE : (inText ? AnsiColor.BLUE : signColor);
                graphics.setCell(col, row, new TextCell(ch, fg, AnsiColor.BRIGHT_WHITE, SGR.BOLD));
            }
            idx++;
        }
        // cycle exhausted mid-window: wrap and repeat (marquee) — but only if
        // the whole cycle is narrower than the window
        if (ordered.size() > 0) {
            long cycleCells = 0;
            for (TickerItem it : ordered) {
                cycleCells += it.renderString().length() + SEPARATOR.length();
            }
            // compute remaining cells to fill and loop the cycle from the front
            int guard = 0;
            while (col < paintCols && guard++ < 64) {
                for (TickerItem item : ordered) {
                    String rendered = item.renderString();
                    int symLen2 = item.symbol().length();
                    AnsiColor sign = item.change() >= 0.0 ? AnsiColor.BRIGHT_GREEN : AnsiColor.BRIGHT_RED;
                    for (int k = 0; k < rendered.length() && col < paintCols; k++, col++) {
                        char ch = rendered.charAt(k);
                        AnsiColor fg = (k < symLen2) ? AnsiColor.BLUE : sign;
                        graphics.setCell(col, row, new TextCell(ch, fg, AnsiColor.BRIGHT_WHITE, SGR.BOLD));
                    }
                    for (int k = 0; k < SEPARATOR.length() && col < paintCols; k++, col++) {
                        graphics.setCell(col, row, new TextCell(SEPARATOR.charAt(k), AnsiColor.BLUE, AnsiColor.BRIGHT_WHITE, SGR.BOLD));
                    }
                }
            }
        }
    }

    /** Last known data per symbol — departed quotes keep rendering until they queue-exit. */
    private final java.util.HashMap<String, TickerItem> everSeen = new java.util.HashMap<>();
    /** Symbols backed by REAL supplier data (demo data never gets flap-keep). */
    private final java.util.HashSet<String> realBacked = new java.util.HashSet<>();

    private TickerItem lastDataFor(String sym) {
        return realBacked.contains(sym) ? everSeen.get(sym) : null;
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
        regenerateOrder(fresh, true);
    }

    /** @param realData false when `fresh` is demo/decorative data (never flap-kept, never marks real-backed). */
    private void regenerateOrder(List<TickerItem> fresh, boolean realData) {
        if (fresh == null) {
            orderedItems = List.of();
            orderSymbols.clear();
            lastOrderSignature = -1;
            return;
        }
        java.util.Map<String, TickerItem> bySym = new java.util.LinkedHashMap<>();
        for (TickerItem it : fresh) {
            bySym.put(it.symbol(), it);
            everSeen.put(it.symbol(), it);
            if (realData) {
                realBacked.add(it.symbol());
            }
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
            // QUEUE LAW (Joe): new/returning symbols join at the BACK of the
            // queue — they enter the window only after the current cycle
            // scrolls past, never popping into an established slot.
            // STREAMING-ORDER LAW (Joe 2026-10-22 "randomize the ticker"):
            // join order must be RANDOM, not supplier order — a growing
            // universe (quotes streaming in, positions added) would
            // otherwise append an alphabetical tail after the shuffled
            // prefix and the cycle would read sorted again.
            java.util.List<String> arrivals = new java.util.ArrayList<>();
            for (String sym : bySym.keySet()) {
                if (!orderSymbols.contains(sym)) {
                    arrivals.add(sym);
                }
            }
            if (arrivals.size() > 1) {
                java.util.Collections.shuffle(arrivals, rnd);
            }
            orderSymbols.addAll(arrivals);
            // symbols the supplier dropped KEEP rendering with their last
            // known data until they scroll off (queue exit) — the window
            // must never tear when a quote flaps. Track departures.
            for (java.util.Iterator<String> it2 = orderSymbols.iterator(); it2.hasNext();) {
                String sym = it2.next();
                if (!bySym.containsKey(sym)) {
                    TickerItem stale = lastDataFor(sym);
                    if (stale != null) {
                        bySym.put(sym, stale); // keep painting it for now
                    }
                }
            }
            orderSymbols.removeIf(sym -> !bySym.containsKey(sym));
        }
        List<TickerItem> ordered = new java.util.ArrayList<>();
        synchronized (stateLock) {
            for (String sym : orderSymbols) {
                TickerItem it = bySym.get(sym);
                if (it != null) {
                    ordered.add(it);
                }
            }
            orderedItems = List.copyOf(ordered);
        }
        java.util.TreeSet<String> syms = new java.util.TreeSet<>(orderSymbols);
        lastOrderSignature = syms.hashCode();
        // supplier-present = genuinely in the latest supplier list — NOT the
        // flap-kept stale items (those must drop at queue exit)
        java.util.Set<String> present = new java.util.HashSet<>();
        for (TickerItem it : fresh) {
            present.add(it.symbol());
        }
        currentSupplierSymbols = java.util.Set.copyOf(present);
    }

    /** Test hook: the current symbol cycle order (package-private, laws only). */
    java.util.List<String> cycleOrderForTests() {
        synchronized (stateLock) {
            return new java.util.ArrayList<>(orderSymbols);
        }
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
        List<TickerItem> cycle = orderedItems;
        if (cycle == null || cycle.isEmpty()) {
            return;
        }
        // width of the CURRENT front item + separator, anchored from the
        // cycle snapshot taken at the last rotation
        long frontWidth = frontItemWidth();
        if (frontWidth <= 0) {
            return;
        }
        // TIME-BASED ADVANCE LAW (Joe 2026-10-26: "pauses and jumps"): the
        // event loop can starve the paint side for seconds at a time; ticking
        // per FRAME then silently catching up 40 ticks turned each stall into
        // one visible jump of ~20 cells. Advance by ELAPSED time × speed and
        // cap the catch-up: a stall reads as a modest pause, never a jump.
        long now2 = nowNanos > 0 ? nowNanos : System.nanoTime();
        if (lastTickNanos == 0) {
            lastTickNanos = now2;
        }
        // BACKLOG-DISCARD LAW (Joe 2026-10-26: "catches up in one big move"):
        // a stall is followed by a burst of catch-up reads as a JUMP. Cap the
        // ELAPSED time credited, not just per-tick cells: a >1s gap between
        // ticks is credited as 0.25s — the tape resumes from where it froze
        // plus a beat of motion, and the accumulated gap is DISCARDED, never
        // discharged (the old 4-cells/tick cap smeared a 5s backlog across
        // several fast ticks = the visible catch-up move).
        // BACKLOG-DISCARD LAW (Joe 2026-10-26 "catches up in one big move"):
        // a stall is followed by a burst of catch-up reads as a JUMP. Cap the
        // ELAPSED time credited, not just per-tick cells: a >1s gap between
        // ticks is credited as 0.25s — the tape resumes from where it froze
        // plus a beat of motion, and the accumulated gap is DISCARDED, never
        // discharged (the old 4-cells/tick cap smeared a 5s backlog across
        // several fast ticks = the visible catch-up move). RED evidence:
        // old code moved 4 cells on the first post-stall tick (law failure).
        long elapsed = now2 - lastTickNanos;
        lastTickNanos = now2;
        if (elapsed > MAX_CREDITED_ELAPSED_NANOS) {
            elapsed = RESUME_CREDIT_NANOS;
        }
        double elapsedCells = elapsed / 1_000_000_000.0 * scrollSpeed * TARGET_FPS;
        scrollCarry += Math.max(0.0, elapsedCells);
        int advance = (int) Math.floor(scrollCarry);
        scrollCarry -= advance;
        absoluteOffsetCells += advance;

        // QUEUE ROTATION (Joe): the item at the front scrolls out; when its
        // last cell plus separator has passed the left edge, it re-enters at
        // the BACK and the window stays anchored — a universe that grows or
        // flaps never shifts the visible content.
        synchronized (stateLock) {
            while (absoluteOffsetCells >= frontWidth) {
                rotateFrontToBack();
                absoluteOffsetCells -= frontWidth;
                frontWidth = frontItemWidth();
                if (frontWidth <= 0) {
                    break;
                }
            }
            scrollOffset = (int) (absoluteOffsetCells % Math.max(1, frontItemWidth() + 1));
            scrollOffset = (int) Math.min(scrollOffset, frontWidth);
        }
    }

    /** Cell width of the item currently at the queue front (+ separator). */
    private long frontItemWidth() {
        List<TickerItem> cycle = orderedItems;
        if (cycle == null || cycle.isEmpty()) {
            return 0;
        }
        TickerItem front = cycle.get(0);
        String rendered = front.renderString();
        return rendered.length() + SEPARATOR.length();
    }

    /** Moves the front item to the back (FIFO), refreshing it from lastCycleItems data if supplied anew. */
    private void rotateFrontToBack() {
        List<TickerItem> cycle = orderedItems;
        if (cycle == null || cycle.size() < 2) {
            return;
        }
        TickerItem front = cycle.get(0);
        List<TickerItem> rotated = new java.util.ArrayList<>(cycle.size());
        for (int i = 1; i < cycle.size(); i++) {
            rotated.add(cycle.get(i));
        }
        // if the front item's data was withdrawn (supplier no longer provides
        // it — flapped quote), its queue exit is the natural drop point
        if (!supplierHas(front.symbol())) {
            orderSymbols.remove(front.symbol());
            rotated.remove(front); // not re-adding
        } else {
            rotated.add(front);
        }
        orderedItems = List.copyOf(rotated);
        orderSymbols.clear();
        for (TickerItem it : rotated) {
            orderSymbols.add(it.symbol());
        }
    }

    /** Is this symbol in the CURRENT subscription/supplier set? (visible for rotation decisions) */
    private volatile java.util.Set<String> currentSupplierSymbols = java.util.Set.of();

    private boolean supplierHas(String sym) {
        return currentSupplierSymbols.contains(sym);
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
        List<TickerItem> real;
        boolean supplierFailed = false;
        try {
            real = dataSupplier.get();
        } catch (Exception e) {
            real = null;
            supplierFailed = true;
        }
        if (real == null || real.isEmpty()) {
            // NO real data: paint demo (library default) or a blank bar — and
            // CRITICALLY, do NOT let demo/blank data poison the queue: the
            // queue resets so real data enters cleanly when it arrives.
            this.items = (demoFallback && !supplierFailed) ? FALLBACK_ITEMS : null;
            if (this.items == null || !demoFallback) {
                regenerateOrder(null, true);
            } else {
                regenerateOrder(this.items, false); // pure demo mode (library use)
            }
            // BLANK-STATE RETRY LAW (Joe 2026-10-26: white empty tape): the
            // closes snapshot can land seconds after open; waiting a full 60s
            // cycle left a blank bar up for a minute+. Empty states re-check
            // every 10s — the 60s cadence is for REFRESHING shown data, not
            // for FINDING the first data.
            lastRefreshMs = System.currentTimeMillis() - (REFRESH_INTERVAL_MS - 10_000);
            return;
        }
        this.items = real;
        regenerateOrder(real);
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
