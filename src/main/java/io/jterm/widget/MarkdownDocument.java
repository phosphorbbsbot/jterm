package io.jterm.widget;

import java.util.List;

/**
 * A parsed markdown document: an immutable list of {@link MarkdownLine} rows
 * ready for terminal rendering. Produced by {@link MarkdownParser#parse}.
 *
 * @param lines the styled lines, in document order (never {@code null})
 */
public record MarkdownDocument(List<MarkdownLine> lines) {

    /**
     * Canonical constructor with a defensive copy.
     *
     * @param lines the styled lines (must not be {@code null})
     */
    public MarkdownDocument {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * Returns the total display height (rows) this document occupies when drawn
     * at its stored per-line widths — equal to the number of parsed lines since
     * wrapping happens during parsing.
     *
     * @return the number of lines
     */
    public int lineCount() {
        return lines.size();
    }
}