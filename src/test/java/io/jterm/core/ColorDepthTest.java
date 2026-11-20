package io.jterm.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Laws for the library-level color-depth gate (io.jterm.core.ColorDepth).
 *
 * <p>The enum is the DECRPM-2026 sync-flag pattern applied to color: the
 * tier carries the depth, and {@code Terminal} consumes it at emit time.
 * Ordering is tier order (ANSI16 &lt; C256 &lt; TRUECOLOR); there is no
 * "unknown" member — unknown is the TRUECOLOR default (the ColorSupports
 * null law: never-degraded clients keep today's bytes).</p>
 */
class ColorDepthTest {

    @Test
    void enumIsExactlyTheThreeEmitTiersInTierOrder() {
        assertArrayEquals(new ColorDepth[] {ColorDepth.ANSI16, ColorDepth.C256, ColorDepth.TRUECOLOR},
                ColorDepth.values());
    }

    @Test
    void tierOrderingIsEscalatingByOrdinal() {
        assertTrue(ColorDepth.ANSI16.ordinal() < ColorDepth.C256.ordinal());
        assertTrue(ColorDepth.C256.ordinal() < ColorDepth.TRUECOLOR.ordinal());
    }
}
