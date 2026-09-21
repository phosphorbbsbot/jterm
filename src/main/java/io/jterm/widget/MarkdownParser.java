package io.jterm.widget;

import io.jterm.util.TerminalTextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * CommonMark-lite parser producing styled lines for terminal rendering.
 *
 * <p>Supported blocks: ATX headings ({@code #}..{@code ####}), paragraphs
 * (word-wrapped to a display width), bullet lists ({@code -} and {@code *},
 * one nested level), fenced code blocks ({@code ```}), horizontal rules
 * ({@code ---}, {@code ***}), and block quotes ({@code >}). Supported inline
 * styles: {@code **bold**}, {@code *italic*}, {@code `code`}, and
 * {@code [text](url)} links rendered as {@code text (url)}.
 *
 * <p>Anything unrecognized degrades to a plain paragraph line — text is never
 * dropped. Span offsets are measured in <em>terminal columns</em> (CJK/fullwidth
 * characters count as 2), matching how the widget draws with
 * {@link TerminalTextUtils} helpers.
 *
 * <p>This class is independent of {@link MarkdownWidget} so callers (e.g. BBS
 * screens) can reuse parsed output directly.
 */
public final class MarkdownParser {

    private MarkdownParser() {}

    /**
     * Parses markdown text into styled lines wrapped to
     * {@link MarkdownLine#DEFAULT_WIDTH} columns.
     *
     * @param markdown the markdown source, may be {@code null}
     * @return the parsed document (never {@code null}; empty for null/blank input)
     */
    public static MarkdownDocument parse(String markdown) {
        return parse(markdown, MarkdownLine.DEFAULT_WIDTH);
    }

    /**
     * Parses markdown text into styled lines word-wrapped at the given display
     * width.
     *
     * @param markdown the markdown source, may be {@code null}
     * @param width    the wrap width in terminal columns (values &lt; 2 are treated as 2)
     * @return the parsed document (never {@code null})
     */
    public static MarkdownDocument parse(String markdown, int width) {
        List<MarkdownLine> lines = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) {
            return new MarkdownDocument(lines);
        }
        int wrapWidth = Math.max(2, width);
        List<String> raw = List.of(markdown.split("\n", -1));
        int i = 0;
        while (i < raw.size()) {
            String line = raw.get(i);
            String trimmed = line.strip();

            if (trimmed.isEmpty()) {
                i++;
                continue;
            }

            // Fenced code block (``` or ~~~ not in scope; only backticks).
            if (trimmed.startsWith("```")) {
                i = consumeCodeBlock(raw, i, lines);
                continue;
            }

            // Horizontal rule: ---, ***, - - -, *** ! etc.
            if (isHorizontalRule(trimmed)) {
                lines.add(new MarkdownLine("", MarkdownLine.Kind.RULE, 0, 0, List.of()));
                i++;
                continue;
            }

            // ATX heading: 1..4 '#' followed by space (or end of line).
            int headingLevel = atxLevel(trimmed);
            if (headingLevel > 0) {
                String content = trimmed.substring(headingLevel).strip();
                List<MarkdownLine.Span> spans = new ArrayList<>();
                String text = parseInline(content, spans);
                lines.add(new MarkdownLine(text, MarkdownLine.Kind.HEADING, headingLevel, 0, List.copyOf(spans)));
                i++;
                continue;
            }

            // Block quote: bare ">" or "> text" (one level in scope; ">>>" and
            // other non-space forms degrade to paragraphs so text is kept).
            if (trimmed.equals(">") || trimmed.startsWith("> ")) {
                String content = trimmed.equals(">") ? "" : trimmed.substring(2).strip();
                List<MarkdownLine.Span> spans = new ArrayList<>();
                String text = parseInline(content, spans);
                lines.add(new MarkdownLine(text, MarkdownLine.Kind.QUOTE, 0, 0, List.copyOf(spans)));
                i++;
                continue;
            }

            // Bullet list item: "- " or "* " (plus indented variants), possibly nested.
            int indent = leadingSpaces(line);
            String afterIndent = line.substring(indent);
            if (afterIndent.startsWith("- ") || afterIndent.startsWith("* ")) {
                // Collect the item text plus continuation lines until the list
                // context ends. Nested bullets (deeper-indented markers) flush
                // the pending item and start a nested one; each item's depth is
                // derived from its own marker indent (max one nested level).
                int itemIndent = indent;
                int curDepth = Math.min(1, itemIndent / 2);
                StringBuilder item = new StringBuilder(afterIndent.substring(2).strip());
                i++;
                while (i < raw.size()) {
                    String next = raw.get(i);
                    String nextTrimmed = next.strip();
                    if (nextTrimmed.isEmpty()) {
                        // Blank line: look ahead — an indented continuation keeps the item alive.
                        if (i + 1 < raw.size() && leadingSpaces(raw.get(i + 1)) > itemIndent
                                && !raw.get(i + 1).strip().isEmpty()) {
                            i++;
                            continue;
                        }
                        break;
                    }
                    int nextIndent = leadingSpaces(next);
                    boolean nestedBullet = nextIndent > itemIndent
                            && (nextTrimmed.startsWith("- ") || nextTrimmed.startsWith("* "));
                    if (nestedBullet) {
                        appendListItem(lines, item.toString(), curDepth, wrapWidth);
                        itemIndent = nextIndent;
                        curDepth = Math.min(1, itemIndent / 2);
                        item = new StringBuilder(nextTrimmed.substring(2).strip());
                        i++;
                        continue;
                    }
                    if (nextIndent > itemIndent) {
                        item.append(' ').append(nextTrimmed);
                        i++;
                        continue;
                    }
                    break;
                }
                appendListItem(lines, item.toString(), curDepth, wrapWidth);
                continue;
            }

            // Paragraph: consume consecutive non-special lines, join, wrap.
            StringBuilder para = new StringBuilder(trimmed);
            i++;
            while (i < raw.size()) {
                String next = raw.get(i);
                String nextTrimmed = next.strip();
                if (nextTrimmed.isEmpty()
                        || nextTrimmed.startsWith("```")
                        || nextTrimmed.startsWith("#")
                        || nextTrimmed.startsWith(">")
                        || isHorizontalRule(nextTrimmed)
                        || isBulletStart(next)) {
                    break;
                }
                para.append(' ').append(nextTrimmed);
                i++;
            }
            List<MarkdownLine.Span> spans = new ArrayList<>();
            String text = parseInline(para.toString(), spans);
            lines.addAll(wrapStyled(text, spans, wrapWidth, 0));
        }
        return new MarkdownDocument(lines);
    }

    /**
     * Parses, styles, wraps, and appends one list item.
     *
     * @param lines     output list to append wrapped rows to
     * @param itemText  the raw item text (may contain inline markers)
     * @param depth     the item's list depth (0 top-level, 1 nested)
     * @param wrapWidth the widget wrap width in columns
     */
    private static void appendListItem(List<MarkdownLine> lines, String item, int depth, int wrapWidth) {
        List<MarkdownLine.Span> spans = inlineSpans(item);
        String text = inlineText(item);
        int inner = Math.max(2, wrapWidth - 4 - 2 * depth);
        var wrapped = wrapStyled(text, spans, inner, 4 + 2 * depth);
        lines.addAll(wrapped.stream()
                .map(w -> new MarkdownLine(w.text(), MarkdownLine.Kind.LIST_ITEM, 0, depth, w.spans()))
                .toList());
    }

    /**
     * Consumes a fenced code block starting at {@code raw.get(i)} (which is the
     * opening fence), appending {@link MarkdownLine.Kind#CODE} lines.
     *
     * @param raw   the raw input lines
     * @param start index of the opening fence line
     * @param out   output list to append code lines to
     * @return the index of the first line after the block (or {@code raw.size()} if unclosed)
     */
    private static int consumeCodeBlock(List<String> raw, int start, List<MarkdownLine> out) {
        String fence = raw.get(start).strip();
        // A fence line carrying a language tag (e.g. ```java) is consumed too.
        int i = start + 1;
        while (i < raw.size()) {
            String candidate = raw.get(i).strip();
            if (candidate.startsWith("```") && candidate.length() <= fence.length() + 3) {
                // Closing fence: ``` or indented ``` — consume it and stop.
                return i + 1;
            }
            out.add(new MarkdownLine(raw.get(i).stripTrailing(), MarkdownLine.Kind.CODE, 0, 0, List.of()));
            i++;
        }
        return i; // unclosed fence consumes the rest
    }

    /**
     * Returns the ATX heading level (1..4) for the given trimmed line, or 0 if
     * the line is not a heading.
     *
     * @param trimmed the trimmed line
     * @return heading level 1..4, or 0
     */
    private static int atxLevel(String trimmed) {
        int level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#') {
            level++;
        }
        if (level < 1 || level > 4) return 0;
        if (level == trimmed.length()) return level;        // "# " with no text
        if (trimmed.charAt(level) == ' ') return level;      // "# text"
        return 0;                                            // "#tag" — no space, not a heading
    }

    /**
     * Returns whether the trimmed line is a horizontal rule ({@code ---},
     * {@code ***}, or the same with interior spaces; three or more markers).
     *
     * @param trimmed the trimmed line
     * @return {@code true} if the line is a thematic break
     */
    private static boolean isHorizontalRule(String trimmed) {
        char marker = trimmed.charAt(0);
        if (marker != '-' && marker != '*' && marker != '_') return false;
        int count = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == marker) {
                count++;
            } else if (c == ' ' || c == '\t') {
                // allowed separator
            } else {
                return false;
            }
        }
        return count >= 3;
    }

    /**
     * Returns the number of leading spaces in the line (tabs count as one).
     *
     * @param line the raw line
     * @return the indent width in columns
     */
    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }

    /**
     * Returns whether the (untrimmed) line starts a bullet list item.
     *
     * @param line the raw line
     * @return {@code true} if the line begins with an optional indent plus "- " or "* "
     */
    private static boolean isBulletStart(String line) {
        int indent = leadingSpaces(line);
        String after = line.substring(indent);
        return after.startsWith("- ") || after.startsWith("* ");
    }

    /**
     * Finds the closing marker position for an emphasis run opened at
     * {@code open}. For a double marker ({@code **}) this is the next
     * {@code **}; for a single marker the scan skips balanced {@code **}
     * pairs (so {@code *outer **inner** end*} closes at the final single
     * {@code *}, not at the inner bold opener).
     *
     * @param src    the inline source
     * @param open   index of the opening {@code *}
     * @param marker the marker string ({@code *} or {@code **})
     * @return the index of the closing marker, or {@code -1} if none
     */
    private static int findEmphasisClose(String src, int open, String marker) {
        int scan = open + marker.length();
        while (scan < src.length()) {
            int candidate = src.indexOf('*', scan);
            if (candidate < 0) return -1;
            if (marker.length() == 2) {
                // Closing ** must be a double marker at the candidate.
                if (doubleMarker(src, candidate)) return candidate;
                scan = candidate + 1;
            } else {
                // Closing single * must be a single marker; balanced ** pairs
                // inside are skipped (so *a **b** c* closes at the last *).
                if (!doubleMarker(src, candidate)) return candidate;
                int pairClose = src.indexOf("**", candidate + 2);
                if (pairClose < 0) return -1; // unpaired double: opener is literal
                scan = pairClose + 2;
            }
        }
        return -1;
    }

    /**
     * Returns whether a {@code *} at {@code pos} begins a double marker.
     *
     * @param src the inline source
     * @param pos the position to test
     * @return {@code true} if {@code src[pos]} and {@code src[pos+1]} are both {@code *}
     */
    private static boolean doubleMarker(String src, int pos) {
        return pos >= 0 && pos + 1 < src.length() && src.charAt(pos) == '*' && src.charAt(pos + 1) == '*';
    }

    /**
     * Parses inline markers in the given source text: collects spans into
     * {@code spans} and returns the cleaned text.
     *
     * @param src   the raw inline source
     * @param spans the output list for spans (column-offset, may grow)
     * @return the text with markup removed
     */
    private static String parseInline(String src, List<MarkdownLine.Span> spans) {
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);

            if (c == '`') {
                int close = src.indexOf('`', i + 1);
                if (close > i + 1) {
                    int start = displayWidth(text.toString());
                    String code = src.substring(i + 1, close);
                    text.append(code);
                    spans.add(new MarkdownLine.Span(start, start + displayWidth(code), MarkdownLine.Span.Style.CODE));
                    i = close + 1;
                    continue;
                }
            }

            if (c == '*') {
                boolean doubleStar = i + 1 < src.length() && src.charAt(i + 1) == '*';
                String marker = doubleStar ? "**" : "*";
                MarkdownLine.Span.Style style = doubleStar ? MarkdownLine.Span.Style.BOLD : MarkdownLine.Span.Style.ITALIC;
                int close = findEmphasisClose(src, i, marker);
                if (close > i) {
                    int start = displayWidth(text.toString());
                    String inner = src.substring(i + marker.length(), close);
                    // Nested markers inside emphasis content: parse recursively so
                    // *a **b** c* keeps both spans.
                    List<MarkdownLine.Span> innerSpans = new ArrayList<>();
                    String innerText = parseInline(inner, innerSpans);
                    text.append(innerText);
                    int end = start + displayWidth(innerText);
                    // Outer span covers the whole region; inner spans (already
                    // offset) are appended after so they win at draw time.
                    spans.add(new MarkdownLine.Span(start, end, style));
                    for (MarkdownLine.Span s : innerSpans) {
                        spans.add(new MarkdownLine.Span(s.start() + start, s.end() + start, s.style()));
                    }
                    i = close + marker.length();
                    continue;
                }
            }

            if (c == '[') {
                int closeBracket = src.indexOf(']', i + 1);
                if (closeBracket > i + 1
                        && closeBracket + 1 < src.length()
                        && src.charAt(closeBracket + 1) == '(') {
                    int closeParen = src.indexOf(')', closeBracket + 2);
                    if (closeParen > closeBracket + 2) {
                        String label = src.substring(i + 1, closeBracket);
                        String url = src.substring(closeBracket + 2, closeParen);
                        int start = displayWidth(text.toString());
                        text.append(label);
                        int afterLabel = start + displayWidth(label);
                        text.append(" (").append(url).append(')');
                        // Dim span covers the full parenthetical " (url)" —
                        // space, both parens, and the URL itself.
                        spans.add(new MarkdownLine.Span(afterLabel,
                                afterLabel + 3 + displayWidth(url), MarkdownLine.Span.Style.DIM));
                        i = closeParen + 1;
                        continue;
                    }
                }
            }

            text.append(c);
            i++;
        }
        return text.toString();
    }

    /** Convenience overload returning only the cleaned inline text. */
    private static String inlineText(String src) {
        return parseInline(src, new ArrayList<>());
    }

    /** Convenience overload returning only the spans of the cleaned inline text. */
    private static List<MarkdownLine.Span> inlineSpans(String src) {
        List<MarkdownLine.Span> spans = new ArrayList<>();
        parseInline(src, spans);
        return List.copyOf(spans);
    }

    /**
     * Word-wraps styled text at the given display width, remapping span
     * offsets into per-row coordinates.
     *
     * @param text    the cleaned text
     * @param spans   the column-offset spans over {@code text}
     * @param width   the wrap width in columns
     * @param padLeft the indent applied to the first row (used to offset spans for hanging indents)
     * @return the wrapped lines with adjusted spans
     */
    private static List<MarkdownLine> wrapStyled(String text, List<MarkdownLine.Span> spans,
                                                 int width, int padLeft) {
        List<MarkdownLine> out = new ArrayList<>();
        if (text.isEmpty()) {
            out.add(new MarkdownLine("", MarkdownLine.Kind.PARAGRAPH, 0, 0, List.of()));
            return out;
        }
        // Tokenize by spaces but allow breaking words longer than the width.
        List<String> words = new ArrayList<>();
        for (String w : text.split(" ")) {
            if (w.isEmpty()) continue;
            while (displayWidth(w) > width && w.length() > 1) {
                // Break overlong words at the width boundary.
                int take = 0;
                int wCols = 0;
                for (int k = 0; k < w.length(); k++) {
                    int cw = charWidth(w.charAt(k));
                    if (wCols + cw > width) break;
                    wCols += cw;
                    take = k + 1;
                }
                words.add(w.substring(0, take));
                w = w.substring(take);
            }
            words.add(w);
        }

        StringBuilder row = new StringBuilder();
        int rowStart = 0;  // column offset in the source text where this row starts
        int used = 0;
        for (String word : words) {
            int wordWidth = displayWidth(word);
            if (used == 0) {
                row.append(word);
                used = wordWidth;
                continue;
            }
            if (used + 1 + wordWidth <= width) {
                row.append(' ').append(word);
                used += 1 + wordWidth;
            } else {
                out.add(new MarkdownLine(row.toString(), MarkdownLine.Kind.PARAGRAPH, 0, 0,
                        remapSpans(spans, rowStart, row.toString(), padLeft)));
                rowStart += displayWidth(row.toString()) + 1; // +1 for the space that joined the next word
                row = new StringBuilder(word);
                used = wordWidth;
            }
        }
        out.add(new MarkdownLine(row.toString(), MarkdownLine.Kind.PARAGRAPH, 0, 0,
                remapSpans(spans, rowStart, row.toString(), padLeft)));
        return out;
    }

    /**
     * Clips {@code spans} to the window {@code [rowStart, rowStart + rowWidth]}
     * and shifts them so they are relative to the wrapped row, plus the left
     * pad offset for hanging indents.
     */
    private static List<MarkdownLine.Span> remapSpans(List<MarkdownLine.Span> spans,
                                                      int rowStart, String rowText, int padLeft) {
        List<MarkdownLine.Span> result = new ArrayList<>();
        int rowEnd = rowStart + displayWidth(rowText);
        for (MarkdownLine.Span s : spans) {
            int lo = Math.max(s.start(), rowStart);
            int hi = Math.min(s.end(), rowEnd);
            if (hi > lo) {
                result.add(new MarkdownLine.Span(lo - rowStart + padLeft, hi - rowStart + padLeft, s.style()));
            }
        }
        return List.copyOf(result);
    }

    private static int charWidth(char c) {
        return TerminalTextUtils.isCharDoubleWidth(c) ? 2 : 1;
    }

    private static int displayWidth(String s) {
        return TerminalTextUtils.getTrueWidth(s);
    }
}