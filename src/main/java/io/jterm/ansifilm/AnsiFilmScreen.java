package io.jterm.ansifilm;

import io.jterm.core.Terminal;
import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.screen.DefaultScreen;
import io.jterm.screen.HalfBlockBuffer;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiCodes;
import io.jterm.style.AnsiColor;
import io.jterm.style.Color;
import io.jterm.style.TextCell;
import io.jterm.window.DefaultTextGUI;
import io.jterm.window.TextGUI;
import io.jterm.window.WindowHint;
import io.jterm.window.WindowImpl;
import io.jterm.graphics.TextGraphics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The pfilm player (animation plan card 1, 2026-11-20): streams a parsed
 * {@link AnsiFilm} frame-by-frame onto a terminal with no tearing. The
 * playback loop runs on the CALLING thread — the BBS plays it from a screen
 * key handler, exactly the shape {@code ScreenTransitionManager} animates
 * screen transitions in — so while the film plays, this player is the single
 * writer on its terminal and nothing repaints under its feet.
 *
 * <p><b>Emission laws (tests: {@code AnsiFilmScreenTest}, {@code AnsiFilmTest}):</b></p>
 * <ul>
 *   <li><b>FRAME 1 = FULL REPAINT</b> — the shape of {@code DefaultScreen}
 *       .doCompleteRefresh: autowrap disabled, then every row — cursor homed,
 *       the SGR reset emitted once after the first home, every cell written —
 *       then autowrap re-enabled.</li>
 *   <li><b>FRAMES 2+ = SgrStateTracker DELTA</b> — the changed cells only,
 *       diffed against the previous frame's grid row-major, cursor repositioned
 *       only when the next changed cell is not contiguous, SGR transitions
 *       batched and depth-mapped by the tracker (colors ride the terminal's
 *       declared {@link io.jterm.core.ColorDepth}). A frame whose grid SHAPE
 *       differs from the previous one emits a full repaint instead (the delta
 *       law cannot walk across a shape change; mixed packs rely on it —
 *       the shape law is a row-count law: COLS is fixed by the film header
 *       and the encode preserves width).</li>
 *   <li><b>SUBCELL LAW</b> — a {@code SUBCELL}-marked frame's declared rows
 *       are sub-rows: before emission the raster re-encodes through
 *       {@link io.jterm.screen.HalfBlockBuffer} at the terminal's declared
 *       depth (mapped BEFORE encode: a pair that degrades equal collapses to
 *       the FULL BLOCK law inside the cell itself), so the frame plays at 2×
 *       vertical density. The sub-cell color of a raster cell rides its
 *       background when any ground is painted, else its foreground; a fully
 *       default cell is transparent — a SUBCELL body is a color field.
 *       Marked frames honor the internal {@code subcellCapable} seam: without
 *       the half-block layer the marker plays normal, byte-identical to an
 *       unmarked frame (tests: {@code AnsiFilmScreenSubcellTest}).</li>
 *   <li><b>2026 LAW</b> — every frame is wrapped in BEGIN/END_SYNC_UPDATE
 *       (DEC 2026) iff {@code terminal.syncOutputSupported()}; the ESU is also
 *       emitted on a mid-frame failure so the bracket never dangles, and the
 *       frame flushes AFTER the ESU (the flush-boundary law).</li>
 *   <li><b>PACING LAW</b> — the render loop awaits each frame's DECLARED
 *       {@code FRAME dur_ms} through terminal input polls (never a busy-wait,
 *       never a blind sleep); SPACE consumes the remaining time and moves the
 *       picture ahead.</li>
 *   <li><b>KEY LAWS</b> — {@code ESC}, {@code Q}/{@code q} quit; {@code
 *       SPACE} skips to the next frame; every other key is consumed and
 *       ignored. The exit hand-off (the caller-injected callback, exactly the
 *       {@code DoorwayWindow} single-exit shape) fires EXACTLY ONCE — at the
 *       film's end or on a quit.</li>
 *   <li><b>DEAD INPUT LAW</b> — an EOF keystroke (or a thrown I/O fault)
 *       aborts playback silently and skips the hand-off: the session teardown
 *       owns the rest.</li>
 *   <li><b>RESYNC LAW</b> — the exit removes the window from its GUI and arms
 *       a COMPLETE refresh: the film wrote raw bytes the screen buffers never
 *       saw, so the next paint must repaint from scratch.</li>
 * </ul>
 */
public class AnsiFilmScreen extends WindowImpl {

    /** The pacing clock of the playback loop (the real path: wall time). */
    public interface FrameClock {
        /** @return the current time in milliseconds. */
        long nowMillis();
    }

    /** The input source of the playback loop (the real path: the terminal). */
    public interface FilmInput {
        /**
         * Polls the next keystroke, waiting at most the given budget.
         *
         * @param timeoutMillis maximum time to wait, 0 for a plain poll
         * @return the keystroke, or empty when the budget elapsed
         * @throws IOException on an input fault (a dead source)
         */
        Optional<KeyStroke> poll(long timeoutMillis) throws IOException;
    }

    private static final byte[] BSU = AnsiCodes.BEGIN_SYNC_UPDATE.getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESU = AnsiCodes.END_SYNC_UPDATE.getBytes(StandardCharsets.UTF_8);
    private static final byte[] AUTOWRAP_OFF = AnsiCodes.DISABLE_AUTOWRAP.getBytes(StandardCharsets.UTF_8);
    private static final byte[] AUTOWRAP_ON = AnsiCodes.ENABLE_AUTOWRAP.getBytes(StandardCharsets.UTF_8);

    /**
     * The SUBCELL capability seam (internal, package-private so the law tests
     * can substitute it): a SUBCELL-marked frame replays through the
     * half-block buffer only when the layer is present. True within this
     * artifact ({@code HalfBlockBuffer} lives in {@code io.jterm.screen});
     * the seam keeps marker frames playing normal in builds without it — the
     * law that let the pfilm card land ahead of this one (2026-11-20
     * animation plan, card 4).
     */
    static java.util.function.BooleanSupplier subcellCapable = () -> true;

    private final AnsiFilm film;
    private final TextGUI gui;
    private final Terminal terminal;
    private final Runnable onExit;
    /** DELTA engine: film-owned, reads the terminal's declared depth per cell. */
    private final DefaultScreen.SgrStateTracker sgr;
    /** The previous emitted frame (the delta baseline); null before frame 1. */
    private ScreenBuffer previousFrame;
    /** The most recent emitted frame (draw-blit safety for stray repaints). */
    private volatile ScreenBuffer lastEmitted;
    /** The next frame index to emit; playback and tests emit strictly forward. */
    private int nextFrameToEmit;
    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicBoolean exited = new AtomicBoolean(false);
    /** SPACE: the current frame's remaining time is consumed on next wait check. */
    private final AtomicBoolean skipToNext = new AtomicBoolean(false);

    /**
     * Creates the film screen.
     *
     * @param film   the parsed film; never null
     * @param gui    the hosting GUI (its screen's terminal is the playback
     *               sink); never null
     * @param onExit the single-exit hand-off; runs exactly once (film end or
     *               quit) on the playback thread
     */
    public AnsiFilmScreen(AnsiFilm film, TextGUI gui, Runnable onExit) {
        super("film");
        this.film = java.util.Objects.requireNonNull(film, "film");
        this.gui = java.util.Objects.requireNonNull(gui, "gui (the film screen plays on its terminal)");
        this.terminal = resolveTerminal(gui);
        this.onExit = onExit;
        this.sgr = new DefaultScreen.SgrStateTracker(terminal::colorDepth);
        setHints(List.of(WindowHint.FULLSCREEN, WindowHint.NO_DECORATIONS));
    }

    private static Terminal resolveTerminal(TextGUI gui) {
        if (gui.getScreen() instanceof DefaultScreen ds) {
            return ds.getTerminal();
        }
        throw new IllegalArgumentException(
                "the film screen plays on a DefaultScreen's terminal, got: "
                        + gui.getScreen().getClass().getName());
    }

    // ── playback API ────────────────────────────────────────────────────

    /**
     * Plays the whole film synchronously on the calling thread: emits every
     * frame (full repaint for frame 1, tracker deltas after), pacing each
     * frame by its declared duration, consuming keystrokes between frames
     * (ESC/Q quit, SPACE skips), and hands off ONCE at the film's end or on a
     * quit. An I/O fault aborts quietly without the hand-off.
     *
     * @throws IllegalStateException when the film is already playing
     */
    public void play() {
        if (!playing.compareAndSet(false, true)) {
            throw new IllegalStateException("the film is already playing");
        }
        try {
            playback(System::currentTimeMillis, terminal::pollInput);
        } catch (IOException dead) {
            // DEAD INPUT: the sink died mid-film. Nothing safe to log (the
            // output lane is gone); the session teardown handles the socket.
        } finally {
            playing.set(false);
        }
    }

    /**
     * The playback loop, decoupled from its clock and input for the law tests.
     * The public paths wire {@code System::currentTimeMillis} and the
     * terminal's blocking input poll — the render loop receives next frames
     * only after the declared durations (the pacing law), never by busy-wait.
     *
     * @param clock the pacing clock
     * @param input the input source (keys consumed between frames)
     * @throws IOException on an input/output fault (dead terminal)
     */
    public void playback(FrameClock clock, FilmInput input) throws IOException {
        while (!exited.get() && nextFrameToEmit < film.frameCount()) {
            tick();
            if (exited.get()) {
                return;
            }
            if (!waitDeclaredDuration(nextFrameToEmit - 1, clock, input)) {
                return; // quit (exit already fired) or dead input: no more frames
            }
        }
        if (exited.get()) {
            return;
        }
        runExitOnce(); // film end: the single hand-off
    }

    /**
     * Awaits the declared duration of a just-emitted frame, consuming keys.
     *
     * @param frameIndex the frame whose rate to honor
     * @param clock      the pacing clock
     * @param input      the input source
     * @return false when playback must stop (quit/EOF), true to continue
     * @throws IOException on an input fault
     */
    private boolean waitDeclaredDuration(int frameIndex, FrameClock clock, FilmInput input)
            throws IOException {
        long deadline = clock.nowMillis() + film.frame(frameIndex).durationMs();
        while (true) {
            long remaining = deadline - clock.nowMillis();
            if (skipToNext.compareAndSet(true, false)) {
                return true; // SPACE: the next frame emits immediately
            }
            if (remaining <= 0) {
                return true;
            }
            Optional<KeyStroke> key = input.poll(remaining);
            if (key.isEmpty()) {
                return true; // the full budget burned — the next frame is due
            }
            switch (applyKeyLaw(key.get())) {
                case CONTINUE:
                    // consumed; keep waiting out the frame
                    break;
                case SKIP:
                    skipToNext.set(true);
                    break;
                case QUIT:
                    runExitOnce();
                    return false;
                case DEAD:
                    return false;
            }
        }
    }

    /** The keystroke laws of the film player. */
    private enum KeyLaw { CONTINUE, SKIP, QUIT, DEAD }

    private KeyLaw applyKeyLaw(KeyStroke key) {
        if (key.type() == KeyType.EOF) {
            return KeyLaw.DEAD;
        }
        if (key.type() == KeyType.ESCAPE) {
            return KeyLaw.QUIT;
        }
        if (key.isCharacter() && !key.ctrl()) {
            char c = key.character();
            if (c == 'Q' || c == 'q') {
                return KeyLaw.QUIT;
            }
            if (c == ' ') {
                return KeyLaw.SKIP;
            }
        }
        return KeyLaw.CONTINUE;
    }

    /**
     * Emits the next frame (a full repaint for frame 1, a tracker delta after)
     * and advances the playhead. The law-test and playback drivers share this
     * one emission path.
     *
     * @throws IllegalStateException past the film's last frame
     * @throws IOException on an output fault
     */
    public void tick() throws IOException {
        if (nextFrameToEmit >= film.frameCount()) {
            throw new IllegalStateException("all frames of the film have been emitted");
        }
        emitFrame(nextFrameToEmit);
        nextFrameToEmit++;
    }

    /** @return true while a play() is in its loop (the single-play guard). */
    boolean isPlayingForTests() {
        return playing.get();
    }

    /** @return true when every frame has been emitted (the exit may fire). */
    boolean isFinishedForFilmTests() {
        return nextFrameToEmit >= film.frameCount();
    }

    /** Law-test seam: runs the single exit (removes + resync + callback once). */
    void quitForFilmTests() {
        exitNowOnce();
    }

    // ── window lifecycle ────────────────────────────────────────────────

    @Override
    public void draw(TextGraphics graphics) {
        var frame = lastEmitted;
        if (frame == null) {
            return; // nothing emitted yet (playback owns the terminal anyway)
        }
        var size = graphics.getSize();
        int maxRows = Math.min(frame.size().rows(), size.rows());
        int maxCols = Math.min(frame.size().columns(), size.columns());
        for (int r = 0; r < maxRows; r++) {
            for (int c = 0; c < maxCols; c++) {
                graphics.setCell(c, r, frame.getCell(c, r));
            }
        }
    }

    @Override
    public TerminalSize getPreferredSize() {
        return null; // FULLSCREEN: the layout sizes the film to the terminal
    }

    /**
     * The film consumes every keystroke while mounted: the playback is the
     * BBS's active surface (its law keys are also honored here so a GUI event
     * loop never loses a skip or quit).
     */
    @Override
    public boolean handleKeyStroke(KeyStroke key) {
        switch (applyKeyLaw(key)) {
            case QUIT:
                exitNowOnce();
                return true;
            case SKIP:
                skipToNext.set(true);
                return true;
            default:
                return true; // every key while mounted is consumed
        }
    }

    /** The single exit: stop, leave the GUI, arm a COMPLETE resync, hand off. */
    private void exitNowOnce() {
        if (!exited.compareAndSet(false, true)) {
            return; // the exit law: exactly once
        }
        playing.set(false);
        if (gui instanceof DefaultTextGUI dtg) {
            dtg.forceCompleteRefresh(); // the raw-byte resync law
        } else {
            gui.requestRefresh();
        }
        gui.removeWindow(this);
        if (onExit != null) {
            onExit.run();
        }
    }

    private void runExitOnce() {
        exitNowOnce();
    }

    // ── emission (the byte laws) ────────────────────────────────────────

    private void emitFrame(int index) throws IOException {
        var frame = film.frame(index);
        var target = targetFor(frame);
        boolean bracket = terminal.syncOutputSupported();
        if (bracket) {
            terminal.writeRaw(BSU);
        }
        try {
            if (previousFrame == null || !sameRowCount(previousFrame, target)) {
                emitComplete(target);
            } else {
                emitDelta(target);
            }
        } finally {
            if (bracket) {
                // The bracket never dangles, even on a mid-frame failure.
                terminal.writeRaw(ESU);
            }
        }
        // FLUSH-BOUNDARY LAW: the frame reaches the wire before the next one
        // is even computed; the ESU closed this frame above.
        terminal.flush();
        // GC law (Joe, 2026-11-21): the delta baseline was a fresh buffer +
        // copy per frame. Same-shape frames reuse it in place — emission
        // above already finished reading it, and the next frame's delta pass
        // compares against exactly these (updated) contents, so reuse has
        // identical semantics to the copy. lastEmitted deliberately points
        // at the live buffer: draw() mirrors the latest emitted frame.
        if (previousFrame == null || !sameRowCount(previousFrame, target)
                || previousFrame.size().columns() != target.size().columns()) {
            previousFrame = copyOf(target);
        } else {
            previousFrame.copyFrom(target);
        }
        lastEmitted = previousFrame;
    }

    /**
     * The frame's emission target: a SUBCELL-marked frame with the capability
     * present re-encodes its raster through the half-block buffer (the rows
     * are SUB-ROWS, the rows*2 grid), mapping through the terminal's declared
     * {@link io.jterm.core.ColorDepth} BEFORE encode; anything else plays
     * exactly as parsed (frames lacking the marker are untouched).
     */
    private ScreenBuffer targetFor(AnsiFilm.Frame frame) {
        if (frame.subcell() && subcellCapable.getAsBoolean()) {
            return encodeSubcell(frame.cells());
        }
        return frame.cells();
    }

    /**
     * Re-encodes a SUBCELL frame: the raster's rows are sub-rows of a
     * {@code rows*2} grid, so the film's declared ROWS double its vertical
     * density. Each raster cell's color rides the RASTER LAW below. An odd
     * sub-row count keeps the trailing partnerless half transparent (the
     * buffer's space-fill law).
     *
     * <p>GC law (Joe, 2026-11-21): the half-block grid and the encoded
     * target were rebuilt per frame. Both are same-shape frame to frame
     * (the row law below), so both are held and re-encoded — the raster
     * walk below overwrites EVERY sub-cell each pass, so no reset is
     * needed; only a shape change reallocates.</p>
     */
    private ScreenBuffer encodeSubcell(ScreenBuffer raster) {
        var size = raster.size();
        int cols = size.columns();
        int subRows = size.rows();
        int cellRows = (subRows + 1) / 2;
        if (reusableHalf == null || reusableHalf.columns() != cols
                || reusableHalf.subRows() != subRows) {
            reusableHalf = HalfBlockBuffer.forSubRows(cols, subRows);
            reusableTarget = null; // the encoded shape follows the half grid
        }
        if (reusableTarget == null
                || reusableTarget.size().rows() != cellRows
                || reusableTarget.size().columns() != cols) {
            reusableTarget = null;
        }
        for (int sub = 0; sub < subRows; sub++) {
            for (int c = 0; c < cols; c++) {
                reusableHalf.setSubCell(c, sub, subCellColor(raster.getCell(c, sub)));
            }
        }
        var out = reusableHalf.toScreenBuffer(terminal.colorDepth(), reusableTarget);
        reusableTarget = out;
        return out;
    }

    /** Same-shape film encoders held across frames (GC law, 2026-11-21). */
    private HalfBlockBuffer reusableHalf;
    private ScreenBuffer reusableTarget;

    /**
     * The SUBCELL raster law: a frame body is a COLOR FIELD. A painted cell's
     * sub-cell color is its BACKGROUND when any ground is painted (the colored
     * band), else its foreground (glyph-painted art); a fully default cell is
     * transparent. Body glyphs carry no text of their own.
     */
    private static Color subCellColor(TextCell cell) {
        Color bg = cell.bg();
        if (!AnsiColor.DEFAULT.equals(bg)) {
            return bg;
        }
        Color fg = cell.fg();
        return AnsiColor.DEFAULT.equals(fg) ? null : fg;
    }

    /**
     * True when the two emission targets share the grid SHAPE. Per-pack
     * columns are fixed by the film header (one COLS for every frame) and the
     * encode preserves width, so a shape change can only come from the row
     * count (the SUBCELL encode halves odd-even pairs) — the law is a
     * row-count law.
     */
    private static boolean sameRowCount(ScreenBuffer a, ScreenBuffer b) {
        return a.size().rows() == b.size().rows();
    }

    private ScreenBuffer copyOf(ScreenBuffer from) {
        var copy = new ScreenBuffer(from.size());
        copy.copyFrom(from);
        return copy;
    }

    /**
     * Full repaint for frame 1 — the shape of {@code DefaultScreen}
     * .doCompleteRefresh: autowrap disabled, then per row — cursor homed, the
     * SGR reset emitted once (the tracker canvas is fresh), every cell written
     * — then autowrap re-enabled.
     */
    private void emitComplete(ScreenBuffer target) throws IOException {
        var size = target.size();
        terminal.writeRaw(AUTOWRAP_OFF);
        for (int r = 0; r < size.rows(); r++) {
            terminal.setCursorPosition(0, r);
            if (r == 0) {
                terminal.writeRaw(sgr.reset());
            }
            for (int c = 0; c < size.columns(); c++) {
                emitCell(target.getCell(c, r));
            }
        }
        terminal.writeRaw(AUTOWRAP_ON);
    }

    /** Tracker DELTA against the previous frame: changed cells only. */
    private void emitDelta(ScreenBuffer target) throws IOException {
        var diffs = target.diffFrom(previousFrame);
        int lastRow = -1;
        int lastCol = -1;
        for (var diff : diffs) {
            if (diff.row() != lastRow || diff.column() != lastCol + 1) {
                terminal.setCursorPosition(diff.column(), diff.row());
            }
            emitCell(diff.cell());
            lastRow = diff.row();
            lastCol = diff.column();
            if (diff.cell().isDoubleWidth()) {
                lastCol++;
            }
        }
    }

    private void emitCell(TextCell cell) throws IOException {
        byte[] sgrBytes = sgr.transitionTo(cell);
        if (sgrBytes.length > 0) {
            terminal.writeRaw(sgrBytes);
        }
        terminal.putCharacter(cell.character().charAt(0));
    }
}
