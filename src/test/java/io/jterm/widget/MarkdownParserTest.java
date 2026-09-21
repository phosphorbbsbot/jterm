package io.jterm.widget;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MarkdownParser} block and inline parsing.
 *
 * <p>Covers: empty input, ATX headings, paragraphs, bullet lists (flat and
 * nested), fenced code blocks (closed and unclosed), horizontal rules, block
 * quotes, inline styling, links, CJK width accounting, and the hr-vs-setext
 * ambiguity.
 */
class MarkdownParserTest {

    // ── Empty and degenerate input ────────────────────────────────

    @Test
    void emptyInputYieldsNoLines() {
        var doc = MarkdownParser.parse("");
        assertTrue(doc.lines().isEmpty(), "empty input should produce no styled lines");
    }

    @Test
    void nullInputYieldsNoLines() {
        var doc = MarkdownParser.parse(null);
        assertTrue(doc.lines().isEmpty());
    }

    @Test
    void plainTextIsSingleParagraphLine() {
        var doc = MarkdownParser.parse("Hello world");
        assertEquals(1, doc.lines().size());
        var line = doc.lines().getFirst();
        assertEquals("Hello world", line.text());
        assertEquals(MarkdownLine.Kind.PARAGRAPH, line.kind());
        assertEquals(0, line.headingLevel());
    }

    @Test
    void blankLinesSeparateParagraphs() {
        var doc = MarkdownParser.parse("first\n\nsecond");
        assertEquals(2, doc.lines().size());
        assertEquals("first", doc.lines().get(0).text());
        assertEquals("second", doc.lines().get(1).text());
    }

    // ── ATX headings ──────────────────────────────────────────────

    @Test
    void atxHeadingLevels() {
        var doc = MarkdownParser.parse("# One\n## Two\n### Three\n#### Four");
        for (int i = 0; i < 4; i++) {
            var line = doc.lines().get(i);
            assertEquals(MarkdownLine.Kind.HEADING, line.kind());
            assertEquals(i + 1, line.headingLevel());
        }
        assertEquals("One", doc.lines().get(0).text());
        assertEquals("Four", doc.lines().get(3).text());
    }

    @Test
    void atxHeadingRequiresSpace() {
        // CommonMark: "#tag" is NOT a heading (no space after #).
        var doc = MarkdownParser.parse("#tag");
        assertEquals(MarkdownLine.Kind.PARAGRAPH, doc.lines().getFirst().kind());
        assertEquals("#tag", doc.lines().getFirst().text());
    }

    @Test
    void fivePlusHashesIsNotHeading() {
        // CommonMark-lite scope is levels 1..4; ##### falls back to a paragraph.
        var doc = MarkdownParser.parse("##### too deep");
        assertEquals(MarkdownLine.Kind.PARAGRAPH, doc.lines().getFirst().kind());
    }

    @Test
    void headingCarriesInlineSpans() {
        var doc = MarkdownParser.parse("# **bold** title");
        var line = doc.lines().getFirst();
        assertEquals("bold title", line.text());
        // The bold span covers chars 0..3 ("bold"), 4 is the space, 5..9 "title"
        assertFalse(line.spans().isEmpty());
        assertTrue(line.spans().stream().anyMatch(s -> s.start() == 0 && s.end() == 4));
    }

    // ── Paragraphs and word-wrap ──────────────────────────────────

    @Test
    void consecutiveTextLinesJoinIntoOneParagraph() {
        var doc = MarkdownParser.parse("one two\nthree four");
        assertEquals(1, doc.lines().size());
        assertEquals("one two three four", doc.lines().getFirst().text());
    }

    @Test
    void longParagraphWrapsAtWidthRespectingSpaces() {
        var doc = MarkdownParser.parse("alpha beta gamma delta", 12);
        assertEquals("alpha beta", doc.lines().get(0).text());
        assertEquals("gamma delta", doc.lines().get(1).text());
        // Inline spans must be adjusted to the wrapped offsets.
        assertEquals(MarkdownLine.Kind.PARAGRAPH, doc.lines().get(0).kind());
    }

    @Test
    void wrapKeepsFullText() {
        String text = "The quick brown fox jumps over the lazy dog again and again";
        var doc = MarkdownParser.parse(text, 20);
        var joined = String.join(" ", doc.lines().stream().map(MarkdownLine::text).toList());
        assertEquals(text, joined, "word wrap must not drop any words");
    }

    @Test
    void wrapBreaksLongWordThatExceedsWidth() {
        var doc = MarkdownParser.parse("supercalifragilistic", 10);
        assertTrue(doc.lines().size() >= 2);
        var joined = String.join("", doc.lines().stream().map(MarkdownLine::text).toList());
        assertEquals("supercalifragilistic", joined);
    }

    @Test
    void wrapAdjustsInlineSpanOffsets() {
        var doc = MarkdownParser.parse("**aa** bb **cc** dd **ee**", 9);
        // "aa bb cc" (8) / "dd ee" — the "cc" span sits at cols 6..8 of row 0,
        // "ee" stays in row 1.
        assertEquals("aa bb cc", doc.lines().get(0).text());
        assertEquals("dd ee", doc.lines().get(1).text());
        boolean ccInRow0 = doc.lines().get(0).spans().stream()
                .anyMatch(s -> s.start() == 6 && s.end() == 8);
        assertTrue(ccInRow0, "bold 'cc' span should be remapped into the wrapped first row");
    }

    @Test
    void cjkParagraphWrapsByDisplayWidth() {
        // Each CJK char is 2 columns: 3 chars per 6-wide row.
        var doc = MarkdownParser.parse("一二三四五六", 6);
        assertEquals("一二三", doc.lines().get(0).text());
        assertEquals("四五六", doc.lines().get(1).text());
    }

    @Test
    void cjkWidthAccountedInSpanOffsets() {
        // "你" (2 cols) + "bold text" — spans counted in *columns*, not chars.
        var doc = MarkdownParser.parse("你**bold text**", 100);
        var line = doc.lines().getFirst();
        assertTrue(line.spans().stream().anyMatch(s -> s.start() == 2 && s.end() == 11),
                "span offsets should be column-based for CJK prefix");
    }

    // ── Bullet lists ──────────────────────────────────────────────

    @Test
    void dashAndAsteriskBulletsParse() {
        var doc = MarkdownParser.parse("- one\n* two");
        assertEquals(2, doc.lines().size());
        for (var line : doc.lines()) {
            assertEquals(MarkdownLine.Kind.LIST_ITEM, line.kind());
            assertEquals(0, line.listDepth());
            assertEquals("one", doc.lines().get(0).text());
            assertEquals("two", doc.lines().get(1).text());
        }
    }

    @Test
    void listItemsMergeContinuationLines() {
        var doc = MarkdownParser.parse("- item with\n  continuation");
        assertEquals(1, doc.lines().size());
        assertEquals("item with continuation", doc.lines().getFirst().text());
    }

    @Test
    void nestedListParsesOneLevelDeep() {
        var doc = MarkdownParser.parse("- top\n  - inner\n- top2");
        assertEquals(3, doc.lines().size());
        assertEquals(MarkdownLine.Kind.LIST_ITEM, doc.lines().get(0).kind());
        assertEquals(0, doc.lines().get(0).listDepth());
        assertEquals(MarkdownLine.Kind.LIST_ITEM, doc.lines().get(1).kind());
        assertEquals(1, doc.lines().get(1).listDepth());
        assertEquals("inner", doc.lines().get(1).text());
        assertEquals(0, doc.lines().get(2).listDepth());
    }

    @Test
    void deeperNestingFlattensToLevelOne() {
        // Scope: one nested level; deeper indents degrade to depth 1.
        var doc = MarkdownParser.parse("- a\n    - b\n- c");
        assertEquals(1, doc.lines().get(1).listDepth());
    }

    @Test
    void listEndsAtNonListNonContinuationLine() {
        var doc = MarkdownParser.parse("- one\nplain text\n- two");
        assertEquals(3, doc.lines().size());
        assertEquals(MarkdownLine.Kind.LIST_ITEM, doc.lines().get(0).kind());
        assertEquals(MarkdownLine.Kind.PARAGRAPH, doc.lines().get(1).kind());
        assertEquals("plain text", doc.lines().get(1).text());
        assertEquals(MarkdownLine.Kind.LIST_ITEM, doc.lines().get(2).kind());
    }

    @Test
    void notAListWithoutSpaceAfterMarker() {
        var doc = MarkdownParser.parse("-notalist");
        assertEquals(MarkdownLine.Kind.PARAGRAPH, doc.lines().getFirst().kind());
        assertEquals("-notalist", doc.lines().getFirst().text());
    }

    // ── Fenced code blocks ────────────────────────────────────────

    @Test
    void fencedCodeBlockContents() {
        var doc = MarkdownParser.parse("```java\nint x = 1;\nint y = 2;\n```");
        assertEquals(2, doc.lines().size());
        var first = doc.lines().getFirst();
        assertEquals(MarkdownLine.Kind.CODE, first.kind());
        assertEquals("int x = 1;", first.text());
        assertEquals("int y = 2;", doc.lines().get(1).text());
        // Fence line with language tag must not leak into content.
        assertTrue(doc.lines().stream().noneMatch(l -> l.text().contains("java")));
    }

    @Test
    void unclosedFenceConsumesRestOfInput() {
        var doc = MarkdownParser.parse("```\nline one\nline two");
        assertEquals(2, doc.lines().size());
        assertEquals(MarkdownLine.Kind.CODE, doc.lines().get(0).kind());
        assertEquals(MarkdownLine.Kind.CODE, doc.lines().get(1).kind());
        assertEquals("line one", doc.lines().get(0).text());
        assertEquals("line two", doc.lines().get(1).text());
    }

    @Test
    void codeBlockPreservesBlankInteriorLine() {
        var doc = MarkdownParser.parse("```\na\n\nb\n```");
        assertEquals(3, doc.lines().size());
        assertEquals("", doc.lines().get(1).text());
        assertEquals(MarkdownLine.Kind.CODE, doc.lines().get(1).kind());
    }

    @Test
    void inlineMarkersInsideCodeAreLiteral() {
        var doc = MarkdownParser.parse("```\n**not bold**\n```");
        var line = doc.lines().getFirst();
        assertEquals("**not bold**", line.text());
        assertTrue(line.spans().isEmpty(), "code lines must not receive inline spans");
    }

    @Test
    void indentedFenceCloses() {
        var doc = MarkdownParser.parse("```\nfoo\n  ```\nafter");
        // One CODE line ("foo") + one PARAGRAPH ("after") — the indented
        // closing fence is consumed and "after" resumes normal flow.
        assertEquals(2, doc.lines().size());
        assertEquals("foo", doc.lines().getFirst().text());
        assertEquals(MarkdownLine.Kind.PARAGRAPH, doc.lines().get(1).kind());
    }

    // ── Horizontal rules ──────────────────────────────────────────

    @Test
    void dashesRuleParses() {
        var doc = MarkdownParser.parse("---");
        assertEquals(MarkdownLine.Kind.RULE, doc.lines().getFirst().kind());
    }

    @Test
    void asterisksRuleParses() {
        var doc = MarkdownParser.parse("***");
        assertEquals(MarkdownLine.Kind.RULE, doc.lines().getFirst().kind());
    }

    @Test
    void ruleWithSpacesInside() {
        // CommonMark allows "- - -" as a thematic break.
        var doc = MarkdownParser.parse("- - -");
        assertEquals(MarkdownLine.Kind.RULE, doc.lines().getFirst().kind());
    }

    @Test
    void fourDashesIsRuleInThisDialect() {
        // Only exactly three chars in scope; four-or-more is tolerated as a rule.
        var doc = MarkdownParser.parse("----");
        assertEquals(MarkdownLine.Kind.RULE, doc.lines().getFirst().kind());
    }

    @Test
    void twoDashesIsNotARule() {
        var doc = MarkdownParser.parse("--");
        assertEquals(MarkdownLine.Kind.PARAGRAPH, doc.lines().getFirst().kind());
    }

    @Test
    void hrVsSetextAmbiguity() {
        // "text" followed by "---" stays paragraph + rule in this dialect
        // (no setext headings) — the text is never swallowed by the rule.
        var doc = MarkdownParser.parse("some text\n---\nmore text");
        assertEquals(3, doc.lines().size());
        assertEquals(MarkdownLine.Kind.PARAGRAPH, doc.lines().get(0).kind());
        assertEquals("some text", doc.lines().get(0).text());
        assertEquals(MarkdownLine.Kind.RULE, doc.lines().get(1).kind());
        assertEquals(MarkdownLine.Kind.PARAGRAPH, doc.lines().get(2).kind());
    }

    // ── Block quotes ──────────────────────────────────────────────

    @Test
    void blockQuoteLines() {
        var doc = MarkdownParser.parse("> quoted one\n> quoted two");
        assertEquals(2, doc.lines().size());
        for (var line : doc.lines()) {
            assertEquals(MarkdownLine.Kind.QUOTE, line.kind());
        }
        assertEquals("quoted one", doc.lines().get(0).text());
        assertEquals("quoted two", doc.lines().get(1).text());
    }

    @Test
    void blockQuoteContentKeepsInlineSpans() {
        var doc = MarkdownParser.parse("> **wise** words");
        var line = doc.lines().getFirst();
        assertEquals("wise words", line.text());
        assertTrue(line.spans().stream().anyMatch(s -> s.start() == 0 && s.end() == 4));
    }

    @Test
    void loneGreaterThanIsQuoteWithEmptyText() {
        var doc = MarkdownParser.parse(">");
        assertEquals(MarkdownLine.Kind.QUOTE, doc.lines().getFirst().kind());
        assertEquals("", doc.lines().getFirst().text());
    }

    // ── Inline parsing ────────────────────────────────────────────

    @Test
    void boldSpan() {
        var doc = MarkdownParser.parse("plain **bold** plain");
        var line = doc.lines().getFirst();
        assertEquals("plain bold plain", line.text());
        assertTrue(line.spans().stream().anyMatch(s -> s.start() == 6 && s.end() == 10));
    }

    @Test
    void italicSpan() {
        var doc = MarkdownParser.parse("plain *em* plain");
        var line = doc.lines().getFirst();
        assertEquals("plain em plain", line.text());
        assertTrue(line.spans().stream().anyMatch(s -> s.start() == 6 && s.end() == 8));
    }

    @Test
    void inlineCodeSpan() {
        var doc = MarkdownParser.parse("use `foo()` here");
        var line = doc.lines().getFirst();
        assertEquals("use foo() here", line.text());
        assertTrue(line.spans().stream().anyMatch(s -> s.start() == 4 && s.end() == 9));
    }

    @Test
    void unclosedBoldIsLiteral() {
        var doc = MarkdownParser.parse("a ** b");
        var line = doc.lines().getFirst();
        assertEquals("a ** b", line.text());
        assertTrue(line.spans().isEmpty());
    }

    @Test
    void unclosedInlineCodeIsLiteral() {
        var doc = MarkdownParser.parse("a ` b");
        var line = doc.lines().getFirst();
        assertEquals("a ` b", line.text());
        assertTrue(line.spans().isEmpty());
    }

    @Test
    void linkRenderedAsTextPlusUrl() {
        var doc = MarkdownParser.parse("see [jterm](https://example.com) now");
        var line = doc.lines().getFirst();
        // [text](url) → "text (url)" — the URL is kept dimmed in parens.
        assertEquals("see jterm (https://example.com) now", line.text());
    }

    @Test
    void linkSpanCoversUrlParenthetical() {
        var doc = MarkdownParser.parse("[site](http://x.io)");
        var line = doc.lines().getFirst();
        // "site" is normal; " (http://x.io)" gets the dim span (parens included).
        assertTrue(line.spans().stream().anyMatch(s -> s.start() == 4 && s.end() == 18),
                "URL parenthetical should be a dim span: spans=" + line.spans());
    }

    @Test
    void brokenLinkIsLiteral() {
        var doc = MarkdownParser.parse("a [b]( c");
        var line = doc.lines().getFirst();
        assertEquals("a [b]( c", line.text());
        assertTrue(line.spans().isEmpty());
    }

    @Test
    void nestedBoldInsideItalic() {
        var doc = MarkdownParser.parse("*outer **inner** end*");
        var line = doc.lines().getFirst();
        assertEquals("outer inner end", line.text());
        // italic covers 0..15, bold covers 6..11 — last matching span wins at
        // draw time; both must exist.
        assertTrue(line.spans().stream().anyMatch(s -> s.start() == 6 && s.end() == 11));
        assertTrue(line.spans().stream().anyMatch(s -> s.start() == 0 && s.end() == 15));
    }

    @Test
    void boldSpanningWrapBoundaryKeepsStyling() {
        var doc = MarkdownParser.parse("**alpha bravo charlie**", 11);
        // Row 0: "alpha", row 1: "bravo", row 2: "charlie" — every wrapped row
        // must carry a span covering its full text.
        for (var line : doc.lines()) {
            assertFalse(line.spans().isEmpty(), "bold styling must survive the wrap: " + line.text());
            assertEquals(0, line.spans().getFirst().start());
        }
        var joined = String.join(" ", doc.lines().stream().map(MarkdownLine::text).toList());
        assertEquals("alpha bravo charlie", joined);
    }

    // ── Mixed document / graceful degradation ─────────────────────

    @Test
    void kitchenSinkDocument() {
        var md = """
                # Title
                Intro **bold** text.
                - first
                - second
                ## Sub
                ```
                code line
                ```
                ---
                > quoted
                Final paragraph.""";
        var doc = MarkdownParser.parse(md);
        var kinds = doc.lines().stream().map(MarkdownLine::kind).toList();
        assertTrue(kinds.contains(MarkdownLine.Kind.HEADING));
        assertTrue(kinds.contains(MarkdownLine.Kind.LIST_ITEM));
        assertTrue(kinds.contains(MarkdownLine.Kind.CODE));
        assertTrue(kinds.contains(MarkdownLine.Kind.RULE));
        assertTrue(kinds.contains(MarkdownLine.Kind.QUOTE));
        assertTrue(kinds.contains(MarkdownLine.Kind.PARAGRAPH));
        // Nothing lost: every non-structural input word appears in output text.
        var allText = doc.lines().stream().map(MarkdownLine::text).reduce("", (a, b) -> a + " " + b);
        for (String word : List.of("Title", "Intro", "first", "second", "Sub",
                "code", "quoted", "Final", "paragraph")) {
            assertTrue(allText.contains(word), "missing word: " + word);
        }
    }

    @Test
    void everyLineHasNonNullSpans() {
        var doc = MarkdownParser.parse("# h\nplain\n- item\n> q\n```\ncode\n```");
        for (var line : doc.lines()) {
            assertNotNull(line.spans());
        }
    }

    @Test
    void noTextLostOnArbitraryInput() {
        // Graceful degradation: random-ish line that matches no rule stays text.
        String input = ">>> weird --- stuff";
        var doc = MarkdownParser.parse(input);
        var allText = doc.lines().stream().map(MarkdownLine::text).reduce("", (a, b) -> a + b);
        assertEquals(">>> weird --- stuff", allText.trim());
    }

    @Test
    void documentExposesParsedLines() {
        List<MarkdownLine> lines = MarkdownParser.parse("just text").lines();
        assertEquals(1, lines.size());
    }
}