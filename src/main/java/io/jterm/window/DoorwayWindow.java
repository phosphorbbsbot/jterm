package io.jterm.window;

import io.jterm.animation.PhosphorDoorwayEffect;
import io.jterm.animation.AnimationTimer;
import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyStroke;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The fullscreen, skippable host of the Phosphor doorway intro (animation
 * plan card 2): plays the {@link PhosphorDoorwayEffect} as the session's
 * ACTIVE window before the login screen, exits on ANY keystroke (the skip
 * law) or at the play's finish, and hands off through a single-exit callback.
 *
 * <p><b>Laws (tests: {@code DoorwayWindowTest}):</b></p>
 * <ul>
 *   <li><b>ACTIVE-WINDOW</b> — FULLSCREEN + NO_DECORATIONS and never
 *       BACKGROUND, so the GUI event loop makes the door the ACTIVE window
 *       and routes every keystroke to the skip handler.</li>
 *   <li><b>SKIP</b> — any keystroke is consumed and exits ONCE; the door then
 *       removes itself from its GUI, the animation timer stops (zero CPU
 *       after the skip), and a later key is still consumed but never re-fires
 *       the exit.</li>
 *   <li><b>FINISH-THROUGH-DRAW</b> — the play finishing on the animation
 *       thread only ARMS the exit ({@code exitPending}); the GUI mutation
 *       happens on the event-loop thread at the next painted {@link #draw}
 *       (the ambient-card draw-time-hook law: an on-screen removal issued
 *       from the animation thread races the paint pipeline; from draw() it
 *       is the menu-ambient precedent's sanctioned shape). Fires once.</li>
 *   <li><b>NO-PRERENDER</b> — start() advances no logic ticks: the intro
 *       opens on its true frame 0 (the bare black ground), unlike the
 *       ambient's warm-start pre-render.</li>
 *   <li><b>REAL-TIME SYNC</b> — with 2026 sync the timer runs at the effect's
 *       60 fps logic rate (1 tick per render); without it the ambient-style
 *       fps cap engages (opt-in {@code degradeWithoutSync}) and the door
 *       compensates with N ticks per render so the 5.0 s timeline holds at
 *       any render fps (the real-time hard cap law).</li>
 *   <li><b>DETACHED-ZERO-CPU</b> — window removal (session disconnect
 *       mid-intro) stops the timer through {@link #close()}; close() never
 *       removes itself from the GUI (the windowsToRemove flush CME law).</li>
 * </ul>
 */
public class DoorwayWindow extends AnimatedBackgroundWindow {

    private final Runnable onExit;
    private final AtomicBoolean exited = new AtomicBoolean(false);
    private volatile boolean exitPending;
    private volatile boolean started;

    /**
     * Constructs the doorway window.
     *
     * @param effect             the doorway effect to play (also the timer's
     *                           render counter clock)
     * @param gui                the hosting GUI (null = pure-library use)
     * @param degradeWithoutSync true caps the timer fps on non-2026 terminals
     *                           (the ambient-synced-output degrade law); the
     *                           effect compensates with ticks-per-render so
     *                           the timeline is rate-independent
     * @param onExit             the single-exit hand-off (attach the login
     *                           screen); fires exactly once, from the GUI
     *                           event loop thread (a key dispatch or draw)
     */
    public DoorwayWindow(PhosphorDoorwayEffect effect, TextGUI gui, boolean degradeWithoutSync,
                         Runnable onExit) {
        super(effect, gui, true, degradeWithoutSync);
        // The super ctor set the ambient BACKGROUND hint; the door must be the
        // ACTIVE window to receive the skip keystrokes. Re-hinting here is the
        // safe shape: addWindow() reads the hints AFTER this ctor returns.
        setHints(List.of(WindowHint.FULLSCREEN, WindowHint.NO_DECORATIONS));
        this.onExit = onExit;
    }

    @Override
    public void start() {
        // NO-PRERENDER: the super's warm-start loop would advance 5 logic
        // ticks; the intro must open on its true frame 0 (bare black).
        var effect = effectForDoorwayTests();
        effect.start();
        var timer = new AnimationTimer(effectiveFps(), this::renderTick);
        setTimerForDoorwayTests(timer);
        // REAL-TIME SYNC: every render advances round(logic fps / rendered fps)
        // logic ticks so the 5.0 s timeline holds at any render rate.
        effect.setTicksPerRender(Math.max(1,
                Math.round((float) PhosphorDoorwayEffect.LOGIC_FPS / effectiveFps())));
        timer.start();
        started = true;
    }

    @Override
    void renderTick() {
        if (exited.get()) {
            return; // stopped: no further frames are rendered after the exit
        }
        super.renderTick();
        var effect = effectForDoorwayTests();
        if (!exited.get() && effect.isFinished()) {
            // FINISH-THROUGH-DRAW: arm only — the paint (event-loop thread)
            // runs the exit. The animation thread never mutates the GUI.
            exitPending = true;
        }
    }

    /** SKIP law: every keystroke is consumed; the first one exits once. */
    @Override
    public boolean handleKeyStroke(KeyStroke keyStroke) {
        exitNow();
        return true;
    }

    /** Event-loop-thread paint: blits the cached frame and runs an armed exit. */
    @Override
    public void draw(TextGraphics graphics) {
        super.draw(graphics);
        if (exitPending) {
            exitNow();
        }
    }

    /**
     * Stops the timer for an eviction WITHOUT exiting (the session died
     * mid-intro). Never removes itself from the GUI: the windowsToRemove
     * flush may be iterating when close() runs (the flush CME law).
     */
    @Override
    public void close() {
        if (!exited.get()) {
            stop();
        }
    }

    /** Stops the animation and the effect (zero CPU), once. */
    @Override
    public void stop() {
        super.stop();
        effectForDoorwayTests().stop();
        started = false;
    }

    /** The single exit: remove the door from its GUI, stop the timer, run the
     *  hand-off once. Re-entrant calls no-op. Runs on the GUI event-loop
     *  thread via the key dispatch or the draw hook. */
    private void exitNow() {
        if (!exited.compareAndSet(false, true)) {
            return; // the skip law: the first key exits, later keys are consumed inertly
        }
        stop();
        exitPending = false;
        var hosted = hostedGui();
        if (hosted != null) {
            // The door leaves its GUI BEFORE the hand-off attaches the next
            // screen (the menu-ambient draw-time-hook precedent — removal on
            // the event-loop thread, the CopyOnWrite window lists are safe).
            hosted.removeWindow(this);
        }
        if (onExit != null) {
            onExit.run();
        }
    }

    /** The doorway effect this window plays. */
    PhosphorDoorwayEffect effectForDoorwayTests() {
        return (PhosphorDoorwayEffect) getBackground();
    }

    /** Test seam: whether the finish armed the exit and the draw has not run it yet. */
    boolean exitPendingForDoorwayTests() {
        return exitPending;
    }

    /** Test seam: installs the animation timer (the no-prerender start path). */
    void setTimerForDoorwayTests(AnimationTimer timer) {
        this.timer = timer;
    }

    @Override
    public TerminalSize getPreferredSize() {
        return null; // FULLSCREEN: the layout sizes the door to the terminal
    }
}
