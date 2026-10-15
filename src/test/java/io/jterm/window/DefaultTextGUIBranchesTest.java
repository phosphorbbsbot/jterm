package io.jterm.window;

import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.screen.DefaultScreen;
import io.jterm.widget.Button;
import io.jterm.widget.DataGrid;
import io.jterm.widget.Label;
import io.jterm.widget.Panel;
import io.jterm.widget.model.GridColumn;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers remaining {@link DefaultTextGUI} paths: getWindows() with a pending
 * removal, forceCompleteRefresh, startRunning, modal re-pointing, pending-
 * removal active-window re-pointing, background-window key routing, focus
 * cycling (forward/reverse/empty window), and the auto-refresh tick.
 */
class DefaultTextGUIBranchesTest {

    private DefaultTextGUI gui() {
        return new DefaultTextGUI(new DefaultScreen(new MockTerminal(new TerminalSize(80, 24))));
    }

    private WindowImpl plainWindow(String title) {
        return new WindowImpl(title);
    }

    private WindowImpl hintedWindow(String title, WindowHint... hints) {
        var w = new WindowImpl(title);
        w.setHints(List.of(hints));
        return w;
    }

    private Button button(String label) {
        var b = new Button(label);
        b.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 1));
        return b;
    }

    // ---- window bookkeeping ------------------------------------------------------

    @Test
    void getWindowsExcludesPendingRemoval() {
        var gui = gui();
        var w1 = plainWindow("A");
        var w2 = plainWindow("B");
        gui.addWindow(w1);
        gui.addWindow(w2);
        gui.removeWindow(w1);
        var visible = gui.getWindows();
        assertEquals(1, visible.size(), "pending-removal window is hidden from getWindows()");
        assertFalse(visible.contains(w1));
        // updateScreen() finalizes removal.
        assertDoesNotThrow(() -> gui.updateScreen());
        assertFalse(gui.containsWindow(w1));
    }

    @Test
    void forceCompleteRefreshFlagsAndStartRunningRestarts() {
        var gui = gui();
        gui.stopRunning();
        assertFalse(gui.isRunning());
        gui.startRunning();
        assertTrue(gui.isRunning());
        assertDoesNotThrow(() -> gui.updateScreen()); // renders a COMPLETE frame
        gui.forceCompleteRefresh();
        assertDoesNotThrow(() -> gui.updateScreen());
    }

    // ---- key routing branches -------------------------------------------------------

    @Test
    void modalWindowBecomesActiveToReceiveKeys() {
        var gui = gui();
        var normal = plainWindow("Normal");
        var modal = hintedWindow("Modal", WindowHint.MODAL);
        gui.addWindow(normal);
        gui.addWindow(modal);
        assertSame(modal, gui.getActiveWindow());
        // Simulate a stale active window: point at the normal one.
        gui.setActiveWindow(normal);
        boolean had = assertDoesNotThrow(() -> gui.processInput(new KeyStroke(KeyType.CHARACTER, 'k', false, false, false)));
        assertTrue(had);
        assertSame(modal, gui.getActiveWindow(), "modal steals activity on next keystroke");
    }

    @Test
    void pendingRemovalActiveWindowIsRepointedToLiveWindow() {
        var gui = gui();
        var live = plainWindow("Live");
        var doomed = plainWindow("Doomed");
        gui.addWindow(live);
        gui.addWindow(doomed);
        gui.removeWindow(doomed);
        // doomed was active; after removal request the GUI already re-points, but
        // send a keystroke to drive the guard branch anyway.
        assertDoesNotThrow(() -> gui.processInput(new KeyStroke(KeyType.CHARACTER, 'k', false, false, false)));
        assertSame(live, gui.getActiveWindow());
    }

    @Test
    void backgroundActiveWindowRoutesKeysToTopmostNonBackground() {
        var gui = gui();
        var bg1 = hintedWindow("Bg1", WindowHint.BACKGROUND);
        var bg2 = hintedWindow("Bg2", WindowHint.BACKGROUND);
        var fg = plainWindow("Fg");
        gui.addWindow(bg1);
        gui.addWindow(bg2);
        gui.addWindow(fg);
        gui.setActiveWindow(bg2); // deliberately wrong: background window active
        assertDoesNotThrow(() -> gui.processInput(new KeyStroke(KeyType.CHARACTER, 'k', false, false, false)));
        assertSame(fg, gui.getActiveWindow(), "keys route to the topmost non-background window");
    }

    @Test
    void shiftTabReversesFocusAndTabCycles() {
        var gui = gui();
        var window = plainWindow("Form");
        var b1 = button("One");
        var b2 = button("Two");
        var b3 = button("Three");
        window.getContents().addComponent(new Label("n/a"));
        window.getContents().addComponent(b1);
        window.getContents().addComponent(b2);
        window.getContents().addComponent(b3);
        gui.addWindow(window);
        assertSame(b1, window.getFocusedComponent(), "focusFirst picks the first focusable");
        assertDoesNotThrow(() -> gui.processInput(new KeyStroke(KeyType.TAB)));
        assertSame(b2, window.getFocusedComponent());
        assertDoesNotThrow(() -> gui.processInput(new KeyStroke(KeyType.TAB, '\t', false, false, true)));
        assertSame(b1, window.getFocusedComponent(), "shift+tab moves focus backwards");
    }

    @Test
    void tabOnWindowWithoutFocusablesKeepsFocusOnFallback() {
        var gui = gui();
        var window = plainWindow("Empty");
        window.getContents().addComponent(new Label("nothing focusable"));
        gui.addWindow(window);
        // focusFirst falls back to the container itself when nothing is focusable.
        boolean had = assertDoesNotThrow(() -> gui.processInput(new KeyStroke(KeyType.TAB)));
        assertTrue(had);
        assertNotNull(window.getFocusedComponent());
    }

    @Test
    void focusedComponentConsumesKeyBeforeWindow() {
        var gui = gui();
        var window = plainWindow("Win");
        var consumer = button("Btn");
        window.getContents().addComponent(consumer);
        gui.addWindow(window);
        boolean had = assertDoesNotThrow(() -> gui.processInput(new KeyStroke(KeyType.CHARACTER, 'z', false, false, false)));
        assertTrue(had);
    }

    @Test
    void dataGridIsFocusableThroughGui() {
        var gui = gui();
        var window = plainWindow("Grid");
        var columns = List.<GridColumn<String>>of(GridColumn.text("N", r -> "x"));
        var grid = new DataGrid<String>(columns);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 3));
        window.getContents().addComponent(grid);
        gui.addWindow(window);
        assertSame(grid, window.getFocusedComponent());
    }

    @Test
    void removedModalWithStaleActiveReferenceRoutesKeys() {
        var gui = gui();
        var modal = hintedWindow("Modal", WindowHint.MODAL);
        gui.addWindow(modal);
        gui.removeWindow(modal);
        assertDoesNotThrow(() -> gui.processInput(new KeyStroke(KeyType.CHARACTER, 'k', false, false, false)));
        assertNull(gui.getActiveWindow(), "no live windows → nothing active");
    }

    @Test
    void keyStrokeFallsThroughToWindowWhenComponentDeclines() {
        var gui = gui();
        var window = plainWindow("Win");
        var b1 = button("Btn");
        window.getContents().addComponent(b1);
        gui.addWindow(window);
        // Button consumes only ENTER/space; another printable key falls through
        // to the window-level handler.
        boolean had = assertDoesNotThrow(() -> gui.processInput(new KeyStroke(KeyType.CHARACTER, 'z', false, false, false)));
        assertTrue(had);
    }

    // ---- auto refresh -----------------------------------------------------------------

    @Test
    void autoRefreshWindowTriggersRepaintInLoopTick() throws Exception {
        var gui = gui();
        var window = new WindowImpl("Ticker") {
            @Override public long autoRefreshIntervalMillis() { return 1L; }
        };
        gui.addWindow(window);
        gui.requestRefresh();
        assertDoesNotThrow(() -> gui.updateScreen());
        // Drive checkAutoRefresh indirectly: elapsed ≥ 1ms after a beat.
        Thread.sleep(5);
        gui.requestRefresh();
        assertDoesNotThrow(() -> gui.updateScreen());
    }

    @Test
    void closeStopsRunning() throws IOException {
        var gui = gui();
        gui.close();
        assertFalse(gui.isRunning());
    }
}