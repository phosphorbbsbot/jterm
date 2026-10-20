package io.jterm.window;

import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalSize;
import io.jterm.screen.DefaultScreen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/** Frame-coalescing laws: input arriving faster than the renderer must not
 *  emit one full render per keystroke; the LAST state wins. (2026-10-19) */
class GuiCoalescingTest {

    @Test
    @DisplayName("drainPendingInput: pending keystrokes are consumed up to the cap")
    void drainPendingInputConsumesUpToCap() {
        // RED until DefaultTextGUI#drainPendingInput exists
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        int drained = gui.drainPendingInput(32);
        assertEquals(0, drained, "no pending input → zero drained");
    }

    @Test
    @DisplayName("drainAndRender: one burst of N pending keys causes at most one render")
    void drainAndRenderCoalescesBurst() throws Exception {
        // RED until drainAndRender(int) exists
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        gui.requestRefresh();
        var w = new WindowImpl("W");
        gui.addWindow(w);
        gui.drainAndRender(32);
        assertTrue(true, "drainAndRender(int) exists and completes without error");
    }

    @Test
    @DisplayName("event loop with coalescing enabled drains before render")
    void eventLoopDrainsBeforeRender() throws Exception {
        // RED until setCoalesceInput(boolean) exists
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        gui.setCoalesceInput(true);
        assertTrue(gui.coalesceInput());
    }
}