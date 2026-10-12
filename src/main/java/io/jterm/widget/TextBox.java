package io.jterm.widget;

import io.jterm.completion.CompletionProvider;
import io.jterm.completion.GhostTextSupport;
import io.jterm.completion.SpellcheckDictionary;
import io.jterm.completion.SpellcheckResolver;
import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.graphics.TextGraphics;
import io.jterm.style.AnsiColor;
import io.jterm.style.Color;
import io.jterm.style.SGR;
import io.jterm.style.TextCell;
import io.jterm.style.TextStyleResolver;
import io.jterm.style.ThemeManager;

/** Single-line text input with optional ghost text (inline completion) support. */
public class TextBox extends AbstractComponent {
    private volatile String value = "";
    /**
     * Maximum characters accepted via user input (typing or completion
     * accept). 0 = unlimited. Trusted programmatic prefills via
     * {@link #setValue(String)} are kept intact even when longer — the cap
     * only stops FURTHER growth by the user, protecting both resource
     * limits on networked hosts and prefill-then-edit workflows.
     */
    private int maxLength = 0;

    /**
     * Caps the characters a user can add to this box. When the current
     * value is at or past the limit, typed characters and completion
     * accepts are refused. Existing longer values (trusted prefills) may
     * still be edited, just not grown.
     *
     * @param maxLength maximum input characters, 0 for unlimited
     * @return configured max length prior to this call, for chaining
     */
    public int setMaxLength(int maxLength) {
        if (maxLength < 0) throw new IllegalArgumentException("maxLength must be >= 0");
        int previous = this.maxLength;
        this.maxLength = maxLength;
        return previous;
    }

    /**
     * Returns the configured max length.
     *
     * @return maximum input characters accepted via user input, 0 = unlimited
     */
    public int getMaxLength() { return maxLength; }

    /** True when a user insert of {@code n} chars is allowed right now. */
    private boolean canAcceptInsert(int n) {
        return maxLength <= 0 || value.length() + n <= maxLength;
    }
    private volatile int cursorPosition = 0;
    private volatile int viewportOffset = 0;
    private volatile int preferredColumns = 20;
    private volatile boolean masked = false;
    private volatile boolean forceUppercase = false;
    private Color backgroundColorOverride = null;
    private String placeholder = null;
    private volatile TextStyleResolver styleResolver = null;
    private final GhostTextSupport ghostTextSupport = new GhostTextSupport();

    /** Creates an empty TextBox with default width. */
    public TextBox() {}
    /**
     * Creates an empty TextBox with the given preferred column width.
     *
     * @param columns the preferred column width
     */
    public TextBox(int columns) { this.preferredColumns = columns; }

    /**
     * Returns the current text value.
     *
     * @return the current text value
     */
    public String getValue() { return value; }
    /**
     * Sets the text value (uppercase-forced if enabled) and clamps cursor/viewport.
     *
     * @param value the new value
     */
    public void setValue(String value) {
        this.value = forceUppercase ? value.toUpperCase() : value;
        cursorPosition = Math.min(cursorPosition, value.length());
        viewportOffset = Math.min(viewportOffset, value.length());
        ghostTextSupport.clear();
        invalidate();
    }

    /**
     * Returns whether characters are drawn as {@code '*'} instead of real text.
     *
     * @return true if password masking is enabled
     */
    public boolean isMasked() { return masked; }

    /**
     * Enables or disables password masking. Invalidates the component.
     *
     * @param masked true to draw {@code '*'} instead of real text
     */
    public void setMasked(boolean masked) {
        this.masked = masked;
        invalidate();
    }

    /**
     * Returns whether typed characters are forced to uppercase.
     *
     * @return true if input is forced to uppercase
     */
    public boolean isForceUppercase() { return forceUppercase; }

    /**
     * When enabled, all typed characters and setValue input are forced to uppercase.
     *
     * @param force true to force uppercase input
     */
    public void setForceUppercase(boolean force) {
        this.forceUppercase = force;
        if (force) value = value.toUpperCase();
        invalidate();
    }

    /**
     * Returns the background color override.
     *
     * @return the background color override, or null if none set (uses theme background)
     */
    public Color getBackgroundColorOverride() { return backgroundColorOverride; }

    /**
     * Sets a background color override for this text box.
     *
     * @param color the background color, or null to revert to theme background
     */
    public void setBackgroundColorOverride(Color color) {
        this.backgroundColorOverride = color;
        invalidate();
    }

    /**
     * Sets placeholder text shown in dim color when the field is empty.
     *
     * @param text the placeholder text
     */
    public void setPlaceholder(String text) {
        this.placeholder = text;
        invalidate();
    }

    /**
     * Returns the placeholder text shown when the field is empty.
     *
     * @return the placeholder text, or null if none set
     */
    public String getPlaceholder() { return placeholder; }

    /**
     * Returns the per-character style resolver, or {@code null} if none is set.
     *
     * @return the style resolver, or {@code null}
     */
    public TextStyleResolver getStyleResolver() {
        return styleResolver;
    }

    /**
     * Sets a per-character style resolver for custom coloring of the text.
     * When non-null, {@code drawComponent} uses
     * {@link io.jterm.graphics.TextGraphics#drawStyledString} instead of
     * {@code drawString} for the visible text. Cursor and ghost text
     * rendering are unaffected.
     *
     * @param resolver the style resolver, or {@code null} to revert to
     *                 default behavior
     */
    public void setStyleResolver(TextStyleResolver resolver) {
        this.styleResolver = resolver;
        invalidate();
    }

    /**
     * Enables spellchecking with the given dictionary. When non-null, a
     * {@link SpellcheckResolver} is created and set as the style resolver.
     * Misspelled words will be highlighted in yellow during rendering.
     * Pass {@code null} to disable spellchecking.
     *
     * @param dictionary the spellcheck dictionary, or {@code null} to disable
     */
    public void setSpellcheckDictionary(SpellcheckDictionary dictionary) {
        var resolver = dictionary != null ? new SpellcheckResolver(dictionary) : null;
        setStyleResolver(resolver);
    }

    /**
     * Returns the current completion provider, or {@code null} if none is set.
     *
     * @return the completion provider, or {@code null}
     */
    public CompletionProvider getCompletionProvider() {
        return ghostTextSupport.getProvider();
    }

    /**
     * Sets the completion provider for ghost text (inline completion) support.
     * Pass {@code null} to disable completions.
     *
     * @param provider the completion provider, or {@code null} to disable
     */
    public void setCompletionProvider(CompletionProvider provider) {
        ghostTextSupport.setProvider(provider);
        invalidate();
    }

    /**
     * Returns the current ghost text suggestion being displayed, or {@code null}
     * if no suggestion is active. This is the suffix that would be appended at
     * the cursor position if the user accepts the completion.
     *
     * @return the current ghost text suffix, or {@code null}
     */
    public String getCurrentGhostText() {
        return ghostTextSupport.getGhostText();
    }

    /**
     * Returns the preferred size (preferred columns × 1 row).
     *
     * @return the preferred terminal size
     */
    @Override
    protected TerminalSize calculatePreferredSize() {
        return new TerminalSize(preferredColumns, 1);
    }

    /**
     * Renders the value (masked if enabled), placeholder when empty, ghost text
     * suggestion, and cursor.
     *
     * @param graphics the text-graphics target
     */
    @Override
    protected void drawComponent(TextGraphics graphics) {
        var size = getSize();
        var theme = ThemeManager.active();
        Color bg = backgroundColorOverride != null ? backgroundColorOverride : theme.background();
        var style = new TextCell(' ', theme.foreground(), bg);
        graphics.fillRectangle(0, 0, size.columns(), 1, style);
        // Defensive: clamp viewportOffset in case it's stale from a prior longer value.
        int offset = Math.min(viewportOffset, value.length());
        var visible = value.substring(offset, Math.min(value.length(), offset + size.columns()));
        var display = masked ? "*".repeat(visible.length()) : visible;
        if (styleResolver != null) {
            if (styleResolver instanceof SpellcheckResolver sr) {
                sr.setText(display);
            }
            graphics.drawStyledString(0, 0, display, style, styleResolver);
        } else {
            graphics.drawString(0, 0, display, style);
        }
        // Show placeholder text in dim color when empty and not focused
        if (value.isEmpty() && placeholder != null && !placeholder.isEmpty()) {
            var phStyle = new TextCell(' ', AnsiColor.BRIGHT_BLACK, bg);
            int phLen = Math.min(placeholder.length(), size.columns());
            graphics.drawString(0, 0, placeholder.substring(0, phLen), phStyle);
        }
        // Query completion provider and render ghost text when focused and not masked.
        // Ghost text starts AFTER the cursor cell so the cursor highlight remains visible.
        if (isFocused() && !masked && ghostTextSupport.getProvider() != null) {
            ghostTextSupport.refresh(value, cursorPosition);
            int cursorCol = cursorPosition - offset;
            int ghostStartCol = cursorCol + 1;
            if (ghostTextSupport.hasGhostText() && ghostStartCol >= 0 && ghostStartCol < size.columns()) {
                ghostTextSupport.drawGhostText(graphics, ghostStartCol, 0, size.columns(), bg);
            }
        } else if (!isFocused()) {
            ghostTextSupport.clear();
        }
        // Only draw cursor when this TextBox has focus
        if (isFocused()) {
            int cursorCol = cursorPosition - viewportOffset;
            if (cursorCol >= 0 && cursorCol < size.columns()) {
                char c = cursorPosition < value.length() ? (masked ? '*' : value.charAt(cursorPosition)) : ' ';
                // Swap fg/bg directly instead of using SGR.REVERSE.
                // Some CP437 clients (MuffinTerm) don't implement ESC[7m (REVERSE),
                // so we invert the colors explicitly. Every terminal supports
                // explicit fg/bg color codes.
                graphics.setCell(cursorCol, 0, new TextCell(c, bg, theme.foreground()));
            }
        }
    }

    /**
     * Handles Emacs-style and arrow key bindings for editing and cursor movement.
     * Space and Tab accept ghost text completions when present.
     *
     * @param keyStroke the keystroke to handle
     * @return {@code true} if the keystroke was consumed
     */
    @Override
    public boolean handleKeyStroke(KeyStroke keyStroke) {
        // Emacs-style key bindings (Ctrl+letter arrives as CHARACTER with ctrl=true)
        if (keyStroke.type() == KeyType.CHARACTER && keyStroke.ctrl()) {
            ghostTextSupport.clear();
            switch (keyStroke.character()) {
                case 'A', 'a' -> { cursorPosition = 0; adjustViewport(); return true; }
                case 'E', 'e' -> { cursorPosition = value.length(); adjustViewport(); return true; }
                case 'K', 'k' -> { value = value.substring(0, cursorPosition); invalidate(); adjustViewport(); return true; }
                case 'F', 'f' -> { cursorPosition = Math.min(value.length(), cursorPosition + 1); adjustViewport(); return true; }
                case 'B', 'b' -> { cursorPosition = Math.max(0, cursorPosition - 1); adjustViewport(); return true; }
                case 'P', 'p' -> { cursorPosition = Math.max(0, cursorPosition - 1); adjustViewport(); return true; }
                case 'N', 'n' -> { cursorPosition = Math.min(value.length(), cursorPosition + 1); adjustViewport(); return true; }
                case 'D', 'd' -> { deleteForwardTextBox(); return true; }
                default -> { return false; }  // Ignore other Ctrl+letter combos
            }
        }
        // Space is always a literal space — it dismisses ghost text but never accepts.
        // Only Tab accepts completions.
        if (keyStroke.type() == KeyType.CHARACTER && keyStroke.character() == ' ') {
            ghostTextSupport.tryAccept(' ');  // dismisses ghost text, returns null
            // Falls through to normal character insertion below
        }
        // Tab accepts ghost text if present (no tab character inserted)
        if (keyStroke.type() == KeyType.TAB) {
            String accepted = ghostTextSupport.tryAcceptTab();
            if (accepted != null) {
                if (!canAcceptInsert(accepted.length())) {
                    return false;  // completion would exceed the cap
                }
                value = value.substring(0, cursorPosition) + accepted + value.substring(cursorPosition);
                cursorPosition += accepted.length();
                invalidate();
                adjustViewport();
                return true;
            }
            return false; // No ghost text — Tab not consumed by TextBox
        }
        // Escape clears ghost text without accepting
        if (keyStroke.type() == KeyType.ESCAPE) {
            if (ghostTextSupport.hasGhostText()) {
                ghostTextSupport.clear();
                return true;
            }
            return false;
        }
        // All other keystrokes: clear ghost text and process normally
        ghostTextSupport.clear();
        switch (keyStroke.type()) {
            case CHARACTER -> {
                if (!canAcceptInsert(1)) {
                    ghostTextSupport.clear();
                    return true;   // consumed but refused: input at cap
                }
                char ch = keyStroke.character();
                if (forceUppercase) ch = Character.toUpperCase(ch);
                value = value.substring(0, cursorPosition) + ch + value.substring(cursorPosition);
                cursorPosition++;
                invalidate();
                adjustViewport();
                return true;
            }
            case BACKSPACE -> {
                if (cursorPosition > 0) {
                    value = value.substring(0, cursorPosition - 1) + value.substring(cursorPosition);
                    cursorPosition--;
                    invalidate();
                }
                adjustViewport();
                return true;
            }
            case DELETE -> { deleteForwardTextBox(); return true; }
            case ARROW_LEFT -> { cursorPosition = Math.max(0, cursorPosition - 1); adjustViewport(); return true; }
            case ARROW_RIGHT -> { cursorPosition = Math.min(value.length(), cursorPosition + 1); adjustViewport(); return true; }
            case HOME -> { cursorPosition = 0; adjustViewport(); return true; }
            case END -> { cursorPosition = value.length(); adjustViewport(); return true; }
            default -> { return false; }
        }
    }

    /** Delete the character at the cursor position (forward delete, emacs Ctrl+D / Delete key). */
    private void deleteForwardTextBox() {
        if (cursorPosition < value.length()) {
            value = value.substring(0, cursorPosition) + value.substring(cursorPosition + 1);
            invalidate();
        }
        adjustViewport();
    }

    private void adjustViewport() {
        var size = getSize().columns();
        if (cursorPosition < viewportOffset) viewportOffset = cursorPosition;
        if (cursorPosition >= viewportOffset + size) viewportOffset = cursorPosition - size + 1;
        if (viewportOffset < 0) viewportOffset = 0;
    }
}