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
        // Joe 2026-11-19: true ember gold — the ANSI bright-yellow slot (255,255,85)
        // renders white-ish on modern panels; the deep palette slot is brown.
        assertEquals(new RgbColor(255, 200, 40), t.foreground());
        assertEquals(AnsiColor.RED, t.background());
        assertEquals(AnsiColor.RED, t.selectionFg());
        assertEquals(AnsiColor.BRIGHT_YELLOW, t.selectionBg());
        assertEquals(AnsiColor.BRIGHT_RED, t.border());
        assertEquals(AnsiColor.YELLOW, t.accent());
    }

    @Test
    void artDecoThemeHasExpectedColors() {
        var t = Theme.ART_DECO;
        assertEquals(AnsiColor.BRIGHT_WHITE, t.foreground());
        assertEquals(AnsiColor.BLACK, t.background());
        assertEquals(AnsiColor.BRIGHT_YELLOW, t.selectionBg());
        assertEquals(AnsiColor.BLACK, t.titleFg());
        assertEquals(AnsiColor.BLACK, t.headerFg());
        assertEquals(AnsiColor.YELLOW, t.border());
        assertEquals(AnsiColor.YELLOW, t.titleBg());
        assertEquals(AnsiColor.YELLOW, t.headerBg());
    }

    /** The 2026-11-17 regression: ART_DECO painted title/header bands BLACK on a
     *  BLACK ground — every window title bar vanished. Law: a built-in theme's
     *  title and header band colors must VISIBLY EXIST, i.e. never repeat the
     *  terminal background. The band is the window's lintel; it must read. */
    @Test
    void builtInTitleAndHeaderBandsContrastTheBackground() {
        for (Theme t : Theme.BUILT_IN) {
            assertNotEquals(t.background(), t.titleBg(),
                t + " title band invisible (titleBg == background)");
            assertNotEquals(t.background(), t.headerBg(),
                t + " header band invisible (headerBg == background)");
        }
    }

    @Test
    void builtInThemesHasTenEntries() {
        assertEquals(10, Theme.BUILT_IN.length);
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
        assertEquals(Theme.ART_DECO, ThemeManager.cycle());
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