package io.jterm.screen;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.style.TextCell;
import io.jterm.style.AnsiColor;
import io.jterm.style.SGR;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

class ScreenBufferTest {
    @Test
    void newBufferIsFilledWithEmpty() {
        var buf = new ScreenBuffer(new TerminalSize(10, 5));
        assertEquals(TextCell.EMPTY, buf.getCell(0, 0));
        assertEquals(TextCell.EMPTY, buf.getCell(9, 4));
    }

    @Test
    void setAndGetCell() {
        var buf = new ScreenBuffer(new TerminalSize(10, 5));
        var cell = new TextCell('X', AnsiColor.RED, AnsiColor.DEFAULT, SGR.BOLD);
        buf.setCell(3, 2, cell);
        assertEquals(cell, buf.getCell(3, 2));
    }

    @Test
    void outOfBoundsReturnsEmpty() {
        var buf = new ScreenBuffer(new TerminalSize(10, 5));
        assertEquals(TextCell.EMPTY, buf.getCell(100, 100));
    }

    @Test
    void outOfBoundsSetIsNoop() {
        var buf = new ScreenBuffer(new TerminalSize(10, 5));
        buf.setCell(100, 100, new TextCell('X'));
        assertEquals(TextCell.EMPTY, buf.getCell(0, 0));
    }

    @Test
    void fillRectSetsRegion() {
        var buf = new ScreenBuffer(new TerminalSize(10, 5));
        var cell = new TextCell('#');
        buf.fillRect(2, 1, 3, 2, cell);
        assertEquals('#', buf.getCell(2, 1).character().charAt(0));
        assertEquals('#', buf.getCell(4, 2).character().charAt(0));
        assertEquals(TextCell.EMPTY, buf.getCell(5, 2));
    }

    @Test
    void diffFromFindsChanges() {
        var a = new ScreenBuffer(new TerminalSize(5, 3));
        var b = new ScreenBuffer(new TerminalSize(5, 3));
        b.setCell(1, 1, new TextCell('X'));
        b.setCell(3, 2, new TextCell('Y'));
        var diffs = a.diffFrom(b);
        assertEquals(2, diffs.size());
    }

    @Test
    void diffFromNoChanges() {
        var a = new ScreenBuffer(new TerminalSize(5, 3));
        var b = new ScreenBuffer(new TerminalSize(5, 3));
        assertTrue(a.diffFrom(b).isEmpty());
    }

    @Test
    void copyFromCopiesCells() {
        var src = new ScreenBuffer(new TerminalSize(5, 3));
        src.setCell(0, 0, new TextCell('A'));
        var dst = new ScreenBuffer(new TerminalSize(5, 3));
        dst.copyFrom(src);
        assertEquals('A', dst.getCell(0, 0).character().charAt(0));
    }

    @Test
    @DisplayName("diffFrom: identical content yields ZERO deltas (value equality, wire-quiet frames)")
    void identicalBuffersDiffToZero() {
        var size = new TerminalSize(80, 24);
        var a = new ScreenBuffer(size);
        var b = new ScreenBuffer(size);
        var g = new io.jterm.graphics.TextGraphics(a);
        var cell = new TextCell('X', AnsiColor.BRIGHT_GREEN, AnsiColor.BLACK, SGR.BOLD);
        g.setCell(3, 4, cell);
        var g2 = new io.jterm.graphics.TextGraphics(b);
        g2.setCell(3, 4, cell);
        assertTrue(a.diffFrom(b).isEmpty(), "identical content must diff to zero deltas");
        g2.setCell(5, 4, new TextCell('Y', AnsiColor.BRIGHT_GREEN, AnsiColor.BLACK, SGR.BOLD));
        assertEquals(1, a.diffFrom(b).size(), "a single char change must yield exactly one delta");
        g2.setCell(5, 4, TextCell.EMPTY); // revert to buffer default
        assertEquals(0, a.diffFrom(b).size(), "reverting must return to zero deltas");
        g2.setCell(3, 4, new TextCell('X', AnsiColor.BLUE, AnsiColor.BLACK, SGR.BOLD));
        assertEquals(1, a.diffFrom(b).size(), "a color-only change must count");
    }
}
