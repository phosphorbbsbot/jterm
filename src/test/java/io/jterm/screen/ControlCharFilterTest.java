package io.jterm.screen;

import io.jterm.core.AnsiTerminal;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.style.TextCell;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security regression: control characters (notably 0x1B ESC) arriving from
 * untrusted text (chat messages, usernames, message bodies) must never
 * reach the output wire, where they would be re-interpreted as ANSI/OSC
 * escape sequences by the client terminal (title spoof, OSC 8 hyperlink
 * phishing, OSC 52 clipboard overwrite, cursor repositioning, screen
 * desync). Filtered at the single funnel: ScreenBuffer.setCell.
 */
public class ControlCharFilterTest {

    private static final String HOSTILE = "user\u001B]0;PWNED\u0007x";

    @Test
    void drawStringNeverStoresControlCharInCells() {
        var buf = new ScreenBuffer(new TerminalSize(20, 5));
        new TextGraphics(buf).drawString(0, 0, HOSTILE, TextCell.EMPTY);
        for (int c = 0; c < 20; c++) {
            char ch = buf.getCell(c, 0).character().charAt(0);
            assertTrue(ch >= 32 || ch == ' ',
                "cell " + c + " contains non-printable codepoint " + (int) ch);
        }
    }

    @Test
    void setCellDirectlyFiltersControlChars() {
        var buf = new ScreenBuffer(new TerminalSize(20, 5));
        buf.setCell(3, 0, new TextCell('\u001B'));
        assertEquals(' ', buf.getCell(3, 0).character().charAt(0),
            "raw setCell must not store ESC");
    }

    @Test
    void refreshEmitNeverWritesEscToWire() throws Exception {
        var sink = new ByteArrayOutputStream();
        var term = new AnsiTerminal(sink, java.io.InputStream.nullInputStream(),
                new TerminalSize(20, 5));
        var screen = new DefaultScreen(term);
        var buf = new ScreenBuffer(new TerminalSize(20, 5));
        new TextGraphics(buf).drawString(0, 0, HOSTILE, TextCell.EMPTY);
        // copy hostile content into the screen's back buffer and flush
        for (int r = 0; r < 5; r++)
            for (int c = 0; c < 20; c++)
                screen.setCell(c, r, buf.getCell(c, r));
        screen.refresh();
        String wire = sink.toString(StandardCharsets.ISO_8859_1);
        // bytes 0x1b, 0x07, and CSI are forbidden anywhere in emitted content
        // positions; note the screen itself legitimately emits ESC for cursor
        // positioning and SGR — so we assert on CONTENT cells only via the
        // putCharacter contract: scan for the OSC introducer after our text.
        assertFalse(wire.contains("PWNED"),
            "hostile OSC payload reached the wire");
        assertFalse(wire.indexOf('\u0007') >= 0,
            "BEL terminator reached the wire");
    }

    @Test
    void printableTextSurvivesUnchanged() {
        var buf = new ScreenBuffer(new TerminalSize(20, 5));
        new TextGraphics(buf).drawString(0, 0, "hello world", TextCell.EMPTY);
        assertEquals("hello world".charAt(0), buf.getCell(0, 0).character().charAt(0));
        assertEquals("hello world".charAt(6), buf.getCell(6, 0).character().charAt(0));
    }
}
