package io.jterm.screen;

import io.jterm.core.Terminal;
import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.style.AnsiColor;
import io.jterm.style.AnsiCodes;
import io.jterm.style.SGR;
import io.jterm.style.TextCell;
import io.jterm.style.Color;
import io.jterm.style.ThemeManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;

/** Double-buffered screen with delta refresh and SGR state batching. */
public class DefaultScreen implements Screen {
    private final Terminal terminal;
    private volatile ScreenBuffer backBuffer;
    private volatile ScreenBuffer frontBuffer;
    private volatile TerminalSize size;
    private volatile TerminalPosition cursorPosition = TerminalPosition.TOP_LEFT;
    private volatile boolean started;
    /** Persistent SGR state tracker — survives across refresh calls so
     *  delta refreshes know the terminal's actual current SGR state. */
    private final SgrStateTracker sgrState = new SgrStateTracker();
    /** True while a BEGIN_SYNC_UPDATE (DEC 2026) bracket is open on the terminal. */
    private volatile boolean syncBracketActive = false;

    /**
     * Blink-visibility law (2026-10-20): when true, refreshes paint
     * UNBRACKETED regardless of the client's declared 2026 support. Opened
     * transiently around latency-sensitive highlight renders (menu-blink
     * phases) so a short bracketed blink cannot be coalesced away by the
     * client renderer when larger frames follow within milliseconds.
     */
    private volatile boolean syncBracketSuppressed = false;

    /** Opens/closes the bracket-suppression window (see field Javadoc). */
    public void setSyncBracketSuppressed(boolean suppressed) {
        this.syncBracketSuppressed = suppressed;
    }

    /**
     * Return the terminal.
     *
     * @return the terminal
     */
    public Terminal getTerminal() { return terminal; }

    /**
     * Create a screen backed by a MockTerminal of the given size.
     *
     * @param size the screen dimensions
     */
    public DefaultScreen(TerminalSize size) {
        this(new io.jterm.core.MockTerminal(size));
    }

    /**
     * Create a screen backed by the given terminal.
     *
     * @param terminal the terminal to wrap
     */
    public DefaultScreen(Terminal terminal) {
        this.terminal = terminal;
        try {
            this.size = terminal.getTerminalSize();
        } catch (IOException e) {
            this.size = new TerminalSize(80, 24);
        }
        this.backBuffer = new ScreenBuffer(this.size);
        this.frontBuffer = new ScreenBuffer(this.size);
    }

    /**
     * Enter private mode and prepare the screen for rendering.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void startScreen() throws IOException {
        terminal.enterPrivateMode();
        terminal.setCursorVisible(false);
        started = true;
    }

    /**
     * Leave private mode and restore the terminal.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void stopScreen() throws IOException {
        started = false;
        terminal.setCursorVisible(true);
        terminal.exitPrivateMode();
    }

    /**
     * Close the terminal and release resources.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void close() throws IOException {
        stopScreen();
        terminal.close();
    }

    /**
     * Clear the back buffer with the theme background.
     */
    @Override
    public void clear() {
        var theme = ThemeManager.active();
        backBuffer.fill(new TextCell(' ', theme.foreground(), theme.background()));
    }

    /**
     * Set a cell in the back buffer.
     *
     * @param col the column index (0-based)
     * @param row the row index (0-based)
     * @param cell the cell to write
     */
    @Override
    public void setCell(int col, int row, TextCell cell) {
        backBuffer.setCell(col, row, cell);
    }

    /**
     * Set a cell in the back buffer.
     *
     * @param pos the pos
     * @param cell the cell to write
     */
    @Override
    public void setCell(TerminalPosition pos, TextCell cell) {
        backBuffer.setCell(pos, cell);
    }

    /**
     * Return a cell from the front (visible) buffer.
     *
     * @param col the column index (0-based)
     * @param row the row index (0-based)
     *
     * @return the frontcell
     */
    @Override
    public TextCell getFrontCell(int col, int row) {
        return frontBuffer.getCell(col, row);
    }

    /**
     * Return a cell from the back (rendering) buffer.
     *
     * @param col the column index (0-based)
     * @param row the row index (0-based)
     *
     * @return the backcell
     */
    @Override
    public TextCell getBackCell(int col, int row) {
        return backBuffer.getCell(col, row);
    }

    /**
     * Returns the back buffer so external code (e.g. screen mirroring) can write directly.
     *
     * @return the back buffer
     */
    public ScreenBuffer getBackBuffer() { return backBuffer; }

    /**
     * Flush the back buffer to the terminal.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void refresh() throws IOException {
        refresh(RefreshType.AUTOMATIC);
    }

    /**
     * Flush the back buffer to the terminal.
     *
     * @param type the type
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void refresh(RefreshType type) throws IOException {
        if (!started) {
            // If not started (e.g., tests), still perform a logical refresh but don't write to terminal.
            frontBuffer.copyFrom(backBuffer);
            return;
        }
        // Compute the refresh path and whether a frame will be emitted BEFORE
        // writing anything, so empty deltas stay byte-for-byte silent (no lone
        // 2026 brackets) while real frames are wrapped in BSU/ESU (DEC 2026)
        // when the terminal declares synchronized-output support.
        List<ScreenBuffer.CellDiff> deltas = null;
        boolean frameEmitted;
        if (type == RefreshType.COMPLETE) {
            frameEmitted = true;
        } else if (type == RefreshType.DELTA) {
            deltas = backBuffer.diffFrom(frontBuffer);
            frameEmitted = !deltas.isEmpty();
        } else {
            deltas = backBuffer.diffFrom(frontBuffer);
            boolean large = deltas.size() > (frontBuffer.size().area() / 2);
            frameEmitted = large || !deltas.isEmpty();
        }
        if (!frameEmitted) {
            frontBuffer.copyFrom(backBuffer);
            return;
        }
        // Blink-visibility law (2026-10-20): latency-sensitive transient
        // deltas (menu-blink highlight phases) paint UNBRACKETED even when
        // the connected client declares 2026 support — Terminal.app holds
        // bracketed frames and a short bracketed blink followed by a burst
        // of larger transition frames gets coalesced away client-side.
        // The suppression window is opened by the caller (MenuWindow blink)
        // around exactly those two renders; every other frame brackets as
        // the card-6 policy dictates.
        boolean bracket;
        if (type == RefreshType.COMPLETE) {
            bracket = false;
        } else {
            int deltaCost = (type == RefreshType.DELTA) ? deltas.size()
                    : (deltas.size() > (frontBuffer.size().area() / 2) ? frontBuffer.size().area() : deltas.size());
            // Latency-sensitive echoes (chat input, login typing, single-cell
            // highlight moves) paint UNBRACKETED: an ESU that slips into the
            // next proxy read parks the browser's renderer until it arrives —
            // the user sees typed text one character late (2026-10-20). A
            // TINY delta cannot tear: skip the bracket, accept immediate
            // progressive paint. Larger deltas below the cap keep the bracket.
            int TINY_DELTA_CELLS = 16;
            bracket = terminal.syncOutputSupported()
                    && !syncBracketSuppressed
                    && deltaCost <= (frontBuffer.size().area() / 4)
                    && deltaCost > TINY_DELTA_CELLS;
        }
        if (bracket) {
            syncBracketActive = true;
            terminal.writeRaw(AnsiCodes.BEGIN_SYNC_UPDATE.getBytes(StandardCharsets.UTF_8));
        }
        try {
            if (type == RefreshType.COMPLETE || deltas == null) {
                doCompleteRefresh();
            } else if (deltas.size() > (frontBuffer.size().area() / 2)) {
                doCompleteRefresh();
            } else if (doRowShiftRefresh(deltas)) {
                // fully handled: every changed row was a clean left-shift
            } else {
                doDeltaRefresh(deltas);
            }
        } finally {
            if (bracket) {
                // ESU goes out after the frame's cells; emit it even if the
                // frame write failed so the 2026 bracket never dangles.
                terminal.writeRaw(AnsiCodes.END_SYNC_UPDATE.getBytes(StandardCharsets.UTF_8));
                syncBracketActive = false;
                // Flush-boundary law (2026-10-20): the frame body's internal
                // flush happened BEFORE this ESU — without a trailing flush
                // the closing bracket rides the NEXT refresh's flush (often
                // seconds later). Clients honoring 2026 hold the whole frame
                // until ESU: bracketed updates painted one refresh behind
                // (arrow-scroll lag on web + telnet). Push the ESU now.
                terminal.flush();
            }
        }
        frontBuffer.copyFrom(backBuffer);
    }

    /**
     * Test hook: whether a synchronized-output (DEC 2026) bracket is currently open.
     *
     * @return true between BSU and ESU emissions
     */
    boolean syncBracketActive() {
        return syncBracketActive;
    }

    private void doCompleteRefresh() throws IOException {
        // Disable auto-wrap before writing. Without this, writing the last cell
        // of the last row (col 80, row 24) triggers auto-wrap → scroll, shifting
        // all content up by one row. Per-row cursor positioning doesn't prevent
        // this because the write itself still advances past the bottom margin.
        terminal.writeRaw(AnsiCodes.DISABLE_AUTOWRAP.getBytes(StandardCharsets.UTF_8));
        terminal.setCursorPosition(0, 0);
        // Write the SGR reset to the terminal — don't discard it.
        // Without this, the terminal keeps its stale SGR state (e.g. white
        // background from a selection highlight) and subsequent cells render
        // with the wrong colors. This is the "white left behind" bug.
        byte[] resetBytes = sgrState.reset();
        if (resetBytes.length > 0) terminal.writeRaw(resetBytes);
        for (int r = 0; r < size.rows(); r++) {
            // Position cursor at start of each row instead of relying on
            // terminal auto-wrap. Auto-wrap after the last row triggers a
            // scroll on terminals where rows == screen height, shifting all
            // content up. Per-row positioning avoids this entirely.
            terminal.setCursorPosition(0, r);
            for (int c = 0; c < size.columns(); c++) {
                var cell = backBuffer.getCell(c, r);
                byte[] sgrBytes = sgrState.transitionTo(cell);
                if (sgrBytes.length > 0) terminal.writeRaw(sgrBytes);
                terminal.putCharacter(emittable(cell));
            }
        }
        // Re-enable auto-wrap for normal operation (delta refreshes, etc.)
        terminal.writeRaw(AnsiCodes.ENABLE_AUTOWRAP.getBytes(StandardCharsets.UTF_8));
        terminal.flush();
    }

    /**
     * Emit-time defense in depth: never write a control character to the
     * terminal stream, even if one reached a buffer through a path that
     * bypassed ScreenBuffer sanitization (e.g. an externally-owned buffer or
     * a future direct-cell writer).
     */
    private static char emittable(TextCell cell) {
        char c = cell.character().charAt(0);
        return (c >= 32 && c != 127) ? c : ' ';
    }

    private void doDeltaRefresh(List<ScreenBuffer.CellDiff> diffs) throws IOException {
        int lastRow = -1;
        int lastCol = -1;

        for (var diff : diffs) {
            if (diff.row() != lastRow || diff.column() != lastCol + 1) {
                terminal.setCursorPosition(diff.column(), diff.row());
            }
            byte[] sgrBytes = sgrState.transitionTo(diff.cell());
            if (sgrBytes.length > 0) terminal.writeRaw(sgrBytes);
            terminal.putCharacter(emittable(diff.cell()));
            lastRow = diff.row();
            lastCol = diff.column();
            if (diff.cell().isDoubleWidth()) lastCol++;
        }
        terminal.flush();
    }

    /**
     * Row-shift refresh (2026-10-22, Joe's tape freeze): when a delta's changed
     * rows are each a pure LEFT-SHIFT of their previous content — every cell
     * {@code back[c] == front[c+k]} (content AND style move together), no
     * double-width cells — emit DCH (CSI Ps P) per row + write only the
     * {@code k} entering trailing cells. A full-width marquee frame drops from
     * a whole-row SGR rewrite (~1.5KB) to a cursor-home + DCH + k cells
     * (~20-40B); slow client renderers (phosphor relay, tmux, busy
     * Terminal.app) that visibly stuttered ~1-1.5s on every tape rotation get
     * the row shifted by the terminal itself. Rows that do not qualify paint
     * through the classic delta path; a frame of only-qualifying rows leaves
     * nothing for it. Returns true when the whole frame was handled here.
     */
    static final int ROW_SHIFT_MAX_CELLS = 4;

    private boolean doRowShiftRefresh(List<ScreenBuffer.CellDiff> diffs) throws IOException {
        if (diffs.isEmpty()) {
            return false;
        }
        var rowsWithDeltas = new java.util.TreeSet<Integer>();
        for (var d : diffs) {
            rowsWithDeltas.add(d.row());
        }
        var shiftK = new java.util.HashMap<Integer, Integer>();
        var remaining = new java.util.ArrayList<ScreenBuffer.CellDiff>();
        for (int r : rowsWithDeltas) {
            Integer k = shiftForRow(r);
            if (k != null) {
                shiftK.put(r, k);
            }
        }
        if (shiftK.isEmpty()) {
            return false;
        }
        for (var d : diffs) {
            if (!shiftK.containsKey(d.row())) {
                remaining.add(d);
            }
        }
        for (var e : shiftK.entrySet()) {
            int r = e.getKey();
            int k = e.getValue();
            terminal.setCursorPosition(0, r);
            byte[] dch = AnsiCodes.deleteChars(k);
            if (dch.length > 0) terminal.writeRaw(dch);
            for (int c = size.columns() - k; c < size.columns(); c++) {
                var cell = backBuffer.getCell(c, r);
                byte[] sgr = sgrState.transitionTo(cell);
                if (sgr.length > 0) terminal.writeRaw(sgr);
                terminal.putCharacter(emittable(cell));
            }
        }
        if (!remaining.isEmpty()) {
            doDeltaRefresh(remaining);
        }
        terminal.flush();
        return true;
    }

    /**
     * Returns the left-shift amount {@code k} (1..ROW_SHIFT_MAX_CELLS) if the
     * row's back buffer is the front buffer shifted left by exactly {@code k}
     * cells (equal content AND style, no double-width cells, at least one
     * cell genuinely differs), else null.
     */
    private Integer shiftForRow(int r) {
        int cols = size.columns();
        for (int k = 1; k <= ROW_SHIFT_MAX_CELLS && k < cols; k++) {
            boolean all = true;
            boolean anyChange = false;
            for (int c = 0; c < cols - k; c++) {
                var back = backBuffer.getCell(c, r);
                var old = frontBuffer.getCell(c + k, r);
                if (back.isDoubleWidth() || old.isDoubleWidth()) {
                    all = false;
                    break;
                }
                if (!back.equals(old)) {
                    all = false;
                    break;
                }
                if (!anyChange) {
                    var backOld = frontBuffer.getCell(c, r);
                    if (!back.equals(backOld)) {
                        anyChange = true;
                    }
                }
            }
            if (all && anyChange) {
                return k;
            }
        }
        return null;
    }

    /**
     * Return the current terminal size (columns x rows).
     *
     * @return the terminalsize
     */
    @Override
    public TerminalSize getTerminalSize() {
        return size;
    }

    /**
     * Check terminal size and resize buffers if changed.
     *
     * @return the (possibly updated) terminal size
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public TerminalSize doResizeIfNecessary() throws IOException {
        var newSize = terminal.getTerminalSize();
        if (!newSize.equals(size)) {
            this.size = newSize;
            this.backBuffer = new ScreenBuffer(size);
            this.frontBuffer = new ScreenBuffer(size);
        }
        return size;
    }

    /**
     * Move the cursor to the specified column and row.
     *
     * @param position the terminal position
     */
    @Override
    public void setCursorPosition(TerminalPosition position) {
        this.cursorPosition = position;
    }

    /**
     * Return the virtual cursor position.
     *
     * @return the cursorposition
     */
    @Override
    public TerminalPosition getCursorPosition() {
        return cursorPosition;
    }

    /** Tracks current terminal SGR state and emits only delta transitions. */
    static class SgrStateTracker {
        private Color currentFg = AnsiColor.DEFAULT;
        private Color currentBg = AnsiColor.DEFAULT;
        private final EnumSet<SGR> currentMods = EnumSet.noneOf(SGR.class);

        byte[] transitionTo(TextCell target) {
            // Optimization + correctness: when target is fully default (no fg/bg/modifiers),
            // emit a single SGR reset rather than individual sequences. This avoids the
            // "stuck background" bug where some terminals don't properly interpret
            // \033[49m (default bg) when preceded by other partial resets.
            boolean targetIsDefault = target.fg() == AnsiColor.DEFAULT
                    && target.bg() == AnsiColor.DEFAULT
                    && target.modifiers().isEmpty();
            if (targetIsDefault && (!currentFg.equals(AnsiColor.DEFAULT)
                    || !currentBg.equals(AnsiColor.DEFAULT)
                    || !currentMods.isEmpty())) {
                byte[] reset = AnsiCodes.reset();
                currentFg = AnsiColor.DEFAULT;
                currentBg = AnsiColor.DEFAULT;
                currentMods.clear();
                return reset;
            }

            var sb = new StringBuilder();
            boolean changed = false;

            if (!target.fg().equals(currentFg)) {
                sb.append(AnsiCodes.CSI).append(new String(target.fg().fgSequence(), StandardCharsets.UTF_8)).append("m");
                currentFg = target.fg();
                changed = true;
            }
            if (!target.bg().equals(currentBg)) {
                sb.append(AnsiCodes.CSI).append(new String(target.bg().bgSequence(), StandardCharsets.UTF_8)).append("m");
                currentBg = target.bg();
                changed = true;
            }
            for (SGR mod : target.modifiers()) {
                if (!currentMods.contains(mod)) {
                    sb.append(new String(AnsiCodes.enable(mod), StandardCharsets.UTF_8));
                    currentMods.add(mod);
                    changed = true;
                }
            }
            var it = currentMods.iterator();
            boolean anyModDisabled = false;
            while (it.hasNext()) {
                SGR mod = it.next();
                if (!target.modifiers().contains(mod)) {
                    sb.append(new String(AnsiCodes.disable(mod), StandardCharsets.UTF_8));
                    it.remove();
                    changed = true;
                    anyModDisabled = true;
                }
            }
            // Some CP437 terminal clients (MuffinTerm, SyncTERM) reset fg/bg
            // colors to default when processing SGR disable sequences (e.g.
            // ESC[27m for REVERSE off, ESC[22m for BOLD off). After disabling
            // a modifier, re-emit the current fg and bg so the terminal
            // restores the correct colors for subsequent cells.
            if (anyModDisabled) {
                sb.append(AnsiCodes.CSI).append(new String(currentFg.fgSequence(), StandardCharsets.UTF_8)).append("m");
                sb.append(AnsiCodes.CSI).append(new String(currentBg.bgSequence(), StandardCharsets.UTF_8)).append("m");
                // Shared reset families: ESC[22m clears BOTH bold and dim (and
                // ESC[23m clears italic+strikethrough). The disable above only
                // removed the departing modifier — re-assert every modifier the
                // tracker still holds so the terminal's state matches ours
                // (fixes bold↔dim transitions degrading on scroll repaints).
                for (SGR mod : currentMods) {
                    sb.append(new String(AnsiCodes.enable(mod), StandardCharsets.UTF_8));
                }
            }

            return changed ? sb.toString().getBytes(StandardCharsets.UTF_8) : new byte[0];
        }

        byte[] reset() {
            currentFg = AnsiColor.DEFAULT;
            currentBg = AnsiColor.DEFAULT;
            currentMods.clear();
            return AnsiCodes.reset();
        }
    }
}
