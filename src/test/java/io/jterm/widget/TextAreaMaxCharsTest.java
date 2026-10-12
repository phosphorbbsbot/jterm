package io.jterm.widget;

import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TextArea total-character cap: paste/typing past the configured limit is
 * refused (resource defense for networked hosts). setText prefills are
 * trusted and kept intact.
 */
public class TextAreaMaxCharsTest {

    private static KeyStroke ch(char c) {
        return KeyStroke.character(c, false, false, false);
    }

    @Test
    void typingPastMaxCharsRefused() {
        var ta = new TextArea();
        ta.setMaxChars(5);
        for (char c : "abcdef".toCharArray()) ta.handleKeyStroke(ch(c));
        assertEquals("abcde", ta.getText());
    }

    @Test
    void newlineCountsTowardCap() {
        var ta = new TextArea();
        ta.setMaxChars(6);
        for (char c : "abc".toCharArray()) ta.handleKeyStroke(ch(c));
        ta.handleKeyStroke(new KeyStroke(KeyType.ENTER));
        ta.handleKeyStroke(ch('d'));
        ta.handleKeyStroke(ch('e'));
        // "abc" (3) + newline (1) + "de" (2) = 6 at cap; next refused
        ta.handleKeyStroke(ch('f'));
        assertEquals("abc\nde", ta.getText());
    }

    @Test
    void prefillIntactEditsStillWork() {
        var ta = new TextArea("prefill-beyond-limit");
        ta.setMaxChars(10);
        assertEquals("prefill-beyond-limit", ta.getText());
        // move cursor to end (arrow keys), backspace works (reduces),
        // further typing refused while at/past cap
        for (int i = 0; i < 20; i++) ta.handleKeyStroke(new KeyStroke(KeyType.ARROW_RIGHT));
        ta.handleKeyStroke(new KeyStroke(KeyType.BACKSPACE));
        assertEquals("prefill-beyond-limi", ta.getText());
        ta.handleKeyStroke(ch('x')); // still past cap → refused
        assertEquals("prefill-beyond-limi", ta.getText());
    }

    @Test
    void unlimitedByDefault() {
        var ta = new TextArea();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) { ta.handleKeyStroke(ch('a')); sb.append('a'); }
        assertEquals(sb.length(), ta.getText().length());
    }
}
