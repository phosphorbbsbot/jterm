
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
        assertTrue(Theme.fromName("cyberpunk").isEmpty(), "custom .phos theme names are not built-ins");
        assertTrue(Theme.fromName("").isEmpty());
        assertTrue(Theme.fromName(null).isEmpty());
    }
}
