package io.jterm.widget;

import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalSize;
import io.jterm.screen.DefaultScreen;
import io.jterm.style.AnsiColor;
import io.jterm.style.TextCell;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GC law (Joe, 2026-11-21): a Label re-drawn with UNCHANGED content reuses
 * its cached paint rows — the SAME cell refs land in the buffer — and a
 * same-text setText() must not tear the cache down (the BBS recomposes its
 * status band every frame with an equal string; equal text = nothing
 * observable changed).
 */
class LabelCacheLawTest {

    private Label labelAt(Label label, int cols) {
        // A bare component has ZERO bounds until layout — give it real ones
        // or drawComponent paints nothing and the laws read vacuously.
        label.setBounds(new io.jterm.core.TerminalPosition(0, 0),
                new TerminalSize(cols, 1));
        label.setHorizontalAlignment(Label.HorizontalAlignment.LEFT); // cell(0,0) = text[0]
        label.setVisible(true);
        return label;
    }

    @Test
    void stableRepaintReusesTheSameCellRefs() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(40, 10)));
        var buf = screen.getBackBuffer();
        var g = new io.jterm.graphics.TextGraphics(buf);
        var label = labelAt(new Label("  AAPL  1,234,567  ",
                new TextCell(' ', AnsiColor.GREEN, AnsiColor.BLACK)), 18);

        label.draw(g);
        var first = buf.getCell(0, 0);
        assertEquals(' ', first.character().charAt(0));

        label.invalidate(); // any repaint driver
        var buf2 = new io.jterm.screen.ScreenBuffer(buf.size());
        label.draw(new io.jterm.graphics.TextGraphics(buf2));
        var second = buf2.getCell(0, 0);

        assertSame(first, second,
                "an unchanged label repaints with the SAME cached cell refs");
    }

    @Test
    void equalTextSetKeepstheCacheAlive() {
        var screen = new DefaultScreen(new MockTerminal(new TerminalSize(40, 10)));
        var buf = screen.getBackBuffer();
        var g = new io.jterm.graphics.TextGraphics(buf);
        var label = labelAt(new Label("counts 12/34/56",
                new TextCell(' ', AnsiColor.WHITE, AnsiColor.BLACK)), 15);

        label.draw(g);
        var before = buf.getCell(0, 0);
        assertEquals('c', before.character().charAt(0));

        // The per-frame band recompose: an equal string must not rebuild rows.
        label.setText("counts 12/34/56");
        var after = new io.jterm.screen.ScreenBuffer(buf.size());
        label.draw(new io.jterm.graphics.TextGraphics(after));

        assertSame(before, after.getCell(0, 0),
                "setText with an EQUAL string preserves the paint cache");
        assertEquals("counts 12/34/56", label.getText());
    }

    @Test
    void changedTextRebuildsAndRepaints() {
        var label = labelAt(new Label("before",
                new TextCell(' ', AnsiColor.WHITE, AnsiColor.BLACK)), 10);
        var buf = new io.jterm.screen.ScreenBuffer(new TerminalSize(10, 1));
        var g = new io.jterm.graphics.TextGraphics(buf);
        label.draw(g);
        var old = buf.getCell(0, 0);
        assertEquals('b', old.character().charAt(0));

        label.setText("after"); // content change: cache invalidated
        var buf2 = new io.jterm.screen.ScreenBuffer(new TerminalSize(10, 1));
        label.draw(new io.jterm.graphics.TextGraphics(buf2));
        var fresh = buf2.getCell(0, 0);

        assertNotSame(old, fresh, "a real change repaints with new cells");
        assertEquals('a', fresh.character().charAt(0));
    }
}
