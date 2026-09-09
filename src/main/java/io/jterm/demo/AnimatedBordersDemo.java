package io.jterm.demo;

import io.jterm.animation.AnimatedBorderEffect;
import io.jterm.animation.AnimationFactory;
import io.jterm.core.AnsiTerminal;
import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.screen.DefaultScreen;
import io.jterm.style.AnsiColor;
import io.jterm.style.TextCell;
import io.jterm.widget.Label;
import io.jterm.window.AnimatedBorderWindow;
import io.jterm.window.DefaultTextGUI;
import io.jterm.window.WindowHint;

import java.io.IOException;
import java.util.List;

/**
 * Demo showing all 5 animated border effects in a single window.
 * Press 1-5 to switch effects, Q to quit.
 *
 * <ul>
 *   <li>1 — Sparkle Corners</li>
 *   <li>2 — Marching Ants</li>
 *   <li>3 — Rotating Dash Corners</li>
 *   <li>4 — Color Pulse</li>
 *   <li>5 — Scanning Line</li>
 * </ul>
 */
public class AnimatedBordersDemo {

    /** Prevents instantiation; run the demo via {@link #main(String[])} instead. */
    private AnimatedBordersDemo() {}

    private static final String[] EFFECT_NAMES = {
            "Sparkle Corners",
            "Marching Ants",
            "Rotating Dash Corners",
            "Color Pulse",
            "Scanning Line"
    };

    /**
     * Runs the animated border effects demo.
     *
     * @param args ignored
     * @throws IOException if the terminal cannot be initialized
     */
    public static void main(String[] args) throws IOException {
        var terminal = new AnsiTerminal();
        var screen = new DefaultScreen(terminal);
        var gui = new DefaultTextGUI(screen);
        screen.startScreen();

        int initialEffect = 0;
        var window = createWindow(initialEffect);

        gui.addWindow(window);
        window.open(gui);
        gui.updateScreen();

        boolean running = true;
        try {
            while (running && gui.isRunning()) {
                // Drain buffered input OURSELVES first (mirrors SpriteDemo):
                // gui.processInput() pulls from the terminal and dispatches to
                // the window, and the window marks 'q' unconsumed — the GUI
                // framework then ignores it, so the quit must be handled here
                // before processInput() ever sees the key. Without this a
                // headless run hangs on the next blocking poll.
                boolean hadInput = false;
                while (true) {
                    var ks = screen instanceof DefaultScreen ds
                            ? ds.getTerminal().pollInput().orElse(null)
                            : null;
                    if (ks == null) break;
                    hadInput = true;
                    if (ks.type() == KeyType.CHARACTER
                            && (ks.character() == 'q' || ks.character() == 'Q')) {
                        running = false;
                        break;
                    }
                    gui.processInput(ks);
                }
                if (!running) break;
                gui.updateScreen();
                if (!hadInput) {
                    Thread.yield();
                }
            }
        } finally {
            window.close();
            gui.close();
        }
    }

    private static AnimatedBorderWindow createWindow(int effectIndex) {
        var effect = createEffect(effectIndex);
        var window = new AnimatedBorderWindow(
                "Animated Borders — " + EFFECT_NAMES[effectIndex], effect) {

            private int currentEffect = effectIndex;

            @Override
            public boolean handleKeyStroke(KeyStroke ks) {
                if (ks.type() == KeyType.CHARACTER) {
                    char ch = ks.character();
                    if (ch == 'q' || ch == 'Q') {
                        // Signal quit — the event loop checks isRunning()
                        return false; // not consumed, let DefaultTextGUI handle Ctrl+C
                    }
                    if (ch >= '1' && ch <= '5') {
                        int idx = ch - '1';
                        setEffect(createEffect(idx));
                        setTitle("Animated Borders — " + EFFECT_NAMES[idx]);
                        currentEffect = idx;
                        return true; // consumed
                    }
                }
                return super.handleKeyStroke(ks);
            }
        };

        window.setHints(List.of(WindowHint.FULLSCREEN, WindowHint.NO_DECORATIONS));

        var contents = window.getContents();
        contents.setLayoutManager(
                new io.jterm.layout.LinearLayout(io.jterm.layout.LinearLayout.Direction.VERTICAL));

        var titleStyle = new TextCell(' ', AnsiColor.BRIGHT_CYAN, AnsiColor.BLACK);
        contents.addComponent(new Label("  Effect: " + EFFECT_NAMES[effectIndex] + "  ", titleStyle));

        var keyStyle = new TextCell(' ', AnsiColor.BRIGHT_GREEN, AnsiColor.BLACK);
        contents.addComponent(new Label("  Keys: 1-5 switch effects, Q quits  ", keyStyle));

        var descStyle = new TextCell(' ', AnsiColor.WHITE, AnsiColor.BLACK);
        contents.addComponent(new Label("", descStyle));
        contents.addComponent(new Label("  Watch the border animate!  ", descStyle));

        return window;
    }

    private static AnimatedBorderEffect createEffect(int effectIndex) {
        return switch (effectIndex) {
            case 0 -> AnimationFactory.sparkleCorners();
            case 1 -> AnimationFactory.marchingAnts();
            case 2 -> AnimationFactory.rotatingDashCorners();
            case 3 -> AnimationFactory.colorPulse();
            case 4 -> AnimationFactory.scanningLine();
            default -> AnimationFactory.sparkleCorners();
        };
    }
}