package io.jterm.widget;

import io.jterm.completion.CompletionProvider;
import io.jterm.completion.GhostTextSupport;
import io.jterm.completion.SpellcheckDictionary;
import io.jterm.completion.SpellcheckResolver;
import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.graphics.TextGraphics;
import io.jterm.style.AnsiColor;
import io.jterm.style.Color;
import io.jterm.style.TextCell;
import io.jterm.style.TextStyleResolver;
import io.jterm.style.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-line text editing widget with cursor movement, scrolling, and
 * emacs-style key bindings. Supports optional inline ghost-text completions
 * via a {@link CompletionProvider}, shared with {@link TextBox} through
 * {@link GhostTextSupport}.
 *
 * <p>Supports:
 * <ul>
 *   <li>Multi-line text with {@code \n}-separated lines</li>
 *   <li>Horizontal and vertical scrolling</li>
 *   <li>Arrow keys, Home/End, Page Up/Down</li>
 *   <li>Emacs bindings: Ctrl+A/E/K/F/B/P/N/D</li>
 *   <li>Backspace (with line join), Delete (with line join), Enter (new line)</li>
 *   <li>Ghost text completions — Space or Tab accepts the suggestion</li>
 * </ul>
 */
public class TextArea extends AbstractComponent {

    private final List<String> lines = new ArrayList<>();

    /**
     * Maximum total characters accepted via user edits (typing, newline,
     * completion/paste inserts). 0 = unlimited. Trusted prefills via
     * {@link #setText(String)} are kept intact even when longer, but user
     * edits cannot grow the content while at/past the cap.
     */
    private int maxChars = 0;

    /**
     * Caps the total characters a user can put into this area.
     *
     * @param maxChars maximum characters including newlines, 0 for unlimited
     * @return configured max prior to this call, for chaining
     */
    public int setMaxChars(int maxChars) {
        if (maxChars < 0) throw new IllegalArgumentException("maxChars must be >= 0");
        int previous = this.maxChars;
        this.maxChars = maxChars;
        return previous;
    }

    /**
     * Returns the configured total-character cap.
     *
     * @return total characters accepted via user edits, 0 = unlimited
     */
    public int getMaxChars() { return maxChars; }

    private int totalChars() {
        int n = 0;
        for (int i = 0; i < lines.size(); i++) {
            n += lines.get(i).length() + 1;  // + newline between lines
        }
        return Math.max(0, n - (lines.isEmpty() ? 0 : 1));
    }

    private boolean canAcceptInsert(int n) {
        return maxChars <= 0 || totalChars() + n <= maxChars;
    }
    private volatile int cursorRow = 0;
    private volatile int cursorCol = 0;
    private volatile int viewportRow = 0;   // vertical scroll offset (which text row is at top of viewport)
    private volatile int viewportCol = 0;   // horizontal scroll offset (which column is at left of viewport)
    private volatile int preferredColumns = 20;
    private volatile int preferredRows = 5;
    private final GhostTextSupport ghostTextSupport = new GhostTextSupport();
    private volatile TextStyleResolver styleResolver = null;

    // ── Construction ───────────────────────────────────────────

    /** Creates an empty TextArea with a single blank line. */
    public TextArea() {
        lines.add(""); // Start with one empty line
    }

    /**
     * Creates a TextArea initialized with the given text.
     *
     * @param text the initial text; lines are split on {@code \n}
     */
    public TextArea(String text) {
        setText(text);
    }

    /**
     * Creates a TextArea with the given text and preferred size.
     *
     * @param text    the initial text
     * @param columns the preferred column width
     * @param rows    the preferred row height
     */
    public TextArea(String text, int columns, int rows) {
        this.preferredColumns = columns;
        this.preferredRows = rows;
        setText(text);
    }

    // ── Public API ─────────────────────────────────────────────

    /**
     * Returns the full text content, with lines joined by {@code \n}.
     *
     * @return the full text content
     */
    public String getText() {
        if (lines.isEmpty()) return "";
        return String.join("\n", lines);
    }

    /**
     * Replaces the entire text content and resets cursor/viewport.
     *
     * @param text the new text; lines are split on {@code \n}
     */
    public void setText(String text) {
        lines.clear();
        if (text.isEmpty()) {
            lines.add("");
        } else {
            // Split preserving trailing newlines
            int start = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    lines.add(text.substring(start, i));
                    start = i + 1;
                }
            }
            lines.add(text.substring(start));
        }
        cursorRow = 0;
        cursorCol = 0;
        viewportRow = 0;
        viewportCol = 0;
        ghostTextSupport.clear();
        invalidate();
    }

    /**
     * Returns the number of lines in the text.
     *
     * @return the line count
     */
    public int getLineCount() {
        return lines.size();
    }

    /**
     * Returns the line at the given index.
     *
     * @param index the line index
     * @return the line content
     */
    public String getLine(int index) {
        return lines.get(index);
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
     * Pass {@code null} to disable completions. The provider is queried with
     * the current line's text and the cursor's column position after each
     * keystroke.
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
     * the cursor position on the current line if the user accepts the completion.
     *
     * @return the current ghost text suffix, or {@code null}
     */
    public String getCurrentGhostText() {
        return ghostTextSupport.getGhostText();
    }

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
     * {@code drawString} for each visible line. Cursor and ghost text
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

    // ── Layout ────────────────────────────────────────────────

    /**
     * Returns the preferred size configured at construction.
     *
     * @return the preferred terminal size
     */
    @Override
    protected TerminalSize calculatePreferredSize() {
        return new TerminalSize(preferredColumns, preferredRows);
    }

    // ── Rendering ──────────────────────────────────────────────

    /**
     * Renders the visible lines, ghost text suggestion, and highlights the
     * cursor position.
     *
     * @param graphics the text-graphics target
     */
    @Override
    protected void drawComponent(TextGraphics graphics) {
        adjustViewport(); // ensure viewport is correct before drawing
        var size = getSize();
        var theme = ThemeManager.active();
        Color bg = theme.background();
        var blank = new TextCell(' ', theme.foreground(), bg);

        // Fill background
        graphics.fillRectangle(0, 0, size.columns(), size.rows(), blank);

        // Draw visible lines
        int maxRow = Math.min(size.rows(), lines.size() - viewportRow);
        for (int r = 0; r < maxRow; r++) {
            String line = lines.get(viewportRow + r);
            int visibleStart = viewportCol;
            int visibleEnd = Math.min(line.length(), viewportCol + size.columns());
            if (visibleStart < visibleEnd) {
                String visible = line.substring(visibleStart, visibleEnd);
                if (styleResolver != null) {
                    if (styleResolver instanceof SpellcheckResolver sr) {
                        sr.setText(visible);
                    }
                    graphics.drawStyledString(0, r, visible, blank, styleResolver);
                } else {
                    graphics.drawString(0, r, visible, blank);
                }
            }
        }

        // Query completion provider and render ghost text when focused.
        // Ghost text starts AFTER the cursor cell so the cursor highlight
        // remains visible.
        int cursorScreenRow = cursorRow - viewportRow;
        int cursorScreenCol = cursorCol - viewportCol;
        if (isFocused() && ghostTextSupport.getProvider() != null) {
            String currentLine = cursorRow < lines.size() ? lines.get(cursorRow) : "";
            ghostTextSupport.refresh(currentLine, cursorCol);
            if (ghostTextSupport.hasGhostText()) {
                int ghostStartCol = cursorScreenCol + 1;
                if (cursorScreenRow >= 0 && cursorScreenRow < size.rows()
                        && ghostStartCol >= 0 && ghostStartCol < size.columns()) {
                    ghostTextSupport.drawGhostTextMultiLine(
                            graphics, ghostStartCol, cursorScreenRow,
                            size.columns(), size.rows(), bg);
                }
            }
        } else if (!isFocused()) {
            ghostTextSupport.clear();
        }

        // Draw cursor (highlight: swapped fg/bg) AFTER ghost text so the
        // cursor cell shows the character with inverted colors.
        if (isFocused()) {
            if (cursorScreenRow >= 0 && cursorScreenRow < size.rows()
                    && cursorScreenCol >= 0 && cursorScreenCol < size.columns()) {
                String line = cursorRow < lines.size() ? lines.get(cursorRow) : "";
                char c = cursorCol < line.length() ? line.charAt(cursorCol) : ' ';
                graphics.setCell(cursorScreenCol, cursorScreenRow,
                        new TextCell(c, theme.selectionFg(), theme.selectionBg()));
            }
        }
    }

    // ── Key handling ──────────────────────────────────────────

    /**
     * Handles Emacs-style and arrow key bindings for cursor movement and editing.
     * Space and Tab accept ghost text completions when present.
     *
     * @param keyStroke the keystroke to handle
     * @return {@code true} if the keystroke was consumed
     */
    @Override
    public boolean handleKeyStroke(KeyStroke keyStroke) {
        // Emacs-style key bindings (Ctrl+letter)
        if (keyStroke.type() == KeyType.CHARACTER && keyStroke.ctrl()) {
            ghostTextSupport.clear();
            switch (keyStroke.character()) {
                case 'A', 'a' -> { cursorCol = 0; adjustViewport(); return true; }
                case 'E', 'e' -> { cursorCol = currentLineLength(); adjustViewport(); return true; }
                case 'K', 'k' -> { killToEndOfLine(); invalidate(); adjustViewport(); return true; }
                case 'F', 'f' -> { moveRight(); return true; }
                case 'B', 'b' -> { moveLeft(); return true; }
                case 'P', 'p' -> { moveUp(); return true; }
                case 'N', 'n' -> { moveDown(); return true; }
                case 'D', 'd' -> { deleteForward(); invalidate(); adjustViewport(); return true; }
                case 'V', 'v' -> { pageDown(); return true; }
                default -> { return false; } // Ignore other Ctrl combos
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
                insertTextAtCursor(accepted);
                invalidate();
                return true;
            }
            return false; // No ghost text — Tab not consumed by TextArea
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
                insertChar(keyStroke.character());
                invalidate();
                return true;
            }
            case ENTER -> {
                splitLine();
                invalidate();
                return true;
            }
            case BACKSPACE -> {
                backspace();
                invalidate();
                return true;
            }
            case DELETE -> {
                deleteForward();
                invalidate();
                return true;
            }
            case ARROW_LEFT -> { moveLeft(); return true; }
            case ARROW_RIGHT -> { moveRight(); return true; }
            case ARROW_UP -> { moveUp(); return true; }
            case ARROW_DOWN -> { moveDown(); return true; }
            case HOME -> { cursorCol = 0; adjustViewport(); return true; }
            case END -> { cursorCol = currentLineLength(); adjustViewport(); return true; }
            case PAGE_UP -> { pageUp(); return true; }
            case PAGE_DOWN -> { pageDown(); return true; }
            default -> { return false; }
        }
    }

    // ── Text editing operations ───────────────────────────────

    private void insertChar(char c) {
        if (!canAcceptInsert(1)) return;
        String line = lines.get(cursorRow);
        String newLine = line.substring(0, cursorCol) + c + line.substring(cursorCol);
        lines.set(cursorRow, newLine);
        cursorCol++;
        adjustViewport();
    }

    /**
     * Inserts a string at the cursor position on the current line and advances
     * the cursor by the length of the string. Used for accepting ghost text.
     *
     * @param text the text to insert
     */
    private void insertTextAtCursor(String text) {
        if (!canAcceptInsert(text.length())) return;
        String line = lines.get(cursorRow);
        String newLine = line.substring(0, cursorCol) + text + line.substring(cursorCol);
        lines.set(cursorRow, newLine);
        cursorCol += text.length();
        adjustViewport();
    }

    private void splitLine() {
        if (!canAcceptInsert(1)) return;
        String line = lines.get(cursorRow);
        String before = line.substring(0, cursorCol);
        String after = line.substring(cursorCol);
        lines.set(cursorRow, before);
        lines.add(cursorRow + 1, after);
        cursorRow++;
        cursorCol = 0;
        adjustViewport();
    }

    private void backspace() {
        if (cursorCol == 0) {
            if (cursorRow == 0) return; // can't backspace at very start
            // Join with previous line
            String prev = lines.get(cursorRow - 1);
            String curr = lines.get(cursorRow);
            cursorCol = prev.length();
            lines.set(cursorRow - 1, prev + curr);
            lines.remove(cursorRow);
            cursorRow--;
        } else {
            String line = lines.get(cursorRow);
            String newLine = line.substring(0, cursorCol - 1) + line.substring(cursorCol);
            lines.set(cursorRow, newLine);
            cursorCol--;
        }
        adjustViewport();
    }

    private void deleteForward() {
        if (cursorCol >= currentLineLength()) {
            if (cursorRow >= lines.size() - 1) return; // last line, end — nothing to delete
            // Join with next line
            String curr = lines.get(cursorRow);
            String next = lines.get(cursorRow + 1);
            lines.set(cursorRow, curr + next);
            lines.remove(cursorRow + 1);
        } else {
            String line = lines.get(cursorRow);
            String newLine = line.substring(0, cursorCol) + line.substring(cursorCol + 1);
            lines.set(cursorRow, newLine);
        }
        adjustViewport();
    }

    private void killToEndOfLine() {
        String line = lines.get(cursorRow);
        if (cursorCol >= line.length()) {
            // At end of line — join with next line if exists
            if (cursorRow < lines.size() - 1) {
                String next = lines.get(cursorRow + 1);
                lines.set(cursorRow, line + next);
                lines.remove(cursorRow + 1);
            }
        } else {
            // Delete from cursor to end of line
            lines.set(cursorRow, line.substring(0, cursorCol));
        }
    }

    // ── Cursor movement ────────────────────────────────────────

    private void moveLeft() {
        if (cursorCol > 0) {
            cursorCol--;
        } else if (cursorRow > 0) {
            cursorRow--;
            cursorCol = currentLineLength();
        }
        adjustViewport();
    }

    private void moveRight() {
        if (cursorCol < currentLineLength()) {
            cursorCol++;
        } else if (cursorRow < lines.size() - 1) {
            cursorRow++;
            cursorCol = 0;
        }
        adjustViewport();
    }

    private void moveUp() {
        if (cursorRow > 0) {
            cursorRow--;
            clampColumn();
        }
        adjustViewport();
    }

    private void moveDown() {
        if (cursorRow < lines.size() - 1) {
            cursorRow++;
            clampColumn();
        }
        adjustViewport();
    }

    private void pageUp() {
        int viewportHeight = Math.max(1, getSize().rows() > 0 ? getSize().rows() : preferredRows);
        cursorRow = Math.max(0, cursorRow - viewportHeight);
        clampColumn();
        adjustViewport();
    }

    private void pageDown() {
        int viewportHeight = Math.max(1, getSize().rows() > 0 ? getSize().rows() : preferredRows);
        cursorRow = Math.min(lines.size() - 1, cursorRow + viewportHeight);
        clampColumn();
        adjustViewport();
    }

    /** Clamp cursor column to the end of the current line. */
    private void clampColumn() {
        int len = currentLineLength();
        if (cursorCol > len) cursorCol = len;
    }

    private int currentLineLength() {
        if (cursorRow >= lines.size()) return 0;
        return lines.get(cursorRow).length();
    }

    // ── Viewport adjustment ───────────────────────────────────

    private void adjustViewport() {
        var size = getSize();
        int viewportHeight = Math.max(1, size.rows() > 0 ? size.rows() : preferredRows);
        int viewportWidth = Math.max(1, size.columns() > 0 ? size.columns() : preferredColumns);

        // Vertical: keep cursor visible
        if (cursorRow < viewportRow) {
            viewportRow = cursorRow;
        } else if (cursorRow >= viewportRow + viewportHeight) {
            viewportRow = cursorRow - viewportHeight + 1;
        }

        // Horizontal: keep cursor visible
        if (cursorCol < viewportCol) {
            viewportCol = cursorCol;
        } else if (cursorCol >= viewportCol + viewportWidth) {
            viewportCol = cursorCol - viewportWidth + 1;
        }

        if (viewportRow < 0) viewportRow = 0;
        if (viewportCol < 0) viewportCol = 0;
    }
}