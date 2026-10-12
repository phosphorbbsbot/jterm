package io.jterm.widget;

import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Max-length caps: interactive fields must refuse typed characters past the
 * configured limit (resource-limit defense for networked BBS hosts; also
 * protects layout). Programmatic setValue is NOT clamped — system code may
 * prefill full-length values, but typing past the cap is refused.
 */
public class TextBoxMaxLengthTest {

    private static KeyStroke ch(char c) {
        return KeyStroke.character(c, false, false, false);
    }

    @Test
    void typingPastMaxLengthIsRefused() {
        var box = new TextBox();
        box.setMaxLength(5);
        for (char c : "12345".toCharArray()) box.handleKeyStroke(ch(c));
        assertEquals("12345", box.getValue());
        box.handleKeyStroke(ch('6'));
        assertEquals("12345", box.getValue(), "sixth char must be refused");
        // edits within the cap still work
        box.handleKeyStroke(new KeyStroke(KeyType.BACKSPACE));
        box.handleKeyStroke(ch('9'));
        assertEquals("12349", box.getValue());
    }

    @Test
    void insertAtMiddleAllowedUnderCap() {
        var box = new TextBox();
        box.setMaxLength(4);
        for (char c : "abc".toCharArray()) box.handleKeyStroke(ch(c));
        box.handleKeyStroke(new KeyStroke(KeyType.ARROW_LEFT));
        box.handleKeyStroke(new KeyStroke(KeyType.ARROW_LEFT));
        box.handleKeyStroke(ch('X'));       // middle insert: 3 < 4 → allowed
        assertEquals("aXbc", box.getValue());
        box.handleKeyStroke(new KeyStroke(KeyType.END));
        box.handleKeyStroke(ch('z'));       // at cap now → refused
        assertEquals("aXbc", box.getValue());
    }

    @Test
    void noLimitByDefaultAndZeroMeansUnlimited() {
        var box = new TextBox();
        for (int i = 0; i < 400; i++) box.handleKeyStroke(ch('a'));
        assertEquals(400, box.getValue().length(), "default must be unlimited");
    }

    @Test
    void prefilledValueAllowsEditsButNotGrowth() {
        var box = new TextBox();
        box.setMaxLength(5);
        box.setValue("beyond-cap-prefill"); // trusted prefill stays intact
        assertEquals("beyond-cap-prefill", box.getValue());
        box.handleKeyStroke(ch('z'));           // at/past cap → refused
        assertEquals("beyond-cap-prefill", box.getValue());
        // edits within existing text still OK: move to end, backspace
        for (int i = 0; i < 19; i++) box.handleKeyStroke(new KeyStroke(KeyType.ARROW_RIGHT));
        box.handleKeyStroke(new KeyStroke(KeyType.BACKSPACE));
        assertEquals("beyond-cap-prefil", box.getValue());
    }

    @Test
    void pasteSizedInsertRefusedAtCap() {
        // a multi-char ghost/paste insert at once
        var box = new TextBox();
        box.setMaxLength(5);
        box.setValue("abc"); // cursor -> start; move to end
        box.handleKeyStroke(KeyStroke.character('c', true, false, false)); // Ctrl+E -> end (harmless pre-typing shortcut)
        box.handleKeyStroke(new KeyStroke(KeyType.END));
        box.handleKeyStroke(ch('d'));
        box.handleKeyStroke(ch('e'));
        assertEquals("abcde", box.getValue());
        box.handleKeyStroke(ch('f'));
        assertEquals(5, box.getValue().length());
    }
}
