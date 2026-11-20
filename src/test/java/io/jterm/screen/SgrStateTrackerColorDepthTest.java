package io.jterm.screen;

import io.jterm.core.ColorDepth;
import io.jterm.style.IndexedColor;
import io.jterm.style.RgbColor;
import io.jterm.style.SGR;
import io.jterm.style.TextCell;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Laws for color-depth degradation in {@link DefaultScreen.SgrStateTracker}
 * (the library-level truecolor depth gate, 2026-11-19).
 *
 * <p>The tracker degrades RgbColor paints BEFORE emitting when the terminal's
 * depth is below TRUECOLOR — C256 maps to the nearest xterm-256 palette index
 * (the 6×6×6 cube + grayscale law of {@link IndexedColor#fromRgb}), ANSI16
 * maps to the nearest ANSI-16 palette entry ({@link AnsiColor#nearest}).
 * Comparisons and every re-emit ride the MAPPED colors, so two distinct
 * RgbColors that map to the same index emit zero redundant sequences.</p>
 *
 * <p>The null law: a terminal whose depth is unknown is TRUECOLOR — the
 * golden 38;2;R;G;B / 48;2;R;G;B bytes every existing path emitted are
 * unchanged.</p>
 */
class SgrStateTrackerColorDepthTest {

    /** ART_DECO gold (the real theme truecolor that must survive degradation laws). */
    private static final RgbColor GOLD = new RgbColor(255, 214, 71);

    private static String bytes(byte[] out) {
        return new String(out, StandardCharsets.UTF_8);
    }

    private static DefaultScreen.SgrStateTracker trackerAt(ColorDepth depth) {
        return new DefaultScreen.SgrStateTracker(() -> depth);
    }

    @Test
    @DisplayName("a null depth supplier is rejected (fail loudly, never silently degrade)")
    void nullDepthSupplierRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DefaultScreen.SgrStateTracker(null));
    }

    // ------------------------------------------------------------------
    // Golden law: TRUECOLOR terminals get today's exact bytes.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TRUECOLOR (default depth): gold RGB emits golden 38;2 / 48;2 exactly as today")
    void truecolorEmitsLegacyRgbBytes() {
        var tracker = trackerAt(ColorDepth.TRUECOLOR);
        tracker.transitionTo(TextCell.EMPTY);
        byte[] out = tracker.transitionTo(
                new TextCell('X', GOLD, new RgbColor(0, 64, 192)));
        assertEquals("\u001b[38;2;255;214;71m\u001b[48;2;0;64;192m", bytes(out),
                "byte-identical golden law for default-depth terminals");
    }

    @Test
    @DisplayName("TRUECOLOR: a depth that is never set on the tier (unknown) is truecolor via the no-arg tracker")
    void noArgTrackerDefaultsToTruecolor() {
        var tracker = new DefaultScreen.SgrStateTracker();
        tracker.transitionTo(TextCell.EMPTY);
        byte[] out = tracker.transitionTo(
                new TextCell('X', GOLD, new RgbColor(0, 64, 192)));
        assertEquals("\u001b[38;2;255;214;71m\u001b[48;2;0;64;192m", bytes(out),
                "the no-arg tracker (legacy callers) keeps the golden bytes");
    }

    // ------------------------------------------------------------------
    // C256 mapping law: nearest 256-palette index (cube + grayscale).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C256: ART_DECO gold RgbColor(255,214,71) maps to the nearest 256-palette index")
    void c256DepthMapsGoldThroughFromRgb() {
        var tracker = trackerAt(ColorDepth.C256);
        tracker.transitionTo(TextCell.EMPTY);
        byte[] out = tracker.transitionTo(new TextCell('X', GOLD, null));
        int expected = IndexedColor.fromRgb(255, 214, 71).index();
        assertEquals("\u001b[38;5;" + expected + "m", bytes(out),
                "C256 emission is the nearest-index law, tracked per-cell");
        assertEquals(221, expected,
                "gold quantizes to cube index 221 (255,215,95) — component-wise nearer than the grayscale ramp");
    }

    @Test
    @DisplayName("C256: pure red maps to cube index 196 and background rides 48;5")
    void c256DepthMapsRedFgAndBlueBg() {
        var tracker = trackerAt(ColorDepth.C256);
        tracker.transitionTo(TextCell.EMPTY);
        byte[] out = tracker.transitionTo(
                new TextCell('X', new RgbColor(255, 0, 0), new RgbColor(0, 0, 255)));
        assertEquals("\u001b[38;5;196m\u001b[48;5;21m", bytes(out),
                "fg 196 (255,0,0 cube exact) + bg 21 (0,0,255 cube exact)");
    }

    // ------------------------------------------------------------------
    // ANSI16 mapping law: nearest 16-palette entry.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ANSI16: gold maps to nearest palette entry (bright yellow, 93) and bg to 103")
    void ansi16DepthMapsGoldToNearestPaletteEntry() {
        var tracker = trackerAt(ColorDepth.ANSI16);
        tracker.transitionTo(TextCell.EMPTY);
        byte[] out = tracker.transitionTo(new TextCell('X', GOLD, new RgbColor(255, 214, 71)));
        assertEquals("\u001b[93m\u001b[103m", bytes(out),
                "gold is nearest BRIGHT_YELLOW for fg (93) and bg (103)");
    }

    @Test
    @DisplayName("ANSI16: exact palette RgbColors map to their classic SGR codes")
    void ansi16DepthMapsExactPaletteRgbColors() {
        var tracker = trackerAt(ColorDepth.ANSI16);
        tracker.transitionTo(TextCell.EMPTY);
        byte[] out = tracker.transitionTo(
                new TextCell('X', new RgbColor(170, 0, 0), new RgbColor(0, 0, 170)));
        assertEquals("\u001b[31m\u001b[44m", bytes(out),
                "(170,0,0) is RED (31) and (0,0,170) is BLUE bg (44)");
    }

    // ------------------------------------------------------------------
    // Downgrade collapse law: two distinct RgbColors that map to the same
    // index emit zero redundant sequences.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C256 collapse: distinct Rgb colors in the same 256 bucket emit nothing on re-transition")
    void c256DowngradeCollapsesEqualIndex() {
        var tracker = trackerAt(ColorDepth.C256);
        tracker.transitionTo(TextCell.EMPTY);
        byte[] first = tracker.transitionTo(new TextCell('X', new RgbColor(255, 255, 255), null));
        assertEquals("\u001b[38;5;231m", bytes(first));
        byte[] second = tracker.transitionTo(new TextCell('X', new RgbColor(255, 254, 254), null));
        assertEquals(0, second.length,
                "RGB(255,255,255) and RGB(255,254,254) both land on index 231 — zero redundant bytes");
    }

    @Test
    @DisplayName("ANSI16 collapse: distinct Rgb colors in the same 16 bucket emit nothing")
    void ansi16DowngradeCollapsesEqualPaletteColor() {
        var tracker = trackerAt(ColorDepth.ANSI16);
        tracker.transitionTo(TextCell.EMPTY);
        byte[] first = tracker.transitionTo(new TextCell('X', new RgbColor(255, 0, 0), null));
        assertEquals("\u001b[31m", bytes(first));
        byte[] second = tracker.transitionTo(new TextCell('X', new RgbColor(250, 0, 0), null));
        assertEquals(0, second.length, "both map to RED (index 1) — zero redundant bytes");
    }

    // ------------------------------------------------------------------
    // Transition law: RGB -> index -> RGB -> default -> RGB (one tracker).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C256 run: RGB -> explicit index -> RGB -> default -> RGB emits exactly the mapped delta stream")
    void transitionsRgbIndexRgbDefaultRgb() {
        var tracker = trackerAt(ColorDepth.C256);
        tracker.transitionTo(TextCell.EMPTY);

        StringBuilder wire = new StringBuilder();
        wire.append(bytes(tracker.transitionTo(new TextCell('A', new RgbColor(255, 0, 0), null))));
        wire.append(bytes(tracker.transitionTo(new TextCell('B', new IndexedColor(196), null))));
        wire.append(bytes(tracker.transitionTo(new TextCell('C', new RgbColor(255, 0, 0), null))));
        wire.append(bytes(tracker.transitionTo(TextCell.EMPTY)));
        wire.append(bytes(tracker.transitionTo(new TextCell('E', new RgbColor(0, 255, 0), null))));

        assertEquals("\u001b[38;5;196m"        // RGB degrades to cube index
                        + ""                    // explicit index at the same mapped state: no bytes
                        + ""                    // RGB back to the same index: no bytes
                        + "\u001b[0m"           // fully-default shortcut rides the mapped state
                        + "\u001b[38;5;46m",    // RGB to a different index
                wire.toString(),
                "the RGB -> index -> RGB -> default -> RGB transition law");
    }

    // ------------------------------------------------------------------
    // CP437 law: post-modifier-disable re-emit rides MAPPED colors.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C256: disabling BOLD re-emits the 256-mapped fg/bg, never 38;2 bytes")
    void postDisableReEmitsMappedColors() {
        var tracker = trackerAt(ColorDepth.C256);
        byte[] enabled = tracker.transitionTo(
                new TextCell('A', new RgbColor(255, 0, 0), new RgbColor(0, 0, 255), SGR.BOLD));
        assertEquals("\u001b[38;5;196m\u001b[48;5;21m\u001b[1m", bytes(enabled));
        String disabled = bytes(tracker.transitionTo(
                new TextCell('A', new RgbColor(255, 0, 0), new RgbColor(0, 0, 255))));
        assertTrue(disabled.contains("\u001b[22m"), "BOLD off: " + disabled);
        assertTrue(disabled.contains("\u001b[38;5;196m"), "mapped fg re-emit: " + disabled);
        assertTrue(disabled.contains("\u001b[48;5;21m"), "mapped bg re-emit: " + disabled);
        assertFalse(disabled.contains("38;2;"), "no truecolor bytes may survive degradation: " + disabled);
        assertFalse(disabled.contains("48;2;"), "no truecolor bytes may survive degradation: " + disabled);
    }

    // ------------------------------------------------------------------
    // Null fg/bg passthrough rides the mapping (no NPE, no extra emission).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C256: null fg/bg stays API-legal — null rides through, non-null side maps")
    void nullFgBgPassthroughRidesMapping() {
        var tracker = trackerAt(ColorDepth.C256);
        tracker.transitionTo(TextCell.EMPTY);
        byte[] fgOnly = tracker.transitionTo(new TextCell(' ', new RgbColor(255, 0, 0), null));
        assertEquals("\u001b[38;5;196m", bytes(fgOnly), "fg mapped, null bg skipped");
        byte[] bgOnly = tracker.transitionTo(new TextCell(' ', null, new RgbColor(0, 0, 255)));
        assertEquals("\u001b[48;5;21m", bytes(bgOnly), "null fg skipped (state carries), bg mapped");
    }

    @Test
    @DisplayName("C256: the fully-default shortcut emits the single reset once the mapped state is non-default")
    void defaultResetShortcutRidesMappedState()
    {
        var tracker = trackerAt(ColorDepth.C256);
        tracker.transitionTo(new TextCell('X', new RgbColor(255, 255, 255), new RgbColor(0, 0, 170)));
        byte[] reset = tracker.transitionTo(TextCell.EMPTY);
        assertEquals("\u001b[0m", bytes(reset), "single SGR reset shortcut");
    }

    // ------------------------------------------------------------------
    // Live-depth law: the tracker re-reads the terminal's depth per call.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("depth flips between calls: same tracker follows TRUECOLOR -> C256 -> TRUECOLOR")
    void trackerRereadsDepthOnEveryTransition() {
        var depth = new AtomicReference<>(ColorDepth.TRUECOLOR);
        var tracker = new DefaultScreen.SgrStateTracker(depth::get);
        tracker.transitionTo(TextCell.EMPTY);
        byte[] rgb = tracker.transitionTo(new TextCell('X', GOLD, null));
        assertEquals("\u001b[38;2;255;214;71m", bytes(rgb), "truecolor first");

        depth.set(ColorDepth.C256);
        byte[] mapped = tracker.transitionTo(new TextCell('X', GOLD, null));
        assertEquals("\u001b[38;5;221m", bytes(mapped), "the same cell degrades after the downgrade");

        depth.set(ColorDepth.TRUECOLOR);
        byte[] restored = tracker.transitionTo(new TextCell('X', GOLD, null));
        assertEquals("\u001b[38;2;255;214;71m", bytes(restored), "restore upgrades back to golden bytes");
    }

    @Test
    @DisplayName("DefaultScreen degrades when the TERMINAL TIER carries C256 (wiring reads the live depth)")
    void defaultScreenReadsTheTerminalTierDepth() throws Exception {
        var terminal = new io.jterm.core.MockTerminal(new io.jterm.core.TerminalSize(80, 24));
        terminal.setColorDepth(ColorDepth.C256);
        var screen = new DefaultScreen(terminal);
        screen.startScreen();
        screen.setCell(0, 0, new TextCell('X', GOLD, null));
        screen.refresh();
        String full = terminal.getOutput();
        assertTrue(full.contains("\u001b[38;5;221m"), "the tier's C256 depth degrades the paint: " + full);
        assertFalse(full.contains("38;2;255;214;71"), "no truecolor gold on a C256 tier: " + full);
    }

    @Test
    @DisplayName("DefaultScreen keeps golden bytes for the default-depth tier (byte-identical contract)")
    void defaultScreenKeepsGoldenBytesAtDefaultDepth() throws Exception {
        var terminal = new io.jterm.core.MockTerminal(new io.jterm.core.TerminalSize(80, 24));
        var screen = new DefaultScreen(terminal);
        screen.startScreen();
        screen.setCell(0, 0, new TextCell('X', GOLD, null));
        screen.refresh();
        String full = terminal.getOutput();
        assertTrue(full.contains("\u001b[38;2;255;214;71m"), "golden truecolor paint: " + full);
    }

    @Test
    @DisplayName("DefaultScreen follows a mid-session downgrade on the SAME screen (live tier read)")
    void defaultScreenFollowsMidSessionDowngrade() throws Exception {
        var terminal = new io.jterm.core.MockTerminal(new io.jterm.core.TerminalSize(80, 24));
        var screen = new DefaultScreen(terminal);
        screen.startScreen();
        screen.setCell(0, 0, new TextCell('X', GOLD, null));
        screen.refresh();                       // golden truecolor paint
        terminal.clearOutput();                 // observe only the post-flip bytes
        terminal.setColorDepth(ColorDepth.C256);
        screen.setCell(1, 1, new TextCell('Y', new RgbColor(255, 0, 0), null));
        screen.refresh();                       // delta refresh against the live depth
        String delta = terminal.getOutput();
        assertTrue(delta.contains("\u001b[38;5;196m"), "the downgrade applies to the next paint: " + delta);
    }
}
