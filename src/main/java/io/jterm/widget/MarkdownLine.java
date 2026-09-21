package io.jterm.widget;

import java.util.List;

/**
 * One rendered row of parsed markdown: the display text, its block kind
 * (heading / paragraph / list item / code / quote / rule), any structural
 * parameters, and inline {@link Span}s measured in terminal columns.
 *
 * @param text         the display text (cleaned of markup)
 * @param kind         the block kind
 * @param headingLevel ATX heading level 1..4, or 0 for non-headings
 * @param listDepth    0 for top-level list items, 1 for nested items, 0 for non-lists
 * @param spans        inline style spans (column-offset over {@code text}, never {@code null})
 */
public record MarkdownLine(
        String text,
        Kind kind,
        int headingLevel,
        int listDepth,
        List<Span> spans) {

    /**
     * Default wrap width when none is supplied.
     */
    public static final int DEFAULT_WIDTH = 80;

    /**
     * Block kinds a parsed line can take.
     */
    public enum Kind {
        /** ATX heading (levels 1..4). */
        HEADING,
        /** Ordinary paragraph text (already word-wrapped). */
        PARAGRAPH,
        /** Bullet list item. */
        LIST_ITEM,
        /** Fenced code block line. */
        CODE,
        /** Horizontal rule (text is empty). */
        RULE,
        /** Block-quoted line. */
        QUOTE
    }

    /**
     * A styled range within {@link MarkdownLine#text()}. Offsets are in
     * terminal columns (CJK counts 2) so drawing code can style cells directly.
     *
     * @param start start column, inclusive
     * @param end   end column, exclusive
     * @param style the inline style to apply
     */
    public record Span(int start, int end, Style style) {

        /**
         * Inline styles recognized by the parser.
         */
        public enum Style {
            /** {@code **bold**} — bold modifier. */
            BOLD,
            /** {@code *italic*} — italic modifier. */
            ITALIC,
            /** {@code `code`} — accent foreground. */
            CODE,
            /** URL text and other de-emphasized content — dim foreground. */
            DIM
        }

        /**
         * Returns whether the given column falls within this span.
         *
         * @param col the column to test
         * @return {@code true} if {@code start <= col < end}
         */
        public boolean contains(int col) {
            return col >= start && col < end;
        }
    }

    /**
     * Canonical constructor with validation and a defensive copy.
     *
     * @param text         the display text (must not be {@code null})
     * @param kind         the block kind (must not be {@code null})
     * @param headingLevel ATX heading level (0 for non-headings)
     * @param listDepth    list nesting depth (0 for non-lists)
     * @param spans        inline spans (must not be {@code null})
     */
    public MarkdownLine {
        text = text == null ? "" : text;
        kind = kind == null ? Kind.PARAGRAPH : kind;
        spans = spans == null ? List.of() : List.copyOf(spans);
        if (headingLevel < 0 || headingLevel > 4) {
            throw new IllegalArgumentException("headingLevel must be 0..4");
        }
        if (listDepth < 0 || listDepth > 1) {
            throw new IllegalArgumentException("listDepth must be 0..1");
        }
    }

    /**
     * Convenience constructor for unstyled lines.
     *
     * @param text the display text
     * @param kind the block kind
     */
    public MarkdownLine(String text, Kind kind) {
        this(text, kind, 0, 0, List.of());
    }
}