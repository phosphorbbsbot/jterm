package io.jterm.window;

import io.jterm.animation.AnimatedBackground;
import io.jterm.animation.AnimationTimer;
import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import io.jterm.style.TextCell;

import java.util.List;

/**
 * A fullscreen background window that delegates rendering to an
 * {@link AnimatedBackground}. Background windows render behind all normal
 * windows, never receive focus, and never handle input.
 *
 * <p>The animation frame is advanced and rendered into a private off-screen
 * buffer by the {@link AnimationTimer} callback at the background's target
 * FPS. The {@link #draw} method only blits the cached buffer to the GUI's
 * graphics context — it never advances animation state. This decouples
 * animation pacing from the input event loop, so keystroke-triggered
 * redraws (which happen in {@link DefaultTextGUI#processInput}) do not
 * cause extra animation steps.</p>
 */
public class AnimatedBackgroundWindow extends WindowImpl {
    private final AnimatedBackground background;
    private AnimationTimer timer;
    private final TextGUI gui;
    private final boolean fullscreen;

    /** Off-screen buffer that holds the most recently rendered animation frame. */
    private volatile ScreenBuffer frameBuffer;
    /** Size of the last rendered frame, so draw() can detect stale buffers. */
    private volatile TerminalSize frameSize;
    /** Counter of refresh requests issued to the GUI (for the pause laws). */
    private volatile int refreshRequests;

    /**
     * Create a fullscreen background window driven by the given animated background.
     *
     * @param background the animation to render
     * @param gui         the GUI this window belongs to
     */
    public AnimatedBackgroundWindow(AnimatedBackground background, TextGUI gui) {
        this(background, gui, true, false);
    }

    /**
     * Creates a background animation window. When {@code fullscreen} is false,
     * the caller must set bounds via {@link #setBounds(TerminalPosition, TerminalSize)}
     * (e.g. a thin strip at the bottom of the screen).
     *
     * @param background the animation to render
     * @param gui        the GUI this window belongs to
     * @param fullscreen true for a fullscreen background window, false to set bounds manually
     */
    public AnimatedBackgroundWindow(AnimatedBackground background, TextGUI gui, boolean fullscreen) {
        this(background, gui, fullscreen, false);
    }

    /**
     * Full constructor. The 2/3-arg ctors keep the pre-ambient-card behavior —
     * the timer runs at the background's target fps regardless of sync
     * capability (login/lock screens and every existing consumer).
     * Menu ambient scenes (card t_a9be4757) construct with
     * {@code degradeWithoutSync=true} to get the SYNCHRONIZED-OUTPUT DEGRADE
     * LAW: without 2026 sync the timer caps at {@link #NO_SYNC_FPS_CAP} fps
     * (unbracketed full-frame repaints read as flicker on slow renderers).
     *
     * @param background          the animation to render
     * @param gui                 the GUI this window belongs to
     * @param fullscreen          true for a fullscreen background window, false to set bounds manually
     * @param degradeWithoutSync  true caps the timer fps on non-2026 terminals
     */
    public AnimatedBackgroundWindow(AnimatedBackground background, TextGUI gui, boolean fullscreen,
                                    boolean degradeWithoutSync) {
        super("animation-bg");
        this.background = background;
        this.gui = gui;
        this.fullscreen = fullscreen;
        this.degradeWithoutSync = degradeWithoutSync;
        if (fullscreen) {
            setHints(List.of(WindowHint.FULLSCREEN, WindowHint.NO_DECORATIONS, WindowHint.BACKGROUND));
        } else {
            setHints(List.of(WindowHint.NO_DECORATIONS, WindowHint.BACKGROUND));
        }
        getContents().setLayoutManager(null);
    }

    /** Menu ambient opt-in: cap the timer fps without 2026 sync. */
    private final boolean degradeWithoutSync;

    /**
     * Blits the cached animation frame to the GUI's graphics context.
     * Does NOT advance animation state — that happens only in the
     * {@link AnimationTimer} callback at the target FPS.
     */
    @Override
    public void draw(TextGraphics graphics) {
        var sz = getSize();
        var buf = frameBuffer;
        var fSize = frameSize;
        if (buf == null || fSize == null) {
            // No frame rendered yet — fill with black and let the first
            // timer tick populate the buffer.
            TextCell bg = new TextCell(' ', AnsiColor.BLACK, AnsiColor.BLACK);
            graphics.fillRectangle(0, 0, sz.columns(), sz.rows(), bg);
            return;
        }
        // Blit the cached frame, clamping to the intersection of the
        // buffer and graphics sizes.
        int maxRows = Math.min(fSize.rows(), sz.rows());
        int maxCols = Math.min(fSize.columns(), sz.columns());
        for (int r = 0; r < maxRows; r++) {
            for (int c = 0; c < maxCols; c++) {
                graphics.setCell(c, r, buf.getCell(c, r));
            }
        }
    }

    /**
     * Advances the animation state and renders a frame into the off-screen
     * buffer. Called by the {@link AnimationTimer} at the target FPS.
     * After rendering, requests a GUI refresh so {@link #draw} is called
     * to blit the new frame.
     *
     * <p>Package-private + pause-gated for the ambient laws (tests:
     * {@code AmbientWindowLawsTest}):</p>
     * <ul>
     *   <li><b>OVERLAY-HOLD LAW</b> — while a MODAL window is on the GUI the
     *       ambient holds: no tick, no frame change, no refresh request. The
     *       modal paints over the scene, so keeping the animation under it
     *       would churn repaints nobody sees.</li>
     *   <li><b>DETACHED-PAUSE LAW</b> — an ambient window absent from its
     *       GUI (evicted by a lock screen, detached session) never advances
     *       again; eviction can never leave a ticking zombie behind.</li>
     * </ul>
     */
    void renderTick() {
        if (isPausedByGui()) {
            return;
        }
        var sz = getSize();
        if (sz.columns() <= 0 || sz.rows() <= 0) return;
        // (Re)allocate the off-screen buffer if the size changed
        if (frameBuffer == null || frameSize == null
                || frameSize.columns() != sz.columns() || frameSize.rows() != sz.rows()) {
            TextCell bg = new TextCell(' ', AnsiColor.BLACK, AnsiColor.BLACK);
            frameBuffer = new ScreenBuffer(sz, bg);
            frameSize = sz;
        }
        var buf = frameBuffer;
        var g = new TextGraphics(buf);
        background.tick(System.nanoTime());
        background.renderFrame(g, sz);
        if (gui != null) {
            refreshRequests++;
            gui.requestRefresh();
        }
    }

    /**
     * Forwards resize to the background animation only when the size actually
     * changes. The GUI layout manager calls setBounds() on every screen update,
     * so forwarding unconditionally would reset animation state (e.g. VoronoiCells
     * recreating seeds) many times per second. By tracking the last forwarded
     * size, we ensure onResize() is called only on real terminal resizes.
     */
    private TerminalSize lastResizeSize;

    /**
     * Set the window position and size.
     *
     * @param position the terminal position
     * @param size the terminal dimensions
     */
    @Override
    public void setBounds(TerminalPosition position, TerminalSize size) {
        super.setBounds(position, size);
        if (lastResizeSize == null
                || lastResizeSize.columns() != size.columns()
                || lastResizeSize.rows() != size.rows()) {
            lastResizeSize = size;
            background.onResize(size);
        }
    }

    /**
     * Start the animation timer and pre-render initial frames.
     *
     * <p><b>SYNCHRONIZED-OUTPUT DEGRADE LAW</b> (ambient, card t_a9be4757):
     * without 2026 synchronized output every frame is an unbracketed
     * progressive paint — full-frame ambient repaints at the target rate
     * read as flicker on slower renderers (the TickerTape full-row freeze
     * lesson). The timer therefore caps at {@value #NO_SYNC_FPS_CAP} fps
     * when the backing terminal does not declare 2026 sync, and runs at the
     * ambient's own {@code targetFps} when it does.</p>
     */
    public void start() {
        background.start();
        // Pre-render several frames so the first visible frame has content
        // spread across the screen instead of clustered at initial positions.
        // tick() alone only updates timing — renderFrame() is what advances
        // star positions (advanceStar), so we must call renderTick() to
        // actually move stars before the first draw.
        for (int i = 0; i < 5; i++) {
            renderTick();
        }
        timer = new AnimationTimer(effectiveFps(), this::renderTick);
        timer.start();
    }

    /** Rate cap for terminals without 2026 synchronized output. */
    static final int NO_SYNC_FPS_CAP = 2;

    /**
     * The fps the timer actually runs at: the background's target rate on a
     * 2026-sync-capable terminal. Without 2026 sync the rate caps ONLY for
     * windows that opted into the degrade ({@code degradeWithoutSync=true} —
     * the menu ambient scenes); every other consumer (login/lock screens)
     * keeps the pre-card behavior.
     *
     * @return the effective timer fps, at least 1
     */
    int effectiveFps() {
        int target = background.targetFps();
        if (!degradeWithoutSync || supportsSynchronizedOutput()) {
            return target;
        }
        return Math.min(target, Math.max(1, NO_SYNC_FPS_CAP));
    }

    private boolean supportsSynchronizedOutput() {
        if (gui == null) {
            return false;
        }
        if (gui.getScreen() instanceof io.jterm.screen.DefaultScreen ds) {
            return ds.getTerminal().syncOutputSupported();
        }
        return false;
    }

    /**
     * True when this ambient window must hold its frame: a MODAL window is
     * active on the GUI (OVERLAY-HOLD) or the window is no longer mounted on
     * its GUI (DETACHED-PAUSE). A null GUI never pauses (pure-library use).
     *
     * @return true while the ambient should hold
     */
    boolean isPausedByGui() {
        if (gui == null) {
            return false;
        }
        if (gui.modalWindow() != null) {
            return true;
        }
        return !gui.getWindows().contains(this);
    }

    /** Visible for tests: refresh requests issued to the GUI (0 when paused). */
    int refreshRequests() {
        return refreshRequests;
    }

    /**
     * Stop the animation timer and the underlying background animation.
     */
    public void stop() {
        if (timer != null) {
            timer.stop();
        }
        background.stop();
    }

    /**
     * Return the animated background rendered by this window.
     *
     * @return the animated background
     */
    public AnimatedBackground getBackground() {
        return background;
    }

    /**
     * Return the animation timer driving this window.
     *
     * @return the animation timer, or null if not started
     */
    public AnimationTimer getTimer() {
        return timer;
    }

    /** Visible for tests: returns the off-screen frame buffer (may be null before first tick). */
    ScreenBuffer getFrameBuffer() {
        return frameBuffer;
    }
}
