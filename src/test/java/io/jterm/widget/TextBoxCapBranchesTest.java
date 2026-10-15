package io.jterm.widget;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers remaining {@link TextBox} branches: masked and placeholder drawing,
 * max-length cap enforcement (insertion and Tab-accepted ghost text), and the
 * non-zero return from {@code setMaxLength}.
 */
class TextBoxCapBranchesTest {

    private ScreenBuffer draw(TextBox box, int columns) {
        var buf = new ScreenBuffer(new TerminalSize(columns, 1));
        box.draw(new TextGraphics(buf));
        return buf;
    }

    private void setCursor(TextBox box, int pos) {
        try {
            var field = TextBox.class.getDeclaredField("cursorPosition");
            field.setAccessible(true);
            field.setInt(box, pos);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void setMaxLengthReturnsPreviousAndRejectsNegative() {
        var box = new TextBox(20);
        assertEquals(0, box.setMaxLength(5));
        assertEquals(5, box.getMaxLength());
        assertEquals(5, box.setMaxLength(0));
        assertEquals(0, box.getMaxLength());
        assertThrows(IllegalArgumentException.class, () -> box.setMaxLength(-1));
    }

    @Test
    void maskedInputDrawsStarsInsteadOfCharacters() {
        var box = new TextBox(20);
        box.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 1));
        box.setValue("secret");
        box.setMasked(true);
        assertTrue(box.isMasked());
        var buf = draw(box, 20);
        for (int i = 0; i < 6; i++) {
            assertTrue(buf.getCell(i, 0).is('*'), "masked cell " + i + " should be '*'");
        }
        assertEquals(' ', buf.getCell(6, 0).character().charAt(0), "beyond the value length: blank");
    }

    @Test
    void placeholderDrawsWhenEmpty() {
        var box = new TextBox(20);
        box.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 1));
        box.setPlaceholder("type here");
        assertEquals("type here", box.getPlaceholder());
        box.setFocused(false);
        var buf = draw(box, 20);
        assertEquals('t', buf.getCell(0, 0).character().charAt(0));
        assertEquals('y', buf.getCell(1, 0).character().charAt(0));
        assertEquals('e', buf.getCell(8, 0).character().charAt(0));
    }

    @Test
    void maxLengthConsumesButRefusesCharacterAtCap() {
        var box = new TextBox(20);
        box.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 1));
        assertEquals(0, box.setMaxLength(4));
        for (char c : "abcd".toCharArray()) {
            assertTrue(box.handleKeyStroke(io.jterm.core.input.KeyStroke.character(c, false, false, false)));
        }
        assertEquals("abcd", box.getValue());
        // At the cap the keystroke is consumed but refused: value unchanged.
        assertTrue(box.handleKeyStroke(io.jterm.core.input.KeyStroke.character('e', false, false, false)));
        assertEquals("abcd", box.getValue(), "character beyond the cap must not be inserted");
    }

    @Test
    void tabGhostAcceptanceRejectedWhenItWouldExceedCap() {
        var box = new TextBox(30);
        box.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(30, 1));
        assertEquals(0, box.setMaxLength(5));
        box.setValue("ab");
        setCursor(box, 2);
        box.setCompletionProvider((text, pos) -> "cdefgh");
        box.setFocused(true);
        box.draw(new TextGraphics(new ScreenBuffer(new TerminalSize(30, 1))));
        assertEquals("cdefgh", box.getCurrentGhostText());
        // Acceptance would push the value to 8 > 5: Tab is not consumed and
        // the completion is not inserted at all.
        assertFalse(box.handleKeyStroke(new io.jterm.core.input.KeyStroke(io.jterm.core.input.KeyType.TAB)));
        assertEquals("ab", box.getValue(), "completion exceeding the cap must be rejected, not truncated");
    }

    @Test
    void placeholderDoesNotDrawWhenFocused() {
        var box = new TextBox(20);
        box.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 1));
        box.setPlaceholder("type here");
        box.setFocused(true);
        var buf = draw(box, 20);
        assertEquals(' ', buf.getCell(0, 0).character().charAt(0), "focused field shows no placeholder");
    }
}