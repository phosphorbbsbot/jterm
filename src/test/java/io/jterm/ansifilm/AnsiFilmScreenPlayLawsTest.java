package io.jterm.ansifilm;

import io.jterm.core.input.KeyStroke;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-completion laws for the film screen's seams outside the
 * {@link AnsiFilmScreenTest} byte laws: the public play() path (real clock,
 * real wiring), the IO-fault law, the draw() blit for stray repaints, and the
 * per-key consumption laws on the window surface.
 */
class AnsiFilmScreenPlayLawsTest {

    /** Film A: the three-frame law film (durations 100/120/200). */
    private static AnsiFilm filmA() {
        String src = String.join("\n",
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
        return AnsiFilm.parse(src);
    }

    private static AnsiFilmScreen screen(AnsiFilm film, Runnable onExit) {
        var terminal = new io.jterm.core.MockTerminal(new io.jterm.core.TerminalSize(4, 3));
        var gui = new io.jterm.window.DefaultTextGUI(new io.jterm.screen.DefaultScreen(terminal));
        return new AnsiFilmScreen(film, gui, onExit);
    }

    @Test
    @DisplayName("play(): the real path (wall clock + terminal input) plays the film and hands off once")
    void realPlayLaw() {
        AtomicInteger exits = new AtomicInteger();
        var screen = screen(filmA(), exits::incrementAndGet);
        long start = System.nanoTime();
        screen.play();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(1, exits.get(), "the film end fires the exit exactly once");
        assertTrue(screen.isFinishedForFilmTests(), "all frames emitted");
        assertTrue(elapsedMs >= 370, "the real pacing awaited the declared durations (>= 420ms): "
                + elapsedMs + "ms");
    }

    @Test
    @DisplayName("IO FAULT: a dead terminal input aborts play() quietly without the hand-off")
    void ioFaultLaw() {
        var terminal = new io.jterm.core.MockTerminal(new io.jterm.core.TerminalSize(4, 3)) {
            @Override
            public java.util.Optional<io.jterm.core.input.KeyStroke> pollInput(long timeoutMillis)
                    throws java.io.IOException {
                throw new java.io.IOException("sink died");
            }
        };
        var gui = new io.jterm.window.DefaultTextGUI(new io.jterm.screen.DefaultScreen(terminal));
        AtomicInteger exits = new AtomicInteger();
        var screen = new AnsiFilmScreen(filmA(), gui, exits::incrementAndGet);
        assertDoesNotThrow(screen::play, "play() swallows the IO fault");
        assertEquals(0, exits.get(), "a dead sink is not a hand-off");
        assertFalse(screen.isFinishedForFilmTests(), "playback stopped at the fault");
    }

    @Test
    @DisplayName("draw(): stray GUI repaints blit the last emitted frame (and no-op before emission)")
    void drawBlitLaw() throws Exception {
        var terminal = new io.jterm.core.MockTerminal(new io.jterm.core.TerminalSize(6, 4));
        var gui = new io.jterm.window.DefaultTextGUI(new io.jterm.screen.DefaultScreen(terminal));
        var screen = new AnsiFilmScreen(AnsiFilm.parse(String.join("\n",
                "FORMAT pfilm1", "COUNT 1", "COLS 3 ROWS 1", "FRAME 50",
                "\u001B[31mAB", "END")), gui, () -> { });

        // Nothing emitted yet: draw() is a no-op (the canvas stays untouched).
        var canvas = new io.jterm.screen.ScreenBuffer(new io.jterm.core.TerminalSize(6, 4));
        var g = new io.jterm.graphics.TextGraphics(canvas);
        assertDoesNotThrow(() -> screen.draw(g));
        assertEquals(io.jterm.style.TextCell.EMPTY, canvas.getCell(0, 0),
                "no frame, no blit");

        screen.tick();
        screen.draw(g);
        assertEquals("A", canvas.getCell(0, 0).character(), "the emitted frame blits");
        assertEquals("B", canvas.getCell(1, 0).character());
        assertEquals(io.jterm.style.TextCell.EMPTY, canvas.getCell(3, 0),
                "the blit clamps to the film's size (3 cols), not the 6-col canvas");
        assertEquals(null, screen.getPreferredSize(), "FULLSCREEN: no preferred size");
    }

    @Test
    @DisplayName("tick(): past the last frame the emitter refuses (the playhead is one-way)")
    void tickPastEndLaw() throws Exception {
        var screen = screen(filmA(), () -> { });
        screen.tick();
        screen.tick();
        screen.tick();
        assertThrows(IllegalStateException.class, screen::tick);
    }

    @Test
    @DisplayName("the SPACE flag raised via handleKeyStroke consumes the current frame's remaining time")
    void skipViaWindowKeyLaw() throws Exception {
        var terminal = new io.jterm.core.MockTerminal(new io.jterm.core.TerminalSize(4, 3));
        var gui = new io.jterm.window.DefaultTextGUI(new io.jterm.screen.DefaultScreen(terminal));
        AtomicInteger exits = new AtomicInteger();
        var screen = new AnsiFilmScreen(filmA(), gui, exits::incrementAndGet);

        // SPACE before the loop: the current frame's wait resolves instantly.
        assertTrue(screen.handleKeyStroke(new KeyStroke(io.jterm.core.input.KeyType.CHARACTER,
                ' ', false, false, false)), "the film consumes the key");

        var clock = new ScriptedClock();
        var input = new ScriptedInput(() -> { });
        screen.playback(clock, input);

        assertEquals(java.util.List.of(120L, 200L), input.timeouts,
                "frame 0's wait was skipped entirely; frames 1-2 ran at law: " + input.timeouts);
        assertEquals(1, exits.get());
    }

    @Test
    @DisplayName("a Ctrl-modified letter is consumed and ignored like every other lawless key")
    void ctrlKeyConsumedLaw() throws Exception {
        AtomicInteger exits = new AtomicInteger();
        var screen = screen(filmA(), exits::incrementAndGet);
        var clock = new ScriptedClock();
        var input = new ScriptedInput(() -> { });
        input.queue.add(new KeyStroke(io.jterm.core.input.KeyType.CHARACTER,
                'C', true, false, false));
        screen.playback(clock, input);
        assertEquals(100L, input.timeouts.get(0), "frame 0's wait opened with its declared budget");
        assertTrue(input.timeouts.get(1) <= 100L,
                "the re-opened frame-0 wait budgets at most the remaining time: " + input.timeouts);
        assertEquals(java.util.List.of(120L, 200L), input.timeouts.subList(2, 4),
                "frames 1 and 2 still honor their declared rates");
        assertEquals(1, exits.get(), "the film end still fires the exit once");
    }

    @Test
    @DisplayName("exit on a plain TextGUI (not DefaultTextGUI) still removes and hands off once")
    void nonResynchronizingGuiLaw() {
        var terminal = new io.jterm.core.MockTerminal(new io.jterm.core.TerminalSize(4, 3));
        var screenObj = new io.jterm.screen.DefaultScreen(terminal);
        var gui = new PlainTextGui(screenObj);
        AtomicInteger exits = new AtomicInteger();
        var screen = new AnsiFilmScreen(filmA(), gui, exits::incrementAndGet);
        gui.addWindow(screen);
        assertTrue(gui.getWindows().contains(screen));

        screen.quitForFilmTests();
        assertFalse(gui.getWindows().contains(screen), "the exit leaves the GUI");
        assertEquals(1, gui.requestRefreshes.get(), "the plain-GUI law arms a refresh");
        assertEquals(1, exits.get(), "the hand-off fired once");
    }

    @Test
    @DisplayName("a lawless key on the window surface is consumed; the exit fires once, twice-safe")
    void windowSurfaceConsumptionLaws() {
        AtomicInteger exits = new AtomicInteger();
        var screen = screen(filmA(), exits::incrementAndGet);
        assertTrue(screen.handleKeyStroke(new KeyStroke(io.jterm.core.input.KeyType.CHARACTER,
                'x', false, false, false)), "lawless keys are consumed while mounted");
        screen.quitForFilmTests();
        assertTrue(screen.handleKeyStroke(new KeyStroke(io.jterm.core.input.KeyType.ESCAPE)),
                "keys after the exit stay consumed");
        assertEquals(1, exits.get(), "the exit law: exactly once, twice-safe");
    }

    @Test
    @DisplayName("PACING: clock drift past the deadline between frames ends the frame's wait early")
    void clockDriftLaw() throws Exception {
        var terminal = new io.jterm.core.MockTerminal(new io.jterm.core.TerminalSize(4, 3));
        var gui = new io.jterm.window.DefaultTextGUI(new io.jterm.screen.DefaultScreen(terminal));
        AtomicInteger exits = new AtomicInteger();
        var screen = new AnsiFilmScreen(filmA(), gui, exits::incrementAndGet);
        var clock = new ScriptedClock();
        var input = new AnsiFilmScreen.FilmInput() {
            boolean first = true;

            @Override
            public java.util.Optional<KeyStroke> poll(long timeoutMillis) {
                if (first) {
                    first = false;
                    clock.nowMillis += timeoutMillis + 50; // one frame's wait, plus drift
                    return java.util.Optional.of(new KeyStroke(
                            io.jterm.core.input.KeyType.CHARACTER, 'a', false, false, false));
                }
                clock.nowMillis += timeoutMillis;
                return java.util.Optional.empty();
            }
        };
        screen.playback(clock, input);
        assertEquals(1, exits.get(), "the film ran to its end despite the drift");
    }

    @Test
    @DisplayName("CTOR: a gui that does not back a DefaultScreen is refused loudly")
    void nonDefaultScreenGuiRefusedLaw() throws Exception {
        var gui = new PlainTextGui(new io.jterm.screen.Screen() {
            @Override
            public io.jterm.core.TerminalSize doResizeIfNecessary() {
                return new io.jterm.core.TerminalSize(4, 3);
            }

            @Override
            public io.jterm.core.TerminalSize getTerminalSize() {
                return new io.jterm.core.TerminalSize(4, 3);
            }

            @Override
            public io.jterm.core.TerminalPosition getCursorPosition() {
                return io.jterm.core.TerminalPosition.TOP_LEFT;
            }

            @Override
            public void setCursorPosition(io.jterm.core.TerminalPosition position) {
            }

            @Override
            public io.jterm.core.TerminalPosition getCursorAnchor() {
                return null;
            }

            @Override
            public void setCursorAnchor(io.jterm.core.TerminalPosition position) {
            }

            @Override
            public io.jterm.style.TextCell getBackCell(int col, int row) {
                return io.jterm.style.TextCell.EMPTY;
            }

            @Override
            public io.jterm.style.TextCell getFrontCell(int col, int row) {
                return io.jterm.style.TextCell.EMPTY;
            }

            @Override
            public void setCell(int col, int row, io.jterm.style.TextCell cell) {
            }

            @Override
            public void setCell(io.jterm.core.TerminalPosition position, io.jterm.style.TextCell cell) {
            }

            @Override
            public void clear() {
            }

            @Override
            public void refresh() {
            }

            @Override
            public void refresh(io.jterm.screen.RefreshType type) {
            }

            @Override
            public void startScreen() {
            }

            @Override
            public void stopScreen() {
            }

            @Override
            public void close() {
            }
        });
        var e = assertThrows(IllegalArgumentException.class,
                () -> new AnsiFilmScreen(filmA(), gui, () -> { }));
        assertTrue(e.getMessage().contains("DefaultScreen"), e.getMessage());
    }

    /** A no-op TextGUI whose getScreen() backs a real DefaultScreen (the plain law). */
    private static final class PlainTextGui implements io.jterm.window.TextGUI {
        private final io.jterm.screen.Screen screen;
        final AtomicInteger requestRefreshes = new AtomicInteger();
        private final java.util.List<io.jterm.window.Window> windows = new java.util.concurrent.CopyOnWriteArrayList<>();

        PlainTextGui(io.jterm.screen.Screen screen) {
            this.screen = screen;
        }

        @Override
        public io.jterm.screen.Screen getScreen() {
            return screen;
        }

        @Override
        public void addWindow(io.jterm.window.Window window) {
            windows.add(window);
        }

        @Override
        public void removeWindow(io.jterm.window.Window window) {
            windows.remove(window);
        }

        @Override
        public io.jterm.window.Window getActiveWindow() {
            return null;
        }

        @Override
        public void setActiveWindow(io.jterm.window.Window window) {
        }

        @Override
        public boolean processInput() {
            return false;
        }

        @Override
        public void waitForInput() {
        }

        @Override
        public void updateScreen() {
        }

        @Override
        public void requestRefresh() {
            requestRefreshes.incrementAndGet();
        }

        @Override
        public java.util.Collection<io.jterm.window.Window> getWindows() {
            return java.util.List.copyOf(windows);
        }

        @Override
        public void close() {
        }
    }
}
