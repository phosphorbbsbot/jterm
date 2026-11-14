package io.jterm.widget;

import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.DefaultScreen;
import io.jterm.screen.ScreenBuffer;
import io.jterm.window.AbstractWindow;
import io.jterm.window.DefaultTextGUI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * CURSOR-ANCHOR LAW (2026-11-13, Joe's report): in text fields/boxes the
 * client's own cursor rendered BESIDE the application caret — frames leave the
 * hardware cursor one cell past the last written cell, so the blinking
 * cursor appeared glued to the neighboring character ("the cursor never sits
 * ON a character; text looks like it slides under it").
 *
 * <p>Laws: (1) after a frame the gui parks the terminal cursor ON the focused
 * widget's caret cell; (2) a focused text widget reports its caret's ABSOLUTE
 * screen cell while drawn at any sub-graphics origin — ON the character, or
 * the empty caret cell at end-of-text; (3) no focused text widget → no anchor
 * (legacy cursor behavior).</p>
 */
class CursorAnchorLawTest {

    /** Focused stub that reports a fixed caret cell — isolates the gui→screen parking. */
    private static final class CursorStub extends Label {
        final int col;
        final int row;

        CursorStub(int col, int row) {
            super("x");
            this.col = col;
            this.row = row;
        }

        @Override
        public TerminalPosition getCursorCell() {
            return new TerminalPosition(col, row);
        }
    }

    private static AbstractWindow stubWindow(Component focused) {
        var w = new AbstractWindow("Law") {
        };
        w.setHints(java.util.List.of(io.jterm.window.WindowHint.FULLSCREEN,
                io.jterm.window.WindowHint.NO_DECORATIONS));
        w.getContents().addComponent(focused);
        w.setFocusedComponent(focused);
        return w;
    }

    private static TextGraphics freshGraphics() {
        return new TextGraphics(new ScreenBuffer(new TerminalSize(80, 24),
                new io.jterm.style.TextCell(' ', null, null)));
    }

    /** 1-based {row, col} of the last ESC[r;cH in the terminal stream. */
    private static int[] terminalCursor(String out) {
        var m = java.util.regex.Pattern.compile("\\x1b\\[(\\d+);(\\d+)H").matcher(out);
        int[] last = null;
        while (m.find()) {
            last = new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        }
        return last;
    }

    private static DefaultScreen startedScreen(MockTerminal terminal) {
        var screen = new DefaultScreen(terminal);
        try {
            screen.startScreen();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("MockTerminal cannot throw", e);
        }
        return screen;
    }

    @Test
    @DisplayName("After a frame the terminal cursor parks ON the focused widget's caret cell")
    void guiParksTheTerminalCursorOnTheAnchor() {
        var terminal = new MockTerminal(new TerminalSize(80, 24));
        var screen = startedScreen(terminal);
        var gui = new DefaultTextGUI(screen);
        gui.addWindow(stubWindow(new CursorStub(6, 7)));
        try {
            gui.updateScreen();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("MockTerminal cannot throw", e);
        }
        int[] cur = terminalCursor(terminal.getOutput());
        assertNotNull(cur, "the frame must position the cursor");
        assertEquals(8, cur[0], "cursor row must be anchor row+1 (1-based)");
        assertEquals(7, cur[1], "cursor column must be anchor col+1 — parked ON the caret cell");
    }

    @Test
    @DisplayName("No focused text widget: no anchor (legacy cursor behavior)")
    void noFocusedTextEmitsNoAnchor() {
        var terminal = new MockTerminal(new TerminalSize(80, 24));
        var screen = startedScreen(terminal);
        var gui = new DefaultTextGUI(screen);
        gui.addWindow(stubWindow(new Label("plain")));
        try {
            gui.updateScreen();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("MockTerminal cannot throw", e);
        }
        assertNull(screen.getCursorAnchor(), "no anchor without a focused text widget");
    }

    @Test
    @DisplayName("TextBox reports its caret cell at the draw-origin offset")
    void textBoxReportsTheAbsoluteCaretCell() {
        var box = new TextBox();
        box.setValue("abc");
        box.setBounds(new TerminalPosition(0, 0), new TerminalSize(10, 1));
        var sub = io.jterm.graphics.TextGraphicsExtensions.subGraphics(
                freshGraphics(), new TerminalPosition(5, 7), new TerminalSize(10, 1));
        box.setFocused(true);
        box.draw(sub);
        // caret=0 (insertion before 'a') → caret cell is box-col 0 → absolute (5,7)
        assertNotNull(box.getCursorCell(), "a focused TextBox must report its caret cell");
        assertEquals(new TerminalPosition(5, 7), box.getCursorCell());
    }

    @Test
    @DisplayName("TextBox caret ON a character: report that character's cell")
    void textBoxReportsTheCaretCharacterCell() {
        var box = new TextBox();
        box.setValue("abc");
        box.setBounds(new TerminalPosition(0, 0), new TerminalSize(10, 1));
        box.handleKeyStroke(new io.jterm.core.input.KeyStroke(io.jterm.core.input.KeyType.ARROW_RIGHT));
        var sub = io.jterm.graphics.TextGraphicsExtensions.subGraphics(
                freshGraphics(), new TerminalPosition(10, 4), new TerminalSize(10, 1));
        box.setFocused(true);
        box.draw(sub);
        // caret=1 → the caret cell holds 'b' at box-col 1 → absolute (11,4)
        assertEquals(new TerminalPosition(11, 4), box.getCursorCell(),
                "the caret cell is the 'b' cell — the cursor is ON a character");
    }

    @Test
    @DisplayName("TextBox at end-of-text: caret cell is one past the last char")
    void textBoxCaretAtEndReportsOnePastLastChar() {
        var box = new TextBox();
        box.setValue("abc");
        box.setBounds(new TerminalPosition(0, 0), new TerminalSize(10, 1));
        var right = new io.jterm.core.input.KeyStroke(io.jterm.core.input.KeyType.ARROW_RIGHT);
        box.handleKeyStroke(right);
        box.handleKeyStroke(right);
        box.handleKeyStroke(right);
        var sub = io.jterm.graphics.TextGraphicsExtensions.subGraphics(
                freshGraphics(), new TerminalPosition(10, 4), new TerminalSize(10, 1));
        box.setFocused(true);
        box.draw(sub);
        assertEquals(new TerminalPosition(13, 4), box.getCursorCell(),
                "caret at end parks on the empty caret cell after 'c'");
    }

    @Test
    @DisplayName("TextArea reports the caret cell on the caret LINE")
    void textAreaReportsTheCaretLineCell() {
        var area = new TextArea("abc\ndef", 60, 10);
        area.setBounds(new TerminalPosition(0, 0), new TerminalSize(60, 10));
        area.handleKeyStroke(new io.jterm.core.input.KeyStroke(io.jterm.core.input.KeyType.ARROW_RIGHT));
        var sub = io.jterm.graphics.TextGraphicsExtensions.subGraphics(
                freshGraphics(), new TerminalPosition(2, 3), new TerminalSize(60, 10));
        area.setFocused(true);
        area.draw(sub);
        // caret (line 0, col 1) = 'b' → absolute (2+1, 3+0)
        assertEquals(new TerminalPosition(3, 3), area.getCursorCell(),
                "the caret cell is line-0 col-1 ('b')");
    }

    @Test
    @DisplayName("Unfocused TextBox reports no anchor")
    void unfocusedTextBoxReportsNoAnchor() {
        var box = new TextBox();
        box.setValue("abc");
        box.setBounds(new TerminalPosition(0, 0), new TerminalSize(10, 1));
        var sub = io.jterm.graphics.TextGraphicsExtensions.subGraphics(
                freshGraphics(), new TerminalPosition(5, 7), new TerminalSize(10, 1));
        box.setFocused(false);
        box.draw(sub);
        assertNull(box.getCursorCell(), "an unfocused box must not report a caret cell");
    }
}
