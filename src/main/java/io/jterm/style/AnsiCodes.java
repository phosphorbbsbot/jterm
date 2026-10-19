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
        return (CSI + sgr.code + "m").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Build an SGR disable escape sequence.
     *
     * @param sgr the SGR attribute
     *
     * @return the ANSI escape sequence as bytes
     */
    public static byte[] disable(SGR sgr) {
        // SGR disable codes: bold/dim/faint=22, italic=23, underline=24, blink=25, reverse=27, hidden=28, strikethrough=29
        int disableCode = switch (sgr) {
            case BOLD, DIM -> 22;
            case ITALIC -> 23;
            case UNDERLINE -> 24;
            case BLINK -> 25;
            case REVERSE -> 27;
            case HIDDEN -> 28;
            case STRIKETHROUGH -> 29;
        };
        return (CSI + disableCode + "m").getBytes(StandardCharsets.UTF_8);
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
        return RESET.getBytes(StandardCharsets.UTF_8);
    }
}
