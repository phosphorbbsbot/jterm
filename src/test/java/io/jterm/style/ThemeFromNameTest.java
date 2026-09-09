
package io.jterm.style;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Theme#fromName(String)}: resolving user-facing theme
 * names (as stored in preferences) to built-in Theme instances.
 */
class ThemeFromNameTest {

    @Test
    void resolvesEveryBuiltInByDisplayName() {
        for (Theme t : Theme.BUILT_IN) {
            Optional<Theme> found = Theme.fromName(t.name());
            assertTrue(found.isPresent(), "name() of every built-in must resolve: " + t.name());
            assertSame(t, found.get());
        }
    }

    @Test
    void resolvesBySimpleKeyCaseInsensitive() {
        assertSame(Theme.DARK, Theme.fromName("dark").orElseThrow());
        assertSame(Theme.DARK, Theme.fromName("DARK").orElseThrow());
        assertSame(Theme.YELLOW_ON_BLUE, Theme.fromName("yellow_on_blue").orElseThrow());
        assertSame(Theme.YELLOW_ON_BLUE, Theme.fromName("Yellow on Blue").orElseThrow());
        assertSame(Theme.GREEN_ON_BLACK, Theme.fromName("green_on_black").orElseThrow());
        assertSame(Theme.WHITE_ON_GREEN, Theme.fromName("white_on_green").orElseThrow());
        assertSame(Theme.YELLOW_ON_RED, Theme.fromName("yellow_on_red").orElseThrow());
    }

    @Test
    void resolvesWithSurroundingWhitespace() {
        assertSame(Theme.DARK, Theme.fromName("  dark  ").orElseThrow());
        assertSame(Theme.DARK, Theme.fromName("Dark (white on black)").orElseThrow());
    }

    @Test
    void unknownNameGivesEmptyOptional() {
        assertTrue(Theme.fromName("synthwave").isEmpty(), "truly unknown names give empty");
        assertTrue(Theme.fromName("").isEmpty());
        assertTrue(Theme.fromName(null).isEmpty());
    }

    @Test
    void resolvesClassicPcAndCyberpunk() {
        assertSame(Theme.CLASSIC_PC, Theme.fromName("classic_pc").orElseThrow());
        assertSame(Theme.CLASSIC_PC, Theme.fromName("Cyan on Blue").orElseThrow());
        assertSame(Theme.CYBERPUNK, Theme.fromName("cyberpunk").orElseThrow());
        assertSame(Theme.CYBERPUNK, Theme.fromName("Cyberpunk").orElseThrow());
    }

    @Test
    void resolvesAmberAndPaperThemes() {
        assertSame(Theme.AMBER_ON_BLACK, Theme.fromName("amber_on_black").orElseThrow());
        assertSame(Theme.AMBER_ON_BLACK, Theme.fromName("Amber on Black").orElseThrow());
        assertSame(Theme.PAPER, Theme.fromName("paper").orElseThrow());
        assertSame(Theme.PAPER, Theme.fromName("Paper White").orElseThrow());
    }

    @Test
    void everyBuiltInHasDistinctKeyAndName() {
        var keys = new java.util.HashSet<String>();
        var names = new java.util.HashSet<String>();
        for (Theme t : Theme.BUILT_IN) {
            assertTrue(keys.add(t.key()), "duplicate key: " + t.key());
            assertTrue(names.add(t.name()), "duplicate name: " + t.name());
        }
    }

}
