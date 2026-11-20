package io.jterm.ansifilm;

import io.jterm.core.ColorDepth;
import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalSize;
import io.jterm.screen.DefaultScreen;
import io.jterm.style.IndexedColor;
import io.jterm.window.DefaultTextGUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SUBCELL playback laws (animation plan card 4, 2026-11-20): half-block
 * frames replay through {@link io.jterm.screen.HalfBlockBuffer} at 2×
 * vertical density.
 *
 * <ul>
 *   <li><b>SUBCELL LAW</b> — a marked frame's declared rows are SUB-ROWS:
 *       before emission the raster re-encodes through the half-block buffer
 *       at the terminal's declared {@link ColorDepth} (mapped BEFORE encode:
 *       a pair that degrades equal collapses to the FULL BLOCK law in the
 *       cell itself). An unmarked frame plays exactly as parsed.</li>
 *   <li><b>RASTER LAW</b> — a sub-cell's color rides its BACKGROUND (a
 *       colored ground band); a default-band cell rides its fg (glyph-painted
 *       art); fully default cells are transparent. A SUBCELL frame is a
 *       color field — body glyphs carry no text of their own.</li>
 *   <li><b>CAPABILITY LAW</b> — the honor hides behind an internal seam;
 *       without the half-block layer a SUBCELL marker plays NORMAL
 *       (byte-identical to the unmarked twin), the law that let the pfilm
 *       card land ahead of this one.</li>
 *   <li><b>SHAPE LAW</b> — a frame whose grid shape differs from the
 *       previous one emits a full repaint (the delta law cannot walk across
 *       a size change; mixed SUBCELL/normal packs rely on it).</li>
 *   <li><b>PHANTOM LAW</b> — consecutive frames re-parse fresh RgbColor
 *       refs, and TextCell.equals compares colors by reference, so a
 *       mapped-identical encoded pair can appear in the delta as a
 *       cursor-only, SGR-less glyph re-put. The SEQUENCE law of the
 *       half-block delta holds (zero redundant SGR bytes); a future
 *       TextCell.equals color-equals fix consciously updates these goldens.
 *       At palette tiers the mapped colors are enum refs, so the phantom
 *       vanishes — no bytes at all for a mapped-identical pair.</li>
 *   <li><b>REGRESSION LAW</b> — unmarked films are untouched: their golden
 *       bytes ride {@code AnsiFilmScreenTest} unchanged, and the chart
 *       renderer sees no refactor (the buffer is additive).</li>
 * </ul>
 */
class AnsiFilmScreenSubcellTest {

    private static final String ESC = "\u001B";
    private static final String GOLD = ESC + "[38;2;255;214;71m";
    private static final String NEAR_GOLD = ESC + "[38;2;250;210;80m";
    private static final String BG_GOLD = ESC + "[48;2;255;214;71m";
    private static final String BG_BLUE = ESC + "[48;2;0;64;192m";
    private static final String BG_NEAR_GOLD = ESC + "[48;2;250;210;80m";

    /** Smoke film: 3 SUBCELL frames, 2 cols × 4 SUB-ROWS (⇒ 2 cell rows). */
    private static final String FILM_S = String.join("\n",
            "FORMAT pfilm1",
            "COUNT 3",
            "COLS 2 ROWS 4",
            "FRAME 100",
            "SUBCELL",
            BG_GOLD + "  ",                       // sub-row 0: gold  gold
            BG_BLUE + " " + BG_GOLD + " ",        // sub-row 1: blue  gold
            "",                                   // sub-row 2: transparent
            "",                                   // sub-row 3: transparent
            "END",
            "FRAME 120",
            "SUBCELL",
            BG_GOLD + "  ",                       // sub-row 0: gold  gold
            BG_GOLD + "  ",                       // sub-row 1: gold  gold
            "",                                   // sub-row 2: transparent
            "",                                   // sub-row 3: transparent
            "END",
            "FRAME 200",
            "SUBCELL",
            ESC + "[0m  ",                        // sub-row 0: transparent
            ESC + "[0m  ",                        // sub-row 1: transparent
            "",                                   // sub-row 2: transparent
            "",                                   // sub-row 3: transparent
            "END");

    /** Frame 1 golden of FILM_S at truecolor: ▀/█/empty by the block law. */
    private static final String FILM_S_FRAME_1 = ESC + "[?7l"
            + ESC + "[1;1H" + ESC + "[0m"
            + ESC + "[38;2;255;214;71m" + ESC + "[48;2;0;64;192m" + "\u2580"
            + ESC + "[49m" + "\u2588"
            + ESC + "[2;1H" + ESC + "[0m" + "  "
            + ESC + "[?7h";
    /** Frame 2: the collapsed pair rides the default-canvas fg re-arm…
     *  and (1,0)'s phantom re-put (fresh parse refs) carries ZERO SGR. */
    private static final String FILM_S_FRAME_2_TRUECOLOR =
            ESC + "[1;1H" + ESC + "[38;2;255;214;71m" + "\u2588" + "\u2588";
    /** Frame 3: every pair cleared — the single-reset shortcut, contiguous. */
    private static final String FILM_S_FRAME_3 =
            ESC + "[1;1H" + ESC + "[0m" + "  ";

    private record Rig(MockTerminal terminal, DefaultTextGUI gui) {
        AnsiFilmScreen screen(AnsiFilm film, Runnable onExit) {
            return new AnsiFilmScreen(film, gui, onExit);
        }
    }

    private static Rig rig() {
        var terminal = new MockTerminal(new TerminalSize(4, 3));
        var gui = new DefaultTextGUI(new DefaultScreen(terminal));
        return new Rig(terminal, gui);
    }

    private static AnsiFilm film(String src) {
        return AnsiFilm.parse(src);
    }

    private static String repr(String s) {
        return s.replace("\u001B", "<ESC>");
    }

    @AfterEach
    void restoreCapabilitySeam() {
        AnsiFilmScreen.subcellCapable = () -> true;
    }

    // ── encoding laws ───────────────────────────────────────────────────

    @Test
    @DisplayName("GOLDEN: the 3-frame SUBCELL film ticks — encode, mapped delta, single-reset clear")
    void goldenSubcellSmoke() throws Exception {
        var r = rig();
        var screen = r.screen(film(FILM_S), () -> { });

        screen.tick();
        assertEquals(FILM_S_FRAME_1, r.terminal().getOutput(),
                "frame 1 encodes by the block law: " + repr(r.terminal().getOutput()));

        r.terminal().clearOutput();
        screen.tick();
        assertEquals(FILM_S_FRAME_2_TRUECOLOR, r.terminal().getOutput(),
                "frame 2 is a delta: one bg transition, zero SGR for the mapped-equal pair "
                        + repr(r.terminal().getOutput()));

        r.terminal().clearOutput();
        screen.tick();
        assertEquals(FILM_S_FRAME_3, r.terminal().getOutput(),
                "frame 3 clears by the single-reset shortcut: " + repr(r.terminal().getOutput()));
    }

    @Test
    @DisplayName("SMOKE: the 3-frame SUBCELL film REPLAYS end-to-end at 2× density with one hand-off")
    void playbackSubcellIntegrationLaw() throws Exception {
        var r = rig();
        AtomicInteger exits = new AtomicInteger();
        var screen = r.screen(film(FILM_S), exits::incrementAndGet);
        var clock = new ScriptedClock();
        var input = new ScriptedInput(() -> { });

        screen.playback(clock, input);

        assertEquals(List.of(100L, 120L, 200L), input.timeouts,
                "the loop awaited the SUBCELL frames' declared durations: " + input.timeouts);
        assertEquals(1, exits.get(), "the film end fires the exit exactly once");
        assertEquals(FILM_S_FRAME_1 + FILM_S_FRAME_2_TRUECOLOR + FILM_S_FRAME_3,
                r.terminal().getOutput(),
                "the whole replay is the three encoded frames: " + repr(r.terminal().getOutput()));
    }

    @Test
    @DisplayName("CAPABILITY: without the half-block layer a SUBCELL marker plays normal (twin bytes)")
    void capabilitySeamLaw() throws Exception {
        String normalBody = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 2 ROWS 2",
                "FRAME 100",
                BG_GOLD + "  ",
                ESC + "[0m  ",
                "END");
        String marked = normalBody.replace("FRAME 100\n", "FRAME 100\nSUBCELL\n");

        // With the capability (the default after @AfterEach restore):
        var r3 = rig();
        r3.screen(film(marked), () -> { }).tick();
        String markedWithCap = r3.terminal().getOutput();
        assertTrue(markedWithCap.contains("\u2580"),
                "the honor encodes half-blocks when the buffer is present: " + repr(markedWithCap));

        // Without the capability: the marker is invisible — bytes of the twin.
        AnsiFilmScreen.subcellCapable = () -> false;
        try {
            var r4 = rig();
            r4.screen(film(marked), () -> { }).tick();
            String markedWithoutCap = r4.terminal().getOutput();

            var r5 = rig();
            r5.screen(film(normalBody), () -> { }).tick();
            String unmarked = r5.terminal().getOutput();

            assertEquals(unmarked, markedWithoutCap,
                    "marker + no capability plays normal: " + repr(markedWithoutCap));
            assertFalse(markedWithoutCap.contains("\u2580") || markedWithoutCap.contains("\u2588"),
                    "no half-block bytes without the capability: " + repr(markedWithoutCap));
            assertEquals(ESC + "[?7l"
                            + ESC + "[1;1H" + ESC + "[0m" + BG_GOLD + "  "
                            + ESC + "[2;1H" + ESC + "[0m" + "  "
                            + ESC + "[?7h",
                    markedWithoutCap,
                    "the normal-emission shape is pinned: " + repr(markedWithoutCap));
        } finally {
            AnsiFilmScreen.subcellCapable = () -> true;
        }
    }

    @Test
    @DisplayName("ODD HEIGHTS: a 3-sub-row film encodes to 2 rows; the partnerless bottom is transparent")
    void oddSubrowsLaw() throws Exception {
        String src = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 1 ROWS 3",
                "FRAME 100",
                "SUBCELL",
                GOLD + "\u2588",        // sub-row 0: fg glyph paint → gold
                BG_BLUE + " ",          // sub-row 1: bg band paint → blue
                ESC + "[0m ",           // sub-row 2: transparent (partnerless bottom)
                "END");
        var r = rig();
        r.screen(film(src), () -> { }).tick();

        String out = r.terminal().getOutput();
        assertEquals(ESC + "[?7l"
                        + ESC + "[1;1H" + ESC + "[0m" + GOLD + BG_BLUE + "\u2580"
                        + ESC + "[2;1H" + ESC + "[0m" + " "
                        + ESC + "[?7h",
                out,
                "the odd grid paints by the top-half/space law: " + repr(out));
        assertFalse(out.contains("\u2584"),
                "a missing bottom half never fabricates a ▄: " + repr(out));
        assertFalse(out.contains(ESC + "[3;1H"),
                "the encoded grid is 2 cell rows, not 3: " + repr(out));
    }

    @Test
    @DisplayName("SHAPE: a normal frame after a SUBCELL frame is a FULL repaint, not a cross-shape delta")
    void mixedFilmShapeLaw() throws Exception {
        String src = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 2",
                "COLS 2 ROWS 2",
                "FRAME 100",
                "SUBCELL",
                BG_GOLD + "  ",        // 2×2 sub-rows → a 2×1 encoded grid
                ESC + "[0m  ",
                "END",
                "FRAME 120",
                "Hi",                  // a regular 2×2 grid
                "+X",
                "END");
        var r = rig();
        var screen = r.screen(film(src), () -> { });

        screen.tick();
        assertEquals(ESC + "[?7l"
                        + ESC + "[1;1H" + ESC + "[0m" + GOLD + "\u2580" + "\u2580"
                        + ESC + "[?7h",
                r.terminal().getOutput(),
                "the SUBCELL frame encodes to one cell row: both pairs ride the "
                        + "top-half law (gold over transparent): " + repr(r.terminal().getOutput()));

        r.terminal().clearOutput();
        screen.tick();
        assertEquals(ESC + "[?7l"
                        + ESC + "[1;1H" + ESC + "[0m" + "Hi"
                        + ESC + "[2;1H" + "+X"
                        + ESC + "[?7h",
                r.terminal().getOutput(),
                "the shape change is a full repaint with a fresh tracker reset: "
                        + repr(r.terminal().getOutput()));
    }

    // ── depth laws ──────────────────────────────────────────────────────

    @Test
    @DisplayName("DEGRADE: SUBCELL frames map to the terminal tier BEFORE encode — palette glyphs, no truecolor bytes")
    void ansi16SubcellLaw() throws Exception {
        var r = rig();
        r.terminal().setColorDepth(ColorDepth.ANSI16);
        var screen = r.screen(film(FILM_S), () -> { });

        screen.tick();
        String out = r.terminal().getOutput();
        // gold→BRIGHT_YELLOW (93), blue→BLUE (bg 44); the (1,0) pair degrades
        // equal and collapses to █ (one SGR) — mapped BEFORE encode.
        assertEquals(ESC + "[?7l"
                        + ESC + "[1;1H" + ESC + "[0m"
                        + ESC + "[93m" + ESC + "[44m" + "\u2580"
                        + ESC + "[49m" + "\u2588"
                        + ESC + "[2;1H" + ESC + "[0m" + "  "
                        + ESC + "[?7h",
                out,
                "the mapped encode law at ANSI16: " + repr(out));
        assertFalse(out.contains("38;2;") || out.contains("48;2;"),
                "no truecolor bytes below the declared tier: " + repr(out));

        r.terminal().clearOutput();
        screen.tick();
        assertEquals(ESC + "[1;1H" + ESC + "[93m" + "\u2588",
                r.terminal().getOutput(),
                "at a palette tier the mapped colors are enum refs: the mapped-equal pair "
                        + "emits no bytes at all, and the already-default canvas needs no bg "
                        + "sequence: " + repr(r.terminal().getOutput()));
    }

    @Test
    @DisplayName("MAPPED COLLAPSE: a pair degrading equal is ▀/split at truecolor but collapsed █ at ANSI16 and C256")
    void mappedCollapseLaw() throws Exception {
        String src = String.join("\n",
                "FORMAT pfilm1",
                "COUNT 1",
                "COLS 1 ROWS 2",
                "FRAME 100",
                "SUBCELL",
                GOLD + " ",          // top half: gold
                NEAR_GOLD + " ",     // bottom half: maps-equal near-gold
                "END");

        var truecolor = rig();
        truecolor.screen(film(src), () -> { }).tick();
        String tc = truecolor.terminal().getOutput();
        assertEquals(ESC + "[?7l"
                        + ESC + "[1;1H" + ESC + "[0m"
                        + GOLD + BG_NEAR_GOLD + "\u2580"
                        + ESC + "[?7h",
                tc,
                "distinct truecolor pairs split: " + repr(tc));

        var ansi16 = rig();
        ansi16.terminal().setColorDepth(ColorDepth.ANSI16);
        ansi16.screen(film(src), () -> { }).tick();
        String a16 = ansi16.terminal().getOutput();
        assertEquals(ESC + "[?7l"
                        + ESC + "[1;1H" + ESC + "[0m"
                        + ESC + "[93m" + "\u2588"
                        + ESC + "[?7h",
                a16,
                "ANSI16 collapses the mapped-equal pair to one FULL BLOCK + one SGR: " + repr(a16));

        var c256 = rig();
        c256.terminal().setColorDepth(ColorDepth.C256);
        c256.screen(film(src), () -> { }).tick();
        String c = c256.terminal().getOutput();
        var gold = new IndexedColor(IndexedColor.fromRgb(255, 214, 71).index());
        var near = new IndexedColor(IndexedColor.fromRgb(250, 210, 80).index());
        assertEquals(gold.index(), near.index(),
                "the law pair degrades to one 256-cube index");
        assertEquals(ESC + "[?7l"
                        + ESC + "[1;1H" + ESC + "[0m"
                        + ESC + "[38;5;" + gold.index() + "m" + "\u2588"
                        + ESC + "[?7h",
                c,
                "C256 collapses the mapped-equal pair too: " + repr(c));

        assertNotEquals(tc, a16, "the tier changes the collapse: ▀ split vs █ single");
    }
}
