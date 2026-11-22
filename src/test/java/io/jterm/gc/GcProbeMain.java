package io.jterm.gc;

import io.jterm.animation.AnimationFactory;
import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalSize;
import io.jterm.screen.DefaultScreen;
import io.jterm.style.AnsiColor;
import io.jterm.style.TextCell;
import io.jterm.widget.AnimatedBorder;
import io.jterm.window.AnimatedBorderWindow;
import io.jterm.widget.Label;
import io.jterm.window.DefaultTextGUI;
import io.jterm.window.WindowHint;

import java.util.List;

/**
 * GC-probe harness (Joe, 2026-11-21: "look for code that generates
 * unnecessary garbage causing JVM pauses"). Drives the REAL frame path —
 * DefaultTextGUI.updateScreen → widget paint → buffer→screen copy →
 * SGR-delta refresh — on a headless MockTerminal for N frames. Run under a
 * JFR recording; the jdk.ObjectAllocationSample ranking then shows exactly
 * what the paint loop allocates.
 */
public final class GcProbeMain {
    private GcProbeMain() {}

    public static void main(String[] args) throws Exception {
        int frames = Integer.parseInt(args.length > 0 ? args[0] : "600");

        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(100, 40)));
        screen.startScreen();
        var gui = new DefaultTextGUI(screen);

        var window = new AnimatedBorderWindow(
                "GC Probe — the paint loop under JFR",
                AnimationFactory.randomBorderEffect());
        window.setHints(List.of(WindowHint.FULLSCREEN, WindowHint.NO_DECORATIONS));
        var contents = window.getContents();
        contents.setLayoutManager(
                new io.jterm.layout.LinearLayout(io.jterm.layout.LinearLayout.Direction.VERTICAL));

        var style = new TextCell(' ', AnsiColor.BRIGHT_GREEN, AnsiColor.BLACK);
        contents.addComponent(new Label("  SLEEVE        MARKET VALUE     DAY      YTD      BETA", style));
        Label tape = new Label("  AAPL          1,234,567.89     -3.59%   -43.44%  1.02", style);
        contents.addComponent(tape);
        String[] syms = {"AAPL", "META", "NVDA", "TSLA", "MSFT", "AMZN", "JPM", "V", "XOM", "UNH"};
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < 20; i++) {
            contents.addComponent(new Label(
                    "  " + syms[i % syms.length] + "   "
                            + (1_000_000 + rng.nextInt(900_000)) + "."
                            + (10 + rng.nextInt(89))
                            + "   -3.59%   -43.44%  1.0" + i, style));
        }
        Label ring = new Label(ringLine(0), style);
        contents.addComponent(ring);

        gui.addWindow(window);
        window.open(gui);
        gui.updateScreen();

        long t0 = System.nanoTime();
        for (int f = 0; f < frames; f++) {
            tape.setText(ringLine(f));                     // live tape cell changes
            ring.setText(ringLine(f + 1));                 // the changing ticker row
            gui.requestRefresh();
            gui.updateScreen();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("gcb frames=" + frames + " paintMs=" + ms + " fps=" + (frames * 1000.0 / ms));
        screen.stopScreen();
        window.close();
        gui.close();
    }

    private static String ringLine(int tick) {
        return "  TICK " + tick
                + "  ████▓▓▓▒▒▒░░  price tape moves here, colors pulse, numbers shift  "
                + (tick % 9 == 0 ? "▲" : "▼");
    }
}
