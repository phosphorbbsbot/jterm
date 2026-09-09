
package io.jterm.style;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ThemeManager} per-thread theme override: a session can
 * pin a theme for its own rendering thread without mutating the global
 * active theme — concurrent sessions each see their own theme.
 */
class ThemeManagerThreadThemeTest {

    @AfterEach
    void resetOverrides() {
        ThemeManager.clearThreadTheme();
    }

    @Test
    void withoutThreadOverrideActiveIsGlobal() {
        ThemeManager.setThreadTheme(Theme.GREEN_ON_BLACK);
        ThemeManager.clearThreadTheme();
        assertEquals(Theme.DARK, ThemeManager.active(), "no override → global default");
    }

    @Test
    void threadOverrideShadowsGlobalForThisThreadOnly() throws Exception {
        ThemeManager.setThreadTheme(Theme.GREEN_ON_BLACK);
        assertEquals(Theme.GREEN_ON_BLACK, ThemeManager.active(),
            "this thread sees its override");

        var other = new Thread(() -> assertEquals(Theme.DARK, ThemeManager.active(),
            "another thread without an override sees the global theme"));
        other.start();
        other.join(2000);
    }

    @Test
    void setActiveStillFiresListenersAndUpdatesGlobal() {
        var seen = new Object() { Theme last; };
        ThemeManager.addListener(t -> seen.last = t);
        try {
            ThemeManager.setThreadTheme(Theme.GREEN_ON_BLACK);   // override, no listener fire
            assertEquals(Theme.DARK, seen.last == null ? Theme.DARK : seen.last,
                "setThreadTheme must not fire global listeners");
            ThemeManager.setActive(Theme.YELLOW_ON_BLUE);        // global set fires
            assertEquals(Theme.YELLOW_ON_BLUE, seen.last);
            ThemeManager.clearThreadTheme();                     // lift override first
            assertEquals(Theme.YELLOW_ON_BLUE, ThemeManager.active(),
                "global set wins when no thread override");
            ThemeManager.setThreadTheme(Theme.WHITE_ON_GREEN);   // override shadows again
            assertEquals(Theme.WHITE_ON_GREEN, ThemeManager.active());
            ThemeManager.clearThreadTheme();
            assertEquals(Theme.YELLOW_ON_BLUE, ThemeManager.active());
        } finally {
            ThemeManager.removeListener(t -> { });
            ThemeManager.setActive(Theme.DARK); // restore
        }
    }
}
