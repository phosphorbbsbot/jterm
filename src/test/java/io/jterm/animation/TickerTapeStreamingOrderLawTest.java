package io.jterm.animation;

import io.jterm.core.TerminalSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Streaming-order law (Joe 2026-10-22: "randomize the ticker, it reads
 * alphabetical"): when the universe GROWS after the initial shuffle, new
 * symbols join at the back of the queue (queue law) — but they must join in
 * a RANDOM position order, not the supplier's alphabetical order, so the
 * cycle never reads as [shuffled prefix][A-Z tail]. New arrivals keep their
 * queue positions afterwards (nothing reshuffles mid-cycle).
 */
class TickerTapeStreamingOrderLawTest {

    /** Synthetic clock: each tick advances one 8fps frame slot (125ms). */
    private static final class T {
        private long now = System.nanoTime();
        long tick() { now += 125_000_000L; return now; }
    }
    private final T T = new T();


    private static List<TickerTape.TickerItem> items(String... syms) {
        var out = new ArrayList<TickerTape.TickerItem>();
        for (String s : syms) {
            out.add(new TickerTape.TickerItem(s, 100.0, 90.0));
        }
        return out;
    }

    @Test
    @DisplayName("universe growth appends new symbols in NON-alphabetical order")
    void growthDoesNotAppendAlphabetically() {
        var initial = new ArrayList<>(items("AAAA", "BBBB", "CCCC", "DDDD", "EEEE"));
        var tape = new TickerTape(new TerminalSize(80, 1), () -> List.copyOf(initial));
        tape.tick(T.tick());
        // grow the universe: F..V arrive (17 new symbols, supplier order = alpha)
        initial.addAll(items("FFFF", "GGGG", "HHHH", "IIII", "JJJJ", "KKKK", "LLLL", "MMMM",
                "NNNN", "OOOO", "PPPP", "QQQQ", "RRRR", "SSSS", "TTTT", "UUUU", "VVVV"));
        tape.refreshData();
        // read the cycle order back through the test hook
        List<String> order = tape.cycleOrderForTests();
        assertEquals(initial.size(), order.size(), "all symbols present in the cycle");
        String tail = String.join("", order.subList(5, order.size()));
        assertFalse(tail.contains("FFFFGGGGHHHHIIIIJJJJKKKKLLLLMMMMNNNNOOOOPPPPQQQQRRRRSSSSTTTTUUUUVVVV"),
                "new symbols appended in supplier (alphabetical) order — streaming growth must randomize join order; tail=" + tail);
    }
}
