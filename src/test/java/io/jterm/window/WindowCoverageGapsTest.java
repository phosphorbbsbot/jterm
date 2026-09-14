package io.jterm.window;

import io.jterm.animation.StarfieldBackground;
import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.screen.DefaultScreen;
import io.jterm.widget.Button;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers public methods in the window package with zero line coverage.
 */
class WindowCoverageGapsTest {

    @Test
    void windowDefaultGetPreferredSizeReturnsSize() {
        Window window = new WindowImpl("test");
        window.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 10));
        // AbstractWindow now derives preferred size from contents (+decoration
        // insets); with an empty contents panel (1x1) and title bar the
        // reported preferred size is 3x3 regardless of the current bounds.
        assertEquals(new TerminalSize(3, 3), window.getPreferredSize());
    }

    @Test
    void animatedBackgroundWindowGetBackground() {
        var bg = new StarfieldBackground(new TerminalSize(80, 24));
        var gui = new DefaultTextGUI(new DefaultScreen(new MockTerminal(new TerminalSize(80, 24))));
        var window = new AnimatedBackgroundWindow(bg, gui);
        assertSame(bg, window.getBackground());
    }

    @Test
    void defaultTextGUIRunEventLoopStopsWhenStopped() throws Exception {
        var term = new MockTerminal(new TerminalSize(40, 20));
        var screen = new DefaultScreen(term);

        var window = new WindowImpl("test");
        window.setHints(java.util.List.of(WindowHint.CENTERED));
        window.getContents().addComponent(new Button("OK"));

        var injectingScreen = new DefaultScreen(new MockTerminal(new TerminalSize(40, 20)) {
            boolean injected = false;
            @Override
            public java.util.Optional<io.jterm.core.input.KeyStroke> pollInput() {
                if (!injected) {
                    injected = true;
                    return java.util.Optional.of(KeyStroke.character('C', true, false, false));
                }
                return java.util.Optional.empty();
            }
        });
        var injectingGui = new DefaultTextGUI(injectingScreen);
        injectingGui.addWindow(window);

        // Ctrl+C no longer terminates the loop (it is forwarded to the active
        // window as a normal keystroke); the loop exits via stopRunning().
        assertDoesNotThrow(() -> {
            new Thread(() -> {
                try { Thread.sleep(200); } catch (InterruptedException e) { }
                injectingGui.stopRunning();
            }).start();
            injectingGui.runEventLoop();
        });
        assertFalse(injectingGui.isRunning());
    }
}
