package io.jterm.core;

/**
 * Declared color-depth tier of a terminal, consumed by the screen layer at
 * SGR emission time (the DECRPM-2026 sync-flag pattern applied to color).
 *
 * <p>There is deliberately no "unknown" member: a terminal whose depth is
 * never declared is a TRUECOLOR terminal ({@link Terminal#colorDepth()}
 * defaults to TRUECOLOR), so every existing output path stays byte-identical
 * — only an explicitly declared, lower depth degrades emission.</p>
 *
 * <p>Tier order is ascending (ANSI16 &lt; C256 &lt; TRUECOLOR).</p>
 */
public enum ColorDepth {
    /** Classic 16-color ANSI palette: nearest ANSI-16 palette entry per RgbColor. */
    ANSI16,
    /** xterm 256-color palette: nearest 6×6×6 cube / grayscale index per RgbColor. */
    C256,
    /** 24-bit truecolor: RgbColor bytes pass through byte-identically (today's default). */
    TRUECOLOR
}
