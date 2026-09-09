package io.jterm.style;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Holds the active {@link Theme} and notifies listeners when it changes.
 *
 * <p>Widgets query {@code ThemeManager.active()} to get semantic colors
 * instead of hardcoding {@link AnsiColor} values. This allows switching
 * the entire look-and-feel at runtime.
 *
 * <pre>{@code
 * // In a widget's drawComponent:
 * var theme = ThemeManager.active();
 * var style = new TextCell(' ', theme.foreground(), theme.background());
 * graphics.fillRectangle(0, 0, w, h, style);
 * }</pre>
 */
public final class ThemeManager {
    private static volatile Theme active = Theme.DARK;
    private static final CopyOnWriteArrayList<Consumer<Theme>> listeners = new CopyOnWriteArrayList<>();

    /**
     * Per-thread theme override. A session (e.g. one BBS user's connection)
     * pins its own theme here so concurrent sessions can render with
     * different themes; {@link #active()} prefers this over the global.
     */
    private static final ThreadLocal<Theme> threadOverride = new ThreadLocal<>();

    private ThemeManager() {} // utility class

    /**
     * Returns the currently active theme for the calling thread: the
     * thread's pinned theme if one is set (see
     * {@link #setThreadTheme(Theme)}), otherwise the global active theme.
     *
     * @return the active theme for this thread
     */
    public static Theme active() {
        Theme override = threadOverride.get();
        return override != null ? override : active;
    }

    /**
     * Sets the active theme and notifies all listeners.
     *
     * @param theme the theme to activate
     */
    public static void setActive(Theme theme) {
        active = theme;
        for (var listener : listeners) {
            listener.accept(theme);
        }
    }

    /**
     * Pins a theme for the calling thread, shadowing the global active
     * theme. Used by servers hosting multiple concurrent sessions (one
     * thread per connection): each session renders with its own theme
     * without affecting other users. Listeners are NOT fired — this is a
     * thread-local view, not a global change. Call {@link
     * #clearThreadTheme()} when the session ends (virtual threads should
     * clear in a {@code finally} to avoid leaking the override across
     * thread reuse).
     *
     * @param theme the theme this thread should render with
     */
    public static void setThreadTheme(Theme theme) {
        threadOverride.set(theme);
    }

    /**
     * Removes the calling thread's pinned theme (if any), restoring the
     * global active theme for this thread.
     */
    public static void clearThreadTheme() {
        threadOverride.remove();
    }

    /**
     * Cycles to the next built-in theme.
     *
     * @return the new active theme
     */
    public static Theme cycle() {
        Theme[] built = Theme.BUILT_IN;
        for (int i = 0; i < built.length; i++) {
            if (active == built[i]) {
                Theme next = built[(i + 1) % built.length];
                setActive(next);
                return next;
            }
        }
        // If current is custom, go to first built-in
        setActive(built[0]);
        return built[0];
    }

    /**
     * Registers a callback to be notified when the theme changes.
     *
     * @param listener the listener to register
     */
    public static void addListener(Consumer<Theme> listener) {
        listeners.add(listener);
    }

    /**
     * Removes a previously registered listener.
     *
     * @param listener the listener to remove
     */
    public static void removeListener(Consumer<Theme> listener) {
        listeners.remove(listener);
    }
}