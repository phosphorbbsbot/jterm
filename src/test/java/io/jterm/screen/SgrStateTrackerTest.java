package io.jterm.screen;

import io.jterm.style.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SgrStateTrackerTest {
    @Test
    void noChangeProducesNoOutput() {
        var tracker = new DefaultScreen.SgrStateTracker();
        tracker.transitionTo(TextCell.EMPTY);
        byte[] output = tracker.transitionTo(TextCell.EMPTY);
        assertEquals(0, output.length);
    }

    @Test
    void foregroundChangeEmitsSequence() {
        var tracker = new DefaultScreen.SgrStateTracker();
        tracker.transitionTo(TextCell.EMPTY);
        var red = TextCell.EMPTY.withForeground(AnsiColor.RED);
        byte[] output = tracker.transitionTo(red);
        String s = new String(output);
        assertTrue(s.contains("31m"));
    }

    @Test
    void addingBoldEmitsEnable() {
        var tracker = new DefaultScreen.SgrStateTracker();
        var base = new TextCell('A');
        tracker.transitionTo(base);
        var bold = base.withModifier(SGR.BOLD);
        byte[] output = tracker.transitionTo(bold);
        assertTrue(new String(output).contains("\033[1m"));
    }

    @Test
    void removingBoldEmitsReset() {
        var tracker = new DefaultScreen.SgrStateTracker();
        var bold = new TextCell('A', AnsiColor.DEFAULT, AnsiColor.DEFAULT, SGR.BOLD);
        tracker.transitionTo(bold);
        var plain = bold.withoutModifier(SGR.BOLD);
        byte[] output = tracker.transitionTo(plain);
        // When transitioning to fully-default (no fg/bg/modifiers), the tracker
        // emits a single SGR reset instead of individual disable sequences.
        String s = new String(output);
        assertTrue(s.contains("\033[0m"), "Expected SGR reset, got: " + s);
    }

    /** Mini ANSI SGR state machine: replays emitted bytes, tracks terminal mods. */
    private java.util.Set<SGR> replayTerminal(String emitted) {
        var mods = new java.util.HashSet<SGR>();
        for (String seq : emitted.split("\u001b\\[")) {
            if (seq.isEmpty()) continue;
            int code = Integer.parseInt(seq.replace("m", "").trim());
            switch (code) {
                case 0 -> mods.clear();
                case 1 -> mods.add(SGR.BOLD);
                case 2 -> mods.add(SGR.DIM);
                case 22 -> { mods.remove(SGR.BOLD); mods.remove(SGR.DIM); }
                default -> { }
            }
        }
        return mods;
    }

    @Test
    void boldToDim_replayMatchesTarget() {
        var tracker = new DefaultScreen.SgrStateTracker();
        var bold = new TextCell('A', AnsiColor.DEFAULT, AnsiColor.DEFAULT, SGR.BOLD);
        tracker.transitionTo(bold);
        var dim = new TextCell('A', AnsiColor.DEFAULT, AnsiColor.DEFAULT, SGR.DIM);
        String all = new String(tracker.transitionTo(dim), java.nio.charset.StandardCharsets.UTF_8);
        var terminalState = replayTerminal("\u001b[1m" + all);
        assertTrue(terminalState.equals(java.util.Set.of(SGR.DIM)),
                "Terminal replayed to " + terminalState + ", expected {DIM}. Emitted: " + all);
    }

    @Test
    void dimToBold_replayMatchesTarget() {
        var tracker = new DefaultScreen.SgrStateTracker();
        var dim = new TextCell('A', AnsiColor.DEFAULT, AnsiColor.DEFAULT, SGR.DIM);
        tracker.transitionTo(dim);
        var bold = new TextCell('A', AnsiColor.DEFAULT, AnsiColor.DEFAULT, SGR.BOLD);
        String all = new String(tracker.transitionTo(bold), java.nio.charset.StandardCharsets.UTF_8);
        var terminalState = replayTerminal("\u001b[2m" + all);
        assertTrue(terminalState.equals(java.util.Set.of(SGR.BOLD)),
                "Terminal replayed to " + terminalState + ", expected {BOLD}. Emitted: " + all);
    }

    @Test
    void removingBoldWithColorEmitsIncremental() {
        // When target still has non-default colors, use incremental disable
        var tracker = new DefaultScreen.SgrStateTracker();
        var boldRed = new TextCell('A', AnsiColor.RED, AnsiColor.DEFAULT, SGR.BOLD);
        tracker.transitionTo(boldRed);
        var plainRed = new TextCell('A', AnsiColor.RED, AnsiColor.DEFAULT);
        byte[] output = tracker.transitionTo(plainRed);
        String s = new String(output);
        assertTrue(s.contains("\033[22m"), "Expected incremental bold-off, got: " + s);
    }

    @Test
    void resetClearsState() {
        var tracker = new DefaultScreen.SgrStateTracker();
        tracker.transitionTo(new TextCell('X', AnsiColor.RED, AnsiColor.BLUE, SGR.BOLD));
        tracker.reset();
        byte[] output = tracker.transitionTo(TextCell.EMPTY);
        assertEquals(0, output.length);
    }

    /**
     * MuffinTerm (CP437 client) resets the background to default (black) when
     * it receives ESC[27m (REVERSE off). After disabling REVERSE, the tracker
     * must re-emit the current fg and bg colors so the terminal restores them.
     * Without this, all cells after a REVERSE cursor render with black background.
     */
    @Test
    void removingReverseReEmitsColors() {
        var tracker = new DefaultScreen.SgrStateTracker();
        // Set up: yellow on blue with REVERSE (cursor cell)
        var cursorCell = new TextCell(' ', AnsiColor.BRIGHT_YELLOW, AnsiColor.BLUE, SGR.REVERSE);
        tracker.transitionTo(cursorCell);
        // Transition to a normal cell: yellow on blue, no REVERSE
        var normalCell = new TextCell(' ', AnsiColor.BRIGHT_YELLOW, AnsiColor.BLUE);
        byte[] output = tracker.transitionTo(normalCell);
        String s = new String(output);
        // Must emit ESC[27m (REVERSE off)
        assertTrue(s.contains("\033[27m"), "Expected REVERSE off, got: " + s);
        // Must ALSO re-emit fg (93m) and bg (44m) because some terminals
        // (MuffinTerm CP437) reset colors when processing ESC[27m
        assertTrue(s.contains("93m"), "Expected fg re-emit (93m) after REVERSE off, got: " + s);
        assertTrue(s.contains("44m"), "Expected bg re-emit (44m) after REVERSE off, got: " + s);
    }

    /**
     * Same issue applies to other SGR modifiers (e.g. BOLD off / ESC[22m).
     * Some terminals reset colors on any SGR disable sequence.
     */
    @Test
    void removingBoldWithColorsReEmitsColors() {
        var tracker = new DefaultScreen.SgrStateTracker();
        var boldCell = new TextCell('A', AnsiColor.RED, AnsiColor.BLUE, SGR.BOLD);
        tracker.transitionTo(boldCell);
        var plainCell = new TextCell('A', AnsiColor.RED, AnsiColor.BLUE);
        byte[] output = tracker.transitionTo(plainCell);
        String s = new String(output);
        assertTrue(s.contains("\033[22m"), "Expected BOLD off, got: " + s);
        // Must re-emit fg and bg after disabling BOLD
        assertTrue(s.contains("31m"), "Expected fg re-emit (31m) after BOLD off, got: " + s);
        assertTrue(s.contains("44m"), "Expected bg re-emit (44m) after BOLD off, got: " + s);
    }
}
