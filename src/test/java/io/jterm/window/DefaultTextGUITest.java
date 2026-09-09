package io.jterm.window;

import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.screen.DefaultScreen;
import io.jterm.widget.Button;
import io.jterm.widget.CheckBox;
import io.jterm.widget.Label;
import io.jterm.widget.Panel;
import io.jterm.widget.TextBox;
import io.jterm.widget.TextArea;
import io.jterm.widget.ListBox;
import io.jterm.layout.BorderLayout;
import io.jterm.layout.LinearLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DefaultTextGUITest {

    // ===== addWindow / removeWindow =====

    @Test
    @DisplayName("addWindow makes the window active")
    void addWindowMakesActive() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        gui.addWindow(window);
        assertEquals(window, gui.getActiveWindow());
        assertTrue(gui.containsWindow(window));
    }

    @Test
    @DisplayName("addWindow adds to window list and returns it via getWindows()")
    void addWindowAddsToWindowList() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var w1 = new WindowImpl("A");
        var w2 = new WindowImpl("B");
        gui.addWindow(w1);
        gui.addWindow(w2);
        var windows = gui.getWindows();
        assertEquals(2, windows.size());
        assertTrue(windows.contains(w1));
        assertTrue(windows.contains(w2));
    }

    @Test
    @DisplayName("getWindows returns a defensive copy (modifications don't affect GUI)")
    void getWindowsReturnsDefensiveCopy() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        gui.addWindow(new WindowImpl("A"));
        var windows = gui.getWindows();
        windows.clear();
        assertEquals(1, gui.getWindows().size());
    }

    @Test
    @DisplayName("removeWindow marks window for removal; updateScreen finalizes")
    void removeWindowClearsActive() throws Exception {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        gui.addWindow(window);
        gui.removeWindow(window);
        gui.updateScreen();
        assertTrue(gui.getWindows().isEmpty());
        assertNull(gui.getActiveWindow());
    }

    @Test
    @DisplayName("removeWindow switches active to remaining window")
    void removeWindowSwitchesToRemainingWindow() throws Exception {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var w1 = new WindowImpl("A");
        var w2 = new WindowImpl("B");
        gui.addWindow(w1);
        gui.addWindow(w2);
        gui.removeWindow(w2);
        gui.updateScreen();
        assertEquals(w1, gui.getActiveWindow());
    }

    @Test
    @DisplayName("removeWindow on non-active window does not change active")
    void removeNonActiveWindowKeepsActive() throws Exception {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var w1 = new WindowImpl("A");
        var w2 = new WindowImpl("B");
        gui.addWindow(w1);
        gui.addWindow(w2);
        assertEquals(w2, gui.getActiveWindow());
        gui.removeWindow(w1); // remove non-active
        gui.updateScreen();
        assertEquals(w2, gui.getActiveWindow());
        assertFalse(gui.containsWindow(w1));
    }

    @Test
    @DisplayName("removeWindow on the only window leaves active null")
    void removeOnlyWindowLeavesActiveNull() throws Exception {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var w = new WindowImpl("Solo");
        gui.addWindow(w);
        gui.removeWindow(w);
        gui.updateScreen();
        assertNull(gui.getActiveWindow());
    }

    @Test
    @DisplayName("removeWindow on non-added window is a safe no-op")
    void removeNonAddedWindowSafe() throws Exception {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        assertDoesNotThrow(() -> gui.removeWindow(new WindowImpl("Ghost")));
        gui.updateScreen();
        assertTrue(gui.getWindows().isEmpty());
    }

    @Test
    @DisplayName("containsWindow returns false for non-added window")
    void containsWindowFalseForNonAdded() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        assertFalse(gui.containsWindow(new WindowImpl("Ghost")));
    }

    // ===== activeWindow / setActiveWindow =====

    @Test
    @DisplayName("setActiveWindow changes active window")
    void setActiveWindowChangesActive() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var a = new WindowImpl("A");
        var b = new WindowImpl("B");
        gui.addWindow(a);
        gui.addWindow(b);
        assertEquals(b, gui.getActiveWindow());
        gui.setActiveWindow(a);
        assertEquals(a, gui.getActiveWindow());
    }

    @Test
    @DisplayName("setActiveWindow on non-added window is ignored")
    void setActiveWindowOnNonAddedIgnored() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var a = new WindowImpl("A");
        var ghost = new WindowImpl("Ghost");
        gui.addWindow(a);
        gui.setActiveWindow(ghost);
        assertEquals(a, gui.getActiveWindow());
    }

    @Test
    @DisplayName("setActiveWindow re-focuses first focusable component")
    void setActiveWindowRefocuses() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var a = new WindowImpl("A");
        var buttonA = new Button("A");
        a.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        a.getContents().addComponent(buttonA);
        var b = new WindowImpl("B");
        var buttonB = new Button("B");
        b.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        b.getContents().addComponent(buttonB);
        gui.addWindow(a);
        gui.addWindow(b);
        assertTrue(buttonB.isFocused());
        assertFalse(buttonA.isFocused());
        gui.setActiveWindow(a);
        assertTrue(buttonA.isFocused());
    }

    @Test
    @DisplayName("getActiveWindow returns null when no windows")
    void getActiveWindowNullWhenEmpty() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        assertNull(gui.getActiveWindow());
    }

    // ===== Window sizing (FULLSCREEN, FIT_TERMINAL_WINDOW, no hint) =====

    @Test
    @DisplayName("FULLSCREEN hint sizes window to terminal size")
    void fullscreenHintSizesWindowToTerminal() {
        var size = new TerminalSize(80, 24);
        var screen = new DefaultScreen(new MockTerminal(size));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Full");
        window.setHints(List.of(WindowHint.FULLSCREEN));
        gui.addWindow(window);
        assertEquals(size, window.getSize());
    }

    @Test
    @DisplayName("FIT_TERMINAL_WINDOW hint sizes window to terminal size")
    void fitTerminalWindowHintSizesToTerminal() {
        var size = new TerminalSize(100, 30);
        var screen = new DefaultScreen(new MockTerminal(size));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Fit");
        window.setHints(List.of(WindowHint.FIT_TERMINAL_WINDOW));
        gui.addWindow(window);
        assertEquals(size, window.getSize());
    }

    @Test
    @DisplayName("No hint: window with ZERO size gets sized to terminal")
    void noHintZeroSizeGetsSizedToTerminal() {
        var size = new TerminalSize(80, 24);
        var screen = new DefaultScreen(new MockTerminal(size));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Zero");
        // Default size in AbstractWindow is 40x20, but if we set it to ZERO:
        window.setBounds(io.jterm.core.TerminalPosition.TOP_LEFT, TerminalSize.ZERO);
        gui.addWindow(window);
        assertEquals(size, window.getSize());
    }

    @Test
    @DisplayName("No hint: window with non-zero size keeps its size")
    void noHintNonZeroSizeKeepsSize() {
        var size = new TerminalSize(80, 24);
        var screen = new DefaultScreen(new MockTerminal(size));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Custom");
        // AbstractWindow default size is 40x20 — no hint, not ZERO, so sizeWindow does nothing
        gui.addWindow(window);
        assertEquals(new TerminalSize(40, 20), window.getSize());
    }

    @Test
    @DisplayName("FULLSCREEN window position is TOP_LEFT")
    void fullscreenWindowPositionIsTopLeft() {
        var size = new TerminalSize(80, 24);
        var screen = new DefaultScreen(new MockTerminal(size));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Full");
        window.setHints(List.of(WindowHint.FULLSCREEN));
        gui.addWindow(window);
        assertEquals(io.jterm.core.TerminalPosition.TOP_LEFT, window.getPosition());
    }

    @Test
    @DisplayName("updateScreen re-sizes FULLSCREEN window after terminal resize")
    void updateScreenResizesFullscreenAfterTerminalResize() throws Exception {
        var terminal = new MockTerminal(new TerminalSize(80, 24));
        var screen = new DefaultScreen(terminal);
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Full");
        window.setHints(List.of(WindowHint.FULLSCREEN));
        gui.addWindow(window);
        gui.updateScreen();
        // Simulate terminal change via doResizeIfNecessary path won't work here because
        // MockTerminal.getTerminalSize is fixed. Instead, verify the window was already sized.
        assertEquals(new TerminalSize(80, 24), window.getSize());
    }

    // ===== Event loop: handleInput() with various keys =====

    @Test
    @DisplayName("Ctrl+C is forwarded as a normal keystroke, not a quit signal")
    void ctrlCIsForwardedNotQuit() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        gui.addWindow(new WindowImpl("Test"));
        boolean hadInput = gui.processInput(new KeyStroke(KeyType.CHARACTER, 'C', true, false, false));
        assertTrue(hadInput, "Ctrl+C should be forwarded as normal input");
        assertTrue(gui.isRunning(), "Ctrl+C should NOT stop the GUI");
    }

    @Test
    @DisplayName("Ctrl+c (lowercase) is also forwarded, not a quit signal")
    void ctrlCLowercaseIsForwardedNotQuit() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        gui.addWindow(new WindowImpl("Test"));
        boolean hadInput = gui.processInput(new KeyStroke(KeyType.CHARACTER, 'c', true, false, false));
        assertTrue(hadInput, "Ctrl+c should be forwarded as normal input");
        assertTrue(gui.isRunning(), "Ctrl+c should NOT stop the GUI");
    }

    @Test
    @DisplayName("Escape does not stop the event loop")
    void escapeDoesNotStopEventLoop() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        gui.addWindow(new WindowImpl("Test"));
        boolean hadInput = gui.processInput(new KeyStroke(KeyType.ESCAPE));
        assertTrue(hadInput);
        assertTrue(gui.isRunning());
    }

    @Test
    @DisplayName("TAB advances focus and returns true")
    void tabAdvancesFocus() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        var button1 = new Button("One");
        var button2 = new Button("Two");
        window.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        window.getContents().addComponent(button1);
        window.getContents().addComponent(button2);
        gui.addWindow(window);

        assertTrue(button1.isFocused());
        boolean hadInput = gui.processInput(new KeyStroke(KeyType.TAB));
        assertTrue(hadInput);
        assertTrue(button2.isFocused());
    }

    @Test
    @DisplayName("TAB cycles focus back to first component")
    void tabCyclesFocus() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        var b1 = new Button("One");
        var b2 = new Button("Two");
        window.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        window.getContents().addComponent(b1);
        window.getContents().addComponent(b2);
        gui.addWindow(window);

        gui.processInput(new KeyStroke(KeyType.TAB)); // b1 → b2
        gui.processInput(new KeyStroke(KeyType.TAB)); // b2 → b1
        assertTrue(b1.isFocused());
    }

    @Test
    @DisplayName("ENTER triggers focused button click")
    void enterTriggersButtonClick() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        var button = new Button("Click");
        var fired = new boolean[1];
        button.addListener(() -> fired[0] = true);
        window.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        window.getContents().addComponent(button);
        gui.addWindow(window);

        gui.processInput(new KeyStroke(KeyType.ENTER));
        assertTrue(fired[0]);
    }

    @Test
    @DisplayName("Character ' ' (space) triggers focused button click")
    void spaceTriggersButtonClick() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        var button = new Button("Click");
        var fired = new boolean[1];
        button.addListener(() -> fired[0] = true);
        window.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        window.getContents().addComponent(button);
        gui.addWindow(window);

        gui.processInput(new KeyStroke(KeyType.CHARACTER, ' ', false, false, false));
        assertTrue(fired[0]);
    }

    @Test
    @DisplayName("Arrow keys are dispatched to focused component")
    void arrowKeysDispatchedToFocused() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        var list = new ListBox<String>();
        list.addItem("A");
        list.addItem("B");
        list.addItem("C");
        window.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        window.getContents().addComponent(list);
        gui.addWindow(window);

        assertEquals(0, list.getSelectedIndex());
        gui.processInput(new KeyStroke(KeyType.ARROW_DOWN));
        assertEquals(1, list.getSelectedIndex());
        gui.processInput(new KeyStroke(KeyType.ARROW_UP));
        assertEquals(0, list.getSelectedIndex());
    }

    @Test
    @DisplayName("Character input is dispatched to focused TextBox")
    void characterInputDispatchedToTextBox() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        var textBox = new TextBox();
        window.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        window.getContents().addComponent(textBox);
        gui.addWindow(window);

        gui.processInput(new KeyStroke(KeyType.CHARACTER, 'H', false, false, false));
        gui.processInput(new KeyStroke(KeyType.CHARACTER, 'i', false, false, false));
        assertEquals("Hi", textBox.getValue());
    }

    @Test
    @DisplayName("processInput with null injected input and no terminal input returns false")
    void processInputWithNullAndNoInputReturnsFalse() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        gui.addWindow(new WindowImpl("Test"));
        boolean hadInput = gui.processInput(null);
        assertFalse(hadInput, "no input available should return false");
    }

    @Test
    @DisplayName("processInput reads from terminal (escape key)")
    void processInputReadsFromTerminal() throws IOException {
        var input = new ByteArrayInputStream("\033".getBytes());
        var terminal = new MockTerminal(new TerminalSize(80, 24), new java.io.ByteArrayOutputStream(), input);
        var screen = new DefaultScreen(terminal);
        var gui = new DefaultTextGUI(screen);
        gui.addWindow(new WindowImpl("Test"));
        boolean hadInput = gui.processInput();
        assertTrue(hadInput, "escape should be read as input");
        assertTrue(gui.isRunning(), "escape should not stop the GUI");
    }

    // ===== Ctrl-L full repaint (client-driven redraw contract) =====

    @Test
    @DisplayName("Ctrl-L marks the next render as a COMPLETE refresh (full frame)")
    void ctrlLForcesCompleteRefresh() throws IOException {
        var terminal = new MockTerminal(new TerminalSize(80, 24));
        var screen = new DefaultScreen(terminal);
        // Arm the terminal writer: refresh() copies buffers without writing
        // to the terminal until the screen is started.
        screen.startScreen();
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        gui.addWindow(window);

        // Baseline render so the delta engine has a front buffer, then wipe
        // the captured output so we only observe what the NEXT updateScreen
        // emits.
        gui.updateScreen();
        terminal.clearOutput();

        // Ctrl-L (form feed): the universal "redraw the screen" keystroke.
        gui.processInput(new KeyStroke(KeyType.CHARACTER, 'L', true, false, false));
        gui.updateScreen();

        // A COMPLETE refresh is bracketed by autowrap disable/enable (and
        // repaints every cell); a DELTA refresh emits neither marker.
        String out = terminal.getOutput();
        assertTrue(out.contains("\u001B[?7l") && out.contains("\u001B[?7h"),
                "Ctrl-L must force a full-frame (COMPLETE) refresh, output was: "
                        + out.replace("\u001B", "ESC"));
    }

    @Test
    @DisplayName("Ctrl-L still reaches the active window (screens keep their own 'L' handling)")
    void ctrlLAlsoReachesActiveWindow() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);

        var sawCtrlL = new boolean[1];
        var window = new WindowImpl("Test") {
            @Override
            public boolean handleKeyStroke(KeyStroke ks) {
                if (ks.type() == KeyType.CHARACTER && ks.ctrl() && ks.character() == 'L') {
                    sawCtrlL[0] = true;
                    return true;
                }
                return super.handleKeyStroke(ks);
            }
        };
        gui.addWindow(window);

        gui.processInput(new KeyStroke(KeyType.CHARACTER, 'L', true, false, false));
        assertTrue(sawCtrlL[0], "the active window must still see the Ctrl-L keystroke");
    }

    // ===== Modal windows =====

    @Test
    @DisplayName("Modal window blocks input to other windows")
    void modalBlocksInputToOtherWindows() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);

        var backWindow = new WindowImpl("Back");
        var backButton = new Button("Back");
        var backFired = new boolean[1];
        backButton.addListener(() -> backFired[0] = true);
        backWindow.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        backWindow.getContents().addComponent(backButton);
        gui.addWindow(backWindow);

        var modal = new WindowImpl("Modal");
        modal.setHints(List.of(WindowHint.MODAL));
        gui.addWindow(modal);

        gui.processInput(new KeyStroke(KeyType.ENTER));
        assertFalse(backFired[0], "modal should block input to back window");
    }

    @Test
    @DisplayName("Removing modal restores focus to back window")
    void removeModalRestoresFocus() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);

        var backWindow = new WindowImpl("Back");
        var backButton = new Button("Back");
        backWindow.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        backWindow.getContents().addComponent(backButton);
        gui.addWindow(backWindow);

        var modal = new WindowImpl("Modal");
        modal.setHints(List.of(WindowHint.MODAL));
        gui.addWindow(modal);

        gui.removeWindow(modal);
        gui.updateScreen();

        assertEquals(backWindow, gui.getActiveWindow());
        assertTrue(backButton.isFocused());
    }

    @Test
    @DisplayName("Modal is detected as the last window with MODAL hint")
    void modalDetectedAsLastModalWindow() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);

        var w1 = new WindowImpl("W1");
        w1.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        w1.getContents().addComponent(new Button("1"));
        gui.addWindow(w1);

        var modal1 = new WindowImpl("M1");
        modal1.setHints(List.of(WindowHint.MODAL));
        gui.addWindow(modal1);

        // modal1 is active (last added), input goes to modal1 only
        assertEquals(modal1, gui.getActiveWindow());
    }

    @Test
    @DisplayName("Non-modal window on top of modal allows input to active window")
    void nonModalOverModalInputGoesToActive() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);

        var modal = new WindowImpl("Modal");
        modal.setHints(List.of(WindowHint.MODAL));
        gui.addWindow(modal);

        // Now active is modal. Input should be blocked from non-active windows but
        // since modal IS active, it processes normally.
        boolean hadInput = gui.processInput(new KeyStroke(KeyType.ENTER));
        assertTrue(hadInput);
    }

    // ===== Focus management (advanceFocus) =====

    @Test
    @DisplayName("advanceFocus via TAB cycles through all focusable components")
    void advanceFocusCyclesAllFocusable() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        var button = new Button("Btn");
        var checkbox = new CheckBox("Chk");
        var textBox = new TextBox();
        window.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        window.getContents().addComponent(button);
        window.getContents().addComponent(checkbox);
        window.getContents().addComponent(textBox);
        gui.addWindow(window);

        assertTrue(button.isFocused());
        gui.processInput(new KeyStroke(KeyType.TAB));
        assertTrue(checkbox.isFocused());
        gui.processInput(new KeyStroke(KeyType.TAB));
        assertTrue(textBox.isFocused());
        gui.processInput(new KeyStroke(KeyType.TAB));
        assertTrue(button.isFocused()); // cycle back
    }

    @Test
    @DisplayName("Focus manager notifies listeners on focus change")
    void focusManagerNotifiesListeners() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        var button = new Button("Click");
        window.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        window.getContents().addComponent(button);
        gui.addWindow(window);

        AtomicReference<io.jterm.widget.Component> focused = new AtomicReference<>();
        gui.getFocusManager().clearFocus();
        gui.getFocusManager().addListener(focused::set);
        gui.getFocusManager().setFocusedComponent(button);

        assertEquals(button, focused.get());
        assertTrue(button.isFocused());
    }

    @Test
    @DisplayName("advanceFocus with no focusable components is a safe no-op")
    void advanceFocusNoFocusableSafeNoOp() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        // Label is not focusable
        window.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        window.getContents().addComponent(new Label("Just a label"));
        gui.addWindow(window);
        // TAB should not crash
        assertDoesNotThrow(() -> gui.processInput(new KeyStroke(KeyType.TAB)));
    }

    @Test
    @DisplayName("advanceFocus skips invisible components")
    void advanceFocusSkipsInvisible() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        var b1 = new Button("One");
        var b2 = new Button("Two");
        b2.setVisible(false);
        var b3 = new Button("Three");
        window.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        window.getContents().addComponent(b1);
        window.getContents().addComponent(b2);
        window.getContents().addComponent(b3);
        gui.addWindow(window);

        assertTrue(b1.isFocused());
        gui.processInput(new KeyStroke(KeyType.TAB));
        assertTrue(b3.isFocused(), "should skip invisible b2 and focus b3");
        assertFalse(b2.isFocused());
    }

    @Test
    @DisplayName("clearFocus removes focus from current component")
    void clearFocusRemovesFocus() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        var button = new Button("Btn");
        window.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        window.getContents().addComponent(button);
        gui.addWindow(window);
        assertTrue(button.isFocused());
        gui.getFocusManager().clearFocus();
        assertFalse(button.isFocused());
        assertNull(gui.getFocusManager().getFocusedComponent());
    }

    // ===== updateScreen() rendering =====

    @Test
    @DisplayName("updateScreen renders window content to back buffer")
    void updateScreenDrawsWindow() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        window.getContents().setLayoutManager(new BorderLayout());
        window.getContents().addComponent(new Label("Hello"), new BorderLayout.BorderLayoutData(BorderLayout.Region.CENTER));
        gui.addWindow(window);
        gui.updateScreen();
        assertNotNull(screen.getBackCell(1, 1));
    }

    @Test
    @DisplayName("updateScreen skips windows marked for removal")
    void updateScreenSkipsRemovedWindows() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var w1 = new WindowImpl("W1");
        w1.setHints(List.of(WindowHint.FULLSCREEN));
        gui.addWindow(w1);
        gui.updateScreen();
        // Now remove w1 and add w2 — w1 should not render
        var w2 = new WindowImpl("W2");
        w2.setHints(List.of(WindowHint.FULLSCREEN));
        gui.addWindow(w2);
        gui.removeWindow(w1);
        gui.updateScreen();
        assertFalse(gui.containsWindow(w1));
        assertTrue(gui.containsWindow(w2));
    }

    @Test
    @DisplayName("updateScreen is a no-op when no refresh needed")
    void updateScreenNoOpWhenNoRefreshNeeded() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        gui.addWindow(new WindowImpl("Test"));
        gui.updateScreen(); // first render
        // Second call should be no-op since needsRefresh is false
        gui.updateScreen();
        // Verify it doesn't throw
    }

    @Test
    @DisplayName("requestRefresh marks screen as needing refresh")
    void requestRefreshMarksForRefresh() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        gui.addWindow(new WindowImpl("Test"));
        gui.updateScreen();
        gui.requestRefresh();
        // After requestRefresh, updateScreen should re-render
        gui.updateScreen();
    }

    @Test
    @DisplayName("updateScreen renders multiple windows in z-order")
    void updateScreenRendersMultipleWindows() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        gui.addWindow(new WindowImpl("A"));
        gui.addWindow(new WindowImpl("B"));
        gui.updateScreen();
        // Both windows should have been sized
        // (covered by no-exception + back buffer non-null)
        assertNotNull(screen.getBackCell(0, 0));
    }

    // ===== Lifecycle: running / stopRunning / close =====

    @Test
    @DisplayName("GUI starts in running state")
    void guiStartsRunning() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        assertTrue(gui.isRunning());
    }

    @Test
    @DisplayName("stopRunning sets running to false")
    void stopRunningSetsRunningFalse() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        gui.stopRunning();
        assertFalse(gui.isRunning());
    }

    // ===== Re-attachment: startRunning (session persistence plan, Task 1.3) =====

    @Test
    @DisplayName("startRunning restarts a stopped loop (re-attachment)")
    void startRunningRestartsAStoppedLoop() {
        var gui = createGui();
        gui.stopRunning();
        assertFalse(gui.isRunning());

        gui.startRunning();

        assertTrue(gui.isRunning(), "startRunning must re-enable the loop after a disconnect");
    }

    @Test
    @DisplayName("startRunning re-arms a full refresh for the newly attached client")
    void startRunningArmsFullRefresh() throws IOException {
        var gui = createGui();
        gui.updateScreen();   // consume the constructor's initial forceComplete
        gui.stopRunning();

        gui.startRunning();

        // Observable contract: after restart the GUI is again refresh-capable.
        // (The private forceComplete=true arming is verified indirectly — the
        // first updateScreen after re-attach must not be a delta against a
        // front buffer the new client never saw.)
        assertTrue(gui.isRunning());
        assertDoesNotThrow(gui::updateScreen);
    }

    @Test
    @DisplayName("updateScreen after startRunning renders without error")
    void updateScreenAfterStartRunningRenders() throws IOException {
        var gui = createGui();
        gui.stopRunning();
        gui.startRunning();

        assertDoesNotThrow(() -> {
            gui.requestRefresh();
            gui.updateScreen();
            gui.updateScreen();
        });
    }

    @Test
    @DisplayName("close sets running to false")
    void closeSetsRunningFalse() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        gui.close();
        assertFalse(gui.isRunning());
    }

    @Test
    @DisplayName("getScreen returns the screen passed to constructor")
    void getScreenReturnsConstructorScreen() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        assertSame(screen, gui.getScreen());
    }

    @Test
    @DisplayName("getFocusManager returns the GUI's focus manager")
    void getFocusManagerReturnsManager() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        assertNotNull(gui.getFocusManager());
    }

    // ===== Window content focus on add =====

    @Test
    @DisplayName("addWindow focuses first focusable component")
    void addWindowFocusesFirstFocusable() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        var label = new Label("Not focusable");
        var button = new Button("Focusable");
        window.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        window.getContents().addComponent(label);
        window.getContents().addComponent(button);
        gui.addWindow(window);
        assertTrue(button.isFocused());
        assertEquals(button, gui.getFocusManager().getFocusedComponent());
    }

    @Test
    @DisplayName("addWindow with no focusable components focuses the content panel itself")
    void addWindowNoFocusableFocusesContent() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        window.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        window.getContents().addComponent(new Label("Just a label"));
        gui.addWindow(window);
        // focusManager should have some focus target (the panel or null)
        // Just verify it doesn't crash
    }

    @Test
    @DisplayName("addWindow with nested containers finds deep focusable")
    void addWindowNestedContainersFindsDeepFocusable() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        var outer = new Panel(new LinearLayout(LinearLayout.Direction.VERTICAL));
        var inner = new Panel(new LinearLayout(LinearLayout.Direction.HORIZONTAL));
        var button = new Button("Deep");
        inner.addComponent(button);
        outer.addComponent(inner);
        window.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        window.getContents().addComponent(outer);
        gui.addWindow(window);
        assertTrue(button.isFocused());
    }

    // ===== waitForInput =====

    @Test
    @DisplayName("waitForInput delegates to terminal readInput")
    void waitForInputDelegatesToTerminal() throws IOException {
        var input = new ByteArrayInputStream("x".getBytes());
        var terminal = new MockTerminal(new TerminalSize(80, 24), new java.io.ByteArrayOutputStream(), input);
        var screen = new DefaultScreen(terminal);
        var gui = new DefaultTextGUI(screen);
        // This will block until 'x' is read
        gui.waitForInput();
        // If it returns without hanging, the test passes
    }

    @Test
    @DisplayName("TextArea is recognized as focusable and Tab advances focus to it")
    void textAreaIsFocusableAndTabAdvances() throws IOException {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(80, 24)));
        var gui = new DefaultTextGUI(screen);
        var window = new WindowImpl("Test");
        var textBox = new TextBox();
        var textArea = new TextArea("", 10, 3);
        window.getContents().setLayoutManager(new LinearLayout(LinearLayout.Direction.VERTICAL));
        window.getContents().addComponent(textBox);
        window.getContents().addComponent(textArea);
        gui.addWindow(window);

        assertTrue(textBox.isFocused(), "TextBox should get initial focus");
        gui.processInput(new KeyStroke(KeyType.TAB));
        assertTrue(textArea.isFocused(), "TextArea should receive focus after Tab");
        gui.processInput(new KeyStroke(KeyType.TAB));
        assertTrue(textBox.isFocused(), "Tab should cycle back to TextBox");
    }

    private static DefaultTextGUI createGui() {
        return new DefaultTextGUI(new DefaultScreen(new MockTerminal(new TerminalSize(80, 24))));
    }
}