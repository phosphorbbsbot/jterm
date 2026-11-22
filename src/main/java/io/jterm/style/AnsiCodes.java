package io.jterm.style;

import java.nio.charset.StandardCharsets;

/** Low-level ANSI escape sequence utilities. */
public final class AnsiCodes {
    /** Control Sequence Introducer — begins every CSI escape sequence. */
    public static final String CSI = "\033[";
    /** Operating System Command — begins OSC escape sequences. */
    public static final String OSC = "\033]";
    /** ANSI sequence to reset all attributes. */
    public static final String RESET = CSI + "0m";
    /** ANSI sequence to clear the entire screen. */
    public static final String CLEAR_SCREEN = CSI + "2J";
    /** ANSI sequence to clear the entire current line. */
    public static final String CLEAR_LINE = CSI + "2K";
    /** ANSI sequence to hide the cursor. */
    public static final String HIDE_CURSOR = CSI + "?25l";
    /** ANSI sequence to show the cursor. */
    public static final String SHOW_CURSOR = CSI + "?25h";
    /** ANSI sequence to enter the alternate screen buffer. */
    public static final String ENTER_ALT_SCREEN = CSI + "?1049h";
    /** ANSI sequence to exit the alternate screen buffer. */
    public static final String EXIT_ALT_SCREEN = CSI + "?1049l";
    /** ANSI sequence to disable line wrapping. */
    public static final String DISABLE_AUTOWRAP = CSI + "?7l";
    /** ANSI sequence to enable line wrapping. */
    public static final String ENABLE_AUTOWRAP = CSI + "?7h";
    /** DEC private mode 2026 — begin a synchronized (BSU) screen update. */
    public static final String BEGIN_SYNC_UPDATE = CSI + "?2026h";
    /** DEC private mode 2026 — end a synchronized (ESU) screen update. */
    public static final String END_SYNC_UPDATE = CSI + "?2026l";

    // GC law (Joe, 2026-11-21): the refresh path called getBytes(UTF_8) on
    // every constant per frame. These byte views are built ONCE. Returned
    // arrays are treated as read-only by every caller (they only reach
    // Terminal.writeRaw); never mutate them.
    private static final byte[] RESET_BYTES = RESET.getBytes(StandardCharsets.UTF_8);
    private static final byte[] BEGIN_SYNC_BYTES = BEGIN_SYNC_UPDATE.getBytes(StandardCharsets.UTF_8);
    private static final byte[] END_SYNC_BYTES = END_SYNC_UPDATE.getBytes(StandardCharsets.UTF_8);
    private static final byte[] DISABLE_AUTOWRAP_BYTES = DISABLE_AUTOWRAP.getBytes(StandardCharsets.UTF_8);
    private static final byte[] ENABLE_AUTOWRAP_BYTES = ENABLE_AUTOWRAP.getBytes(StandardCharsets.UTF_8);
    /** SGR enable/disable escapes: built once per SGR in a static block. */
    private static final byte[][] ENABLE_BYTES;
    private static final byte[][] DISABLE_BYTES;
    static {
        var values = SGR.values();
        ENABLE_BYTES = new byte[values.length][];
        DISABLE_BYTES = new byte[values.length][];
        for (SGR sgr : values) {
            ENABLE_BYTES[sgr.ordinal()] = (CSI + sgr.code + "m").getBytes(StandardCharsets.UTF_8);
            int disableCode = switch (sgr) {
                case BOLD, DIM -> 22;
                case ITALIC -> 23;
                case UNDERLINE -> 24;
                case BLINK -> 25;
                case REVERSE -> 27;
                case HIDDEN -> 28;
                case STRIKETHROUGH -> 29;
            };
            DISABLE_BYTES[sgr.ordinal()] = (CSI + disableCode + "m").getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * DCH (Delete Character) — delete {@code n} characters at the cursor,
     * shifting the remainder of the line left and opening blanks at the end.
     * VT102/ECMA-48; how a scrolling marquee row is shifted client-side
     * without a full-row rewrite.
     *
     * @param n cells to delete, clamped to 1..255
     * @return the escape bytes
     */
    public static byte[] deleteChars(int n) {
        int v = Math.max(1, Math.min(255, n));
        return (CSI + v + "P").getBytes(StandardCharsets.UTF_8);
    }

    private AnsiCodes() {}

    /**
     * Build a cursor-position escape sequence for the given row and column.
     *
     * @param row the row index (0-based)
     * @param col the column index (0-based)
     *
     * @return the ANSI escape sequence as bytes
     */
    public static byte[] cursorTo(int row, int col) {
        return (CSI + (row + 1) + ";" + (col + 1) + "H").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Build an SGR enable escape sequence.
     *
     * @param sgr the SGR attribute
     *
     * @return the ANSI escape sequence as bytes
     */
    public static byte[] enable(SGR sgr) {
        return ENABLE_BYTES[sgr.ordinal()];
    }

    /**
     * Build an SGR disable escape sequence.
     *
     * @param sgr the SGR attribute
     *
     * @return the ANSI escape sequence as bytes
     */
    public static byte[] disable(SGR sgr) {
        return DISABLE_BYTES[sgr.ordinal()];
    }

    /**
     * Build a foreground-color escape sequence.
     *
     * @param color the color to apply
     *
     * @return the ANSI escape sequence as bytes
     */
    public static byte[] setForeground(Color color) {
        return (CSI + new String(color.fgSequence(), StandardCharsets.UTF_8) + "m").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Build a background-color escape sequence.
     *
     * @param color the color to apply
     *
     * @return the ANSI escape sequence as bytes
     */
    public static byte[] setBackground(Color color) {
        return (CSI + new String(color.bgSequence(), StandardCharsets.UTF_8) + "m").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Build an SGR reset escape sequence.
     *
     * @return the ANSI escape sequence as bytes
     */
    public static byte[] reset() {
        return RESET_BYTES;
    }

    /** Synchronized-output BSU bytes (built once; read-only). */
    public static byte[] beginSyncUpdate() {
        return BEGIN_SYNC_BYTES;
    }

    /** Synchronized-output ESU bytes (built once; read-only). */
    public static byte[] endSyncUpdate() {
        return END_SYNC_BYTES;
    }

    /** Disable-auto-wrap bytes (built once; read-only). */
    public static byte[] disableAutowrap() {
        return DISABLE_AUTOWRAP_BYTES;
    }

    /** Enable-auto-wrap bytes (built once; read-only). */
    public static byte[] enableAutowrap() {
        return ENABLE_AUTOWRAP_BYTES;
    }
}
