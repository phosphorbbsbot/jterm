package io.jterm.widget;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract tests for the {@link MarkdownLine} and {@link MarkdownDocument}
 * records: canonical-constructor validation and null-hardening, span
 * containment edges, defensive copies, and the convenience constructor.
 */
class MarkdownLineDocumentContractTest {

    // ---- MarkdownLine canonical constructor ---------------------------------

    @Test
    void canonicalConstructorNullHardensTextKindAndSpans() {
        var line = new MarkdownLine(null, null, 0, 0, null);
        assertEquals("", line.text());
        assertEquals(MarkdownLine.Kind.PARAGRAPH, line.kind());
        assertNotNull(line.spans());
        assertTrue(line.spans().isEmpty());
    }

    @Test
    void canonicalConstructorRejectsHeadingLevelOutOfRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new MarkdownLine("t", MarkdownLine.Kind.HEADING, -1, 0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new MarkdownLine("t", MarkdownLine.Kind.HEADING, 5, 0, List.of()));
    }

    @Test
    void canonicalConstructorAcceptsHeadingLevelsOneThroughFour() {
        for (int level = 1; level <= 4; level++) {
            var line = new MarkdownLine("t", MarkdownLine.Kind.HEADING, level, 0, List.of());
            assertEquals(level, line.headingLevel());
        }
    }

    @Test
    void canonicalConstructorRejectsListDepthOutOfRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new MarkdownLine("t", MarkdownLine.Kind.LIST_ITEM, 0, -1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new MarkdownLine("t", MarkdownLine.Kind.LIST_ITEM, 0, 2, List.of()));
    }

    @Test
    void canonicalConstructorAcceptsListDepthsZeroAndOne() {
        assertEquals(0, new MarkdownLine("t", MarkdownLine.Kind.LIST_ITEM, 0, 0, List.of()).listDepth());
        assertEquals(1, new MarkdownLine("t", MarkdownLine.Kind.LIST_ITEM, 0, 1, List.of()).listDepth());
    }

    @Test
    void spansAreDefensivelyCopied() {
        var mutable = new ArrayList<MarkdownLine.Span>();
        mutable.add(new MarkdownLine.Span(0, 2, MarkdownLine.Span.Style.BOLD));
        var line = new MarkdownLine("ab", MarkdownLine.Kind.PARAGRAPH, 0, 0, mutable);
        mutable.clear();
        assertEquals(1, line.spans().size(), "mutating the source list must not affect the line");
        assertThrows(UnsupportedOperationException.class, () -> line.spans().add(null));
    }

    // ---- convenience constructor ---------------------------------------------

    @Test
    void convenienceConstructorDefaultsHeadingLevelAndDepth() {
        var line = new MarkdownLine("hello", MarkdownLine.Kind.QUOTE);
        assertEquals(0, line.headingLevel());
        assertEquals(0, line.listDepth());
        assertTrue(line.spans().isEmpty());
    }

    // ---- Span.contains edges ---------------------------------------------------

    @Test
    void spanContainsIsStartInclusiveEndExclusive() {
        var span = new MarkdownLine.Span(1, 3, MarkdownLine.Span.Style.CODE);
        assertFalse(span.contains(0));
        assertTrue(span.contains(1));
        assertTrue(span.contains(2));
        assertFalse(span.contains(3));
    }

    // ---- MarkdownDocument -------------------------------------------------------

    @Test
    void documentNullHardensToEmptyList() {
        var doc = new MarkdownDocument(null);
        assertEquals(0, doc.lineCount());
        assertNotNull(doc.lines());
        assertTrue(doc.lines().isEmpty());
    }

    @Test
    void documentCopiesLineListDefensively() {
        var mutable = new ArrayList<MarkdownLine>();
        mutable.add(new MarkdownLine("x", MarkdownLine.Kind.PARAGRAPH));
        var doc = new MarkdownDocument(mutable);
        mutable.clear();
        assertEquals(1, doc.lines().size());
        assertThrows(UnsupportedOperationException.class, () -> doc.lines().add(null));
    }

    @Test
    void documentLineCountMatchesListSize() {
        var doc = new MarkdownDocument(List.of(
                new MarkdownLine("a", MarkdownLine.Kind.PARAGRAPH),
                new MarkdownLine("b", MarkdownLine.Kind.CODE),
                new MarkdownLine("c", MarkdownLine.Kind.RULE)));
        assertEquals(3, doc.lineCount());
    }

    @Test
    void defaultWidthIsEighty() {
        assertEquals(80, MarkdownLine.DEFAULT_WIDTH);
    }
}