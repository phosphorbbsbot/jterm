package io.jterm.style;

/**
 * Color theme for the JTerm UI toolkit. Defines semantic colors used by
 * widgets: foreground, background, selection, focus, border, title, accent, etc.
 *
 * <p>Themes allow swapping the entire look-and-feel of a JTerm application
 * without modifying individual widget constructors. A {@link ThemeManager}
 * holds the active theme; widgets query it for colors instead of hardcoding
 * {@link AnsiColor} values.
 *
 * <p><b>Built-in Themes</b></p>
 * <ul>
 *   <li>{@link #DARK} — white on black (default, current behavior)</li>
 *   <li>{@link #YELLOW_ON_BLUE} — classic yellow text on blue background</li>
 *   <li>{@link #GREEN_ON_BLACK} — matrix-style green on black</li>
 *   <li>{@link #WHITE_ON_GREEN} — light green background with dark text</li>
 *   <li>{@link #YELLOW_ON_RED} — bright yellow/orange text on red background</li>
 * </ul>
 *
 * @param foreground      default text color
 * @param background      default background color
 * @param selectionFg     selection foreground (highlighted items)
 * @param selectionBg     selection background
 * @param focusFg         focused widget foreground
 * @param focusBg         focused widget background
 * @param border           border line color
 * @param titleFg         window/menu title foreground
 * @param titleBg         window/menu title background
 * @param accent           accent color (progress bars, highlights)
 * @param headerFg        table header foreground
 * @param headerBg        table header background
 */
public record Theme(
        Color foreground,
        Color background,
        Color selectionFg,
        Color selectionBg,
        Color focusFg,
        Color focusBg,
        Color border,
        Color titleFg,
        Color titleBg,
        Color accent,
        Color headerFg,
        Color headerBg
) {
    /** Dark theme: white on black. The default, matching prior behavior. */
    public static final Theme DARK = new Theme(
            AnsiColor.WHITE,
            AnsiColor.BLACK,
            AnsiColor.BLACK,
            AnsiColor.WHITE,
            AnsiColor.BLACK,
            AnsiColor.WHITE,
            AnsiColor.BRIGHT_BLACK,
            AnsiColor.WHITE,
            AnsiColor.BLUE,
            AnsiColor.GREEN,
            AnsiColor.WHITE,
            AnsiColor.BLUE
    );

    /** Classic yellow-on-blue: amber text on navy. */
    public static final Theme YELLOW_ON_BLUE = new Theme(
            AnsiColor.BRIGHT_YELLOW,
            AnsiColor.BLUE,
            AnsiColor.BRIGHT_YELLOW,
            AnsiColor.BRIGHT_BLUE,
            AnsiColor.BLACK,
            AnsiColor.BRIGHT_YELLOW,
            AnsiColor.BRIGHT_BLUE,
            AnsiColor.BRIGHT_YELLOW,
            AnsiColor.BRIGHT_BLUE,
            AnsiColor.BRIGHT_CYAN,
            AnsiColor.BRIGHT_YELLOW,
            AnsiColor.BRIGHT_BLUE
    );

    /** Matrix-style: bright green on black. */
    public static final Theme GREEN_ON_BLACK = new Theme(
            AnsiColor.BRIGHT_GREEN,
            AnsiColor.BLACK,
            AnsiColor.BLACK,
            AnsiColor.BRIGHT_GREEN,
            AnsiColor.BLACK,
            AnsiColor.BRIGHT_GREEN,
            AnsiColor.GREEN,
            AnsiColor.BRIGHT_GREEN,
            AnsiColor.BLACK,
            AnsiColor.BRIGHT_CYAN,
            AnsiColor.BRIGHT_GREEN,
            AnsiColor.BLACK
    );

    /** Light green terminal: dark text on green background. */
    public static final Theme WHITE_ON_GREEN = new Theme(
            AnsiColor.BLACK,
            AnsiColor.BRIGHT_GREEN,
            AnsiColor.BRIGHT_GREEN,
            AnsiColor.BLACK,
            AnsiColor.BRIGHT_GREEN,
            AnsiColor.BLACK,
            AnsiColor.BLACK,
            AnsiColor.BLACK,
            AnsiColor.BRIGHT_GREEN,
            AnsiColor.BLACK,
            AnsiColor.BLACK,
            AnsiColor.BRIGHT_GREEN
    );

    /** Inferno: bright yellow/orange text on red background. */
    public static final Theme YELLOW_ON_RED = new Theme(
            AnsiColor.BRIGHT_YELLOW,       // foreground
            AnsiColor.RED,                 // background
            AnsiColor.RED,                 // selectionFg
            AnsiColor.BRIGHT_YELLOW,       // selectionBg
            AnsiColor.BLACK,               // focusFg
            AnsiColor.BRIGHT_YELLOW,       // focusBg
            AnsiColor.BRIGHT_RED,          // border
            AnsiColor.BRIGHT_YELLOW,       // titleFg
            AnsiColor.RED,                 // titleBg
            AnsiColor.YELLOW,              // accent
            AnsiColor.BRIGHT_YELLOW,       // headerFg
            AnsiColor.RED                  // headerBg
    );

    /** All built-in themes in a list for cycling. */
    public static final Theme[] BUILT_IN = {
            Theme.DARK,
            Theme.YELLOW_ON_BLUE,
            Theme.GREEN_ON_BLACK,
            Theme.WHITE_ON_GREEN,
            Theme.YELLOW_ON_RED
    };

    /**
     * Human-readable name for display.
     *
     * @return the display name of this theme
     */
    public String name() {
        if (this == DARK) return "Dark (white on black)";
        if (this == YELLOW_ON_BLUE) return "Yellow on Blue";
        if (this == GREEN_ON_BLACK) return "Green on Black";
        if (this == WHITE_ON_GREEN) return "White on Green";
        if (this == YELLOW_ON_RED) return "Yellow on Red";
        return "Custom";
    }

    /**
     * Canonical lowercase key for this theme (e.g. {@code "dark"},
     * {@code "yellow_on_blue"}) — the form stored in preferences and
     * accepted by {@link #fromName(String)}.
     *
     * @return the key of this theme, or {@code null} for custom themes
     */
    public String key() {
        if (this == DARK) return "dark";
        if (this == YELLOW_ON_BLUE) return "yellow_on_blue";
        if (this == GREEN_ON_BLACK) return "green_on_black";
        if (this == WHITE_ON_GREEN) return "white_on_green";
        if (this == YELLOW_ON_RED) return "yellow_on_red";
        return null;
    }

    /**
     * Resolves a theme from a user-facing name or key. Accepts the display
     * name (e.g. {@code "Yellow on Blue"}), the lowercase key (e.g.
     * {@code "yellow_on_blue"}), any casing of either, and surrounding
     * whitespace. Theme names come from user preferences, so lookups are
     * lenient.
     *
     * @param name the theme name or key (may be null or blank)
     * @return an Optional holding the matching built-in theme, or empty
     *         if the name is null, blank, or does not match a built-in
     */
    public static java.util.Optional<Theme> fromName(String name) {
        if (name == null) {
            return java.util.Optional.empty();
        }
        String normalized = name.trim().toLowerCase().replace(' ', '_');
        for (Theme t : BUILT_IN) {
            if (t.key().equals(normalized) || t.name().toLowerCase().replace(' ', '_').equals(normalized)) {
                return java.util.Optional.of(t);
            }
        }
        return java.util.Optional.empty();
    }
}