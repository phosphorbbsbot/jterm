package io.jterm.style;

/**
 * Classic 16-color ANSI palette. Maximum terminal compatibility.
 *
 * <p>Constants: BLACK(0), RED(1), GREEN(2), YELLOW(3), BLUE(4), MAGENTA(5),
 * CYAN(6), WHITE(7), BRIGHT_BLACK(8), BRIGHT_RED(9), BRIGHT_GREEN(10),
 * BRIGHT_YELLOW(11), BRIGHT_BLUE(12), BRIGHT_MAGENTA(13), BRIGHT_CYAN(14),
 * BRIGHT_WHITE(15), DEFAULT(-1, terminal default colors).</p>
 */
public enum AnsiColor implements Color {
    /** Black — palette index 0. */
    BLACK(0, 0, 0, 0),
    /** Red — palette index 1. */
    RED(1, 170, 0, 0),
    /** Green — palette index 2. */
    GREEN(2, 0, 170, 0),
    /** Yellow — palette index 3. */
    YELLOW(3, 170, 85, 0),
    /** Blue — palette index 4. */
    BLUE(4, 0, 0, 170),
    /** Magenta — palette index 5. */
    MAGENTA(5, 170, 0, 170),
    /** Cyan — palette index 6. */
    CYAN(6, 0, 170, 170),
    /** White — palette index 7. */
    WHITE(7, 170, 170, 170),
    /** Bright black (dark gray) — palette index 8. */
    BRIGHT_BLACK(8, 85, 85, 85),
    /** Bright red — palette index 9. */
    BRIGHT_RED(9, 255, 85, 85),
    /** Bright green — palette index 10. */
    BRIGHT_GREEN(10, 85, 255, 85),
    /** Bright yellow — palette index 11. */
    BRIGHT_YELLOW(11, 255, 255, 85),
    /** Bright blue — palette index 12. */
    BRIGHT_BLUE(12, 85, 85, 255),
    /** Bright magenta — palette index 13. */
    BRIGHT_MAGENTA(13, 255, 85, 255),
    /** Bright cyan — palette index 14. */
    BRIGHT_CYAN(14, 85, 255, 255),
    /** Bright white — palette index 15. */
    BRIGHT_WHITE(15, 255, 255, 255),
    /** Terminal default colors (no explicit SGR color), index -1. Not valid for backgrounds. */
    DEFAULT(-1, 0, 0, 0);

    private final int index;
    private final int r, g, b;

    AnsiColor(int index, int r, int g, int b) {
        this.index = index;
        this.r = r;
        this.g = g;
        this.b = b;
    }

    /**
     * Returns the RGB representation of this ANSI color.
     *
     * @return the equivalent 24-bit {@link RgbColor}
     */
    public RgbColor toRgb() {
        return new RgbColor(r, g, b);
    }

    /**
     * Returns the ANSI color closest to the given RGB values.
     *
     * @param r red component, 0-255
     * @param g green component, 0-255
     * @param b blue component, 0-255
     * @return the nearest palette color (never {@link #DEFAULT})
     */
    public static AnsiColor nearest(int r, int g, int b) {
        AnsiColor best = BLACK;
        double bestDist = Double.MAX_VALUE;
        for (AnsiColor c : values()) {
            if (c == DEFAULT) continue;
            double dist = (c.r - r) * (c.r - r) + (c.g - g) * (c.g - g) + (c.b - b) * (c.b - b);
            if (dist < bestDist) {
                bestDist = dist;
                best = c;
            }
        }
        return best;
    }

    /**
     * Blends two ANSI colors and returns the nearest ANSI color in the palette.
     *
     * @param a       first color
     * @param b       second color
     * @param aWeight blend weight of {@code a}, 0.0 (all {@code b}) to 1.0 (all {@code a})
     * @return the blended color, snapped to the nearest palette entry
     */
    public static AnsiColor blendAnsi(AnsiColor a, AnsiColor b, double aWeight) {
        if (aWeight <= 0.0) return b;
        if (aWeight >= 1.0) return a;
        int r = (int) (a.r + (b.r - a.r) * aWeight);
        int g = (int) (a.g + (b.g - a.g) * aWeight);
        int bl = (int) (a.b + (b.b - a.b) * aWeight);
        return nearest(r, g, bl);
    }

    /**
     * Return the ANSI foreground escape sequence for this color.
     *
     * @return the SGR foreground parameter bytes
     */
    // GC law (Joe, 2026-11-21): 16 fixed colors + DEFAULT — the parameter
    // bytes are built once, indexed by the color's index with the final slot
    // (17) carrying DEFAULT's reset code. Callers treat them as read-only.
    private static final byte[][] FG_SEQS = {
            "30".getBytes(), "31".getBytes(), "32".getBytes(), "33".getBytes(),
            "34".getBytes(), "35".getBytes(), "36".getBytes(), "37".getBytes(),
            "90".getBytes(), "91".getBytes(), "92".getBytes(), "93".getBytes(),
            "94".getBytes(), "95".getBytes(), "96".getBytes(), "97".getBytes(),
            "39".getBytes(), // DEFAULT
    };
    private static final byte[][] BG_SEQS = {
            "40".getBytes(), "41".getBytes(), "42".getBytes(), "43".getBytes(),
            "44".getBytes(), "45".getBytes(), "46".getBytes(), "47".getBytes(),
            "100".getBytes(), "101".getBytes(), "102".getBytes(), "103".getBytes(),
            "104".getBytes(), "105".getBytes(), "106".getBytes(), "107".getBytes(),
            "49".getBytes(), // DEFAULT
    };

    @Override
    public byte[] fgSequence() {
        return FG_SEQS[index < 0 ? 16 : index];
    }

    /**
     * Return the ANSI background escape sequence for this color.
     *
     * @return the SGR background parameter bytes
     */
    @Override
    public byte[] bgSequence() {
        return BG_SEQS[index < 0 ? 16 : index];
    }
}
