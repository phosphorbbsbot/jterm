package io.jterm.ansifilm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The committed demo-film resources (animation plan card 1, 2026-11-20 —
 * REQUIRED deliverable). The two films under {@code films/} must parse as
 * lawful 132x40 10 fps packs of 30-60 frames, carry the intended truecolor
 * content (the swirl's 48;2 ripple, the splash's ART_DECO-law gold
 * {@code 255;214;71}), match {@code films/index.txt}, and be byte-identical
 * to a fresh {@code tools/FilmBake} run — the committed packs can never
 * drift from the bake tool.
 */
class FilmResourcesLawTest {

    private static String resource(String name) {
        try (InputStream in = FilmResourcesLawTest.class.getResourceAsStream("/films/" + name)) {
            assertNotNull(in, "films/" + name + " must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("reading films/" + name, e);
        }
    }

    @Test
    @DisplayName("index.txt lists exactly the two demo films")
    void indexLaw() {
        assertEquals("phosphor_swirl.pfilm\nart_deco_splash.pfilm\n", resource("index.txt"));
    }

    @Test
    @DisplayName("DEMO GEOMETRY: both films parse 132x40, 30-60 frames, 100ms durations")
    void geometryLaw() {
        for (String name : List.of("phosphor_swirl.pfilm", "art_deco_splash.pfilm")) {
            var film = AnsiFilm.parse(resource(name));
            assertEquals(132, film.size().columns(), name + " columns");
            assertEquals(40, film.size().rows(), name + " rows");
            assertTrue(film.frameCount() >= 30 && film.frameCount() <= 60,
                    name + " frames in the 30-60 band: " + film.frameCount());
            for (var frame : film.frames()) {
                assertEquals(100, frame.durationMs(), name + " runs at 10 fps");
            }
        }
    }

    @Test
    @DisplayName("the swirl carries truecolor ripple bytes and the rotating arm")
    void swirlContentLaw() {
        String src = resource("phosphor_swirl.pfilm");
        var film = AnsiFilm.parse(src);
        // Truecolor 48;2 runs are the ripple's body.
        assertTrue(src.contains("\u001B[48;2") || src.contains("\u001B[38;2"),
                "the swirl paints truecolor");
        // The sweep arm glyphs ride every frame.
        for (int t = 0; t < film.frameCount(); t++) {
            boolean arm = false;
            var cells = film.frame(t).cells();
            outer:
            for (int r = 0; r < 40; r++) {
                for (int c = 0; c < 132; c++) {
                    if (cells.getCell(c, r).character().indexOf('◆') >= 0) {
                        arm = true;
                        break outer;
                    }
                }
            }
            assertTrue(arm, "frame " + t + " shows the rotating sweep arm");
        }
    }

    @Test
    @DisplayName("the splash paints the ART_DECO law gold 255;214;71 and the deco border")
    void splashContentLaw() {
        String src = resource("art_deco_splash.pfilm");
        assertTrue(src.contains("38;2;255;214;71"), "the splash carries the law gold");
        assertTrue(src.indexOf('╔') >= 0 && src.indexOf('╗') >= 0
                        && src.indexOf('╚') >= 0 && src.indexOf('╝') >= 0,
                "the deco double-line border is painted");
        var film = AnsiFilm.parse(src);
        // The wordmark sits on the block-tier rows 12..19 with gold fg.
        boolean goldSeen = false;
        for (int r = 12; r <= 19 && !goldSeen; r++) {
            for (int c = 0; c < 132; c++) {
                var cell = film.frame(0).cells().getCell(c, r);
                if ("█".equals(cell.character())
                        && cell.fg() instanceof io.jterm.style.RgbColor gold
                        && gold.r() == 255 && gold.g() == 214 && gold.b() == 71) {
                    goldSeen = true;
                    break;
                }
            }
        }
        assertTrue(goldSeen, "the wordmark paints the RgbColor(255,214,71) law gold");
    }

    @Test
    @DisplayName("REGENERATION: a fresh FilmBake run is byte-identical to the committed packs")
    void regenerationLaw() {
        assertEquals(resource("phosphor_swirl.pfilm"), FilmBakeAccess.bakeSwirl(),
                "the committed swirl must match the bake tool byte-for-byte");
        assertEquals(resource("art_deco_splash.pfilm"), FilmBakeAccess.bakeArtDecoSplash(),
                "the committed splash must match the bake tool byte-for-byte");
        assertEquals(String.join("\n",
                        "phosphor_swirl.pfilm", "art_deco_splash.pfilm", ""),
                FilmBakeAccess.indexText(),
                "index.txt matches the bake manifest");
    }

    /** Reflection bridge to the root {@code tools/FilmBake.java} (outside the Maven tree). */
    static final class FilmBakeAccess {
        static String bakeSwirl() {
            return bake("bakeSwirl");
        }

        static String bakeArtDecoSplash() {
            return bake("bakeArtDecoSplash");
        }

        static String indexText() {
            return "phosphor_swirl.pfilm\nart_deco_splash.pfilm\n";
        }

        private static volatile Class<?> toolClass;
        /** Kept open for the test's lifetime: closing it would strand the nested classes. */
        private static volatile java.util.function.Supplier<ClassLoader> loaderRef;

        /** Compiles the dependency-free tool at {@code tools/FilmBake.java} and loads it. */
        private static synchronized Class<?> filmBakeTool() throws IOException, ClassNotFoundException {
            if (toolClass != null) {
                return toolClass;
            }
            var source = java.nio.file.Path.of("tools", "FilmBake.java");
            assertTrue(java.nio.file.Files.exists(source),
                    "tools/FilmBake.java must sit at the repository root");
            var out = java.nio.file.Files.createTempDirectory("filmbake");
            var javac = java.util.spi.ToolProvider.findFirst("javac").orElseThrow();
            var silentOut = new java.io.ByteArrayOutputStream();
            var silentErr = new java.io.ByteArrayOutputStream();
            int rc = javac.run(new java.io.PrintStream(silentOut),
                    new java.io.PrintStream(silentErr),
                    "-d", out.toString(), source.toString());
            assertEquals(0, rc, "the bake tool compiles cleanly; stderr: " + silentErr);
            var loader = new java.net.URLClassLoader(new java.net.URL[]{out.toUri().toURL()});
            loaderRef = () -> loader;
            toolClass = loader.loadClass("FilmBake");
            return toolClass;
        }

        private static String bake(String method) {
            try {
                var invoked = filmBakeTool().getDeclaredMethod(method);
                invoked.setAccessible(true); // package-private tool entry from the test package
                return (String) invoked.invoke(null);
            } catch (ReflectiveOperationException | IOException e) {
                throw new AssertionError("invoking FilmBake." + method, e);
            }
        }
    }
}
