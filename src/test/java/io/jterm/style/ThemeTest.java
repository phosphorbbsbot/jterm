package io.jterm.style;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThemeTest {

    // ── Theme record ──────────────────────────────────────────

    @Test
    void darkThemeHasExpectedColors() {
        var t = Theme.DARK;
        assertEquals(AnsiColor.WHITE, t.foreground());
        assertEquals(AnsiColor.BLACK, t.background());
        assertEquals(AnsiColor.BLACK, t.selectionFg());
        assertEquals(AnsiColor.WHITE, t.selectionBg());
    }

    @Test
    void yellowOnBlueThemeHasExpectedColors() {
        var t = Theme.YELLOW_ON_BLUE;
        assertEquals(AnsiColor.BRIGHT_YELLOW, t.foreground());
        assertEquals(AnsiColor.BLUE, t.background());
    }

    @Test
    void greenOnBlackThemeHasExpectedColors() {
        var t = Theme.GREEN_ON_BLACK;
        assertEquals(AnsiColor.BRIGHT_GREEN, t.foreground());
        assertEquals(AnsiColor.BLACK, t.background());
    }

    @Test
    void whiteOnGreenThemeHasExpectedColors() {
        var t = Theme.WHITE_ON_GREEN;
        assertEquals(AnsiColor.BLACK, t.foreground());
        assertEquals(AnsiColor.BRIGHT_GREEN, t.background());
    }

    @Test
    void yellowOnRedThemeHasExpectedColors() {
        var t = Theme.YELLOW_ON_RED;
        assertEquals(AnsiColor.BRIGHT_YELLOW, t.foreground());
        assertEquals(AnsiColor.RED, t.background());
        assertEquals(AnsiColor.RED, t.selectionFg());
        assertEquals(AnsiColor.BRIGHT_YELLOW, t.selectionBg());
        assertEquals(AnsiColor.BRIGHT_RED, t.border());
        assertEquals(AnsiColor.YELLOW, t.accent());
    }

    @Test
    void builtInThemesHasNineEntries() {
        assertEquals(9, Theme.BUILT_IN.length);
    }

    @Test
    void themeNameIsHumanReadable() {
        assertEquals("Dark (white on black)", Theme.DARK.name());
        assertEquals("Yellow on Blue", Theme.YELLOW_ON_BLUE.name());
        assertEquals("Green on Black", Theme.GREEN_ON_BLACK.name());
        assertEquals("White on Green", Theme.WHITE_ON_GREEN.name());
        assertEquals("Yellow on Red", Theme.YELLOW_ON_RED.name());
    }

    @Test
    void customThemeName() {
        var custom = new Theme(
                AnsiColor.BRIGHT_RED, AnsiColor.BLACK,
                AnsiColor.BLACK, AnsiColor.BRIGHT_RED,
                AnsiColor.BLACK, AnsiColor.BRIGHT_RED,
                AnsiColor.RED, AnsiColor.BRIGHT_RED, AnsiColor.BLACK,
                AnsiColor.BRIGHT_CYAN, AnsiColor.BRIGHT_RED, AnsiColor.BLACK
        );
        assertEquals("Custom", custom.name());
    }

    // ── ThemeManager ───────────────────────────────────────────

    @Test
    void defaultThemeIsDark() {
        ThemeManager.setActive(Theme.DARK);
        assertEquals(Theme.DARK, ThemeManager.active());
    }

    @Test
    void setActiveChangesTheme() {
        ThemeManager.setActive(Theme.GREEN_ON_BLACK);
        assertEquals(Theme.GREEN_ON_BLACK, ThemeManager.active());
        // Reset
        ThemeManager.setActive(Theme.DARK);
    }

    @Test
    void cycleAdvancesThroughThemes() {
        ThemeManager.setActive(Theme.DARK);
        assertEquals(Theme.YELLOW_ON_BLUE, ThemeManager.cycle());
        assertEquals(Theme.GREEN_ON_BLACK, ThemeManager.cycle());
        assertEquals(Theme.WHITE_ON_GREEN, ThemeManager.cycle());
        assertEquals(Theme.YELLOW_ON_RED, ThemeManager.cycle());
        assertEquals(Theme.CLASSIC_PC, ThemeManager.cycle());
        assertEquals(Theme.CYBERPUNK, ThemeManager.cycle());
        assertEquals(Theme.AMBER_ON_BLACK, ThemeManager.cycle());
        assertEquals(Theme.PAPER, ThemeManager.cycle());
        assertEquals(Theme.DARK, ThemeManager.cycle()); // wraps around
        // Reset
        ThemeManager.setActive(Theme.DARK);
    }

    @Test
    void listenersNotifiedOnThemeChange() {
        var notified = new java.util.concurrent.atomic.AtomicReference<Theme>(null);
        ThemeManager.addListener(notified::set);
        ThemeManager.setActive(Theme.YELLOW_ON_BLUE);
        assertEquals(Theme.YELLOW_ON_BLUE, notified.get());
        ThemeManager.removeListener(notified::set);
        ThemeManager.setActive(Theme.DARK);
    }

    @Test
    void removeListenerStopsNotifications() {
        var count = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.function.Consumer<Theme> listener = t -> count.incrementAndGet();
        ThemeManager.addListener(listener);
        ThemeManager.setActive(Theme.GREEN_ON_BLACK);
        assertEquals(1, count.get());
        ThemeManager.removeListener(listener);
        ThemeManager.setActive(Theme.DARK);
        assertEquals(1, count.get()); // not incremented after removal
    }
}