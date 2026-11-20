package io.jterm.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Laws for {@link Terminal#colorDepth()} / {@link Terminal#setColorDepth(io.jterm.core.ColorDepth)}.
 *
 * <p>Mirror of the DECRPM-2026 sync-flag pattern: the interface defaults are
 * the safe legacy behavior, and the concrete tiers (MockTerminal for tests,
 * SocketTerminal for network sessions) carry the real flag.</p>
 */
class TerminalColorDepthLawTest {

    @Test
    void interfaceDefaults_unknownTerminalDepthIsTruecolor_nullLaw() {
        var terminal = new Terminal() {
            @Override public void enterPrivateMode() { }
            @Override public void exitPrivateMode() { }
            @Override public void clearScreen() { }
            @Override public void setCursorPosition(int column, int row) { }
            @Override public void setCursorVisible(boolean visible) { }
            @Override public void putCharacter(char c) { }
            @Override public void flush() { }
            @Override public void setForegroundColor(io.jterm.style.Color color) { }
            @Override public void setBackgroundColor(io.jterm.style.Color color) { }
            @Override public void enableSGR(io.jterm.style.SGR sgr) { }
            @Override public void disableSGR(io.jterm.style.SGR sgr) { }
            @Override public void resetColorAndSGR() { }
            @Override public TerminalSize getTerminalSize() { return new TerminalSize(80, 24); }
            @Override public java.util.Optional<io.jterm.core.input.KeyStroke> pollInput() { return java.util.Optional.empty(); }
            @Override public io.jterm.core.input.KeyStroke readInput() { return new io.jterm.core.input.KeyStroke(io.jterm.core.input.KeyType.EOF); }
            @Override public void addResizeListener(TerminalResizeListener listener) { }
            @Override public void removeResizeListener(TerminalResizeListener listener) { }
            @Override public void close() { }
        };

        assertEquals(ColorDepth.TRUECOLOR, terminal.colorDepth(),
                "the null law: unknown = truecolor — byte-identical for every existing path");
        assertDoesNotThrow(() -> terminal.setColorDepth(ColorDepth.C256),
                "the interface default setter is a no-op (tier not carrying the flag cannot be flipped)");
        assertEquals(ColorDepth.TRUECOLOR, terminal.colorDepth(),
                "a no-op setter cannot change the depth");
    }

    @Test
    void mockTerminal_carriesTheFlag_defaultTruecolor() {
        var terminal = new MockTerminal(new TerminalSize(80, 24));
        assertEquals(ColorDepth.TRUECOLOR, terminal.colorDepth(),
                "MockTerminal starts at the byte-identical default");
    }

    @Test
    void mockTerminal_setColorDepth_roundtrips() {
        var terminal = new MockTerminal(new TerminalSize(80, 24));
        terminal.setColorDepth(ColorDepth.C256);
        assertEquals(ColorDepth.C256, terminal.colorDepth());
        terminal.setColorDepth(ColorDepth.ANSI16);
        assertEquals(ColorDepth.ANSI16, terminal.colorDepth());
    }

    @Test
    void mockTerminal_setColorDepth_rejectsNull() {
        var terminal = new MockTerminal(new TerminalSize(80, 24));
        assertThrows(IllegalArgumentException.class, () -> terminal.setColorDepth(null));
    }

    @Test
    void socketTerminal_carriesTheFlag_defaultThenRoundtrip() throws Exception {
        var terminal = new SocketTerminal(
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(),
                new TerminalSize(80, 24));
        try {
            assertEquals(ColorDepth.TRUECOLOR, terminal.colorDepth(),
                    "SocketTerminal starts at the byte-identical default");
            terminal.setColorDepth(ColorDepth.C256);
            assertEquals(ColorDepth.C256, terminal.colorDepth());
            terminal.setColorDepth(ColorDepth.TRUECOLOR);
            assertEquals(ColorDepth.TRUECOLOR, terminal.colorDepth());
            terminal.setColorDepth(ColorDepth.ANSI16);
            assertEquals(ColorDepth.ANSI16, terminal.colorDepth());
        } finally {
            terminal.close();
        }
    }

    @Test
    void socketTerminal_setColorDepth_rejectsNull() throws Exception {
        var terminal = new SocketTerminal(
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(),
                new TerminalSize(80, 24));
        try {
            assertThrows(IllegalArgumentException.class, () -> terminal.setColorDepth(null));
        } finally {
            terminal.close();
        }
    }
}
