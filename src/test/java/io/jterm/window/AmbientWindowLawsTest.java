package io.jterm.window;

import io.jterm.animation.AnimatedBackground;
import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyStroke;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.DefaultScreen;
import io.jterm.screen.Screen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ambient-window laws added with the menu ambient scenes (card t_a9be4757):
 *
 * <ul>
 *   <li>OVERLAY-HOLD LAW — an ambient window holds its frame (no tick, no
 *       refresh request) while a MODAL window is active on the GUI.</li>
 *   <li>DETACHED-PAUSE LAW — an ambient window not mounted on its GUI no
 *       longer advances or requests refreshes (eviction can never leave a
 *       ticking, repainting zombie).</li>
 *   <li>SYNCHRONIZED-OUTPUT DEGRADE LAW — with 2026 sync the ambient runs at
 *       its target fps; without it the timer caps at 2 fps so unbracketed
 *       full-frame repaints never churn slow renderers.</li>
 *   <li>INPUT-LATENCY CONTRACT — animation ticks run on the animation thread
 *       only: a slow ambient frame cannot stretch key handling on the GUI
 *       loop.</li>
 * </ul>
 */
class AmbientWindowLawsTest {

    private static final TerminalSize SIZE = new TerminalSize(80, 24);

    /** Ambient stub: counts ticks/renders, optional target fps and delay. */
    static class CountingAmbient implements AnimatedBackground {
        final int fps;
        final long renderDelayMillis;
        int tickCount;
        int renderCount;
        boolean running;

        CountingAmbient(int fps) { this(fps, 0); }

        CountingAmbient(int fps, long renderDelayMillis) {
            this.fps = fps;
            this.renderDelayMillis = renderDelayMillis;
        }

        @Override public void renderFrame(TextGraphics g, TerminalSize size) {
            renderCount++;
            if (renderDelayMillis > 0) {
                try { Thread.sleep(renderDelayMillis); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        @Override public void onResize(TerminalSize newSize) { }
        @Override public void start() { running = true; }
        @Override public void stop() { running = false; }
        @Override public boolean isRunning() { return running; }
        @Override public int targetFps() { return fps; }
        @Override public void tick(long nowNanos) { tickCount++; }
    }

    /** Recording TextGUI: owns a window list, a configurable modal, and a refresh counter. */
    static class RecordingGui implements TextGUI {
        final List<Window> windows = new ArrayList<>();
        Window modal;
        Screen screen;
        int refreshRequests;

        @Override public Screen getScreen() { return screen; }
        @Override public void addWindow(Window window) { windows.add(window); }
        @Override public void removeWindow(Window window) { windows.remove(window); }
        @Override public Window getActiveWindow() { return null; }
        @Override public void setActiveWindow(Window window) { }
        @Override public boolean processInput() { return false; }
        @Override public void waitForInput() { }
        @Override public void updateScreen() { }
        @Override public void requestRefresh() { refreshRequests++; }
        @Override public void close() { }
        @Override public List<Window> getWindows() { return windows; }
        @Override public Window modalWindow() { return modal; }
    }

    private final List<AutoCloseable> timers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (var t : timers) {
            try { t.close(); } catch (Exception ignored) { }
        }
        timers.clear();
    }

    private void track(AnimatedBackgroundWindow window) {
        timers.add(() -> window.stop());
    }

    @Test
    @DisplayName("OVERLAY-HOLD: renderTick holds while a MODAL window is active")
    void overlayHoldLaw() {
        var ambient = new CountingAmbient(10);
        var gui = new RecordingGui();
        var window = new AnimatedBackgroundWindow(ambient, gui, true);
        window.setBounds(TerminalPosition.TOP_LEFT, SIZE);
        gui.addWindow(window);

        window.renderTick();
        assertEquals(1, ambient.tickCount, "tick advances with no modal");

        // A modal on the stack: the ambient holds — no tick, no refresh ask.
        var modalWindow = new WindowImpl("modal");
        modalWindow.setHints(List.of(WindowHint.MODAL));
        gui.modal = modalWindow;

        window.renderTick();
        window.renderTick();
        assertEquals(1, ambient.tickCount, "a modal pauses animation ticks");
        assertEquals(1, window.refreshRequests(), "a modal pauses refresh requests");
        assertTrue(window.isPausedByGui(), "the pause predicate reports the held state");
    }

    @Test
    @DisplayName("DETACHED-PAUSE: an unmounted ambient never advances or refreshes")
    void detachedPauseLaw() {
        var ambient = new CountingAmbient(10);
        var gui = new RecordingGui();
        var window = new AnimatedBackgroundWindow(ambient, gui, true);
        window.setBounds(TerminalPosition.TOP_LEFT, SIZE);
        gui.addWindow(window);

        window.renderTick();
        assertEquals(1, ambient.tickCount);

        // Eviction: removeWindow filters the pending-removal queue, so the
        // ambient immediately reads as absent from getWindows().
        gui.removeWindow(window);
        assertFalse(gui.getWindows().contains(window), "removed windows leave getWindows()");

        window.renderTick();
        assertEquals(1, ambient.tickCount, "an unmounted ambient must not tick");
        assertEquals(1, window.refreshRequests(), "an unmounted ambient must not refresh");
        assertTrue(window.isPausedByGui(), "the pause predicate reports the detached state");
    }

    @Test
    @DisplayName("SYNCHRONIZED-OUTPUT DEGRADE (ambient opt-in): 8 fps target -> 2 fps without 2026 sync")
    void degradeLawWithoutSync() throws Exception {
        var screen = new DefaultScreen(new MockTerminal(SIZE));
        var gui = new DefaultTextGUI(screen);

        var ambient = new CountingAmbient(8);
        var window = new AnimatedBackgroundWindow(ambient, gui, true, true);
        window.setBounds(TerminalPosition.TOP_LEFT, SIZE);
        gui.addWindow(window);
        track(window);
        window.start();

        assertEquals(2, window.getTimer().getTargetFps(),
                "without 2026 sync the ambient timer caps at 2 fps");
        window.stop();
    }

    @Test
    @DisplayName("SYNCHRONIZED-OUTPUT DEGRADE (ambient opt-in): full target fps with 2026 sync")
    void degradeLawWithSync() throws Exception {
        var terminal = new MockTerminal(SIZE);
        terminal.setSyncOutputSupported(true);
        var screen = new DefaultScreen(terminal);
        var gui = new DefaultTextGUI(screen);

        var ambient = new CountingAmbient(8);
        var window = new AnimatedBackgroundWindow(ambient, gui, true, true);
        window.setBounds(TerminalPosition.TOP_LEFT, SIZE);
        gui.addWindow(window);
        track(window);
        window.start();

        assertEquals(8, window.getTimer().getTargetFps(),
                "with 2026 sync the ambient runs at its target fps");
        window.stop();
    }

    @Test
    @DisplayName("LEGACY WINDOWS never cap: a non-opt-in window keeps its target rate")
    void legacyWindowRunsUncapped() throws Exception {
        var screen = new DefaultScreen(new MockTerminal(SIZE));
        var gui = new DefaultTextGUI(screen);

        var window = new AnimatedBackgroundWindow(new CountingAmbient(8), gui, true);
        window.setBounds(TerminalPosition.TOP_LEFT, SIZE);
        gui.addWindow(window);
        track(window);
        window.start();

        assertEquals(8, window.getTimer().getTargetFps(),
                "a window that did not opt into the ambient degrade keeps its target fps");
        window.stop();
    }

    @Test
    @DisplayName("DEGRADE falls open: a window with no (or non-Default) screen cannot see sync")
    void degradeWithoutSyncAwareTerminal() {
        // gui == null: no terminal to consult -> the degrade cap applies.
        var degenerate = new AnimatedBackgroundWindow(new CountingAmbient(8), null, true, true);
        assertEquals(2, degenerate.effectiveFps(), "no gui: no sync query, capped");

        // A non-DefaultScreen screen: no terminal access -> capped.
        var stubScreen = new io.jterm.screen.Screen() {
            @Override public void startScreen() { }
            @Override public void stopScreen() { }
            @Override public void clear() { }
            @Override public void setCell(int c, int r, io.jterm.style.TextCell cell) { }
            @Override public void setCell(io.jterm.core.TerminalPosition pos, io.jterm.style.TextCell cell) { }
            @Override public io.jterm.style.TextCell getFrontCell(int c, int r) { return null; }
            @Override public io.jterm.style.TextCell getBackCell(int c, int r) { return null; }
            @Override public void refresh() { }
            @Override public void refresh(io.jterm.screen.RefreshType type) { }
            @Override public io.jterm.core.TerminalSize getTerminalSize() { return SIZE; }
            @Override public io.jterm.core.TerminalPosition getCursorPosition() { return null; }
            @Override public io.jterm.core.TerminalSize doResizeIfNecessary() { return SIZE; }
            @Override public void setCursorPosition(io.jterm.core.TerminalPosition position) { }
            @Override public void close() { }
        };
        var stubGui = new RecordingGui();
        stubGui.screen = stubScreen;
        var capped = new AnimatedBackgroundWindow(new CountingAmbient(8), stubGui, true, true);
        assertEquals(2, capped.effectiveFps(), "non-DefaultScreen screen: capped");
    }

    @Test
    @DisplayName("INPUT-LATENCY: a slow ambient frame cannot stretch key handling")
    void inputLatencyContract() throws Exception {
        var terminal = new MockTerminal(SIZE) {
            boolean injected = false;
            @Override
            public java.util.Optional<KeyStroke> pollInput() {
                if (!injected) {
                    injected = true;
                    return java.util.Optional.of(KeyStroke.character('L', false, false, false));
                }
                return java.util.Optional.empty();
            }
        };
        var screen = new DefaultScreen(terminal);
        screen.startScreen();
        var gui = new DefaultTextGUI(screen);

        var window = new WindowImpl("menu");
        window.setHints(List.of(WindowHint.FULLSCREEN, WindowHint.TRANSPARENT));
        gui.addWindow(window);

        var ambient = new CountingAmbient(6, 350); // one frame = 350 ms
        var ambientWindow = new AnimatedBackgroundWindow(ambient, gui, true);
        ambientWindow.setBounds(TerminalPosition.TOP_LEFT, SIZE);
        gui.addWindow(ambientWindow);
        track(ambientWindow);
        ambientWindow.start();

        // Key handling on the GUI loop must stay fast while the animation
        // ticks run on their own thread with 350 ms frames.
        long start = System.nanoTime();
        boolean processed = gui.processInput();
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(processed, "the keystroke was processed");
        assertTrue(latencyMs < 100,
                "input latency " + latencyMs + "ms must stay far below the 350ms frame time");
        assertTrue(ambient.renderCount >= 1, "the ambient is rendering on its own thread");
    }
}
