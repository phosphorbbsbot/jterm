package io.jterm.window;

import io.jterm.animation.PhosphorDoorwayEffect;
import io.jterm.core.input.KeyType;
import io.jterm.core.input.KeyStroke;
import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.DefaultScreen;
import io.jterm.screen.Screen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Doorway-window laws for {@link DoorwayWindow} — the fullscreen skippable
 * host of the Phosphor doorway intro (animation plan card 2):
 *
 * <ul>
 *   <li><b>ACTIVE-WINDOW LAW</b> — the door is a FULLSCREEN, NO_DECORATIONS
 *       window and never carries the BACKGROUND hint: the GUI event loop
 *       makes it the ACTIVE window, so keystrokes reach its skip handler
 *       (a background window would never receive input).</li>
 *   <li><b>SKIP LAW</b> — ANY keystroke consumed by the door exits once: the
 *       single-exit callback fires exactly once, the animation timer stops
 *       (zero CPU after the skip), and a further keystroke is still consumed
 *       but never re-fires the exit.</li>
 *   <li><b>FINISH-THROUGH-DRAW LAW</b> — the play finishing on the animation
 *       thread only ARMS the exit ({@code exitPending}); the GUI mutation
 *       itself happens on the event-loop thread, from the next painted
 *       {@code draw()} (the ambient-card draw-time-hook law). The exit runs
 *       once.</li>
 *   <li><b>NO-PRERENDER LAW</b> — start() advances no logic ticks: the intro
 *       opens on its true frame 0 (the bare black ground), unlike the
 *       ambient's warm-start pre-render.</li>
 *   <li><b>REAL-TIME SYNC LAW</b> — with 2026 sync the timer runs at the
 *       effect's 60 fps logic rate (1 tick per render); without it the
 *       ambient-style fps cap engages and the door compensates with N ticks
 *       per render, so the 5.0 s timeline holds at any render fps.</li>
 *   <li><b>DETACHED-ZERO-CPU LAW</b> — eviction (window closed off its GUI,
 *       e.g. the session disconnecting mid-intro) stops the timer via
 *       close(): no ticking zombie survives the door.</li>
 * </ul>
 */
class DoorwayWindowTest {

    private static final TerminalSize SIZE = new TerminalSize(80, 24);

    private final List<AutoCloseable> timers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (var t : timers) {
            try { t.close(); } catch (Exception ignored) { }
        }
        timers.clear();
    }

    /** TextGUI stub with a real screen tier (for the sync-capability reads). */
    static class RecordingGui implements TextGUI {
        final List<Window> windows = new ArrayList<>();
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
        @Override public Window modalWindow() { return null; }
    }

    private RecordingGui gui(boolean sync) throws IOException {
        var terminal = new MockTerminal(SIZE);
        terminal.setSyncOutputSupported(sync);
        var gui = new RecordingGui();
        gui.screen = new DefaultScreen(terminal);
        return gui;
    }

    private void track(DoorwayWindow window) {
        timers.add(() -> window.stop());
    }

    @Test
    @DisplayName("ACTIVE-WINDOW: FULLSCREEN + NO_DECORATIONS, never BACKGROUND; FULLSCREEN sizes to the terminal")
    void activeWindowLaw() {
        var door = new DoorwayWindow(new PhosphorDoorwayEffect(), null, false, () -> { });
        var hints = door.getHints();
        assertTrue(hints.contains(WindowHint.FULLSCREEN), "fullscreen: " + hints);
        assertTrue(hints.contains(WindowHint.NO_DECORATIONS), "no decorations: " + hints);
        assertFalse(hints.contains(WindowHint.BACKGROUND),
                "the door must be the ACTIVE window to receive the skip keys: " + hints);
        assertNull(door.getPreferredSize(), "FULLSCREEN: the layout sizes the door to the terminal");
    }

    @Test
    @DisplayName("SKIP: any keystroke consumes, fires the exit exactly once, stops the timer")
    void skipLaw() {
        var exits = new AtomicInteger();
        var effect = new PhosphorDoorwayEffect();
        var door = new DoorwayWindow(effect, null, false, exits::incrementAndGet);
        track(door);
        door.setBounds(TerminalPosition.TOP_LEFT, SIZE);
        door.start();
        assertTrue(door.getTimer().isRunning(), "the animation timer runs during the intro");

        for (var ks : Arrays.asList(
                new KeyStroke(KeyType.CHARACTER, 'x', false, false, false),
                new KeyStroke(KeyType.ESCAPE),
                new KeyStroke(KeyType.ENTER),
                new KeyStroke(KeyType.ESCAPE))) {
            assertTrue(door.handleKeyStroke(ks), "ANY key is consumed by the door: " + ks.type());
        }
        assertEquals(1, exits.get(), "the single-exit callback fired exactly once");
        assertFalse(door.getTimer().isRunning(), "zero CPU after the skip: the timer stopped");
        assertFalse(effect.isRunning(), "the effect is stopped with the timer");
    }

    @Test
    @DisplayName("SKIP before the first tick: the consumed key never reaches a window behind the door")
    void skipConsumesAllLaw() {
        var exits = new AtomicInteger();
        var door = new DoorwayWindow(new PhosphorDoorwayEffect(), null, false, exits::incrementAndGet);
        var keys = List.of(
                "a", "1", " ", "\r");
        for (var s : keys) {
            assertTrue(door.handleKeyStroke(new KeyStroke(KeyType.CHARACTER, s.charAt(0), false, false, false)));
        }
        assertEquals(1, exits.get(), "the first key exits; later keys cannot re-exit");
    }

    @Test
    @DisplayName("FINISH-THROUGH-DRAW: the finished play arms the exit; draw() runs it on the loop thread")
    void finishThroughDrawLaw() {
        var exits = new AtomicInteger();
        var effect = new PhosphorDoorwayEffect();
        var gui = new RecordingGui(); // null screen: no sync read, library-only drive
        var door = new DoorwayWindow(effect, gui, false, exits::incrementAndGet);
        track(door);
        door.setBounds(TerminalPosition.TOP_LEFT, SIZE);
        gui.addWindow(door);
        door.start();

        // Drive the effect to the finish directly (the timer thread's job).
        while (!effect.isFinished()) {
            door.renderTick();
        }
        assertTrue(door.exitPendingForDoorwayTests(), "the finish arms the exit");
        assertEquals(0, exits.get(), "the animation thread must not mutate the GUI windows");

        var buf = new io.jterm.screen.ScreenBuffer(SIZE);
        door.draw(new TextGraphics(buf)); // the event loop's next painted frame
        assertEquals(1, exits.get(), "draw() runs the armed exit exactly once");
        assertFalse(door.getTimer().isRunning(), "zero CPU after the finish");
        assertFalse(door.exitPendingForDoorwayTests(), "the armed flag is consumed");

        door.draw(new TextGraphics(new io.jterm.screen.ScreenBuffer(SIZE)));
        assertEquals(1, exits.get(), "a later draw never re-fires the exit");
    }

    @Test
    @DisplayName("NO-PRERENDER: start() advances no logic ticks — the intro opens on its true frame 0")
    void noPrerenderLaw() {
        var gui = new RecordingGui();
        var effect = new PhosphorDoorwayEffect();
        var door = new DoorwayWindow(effect, gui, false, () -> { });
        track(door);
        door.setBounds(TerminalPosition.TOP_LEFT, SIZE);
        gui.addWindow(door);
        door.start();
        assertEquals(0, effect.tickCount(), "start() alone advances no logic ticks");
        assertTrue(door.getTimer().isRunning(), "the timer runs after start");
    }

    @Test
    @DisplayName("zero CPU after the skip: renderTick is inert — the effect stops advancing")
    void renderTickAfterSkipLaw() {
        var exits = new AtomicInteger();
        var effect = new PhosphorDoorwayEffect();
        var gui = new RecordingGui();
        var door = new DoorwayWindow(effect, gui, false, exits::incrementAndGet);
        track(door);
        door.setBounds(TerminalPosition.TOP_LEFT, SIZE);
        gui.addWindow(door);
        door.start();
        assertTrue(door.handleKeyStroke(new KeyStroke(KeyType.ESCAPE)));
        int ticksAtSkip = effect.tickCount();
        door.renderTick();
        assertEquals(ticksAtSkip, effect.tickCount(), "no logic tick advances after the skip");
        assertEquals(1, exits.get(), "no re-exit from the inert tick");
        // The eviction-style close() after an exit: a second stop, never a second exit.
        door.close();
        assertEquals(1, exits.get(), "no re-exit from the post-exit close");
        assertFalse(door.getTimer().isRunning());
    }

    @Test
    @DisplayName("DETACHED-ZERO-CPU: eviction (close without exit) stops the timer and fires nothing")
    void evictedZeroCpuLaw() {
        var exits = new AtomicInteger();
        var effect = new PhosphorDoorwayEffect();
        var gui = new RecordingGui();
        var door = new DoorwayWindow(effect, gui, false, exits::incrementAndGet);
        track(door);
        door.setBounds(TerminalPosition.TOP_LEFT, SIZE);
        gui.addWindow(door);
        door.start();
        door.close(); // the eviction path (session disconnect mid-intro)
        assertFalse(door.getTimer().isRunning(), "the timer is stopped by the eviction");
        assertFalse(effect.isRunning(), "the effect is stopped with the timer");
        assertEquals(0, exits.get(), "an eviction is not an exit — no hand-off fires");
    }

    @Test
    @DisplayName("null onExit is lawful: the skip stays a silent local exit")
    void nullExitLaw() {
        var door = new DoorwayWindow(new PhosphorDoorwayEffect(), null, false, null);
        track(door);
        door.start();
        assertDoesNotThrow(() -> assertTrue(door.handleKeyStroke(new KeyStroke(KeyType.ESCAPE))));
        assertFalse(door.getTimer().isRunning(), "the zero-CPU law holds without a hand-off");
    }

    @Test
    @DisplayName("REAL-TIME SYNC: 2026 sync → 60 fps renders at 1 tick; no-sync cap → 30 ticks per render")
    void realTimeSyncLaw() throws IOException {
        var effectSync = new PhosphorDoorwayEffect();
        var doorSync = new DoorwayWindow(effectSync, gui(true), false, () -> { });
        track(doorSync);
        doorSync.setBounds(TerminalPosition.TOP_LEFT, SIZE);
        doorSync.start();
        assertEquals(1, effectSync.getTicksPerRender(), "60 fps renders advance 1 logic tick each");

        var effectCap = new PhosphorDoorwayEffect();
        var doorCap = new DoorwayWindow(effectCap, gui(false), true, () -> { });
        track(doorCap);
        doorCap.setBounds(TerminalPosition.TOP_LEFT, SIZE);
        doorCap.start();
        int expected = Math.max(1, Math.round(PhosphorDoorwayEffect.LOGIC_FPS / (float) AnimatedBackgroundWindow.NO_SYNC_FPS_CAP));
        assertEquals(expected, effectCap.getTicksPerRender(),
                "the no-sync fps cap compensates with N ticks per render (the timeline still covers 5.0 s)");
        assertTrue(doorCap.getTimer().getTargetFps() <= AnimatedBackgroundWindow.NO_SYNC_FPS_CAP,
                "the no-sync timer is capped");
        assertEquals(60, doorSync.getTimer().getTargetFps(), "the sync timer runs at the 60 fps logic rate");
    }
}
