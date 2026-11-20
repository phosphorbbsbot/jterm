package io.jterm.ansifilm;

import io.jterm.core.ColorDepth;
import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.screen.DefaultScreen;
import io.jterm.window.DefaultTextGUI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AnsiFilmScreen playback laws (animation plan card 1, 2026-11-20):
 *
 * <ul>
 *   <li><b>GOLDEN BYTES</b> — the emitter is byte-lawful: frame 1 is a full
 *       repaint (autowrap disable, cursor home, SGR reset, per-row cells,
 *       autowrap enable; the exact shape of DefaultScreen's doCompleteRefresh),
 *       and frames 2+ are SgrStateTracker DELTAs against the previous frame
 *       (changed cells only, positioned only on run breaks).</li>
 *   <li><b>2026 LAW</b> — every frame is wrapped in BSU/ESU iff the terminal
 *       declares synchronized output; without it no bracket bytes exist.</li>
 *   <li><b>DEGRADE LAWS</b> — ANSI16/C256 tiers receive mapped palette bytes
 *       (never 38;2/48;2) through the SgrStateTracker's mapped comparisons;
 *       two distinct RgbColors that map to one palette entry emit zero
 *       redundant sequences.</li>
 *   <li><b>PACING LAW</b> — the render loop waits each frame's DECLARED
 *       duration (the loop contract, tested with a scripted clock — no real
 *       sleeps); SPACE consumes the remaining time and emits the next frame
 *       immediately.</li>
 *   <li><b>KEY LAWS</b> — ESC and Q/q quit; SPACE skips; other keys are
 *       consumed. The exit callback fires EXACTLY ONCE — on the film's end or
 *       on a quit, never both, never twice. End-of-input (EOF) aborts
 *       playback without the hand-off (a dead sink's teardown owns the rest).</li>
 *   <li><b>WINDOW LAWS</b> — the screen mounts fullscreen/undecorated, and its
 *       single exit removes it from the GUI and arms a COMPLETE refresh (the
 *       resync law: the film wrote raw bytes the screen buffers never saw).</li>
 * </ul>
 */
class AnsiFilmScreenTest {

    /** Film A: palette colors, 2x2, three frames (durations 100/120/200). */
    private static final String FILM_A = String.join("\n",
            "FORMAT pfilm1",
            "COUNT 3",
            "COLS 2 ROWS 2",
            "FRAME 100",
            "Hi",
            "+X",
            "END",
            "FRAME 120",
            "\u001B[31mR\u001B[0mY",
            "\u001B[44mZ\u001B[0mW",
            "END",
            "FRAME 200",
            "\u001B[32mG",
            "t",
            "END");

    /** Film B: truecolor, 3x1, three frames (durations 150/250/100). */
    private static final String FILM_B = String.join("\n",
            "FORMAT pfilm1",
            "COUNT 3",
            "COLS 3 ROWS 1",
            "FRAME 150",
            "\u001B[38;2;255;214;71mA",
            "END",
            "FRAME 250",
            "\u001B[48;2;6;8;16m\u001B[38;2;10;200;100mB\u001B[0mC",
            "END",
            "FRAME 100",
            "D",
            "END");

    private record Rig(MockTerminal terminal, DefaultTextGUI gui) {
        AnsiFilmScreen screen(AnsiFilm film, Runnable onExit) {
            return new AnsiFilmScreen(film, gui, onExit);
        }
    }

    private static Rig rig() {
        var terminal = new MockTerminal(new TerminalSize(4, 3));
        var gui = new DefaultTextGUI(new DefaultScreen(terminal));
        return new Rig(terminal, gui);
    }

    private static AnsiFilm film(String src) {
        return AnsiFilm.parse(src);
    }

    /** Reads escapes readably in failure messages. */
    private static String repr(String s) {
        return s.replace("\u001B", "<ESC>");
    }

    // ── golden byte laws ────────────────────────────────────────────────

    @Test
    @DisplayName("GOLDEN: film A ticks — full frame 1, delta frames 2 and 3")
    void goldenFilmA() throws Exception {
        var r = rig();
        var screen = r.screen(film(FILM_A), () -> { });
        screen.tick();
        // Full repaint: autowrap off, home, reset, every cell of every row,
        // autowrap on. Tracker is fresh, so all-default cells emit no SGR.
        assertEquals("\u001B[?7l"
                        + "\u001B[1;1H\u001B[0mHi"
                        + "\u001B[2;1H+X"
                        + "\u001B[?7h",
                r.terminal().getOutput(), repr(r.terminal().getOutput()));

        r.terminal().clearOutput();
        screen.tick();
        // Delta vs frame 0: H→R (cursor+fg), i→Y (contiguous, reset to default),
        // +→Z (new row: cursor + bg), X→W (contiguous, bg→default reset).
        assertEquals("\u001B[1;1H\u001B[31mR\u001B[0mY"
                        + "\u001B[2;1H\u001B[44mZ\u001B[0mW",
                r.terminal().getOutput(), repr(r.terminal().getOutput()));

        r.terminal().clearOutput();
        screen.tick();
        // Delta vs frame 1 (the frame-2 parse brush paints t green: the brush
        // persists across body lines): R→G (fg swap), Y→empty (reset+space),
        // Zbg→t (new row: cursor + green fg; the bg was already reset by
        // frame 1), W→empty (contiguous space after the reset).
        assertEquals("\u001B[1;1H\u001B[32mG\u001B[0m "
                        + "\u001B[2;1H\u001B[32mt\u001B[0m ",
                r.terminal().getOutput(), repr(r.terminal().getOutput()));
    }

    @Test
    @DisplayName("GOLDEN: film B ticks — truecolor body, unchanged cells drop out of the delta")
    void goldenFilmB() throws Exception {
        var r = rig();
        var screen = r.screen(film(FILM_B), () -> { });
        screen.tick();
        assertEquals("\u001B[?7l"
                        + "\u001B[1;1H\u001B[0m\u001B[38;2;255;214;71mA\u001B[0m  "
                        + "\u001B[?7h",
                r.terminal().getOutput(), repr(r.terminal().getOutput()));

        r.terminal().clearOutput();
        screen.tick();
        // A→B (fg+bg swap); col 1 reverts to default in one reset; col 2 never
        // changed (EMPTY stays EMPTY) so it is not in the delta at all.
        assertEquals("\u001B[1;1H\u001B[38;2;10;200;100m\u001B[48;2;6;8;16mB\u001B[0mC",
                r.terminal().getOutput(), repr(r.terminal().getOutput()));

        r.terminal().clearOutput();
        screen.tick();
        // B→D needs no SGR (frame 1's own emission already reset the terminal
        // to defaults); the 'C'→space cell diff rides along with the 'D'.
        assertEquals("\u001B[1;1HD ",
                r.terminal().getOutput(), repr(r.terminal().getOutput()));
    }

    @Test
    @DisplayName("2026 LAW: brackets wrap every tick when sync is supported; none otherwise")
    void syncBracketLaw() throws Exception {
        var r = rig();
        r.terminal().setSyncOutputSupported(true);
        var screen = r.screen(film(FILM_A), () -> { });
        screen.tick();
        String first = r.terminal().getOutput();
        assertTrue(first.startsWith(io.jterm.style.AnsiCodes.BEGIN_SYNC_UPDATE),
                repr(first) + " must open with BSU");
        assertTrue(first.endsWith(io.jterm.style.AnsiCodes.END_SYNC_UPDATE),
                repr(first) + " must close with ESU");
        assertEquals(1, count(first, io.jterm.style.AnsiCodes.BEGIN_SYNC_UPDATE), repr(first));
        assertEquals(1, count(first, io.jterm.style.AnsiCodes.END_SYNC_UPDATE), repr(first));

        r.terminal().clearOutput();
        screen.tick();
        String second = r.terminal().getOutput();
        assertTrue(second.startsWith(io.jterm.style.AnsiCodes.BEGIN_SYNC_UPDATE), repr(second));
        assertTrue(second.endsWith(io.jterm.style.AnsiCodes.END_SYNC_UPDATE), repr(second));

        var r2 = rig(); // sync never declared
        var screen2 = r2.screen(film(FILM_A), () -> { });
        screen2.tick();
        String plain = r2.terminal().getOutput();
        assertFalse(plain.contains(io.jterm.style.AnsiCodes.BEGIN_SYNC_UPDATE), repr(plain));
        assertFalse(plain.contains(io.jterm.style.AnsiCodes.END_SYNC_UPDATE), repr(plain));
    }

    @Test
    @DisplayName("DEGRADE: ANSI16 tier — mapped palette bytes, no 38;2/48;2 anywhere")
    void ansi16DegradeLaw() throws Exception {
        var r = rig();
        r.terminal().setColorDepth(ColorDepth.ANSI16);
        var screen = r.screen(film(FILM_B), () -> { });
        screen.tick();
        // gold RgbColor(255,214,71) maps to BRIGHT_YELLOW (nearest ANSI-16).
        assertEquals("\u001B[?7l"
                        + "\u001B[1;1H\u001B[0m\u001B[93mA\u001B[0m  "
                        + "\u001B[?7h",
                r.terminal().getOutput(), repr(r.terminal().getOutput()));

        r.terminal().clearOutput();
        screen.tick();
        // fg (10,200,100) → CYAN "36"; bg (6,8,16) → BLACK "40".
        assertEquals("\u001B[1;1H\u001B[36m\u001B[40mB\u001B[0mC",
                r.terminal().getOutput(), repr(r.terminal().getOutput()));
    }

    @Test
    @DisplayName("DEGRADE: C256 tier — bytes are the mapped 38;5/48;5 indices, never 38;2/48;2")
    void c256DegradeLaw() throws Exception {
        var r = rig();
        r.terminal().setColorDepth(ColorDepth.C256);
        var screen = r.screen(film(FILM_B), () -> { });
        screen.tick();
        String out = r.terminal().getOutput();
        assertFalse(out.contains("38;2;") || out.contains("48;2;"), repr(out));
        var gold = new io.jterm.style.IndexedColor(
                io.jterm.style.IndexedColor.fromRgb(255, 214, 71).index());
        String goldFg = "\u001B[" + new String(gold.fgSequence(), java.nio.charset.StandardCharsets.UTF_8) + "m";
        assertTrue(out.contains(goldFg), repr(out) + " must carry the mapped gold index " + goldFg);
    }

    @Test
    @DisplayName("DEGRADE: two distinct RgbColors mapping to one ANSI16 entry emit zero redundant SGR")
    void mappedCollapseLaw() throws Exception {
        // (255,214,71) and (250,210,80) both degrade to BRIGHT_YELLOW on ANSI16.
        String src = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 2 ROWS 1",
                "FRAME 100",
                "\u001B[38;2;255;214;71mX\u001B[38;2;250;210;80mY",
                "END");
        var r = rig();
        r.terminal().setColorDepth(ColorDepth.ANSI16);
        var screen = r.screen(film(src), () -> { });
        screen.tick();
        assertEquals("\u001B[?7l"
                        + "\u001B[1;1H\u001B[0m\u001B[93mXY"
                        + "\u001B[?7h",
                r.terminal().getOutput(),
                "the second mapped-equal cell must emit no SGR at all: "
                        + repr(r.terminal().getOutput()));
    }

    // ── pacing law (loop contract, scripted clock — no real sleeps) ─────

    @Test
    @DisplayName("PACING: the loop awaits each frame's declared duration; the film end fires the exit once")
    void pacingLaw() throws Exception {
        var r = rig();
        AtomicBoolean exit = new AtomicBoolean(false);
        AtomicInteger exits = new AtomicInteger();
        Runnable onExit = () -> {
            if (exit.compareAndSet(false, true)) {
                exits.incrementAndGet();
            }
        };
        long[] durations = {100, 120, 200};
        assertEquals(durations[0] + durations[1] + durations[2],
                420, "the law films tile 420ms");

        var clock = new ScriptedClock();
        var input = new ScriptedInput(() -> { });
        var screen = r.screen(film(FILM_A), onExit);
        screen.playback(clock, input);

        // Waited exactly the declared duration of every frame, in order.
        assertEquals(List.of(100L, 120L, 200L), input.timeouts,
                "the loop must await the declared durations: " + input.timeouts);
        assertEquals(1, exits.get(), "the film end fires the exit exactly once");
        assertTrue(screen.isFinishedForFilmTests());
    }

    @Test
    @DisplayName("PACING: SPACE consumes the remaining frame time and the next frame emits immediately")
    void spaceSkipLaw() throws Exception {
        var r = rig();
        AtomicInteger exits = new AtomicInteger();
        Runnable onExit = exits::incrementAndGet;

        var clock = new ScriptedClock();
        var input = new ScriptedInput(() -> { });
        input.queue.add(new KeyStroke(KeyType.CHARACTER, ' ', false, false, false));
        var screen = r.screen(film(FILM_A), onExit);
        screen.playback(clock, input);

        // Frame 0's wait is SKIPPED at once (timeout budget was 100, the wait
        // ended before the clock moved); frames 1 and 2 honor their rates.
        assertEquals(100L, input.timeouts.get(0), "frame 0's wait opened with its declared budget");
        assertEquals(120L, input.timeouts.get(1), "frame 1's wait opened with its declared budget");
        assertEquals(200L, input.timeouts.get(2), "frame 2's wait opened with its declared budget");
        assertEquals(1, exits.get(), "the skip does not fire an extra exit");
    }

    @Test
    @DisplayName("KEY LAWS: ESC and Q quit exactly once; other keys are consumed and ignored")
    void quitKeyLaws() throws Exception {
        for (KeyStroke quitKey : List.of(
                new KeyStroke(KeyType.ESCAPE),
                new KeyStroke(KeyType.CHARACTER, 'Q', false, false, false),
                new KeyStroke(KeyType.CHARACTER, 'q', false, false, false))) {
            var r = rig();
            AtomicInteger exits = new AtomicInteger();
            var clock = new ScriptedClock();
            var input = new ScriptedInput(() -> { });
            input.queue.add(quitKey);
            var screen = r.screen(film(FILM_A), exits::incrementAndGet);
            screen.playback(clock, input);

            assertEquals(1, exits.get(), "quit via " + quitKey.type() + " fires the exit once");
            assertFalse(screen.isFinishedForFilmTests(),
                    "a quit mid-film stops before the film completes (frame 1 only)");
            assertEquals(1, input.timeouts.size(),
                    "playback stops at the quit: only frame 0's wait was opened");
        }
    }

    @Test
    @DisplayName("KEY LAWS: an uninteresting key is consumed and playback continues")
    void otherKeysConsumed() throws Exception {
        var r = rig();
        AtomicInteger exits = new AtomicInteger();
        var clock = new ScriptedClock();
        var input = new ScriptedInput(() -> { });
        input.queue.add(new KeyStroke(KeyType.CHARACTER, 'a', false, false, false));
        var screen = r.screen(film(FILM_A), exits::incrementAndGet);
        screen.playback(clock, input);
        // 'a' was consumed during frame 0's wait: the frame-0 wait re-opens
        // with the (unadvanced) remaining budget, then frames 1-2 run at law.
        assertEquals(100L, input.timeouts.get(0), "frame 0's wait opened with its declared budget");
        assertTrue(input.timeouts.get(1) <= 100L,
                "the re-opened frame-0 wait budgets at most the remaining time: " + input.timeouts);
        assertEquals(List.of(120L, 200L), input.timeouts.subList(2, 4),
                "frames 1 and 2 still honor their declared rates");
        assertEquals(1, exits.get(), "the film end still fires the exit once");
    }

    @Test
    @DisplayName("DEAD INPUT: EOF aborts playback without the exit hand-off")
    void eofAbortsQuietly() throws Exception {
        var r = rig();
        AtomicInteger exits = new AtomicInteger();
        var clock = new ScriptedClock();
        var input = new ScriptedInput(() -> { });
        input.queue.add(new KeyStroke(KeyType.EOF));
        var screen = r.screen(film(FILM_A), exits::incrementAndGet);
        screen.playback(clock, input);
        assertEquals(0, exits.get(), "a dead input is not a hand-off");
        assertEquals(1, input.timeouts.size(), "playback stopped at the EOF");
    }

    // ── window lifecycle laws ───────────────────────────────────────────

    @Test
    @DisplayName("WINDOW: the screen mounts fullscreen, and its exit removes it and arms a complete refresh")
    void windowLifecycleLaw() throws Exception {
        var r = rig();
        AtomicInteger exits = new AtomicInteger();
        r.gui().addWindow(r.screen(film(FILM_A), exits::incrementAndGet));
        var screen = theFilm(r);
        assertTrue(r.gui().getWindows().contains(screen), "the film window is mounted");
        assertEquals(java.util.List.of(io.jterm.window.WindowHint.FULLSCREEN,
                        io.jterm.window.WindowHint.NO_DECORATIONS),
                screen.getHints(), "fullscreen + no decorations like the doorway window");

        // The exit removes the window, runs the hand-off once and arms the
        // COMPLETE resync (the film wrote raw bytes the screen buffers never saw).
        screen.quitForFilmTests();
        assertFalse(r.gui().getWindows().contains(screen), "the exit leaves the GUI");
        assertEquals(1, exits.get(), "the exit callback ran exactly once");
    }

    @Test
    @DisplayName("QUIT via handleKeyStroke is the window law: exit once, keys consumed")
    void handleKeyStrokeLaws() throws Exception {
        var r = rig();
        AtomicInteger exits = new AtomicInteger();
        var screen = r.screen(film(FILM_A), exits::incrementAndGet);
        assertTrue(screen.handleKeyStroke(new KeyStroke(KeyType.ESCAPE)),
                "the film consumes ESC");
        assertEquals(1, exits.get());
        assertTrue(screen.handleKeyStroke(new KeyStroke(KeyType.CHARACTER, ' ', false, false, false)),
                "later keys stay consumed");
        assertEquals(1, exits.get(), "no second exit after the first");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a wide-glyph frame delta marks the twin column (double-width walk law)")
    void wideGlyphDeltaLaw() throws Exception {
        var terminal = new io.jterm.core.MockTerminal(new io.jterm.core.TerminalSize(6, 3));
        var gui = new io.jterm.window.DefaultTextGUI(new io.jterm.screen.DefaultScreen(terminal));
        var screen = new AnsiFilmScreen(AnsiFilm.parse(String.join("\n",
                "FORMAT pfilm1",
                "COUNT 2",
                "COLS 4 ROWS 1",
                "FRAME 100",
                "\u66F4A",
                "END",
                "FRAME 100",
                "B\u65B0",
                "END")), gui, () -> { });
        screen.tick();
        screen.tick();
        // The wide cells both landed on the wire; the delta walk advanced
        // past the wide cells' twin columns without a phantom cursor reposition.
        String out = terminal.getOutput();
        assertTrue(out.contains("\u66F4") && out.contains("\u65B0"), repr(out));
    }

    @Test
    @DisplayName("the double-play guard refuses a concurrent second play")
    void doublePlayGuardLaw() throws Exception {
        var gui = new io.jterm.window.DefaultTextGUI(new io.jterm.screen.DefaultScreen(
                new io.jterm.core.MockTerminal(new io.jterm.core.TerminalSize(4, 3)) {
                    @Override
                    public java.util.Optional<io.jterm.core.input.KeyStroke> pollInput(long timeoutMillis)
                            throws IOException {
                        // Every poll burns most of its budget: play() hangs mid-film.
                        try {
                            Thread.sleep(Math.max(1, Math.min(timeoutMillis, 40)));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return java.util.Optional.empty();
                        }
                        return java.util.Optional.empty();
                    }
                }));
        AtomicInteger exits = new AtomicInteger();
        var screen = new AnsiFilmScreen(AnsiFilm.parse(FILM_A), gui, exits::incrementAndGet);
        var player = Thread.ofVirtual().start(screen::play);

        long waited = 0;
        while (!screen.isPlayingForTests() && waited < 2_000) {
            Thread.sleep(5);
            waited += 5;
        }
        assertTrue(screen.isPlayingForTests(), "the first play is running");
        assertThrows(IllegalStateException.class, screen::play,
                "a concurrent second play is refused");
        player.join(3_000);
        assertFalse(player.isAlive(), "the first play finished (poll budgets ≤ 40ms)");
        assertEquals(1, exits.get(), "the film still handed off exactly once");
    }

    private static AnsiFilmScreen theFilm(Rig r) {
        for (var w : r.gui().getWindows()) {
            if (w instanceof AnsiFilmScreen film) {
                return film;
            }
        }
        throw new AssertionError("no film screen mounted");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            n++;
            idx += needle.length();
        }
        return n;
    }
}
