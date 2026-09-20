package io.jterm.window;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.event.FocusManager;
import io.jterm.event.Listener;
import io.jterm.graphics.TextGraphics;
import io.jterm.graphics.TextGraphicsExtensions;
import io.jterm.screen.Screen;
import io.jterm.widget.Button;
import io.jterm.widget.CheckBox;
import io.jterm.widget.ListBox;
import io.jterm.widget.RadioButton;
import io.jterm.widget.Table;
import io.jterm.widget.TextBox;
import io.jterm.widget.TextArea;
import io.jterm.widget.DataGrid;
import io.jterm.widget.Container;
import io.jterm.widget.Component;
import io.jterm.style.ThemeManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Default window manager, input dispatch, and event loop. */
public class DefaultTextGUI implements TextGUI, WindowManager {
    private final Screen screen;
    private final List<Window> windows = new CopyOnWriteArrayList<>();
    private final List<Window> windowsToRemove = new CopyOnWriteArrayList<>();
    private volatile Window activeWindow;
    private final FocusManager focusManager = new FocusManager();
    private volatile boolean running = true;
    private volatile boolean needsRefresh = true;
    private volatile boolean forceComplete = true;  // start with a complete refresh
    private final Object screenLock = new Object();

    /**
     * Nanotime of the most recent auto-refresh tick, for the event loop's
     * interval check (window auto-refresh, see
     * {@link Window#autoRefreshIntervalMillis()}). Guarded by the event loop
     * thread only.
     */
    private long lastAutoRefreshNanos = System.nanoTime();

    /**
     * Create a GUI backed by the given screen.
     *
     * @param screen the screen to render to
     */
    public DefaultTextGUI(Screen screen) {
        this.screen = screen;
    }

    /**
     * Return the screen backing this GUI.
     *
     * @return the screen
     */
    @Override
    public Screen getScreen() { return screen; }

    /**
     * Add a window to the GUI and make it active.
     *
     * @param window the window to add or remove
     */
    @Override
    public void addWindow(Window window) {
        windows.add(window);
        if (!window.getHints().contains(WindowHint.BACKGROUND)) {
            activeWindow = window;
            focusFirst(window.getContents());
        }
        sizeWindow(window);
        needsRefresh = true;
        forceComplete = true;
    }

    /**
     * Remove a window from the GUI.
     *
     * @param window the window to add or remove
     */
    @Override
    public void removeWindow(Window window) {
        windowsToRemove.add(window);
        if (activeWindow == window) {
            var remaining = new ArrayList<>(windows);
            remaining.removeAll(windowsToRemove);
            activeWindow = remaining.isEmpty() ? null : findTopmostNonBackground(remaining);
            focusManager.clearFocus();
            if (activeWindow != null) focusFirst(activeWindow.getContents());
        }
        needsRefresh = true;
        forceComplete = true;
    }

    /**
     * Return the currently active window.
     *
     * @return the activewindow
     */
    @Override
    public Window getActiveWindow() { return activeWindow; }

    /**
     * Set the active window by reference.
     *
     * @param window the window to add or remove
     */
    @Override
    public void setActiveWindow(Window window) {
        if (windows.contains(window)) {
            activeWindow = window;
            focusFirst(window.getContents());
            needsRefresh = true;
        }
    }

    /**
     * Return all currently managed windows.
     *
     * @return the windows
     */
    @Override
    public Collection<Window> getWindows() {
        if (windowsToRemove.isEmpty()) return new ArrayList<>(windows);
        var visible = new ArrayList<>(windows);
        visible.removeAll(windowsToRemove);
        return visible;
    }

    /**
     * Check if a window is currently managed by this GUI.
     *
     * @param window the window to check
     *
     * @return true if the window is present and not pending removal
     */
    @Override
    public boolean containsWindow(Window window) {
        return windows.contains(window) && !windowsToRemove.contains(window);
    }

    /**
     * Return the focus manager for this GUI.
     *
     * @return the focusmanager
     */
    public FocusManager getFocusManager() {
        return focusManager;
    }

    /**
     * Return whether the event loop is still running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }

    /** Marks the screen as needing a refresh on the next {@link #updateScreen()} call. */
    public void requestRefresh() {
        needsRefresh = true;
    }

    /**
     * Signal the event loop to stop.
     */
    public void stopRunning() {
        running = false;
    }

    /**
     * Restart the event loop after a {@link #stopRunning()}. Used on session
     * re-attachment: the loop was stopped when the old socket disconnected,
     * and a re-attached client resumes input processing and screen refresh on
     * the newly attached terminal. The first refresh after restart is a
     * COMPLETE render so the new client receives the full current screen.
     */
    public synchronized void startRunning() {
        running = true;
        needsRefresh = true;
        forceComplete = true;
    }

    /**
     * Requests that the NEXT screen update be a COMPLETE repaint (full
     * redraw with cursor homing) rather than a cell delta. Use when a
     * structural screen change must be unmistakable to thin clients whose
     * delta application can desync (e.g. a modal popup over an animated
     * background).
     */
    public synchronized void forceCompleteRefresh() {
        needsRefresh = true;
        forceComplete = true;
    }

    /**
     * Process one input event from the terminal.
     *
     * @return true if input was processed, false if none available
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public boolean processInput() throws IOException {
        return processInput(null);
    }

    /**
     * Process one input event from the terminal.
     *
     * @param injected an optional injected key stroke
     *
     * @return true if input was processed, false if none available
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    public boolean processInput(KeyStroke injected) throws IOException {
        synchronized (screenLock) {
            var ks = injected != null ? injected : getInput();
            if (ks == null) return false; // no input available
            if (ks.type() == KeyType.CHARACTER && ks.ctrl() && (ks.character() == 'C' || ks.character() == 'c')) {
                // Ctrl+C: forward to the active window for BBS-level handling
                // (detach/logout), rather than killing the event loop. The window
                // hierarchy (MenuWindow, ContentScreen) decides what to do.
            }
            if (ks.type() == KeyType.CHARACTER && ks.ctrl() && ks.character() == 'L') {
                // Ctrl-L: the classic "redraw the screen" keystroke. Thin
                // clients (e.g. the iOS app after a foreground restore) send
                // it to ask for a full frame — the delta engine's partial
                // refresh can't repaint a client whose canvas was wiped
                // externally. The next updateScreen() emits a COMPLETE
                // refresh; the keystroke then falls through to the window
                // dispatch below so screens keep their own 'L' handling.
                forceComplete = true;
                needsRefresh = true;
            }
            if (ks.type() == KeyType.ESCAPE) {
                // Don't quit on Escape in BBS mode — let screens handle it
            }
            var modal = modalWindow();
            if (modal != null && activeWindow != null && !modal.equals(activeWindow)) {
                // A modal window exists but isn't active — dispatch the key to
                // it instead of discarding it. The old behavior returned true
                // and DROPPED the keystroke entirely (e.g. a Ctrl-T arriving
                // while a stale modal reference lingered was silently eaten).
                activeWindow = modal;
            }
            if (activeWindow != null && windowsToRemove.contains(activeWindow)) {
                // The active window is pending removal (e.g. the chat popup
                // just closed via ESC): dispatching to it would feed a dead
                // window. Re-point to the topmost live window instead.
                var live = findTopmostNonBackground(new ArrayList<>(windows));
                activeWindow = live != null ? live : activeWindow;
            }
            if (activeWindow != null && activeWindow.getHints().contains(WindowHint.BACKGROUND)) {
                activeWindow = findTopmostNonBackground(new ArrayList<>(windows));
            }
            var focused = activeWindow != null ? activeWindow.getFocusedComponent() : null;
            var windowBefore = activeWindow;
            boolean consumed = false;
            if (focused != null) {
                consumed = focused.handleKeyStroke(ks);
            }
            // Only forward to the window if the focused component didn't consume it
            // AND the active window didn't change (e.g. ESC closing a sub-screen)
            if (!consumed && activeWindow != null && activeWindow == windowBefore) {
                consumed = activeWindow.handleKeyStroke(ks);
            }
            // Tab advances focus only if neither the focused component nor the
            // window consumed it. This lets screens intercept Tab for custom
            // focus management (e.g. DatabaseQueryScreen toggles between query
            // field and results grid). Shift+Tab reverses focus direction.
            if (!consumed && ks.type() == KeyType.TAB && activeWindow != null) {
                advanceFocus(!ks.shift());
            }
            needsRefresh = true;
            return true;
        }
    }

    private KeyStroke getInput() throws IOException {
        // Use pollInput with a 5ms timeout for near-instant input delivery.
        // This replaces the old pattern of pollInput() + Thread.sleep(16),
        // which added up to 16ms latency to every keystroke.
        // With a BlockingQueue-backed terminal, this call blocks efficiently
        // and returns immediately when input arrives.
        // 5ms timeout = 200 wake-ups/sec idle, still <1ms average input latency.
        return screen instanceof io.jterm.screen.DefaultScreen ds
                ? ds.getTerminal().pollInput(5).orElse(null)
                : null;
    }

    /**
     * Block until input is available.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void waitForInput() throws IOException {
        if (screen instanceof io.jterm.screen.DefaultScreen ds) {
            ds.getTerminal().readInput();
        }
    }

    /**
     * Render all windows to the screen.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void updateScreen() throws IOException {
        synchronized (screenLock) {
            if (!needsRefresh) return;
            screen.doResizeIfNecessary();
            screen.clear();
            // Initialize the rendering buffer with theme colors so areas not
            // covered by any window get the theme background instead of black.
            var theme = ThemeManager.active();
            var fillCell = new io.jterm.style.TextCell(' ', theme.foreground(), theme.background());
            var buf = new io.jterm.screen.ScreenBuffer(screen.getTerminalSize(), fillCell);
            var g = new io.jterm.graphics.TextGraphics(buf);
            for (var window : sortedWindows()) {
                if (windowsToRemove.contains(window)) continue;
                sizeWindow(window);
                var sub = io.jterm.graphics.TextGraphicsExtensions.subGraphics(g, window.getPosition(), window.getSize());
                window.draw(sub);
            }
            // Copy g buffer to screen
            for (int r = 0; r < screen.getTerminalSize().rows(); r++) {
                for (int c = 0; c < screen.getTerminalSize().columns(); c++) {
                    screen.setCell(c, r, buf.getCell(c, r));
                }
            }
            if (forceComplete) {
                screen.refresh(io.jterm.screen.RefreshType.COMPLETE);
                forceComplete = false;
            } else {
                screen.refresh();
            }
            needsRefresh = false;
            // Call close() on windows being removed so they can clean up listeners/resources
            for (var w : windowsToRemove) {
                w.close();
            }
            windows.removeAll(windowsToRemove);
            windowsToRemove.clear();
        }
    }

    private io.jterm.screen.ScreenBuffer gBuffer;

    /**
     * Run the main event loop until stopped.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    public void runEventLoop() throws IOException {
        needsRefresh = true;
        while (running) {
            boolean hadInput = false;
            try {
                hadInput = processInput();
                checkAutoRefresh();
                updateScreen();
            } catch (RuntimeException | IOException e) {
                // A fault in a key handler or a paint must never kill the loop:
                // the loop dying tears the session down (clients see a blank
                // terminal). Log and keep serving input.
                java.util.logging.Logger.getLogger(DefaultTextGUI.class.getName())
                        .log(java.util.logging.Level.SEVERE,
                                "[GUI-LOOP] recovered from fault", e);
            }
            // No more Thread.sleep(16) — getInput() now uses pollInput(1ms)
            // which blocks efficiently and returns as soon as input arrives.
            // The 1ms timeout ensures needsRefresh is checked promptly even
            // when no input is available.
            if (!hadInput) {
                // Yield to other threads (e.g. background CompletableFuture workers)
                Thread.yield();
            }
        }
    }

    /**
     * Repaint when any window's auto-refresh interval has elapsed (see
     * {@link Window#autoRefreshIntervalMillis()}). Called once per idle spin
     * of the event loop; a single nanotime compare in the common case, so
     * zero-input spins stay effectively free. Windows displaying time-derived
     * data (idle timers, clocks) rely on this to stay current between
     * keystrokes.
     */
    private void checkAutoRefresh() {
        long now = System.nanoTime();
        long earliest = Long.MAX_VALUE;
        for (var w : windows) {
            if (windowsToRemove.contains(w)) continue;
            long interval = w.autoRefreshIntervalMillis();
            if (interval <= 0) continue;
            long elapsedMillis = (now - lastAutoRefreshNanos) / 1_000_000L;
            if (elapsedMillis >= interval) {
                needsRefresh = true;
                lastAutoRefreshNanos = now;
                return;
            }
            earliest = Math.min(earliest, interval - elapsedMillis);
        }
        // No tick due; nothing else to do — the loop keeps spinning.
    }

    /**
     * Close the terminal and release resources.
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    @Override
    public void close() throws IOException {
        running = false;
        screen.close();
    }

    private Window findTopmostNonBackground(List<Window> list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (!list.get(i).getHints().contains(WindowHint.BACKGROUND)) return list.get(i);
        }
        return null;
    }

    private Window modalWindow() {
        for (int i = windows.size() - 1; i >= 0; i--) {
            var w = windows.get(i);
            if (!windowsToRemove.contains(w) && w.getHints().contains(WindowHint.MODAL)) return w;
        }
        return null;
    }

    private List<Window> sortedWindows() {
        return windows.stream()
                .sorted(Comparator.comparingInt((Window w) -> w.getHints().contains(WindowHint.BACKGROUND) ? 0 : 1)
                        .thenComparingInt(windows::indexOf))
                .toList();
    }

    private void sizeWindow(Window window) {
        var hints = window.getHints();
        var size = screen.getTerminalSize();
        if (hints.contains(WindowHint.FULLSCREEN)) {
            window.setBounds(TerminalPosition.TOP_LEFT, size);
        } else if (hints.contains(WindowHint.FIT_TERMINAL_WINDOW) || window.getSize().equals(TerminalSize.ZERO)) {
            window.setBounds(TerminalPosition.TOP_LEFT, size);
        } else if (hints.contains(WindowHint.CENTERED)) {
            var preferred = window.getPreferredSize();
            int width = Math.min(preferred.columns(), size.columns());
            int height = Math.min(preferred.rows(), size.rows());
            int col = Math.max(0, (size.columns() - width) / 2);
            int row = Math.max(0, (size.rows() - height) / 2);
            window.setBounds(new TerminalPosition(col, row), new TerminalSize(width, height));
        }
    }

    private void focusFirst(Component component) {
        if (component == null) return;
        var first = findFirstFocusable(component);
        var target = first != null ? first : component;
        if (activeWindow != null) activeWindow.setFocusedComponent(target);
        focusManager.setFocusedComponent(target);
    }

    private Component findFirstFocusable(Component component) {
        if (isFocusable(component)) return component;
        if (component instanceof Container cont) {
            for (var child : cont.getChildren()) {
                if (!child.isVisible()) continue;
                var found = findFirstFocusable(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void advanceFocus() {
        advanceFocus(true);
    }

    private void advanceFocus(boolean forward) {
        if (activeWindow == null) return;
        var root = activeWindow.getContents();
        var order = collectFocusable(root);
        if (order.isEmpty()) return;
        var current = activeWindow.getFocusedComponent();
        if (current == null) current = focusManager.getFocusedComponent();
        int idx = current != null ? order.indexOf(current) : -1;
        int next;
        if (forward) {
            next = (idx + 1) % order.size();
        } else {
            next = idx <= 0 ? order.size() - 1 : idx - 1;
        }
        var nextComponent = order.get(next);
        activeWindow.setFocusedComponent(nextComponent);
        focusManager.setFocusedComponent(nextComponent);
    }

    private List<Component> collectFocusable(Component component) {
        List<Component> list = new ArrayList<>();
        if (component.isVisible() && isFocusable(component)) list.add(component);
        if (component instanceof Container cont) {
            for (var child : cont.getChildren()) {
                list.addAll(collectFocusable(child));
            }
        }
        return list;
    }

    private boolean isFocusable(Component component) {
        if (!component.isFocusable()) return false;
        return component instanceof Button
                || component instanceof CheckBox
                || component instanceof ListBox
                || component instanceof RadioButton
                || component instanceof Table
                || component instanceof TextBox
                || component instanceof TextArea
                || component instanceof DataGrid;
    }
}
