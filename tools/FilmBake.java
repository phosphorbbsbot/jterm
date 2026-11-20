import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * FilmBake — the pure-Java, headless bake tool for the pfilm demo packs
 * (animation plan card 1, 2026-11-20). Generates BOTH required demo films
 * (132x40, 10 fps truecolor frame packs) under
 * {@code src/main/resources/films/} plus the {@code index.txt} manifest the
 * BBS FilmLibrary uses to enumerate classpath films in packaged jars.
 *
 * <p>Run from the repository root (no build needed — this tool is
 * dependency-free pure Java):</p>
 *
 * <pre>
 *   java tools/FilmBake.java src/main/resources/films
 * </pre>
 *
 * <p><b>Laws baked in (asserted by {@code FilmResourcesLawTest}):</b></p>
 * <ul>
 *   <li><b>PHOSPHOR SWIRL</b> — {@code phosphor_swirl.pfilm}: a rotating
 *       truecolor radial swirl over the Phosphor ground dark; band colors
 *       ride the GROUND → gold ramp; the glyph ramp carries the texture.</li>
 *   <li><b>ART_DECO SPLASH</b> — {@code art_deco_splash.pfilm}: the gold
 *       ART_DECO law constant (255, 214, 71 — the {@code RgbColor(255,214,71)}
 *       law gold of the library's deco surfaces) paints a deco border, the
 *       PHOSPHOR wordmark and caption, and a marching accent that cycles the
 *       inner ring.</li>
 *   <li><b>DEMO GEOMETRY</b> — both films are 132x40 at 10 fps (100 ms per
 *       frame) with 30-60 frames, deterministic (same bake, same bytes).</li>
 * </ul>
 */
public final class FilmBake {

    /** The demo film geometry: full-width Phosphor frames at 10 fps. */
    static final int COLS = 132;
    static final int ROWS = 40;
    static final int DURATION_MS = 100;

    /** The ART_DECO law gold — jterm's RgbColor(255, 214, 71) constant. */
    static final int[] LAW_GOLD = {255, 214, 71};
    /** The phosphor ground dark (the doorway effect's GROUND). */
    static final int[] LAW_GROUND = {6, 8, 16};
    /** The sweep highlight warm cream (the doorway's SWEEP_CREAM). */
    static final int[] LAW_CREAM = {255, 249, 196};

    // ── film 1: the phosphor swirl ──────────────────────────────────────

    static final int SWIRL_FRAMES = 32;
    /** Dist-cells per ripple ring (the expanding pulse wavelength). */
    static final double SWIRL_RING_W = 28.0;
    /** Angular sectors of the rotating sweep arm. */
    static final int SWIRL_SECTORS = 18;
    /** The sweep arm's glyphs: bright head one sector behind. */
    static final char ARM_HEAD = '◆';
    static final char ARM_BODY = '▓';
    /** The shading glyph ramp for painted rings (index 0 is a blank — skipped). */
    static final String SWIRL_GLYPHS = " .-:=+*x#%";

    /**
     * The swirl: expanding truecolor ground → gold ripple rings over the
     * terminal's default black, with a bright CREAM sweep arm rotating around
     * the center (the phosphor radar). The ground cells stay UNPAINTED — the
     * pack only carries painted cells, so runs stay few and the file stays
     * git-friendly.
     */
    static String bakeSwirl() {
        List<String> bodies = new ArrayList<>();
        for (int t = 0; t < SWIRL_FRAMES; t++) {
            bodies.add(swirlBody(t));
        }
        return pack(SWIRL_FRAMES, bodies);
    }

    static String swirlBody(int t) {
        var w = new FrameWriter();
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                SwirlCell cell = swirlCellAt(x, y, t);
                if (cell.color() == null) {
                    w.glyph(' '); // ground: default-black, no paint state
                } else {
                    w.setFg(cell.color());
                    w.glyph(cell.glyph());
                }
            }
            w.reset();
            if (y < ROWS - 1) {
                w.glyph('\n');
            }
        }
        return w.sb.toString();
    }

    /** One computed swirl cell: a glyph with its color, or null = ground. */
    record SwirlCell(char glyph, int[] color) {
        static final SwirlCell GROUND = new SwirlCell(' ', null);
    }

    static SwirlCell swirlCellAt(int x, int y, int t) {
        double cx = (COLS - 1) / 2.0;
        double cy = (ROWS - 1) / 2.0;
        double dx = x - cx;
        double dy = (y - cy) * 2.15; // terminal cells are ~2x tall as wide
        double dist = Math.hypot(dx, dy);

        double angle = Math.atan2(dy, dx) / (2 * Math.PI); // 0..1
        if (angle < 0) {
            angle += 1.0;
        }
        int ring = (int) (dist / SWIRL_RING_W);
        int sector = (int) Math.round(angle * SWIRL_SECTORS) % SWIRL_SECTORS;
        int armSector = (SWIRL_SECTORS - 1 - t % SWIRL_SECTORS) % SWIRL_SECTORS;
        if (sector == armSector) {
            return new SwirlCell(ARM_HEAD, LAW_CREAM);
        }
        if (sector == (armSector + SWIRL_SECTORS - 1) % SWIRL_SECTORS) {
            return new SwirlCell(ARM_BODY, LAW_GOLD);
        }
        // The expanding ripple: every other ring paints, phase walks with t.
        if ((ring + t) % 2 != 0) {
            return SwirlCell.GROUND;
        }
        int[] color = GOLD_TONES[ring % GOLD_TONES.length];
        char glyph = SWIRL_GLYPHS.charAt(1 + (ring * 2) % (SWIRL_GLYPHS.length() - 1));
        return new SwirlCell(glyph, color);
    }

    /** The four fixed gold tone plates of the ripple rings (dim → bright). */
    private static final int[][] GOLD_TONES = {
            mix(LAW_GROUND, LAW_GOLD, 0.35),
            mix(LAW_GROUND, LAW_GOLD, 0.55),
            mix(LAW_GROUND, LAW_GOLD, 0.80),
            LAW_GOLD,
    };

    // ── film 2: the ART_DECO splash ─────────────────────────────────────

    static final int SPLASH_FRAMES = 34;
    /** The deco double-line border glyphs. */
    static final char DECO_H = '═';
    static final char DECO_V = '║';
    static final char DECO_TL = '╔';
    static final char DECO_TR = '╗';
    static final char DECO_BL = '╚';
    static final char DECO_BR = '╝';
    /** The marching accent: a bright head with a fading trail. */
    static final char ACCENT_HEAD = '◆';
    static final char ACCENT_TRAIL = '·';
    static final int ACCENT_TRAIL_LEN = 12;
    /** Accent cells advanced per frame along the inner ring (full lap in play length). */
    static final int ACCENT_STEP = 10;
    static final String TITLE = "PHOSPHOR";
    static final String CAPTION = "·  A  P F I L M   D E M O  ·";

    /**
     * The splash: gold deco border, the PHOSPHOR wordmark centered under a
     * marching cream sweep, the letterspaced caption, and the accent head with
     * its trail cycling the inner ring. Ground stays default-black.
     */
    static String bakeArtDecoSplash() {
        List<String> bodies = new ArrayList<>();
        for (int t = 0; t < SPLASH_FRAMES; t++) {
            bodies.add(splashBody(t));
        }
        return pack(SPLASH_FRAMES, bodies);
    }

    static String splashBody(int t) {
        var w = new FrameWriter();
        var ring = decoPath();
        int head = (t * ACCENT_STEP) % ring.size();

        var title = titleCells();
        int sweep = title.x0() + (t * 3) % title.width();

        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                char deco = decoCellType(x, y);
                if (deco != ' ') {
                    w.setFg(LAW_GOLD);
                    w.glyph(deco);
                    continue;
                }
                if (title.contains(x, y)) {
                    w.setFg(Math.abs(x - sweep) <= 1 ? LAW_CREAM : LAW_GOLD);
                    w.glyph('█');
                    continue;
                }
                if (y == CAPTION_ROW && x >= captionX0() && x < captionX0() + CAPTION.length()) {
                    w.setFg(LAW_GOLD);
                    w.glyph(CAPTION.charAt(x - captionX0()));
                    continue;
                }
                int ringIndex = ringIndexOf(x, y);
                if (ringIndex >= 0) {
                    int offset = head - ringIndex;
                    if (offset < 0) {
                        offset += ring.size();
                    }
                    if (offset == 0) {
                        w.setFg(LAW_CREAM);
                        w.glyph(ACCENT_HEAD);
                        continue;
                    }
                    if (offset <= ACCENT_TRAIL_LEN && offset % 2 == 0) {
                        w.setFg(LAW_GOLD);
                        w.glyph(ACCENT_TRAIL);
                        continue;
                    }
                }
                w.glyph(' '); // ground stays default-black
            }
            if (y < ROWS - 1) {
                w.glyph('\n');
            }
        }
        w.reset(); // the frame body closes on default attrs
        return w.sb.toString();
    }

    /** The wordmark rows start here (8 rows tall down to TITLE_TOP+7). */
    static final int TITLE_TOP = 12;
    static final int TITLE_CELL_W = 12; // 2x-width letter + 2 gap
    static final int CAPTION_ROW = 23;

    /** Left x of the centered caption. */
    static int captionX0() {
        return (COLS - CAPTION.length()) / 2;
    }

    /** The border glyph at a screen edge, or ' ' for interior ground. */
    static char decoCellType(int x, int y) {
        boolean leftEdge = x == 0;
        boolean rightEdge = x == COLS - 1;
        boolean topEdge = y == 0;
        boolean bottomEdge = y == ROWS - 1;
        if (topEdge && leftEdge) {
            return DECO_TL;
        }
        if (topEdge && rightEdge) {
            return DECO_TR;
        }
        if (bottomEdge && leftEdge) {
            return DECO_BL;
        }
        if (bottomEdge && rightEdge) {
            return DECO_BR;
        }
        if (topEdge || bottomEdge) {
            return DECO_H;
        }
        if (leftEdge || rightEdge) {
            return DECO_V;
        }
        return ' ';
    }

    /** The ordered ring the accent marches: margin-2, clockwise. */
    static List<int[]> decoPath() {
        var path = new ArrayList<int[]>();
        int left = 2, right = COLS - 3, top = 2, bottom = ROWS - 3;
        for (int x = left; x <= right; x++) {
            path.add(new int[]{x, top});
        }
        for (int y = top + 1; y <= bottom; y++) {
            path.add(new int[]{right, y});
        }
        for (int x = right - 1; x >= left; x--) {
            path.add(new int[]{x, bottom});
        }
        for (int y = bottom - 1; y > top; y--) {
            path.add(new int[]{left, y});
        }
        return path;
    }

    private static int ringIndexOf(int x, int y) {
        int left = 2, right = COLS - 3, top = 2, bottom = ROWS - 3;
        boolean on = (y == top || y == bottom) && x >= left && x <= right
                || (x == left || x == right) && y >= top && y <= bottom;
        if (!on) {
            return -1;
        }
        if (y == top) {
            return x - left;
        }
        if (x == right) {
            return (right - left) + (y - top);
        }
        if (y == bottom) {
            return (right - left) + (bottom - top) + (right - x);
        }
        return (right - left) + (bottom - top) + (right - left) + (bottom - y);
    }

    /** The placed wordmark cell set plus its layout geometry. */
    record TitleGrid(java.util.Set<Long> cells, int width, int x0) {
        boolean contains(int x, int y) {
            return cells.contains((long) y * 100_000L + x);
        }
    }

    /** The wordmark as solid cells: a 2x-width placement of the 5x8 letters. */
    static TitleGrid titleCells() {
        var set = new java.util.LinkedHashSet<Long>();
        int width = TITLE.length() * TITLE_CELL_W - 2;
        int x0 = (COLS - width) / 2;
        for (int li = 0; li < TITLE.length(); li++) {
            int[][] bitmap = wordmarkLetter(TITLE.charAt(li));
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 5; c++) {
                    if (bitmap[r][c] == 1) {
                        int px = x0 + li * TITLE_CELL_W + c * 2;
                        set.add(key(px, TITLE_TOP + r));
                        set.add(key(px + 1, TITLE_TOP + r));
                    }
                }
            }
        }
        return new TitleGrid(set, width, x0);
    }

    private static long key(int x, int y) {
        return (long) y * 100_000L + x;
    }

    // ── pack assembly ───────────────────────────────────────────────────

    /** Assembles one pack: the header, then FRAME/body/END per frame. */
    private static String pack(int frames, List<String> bodies) {
        var sb = new StringBuilder();
        sb.append("FORMAT pfilm1\nCOUNT ").append(frames)
                .append("\nCOLS ").append(COLS).append(" ROWS ").append(ROWS).append('\n');
        for (String body : bodies) {
            sb.append("FRAME ").append(DURATION_MS).append('\n').append(body).append("\nEND\n");
        }
        return sb.toString();
    }

    /**
     * Paints one frame body; the brush state rides the writer. Emits an SGR
     * only when the cell's fg/bg differs from the scanner's current brush.
     */
    static final class FrameWriter {
        final StringBuilder sb = new StringBuilder();
        int[] fg;
        int[] bg;

        /** Sets the foreground brush; null = terminal default fg. */
        void setFg(int[] color) {
            if (!same(fg, color)) {
                sb.append(color == null ? "\u001B[39m" : sgr("38;2", color));
                fg = color;
            }
        }

        /** Sets the background brush; null = terminal default bg. */
        void setBg(int[] color) {
            if (!same(bg, color)) {
                sb.append(color == null ? "\u001B[49m" : sgr("48;2", color));
                bg = color;
            }
        }

        /** Closes any paint state: back to terminal default attributes. */
        void reset() {
            if (fg != null || bg != null) {
                sb.append("\u001B[0m");
                fg = null;
                bg = null;
            }
        }

        void glyph(char c) {
            sb.append(c);
        }

        private static boolean same(int[] a, int[] b) {
            if (a == b) {
                return true;
            }
            if (a == null || b == null) {
                return false;
            }
            return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
        }

        private static String sgr(String prefix, int[] color) {
            return "\u001B[" + prefix + ";" + color[0] + ";" + color[1] + ";" + color[2] + "m";
        }
    }

    // ── shared helpers ──────────────────────────────────────────────────

    private static int[] mix(int[] from, int[] to, double k) {
        return new int[]{
                (int) Math.round(from[0] + (to[0] - from[0]) * k),
                (int) Math.round(from[1] + (to[1] - from[1]) * k),
                (int) Math.round(from[2] + (to[2] - from[2]) * k)};
    }

    // ── wordmark letter bitmaps (the doorway's P-H-O-S-R set) ───────────

    private static final int[][] LETTER_P = {
            {1, 1, 1, 1, 0}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 1, 1, 1, 0},
            {1, 0, 0, 0, 0}, {1, 0, 0, 0, 0}, {1, 0, 0, 0, 0}, {1, 0, 0, 0, 0}};
    private static final int[][] LETTER_H = {
            {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 1, 1, 1, 1},
            {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}};
    private static final int[][] LETTER_O = {
            {0, 1, 1, 1, 0}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1},
            {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {0, 1, 1, 1, 0}};
    private static final int[][] LETTER_S = {
            {0, 1, 1, 1, 1}, {1, 0, 0, 0, 0}, {1, 0, 0, 0, 0}, {0, 1, 1, 1, 0},
            {0, 0, 0, 0, 1}, {0, 0, 0, 0, 1}, {0, 0, 0, 0, 1}, {1, 1, 1, 1, 0}};
    private static final int[][] LETTER_R = {
            {1, 1, 1, 1, 0}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}, {1, 1, 1, 1, 0},
            {1, 0, 1, 0, 0}, {1, 0, 0, 1, 0}, {1, 0, 0, 0, 1}, {1, 0, 0, 0, 1}};

    private static int[][] wordmarkLetter(char letter) {
        return switch (letter) {
            case 'P' -> LETTER_P;
            case 'H' -> LETTER_H;
            case 'O' -> LETTER_O;
            case 'S' -> LETTER_S;
            case 'R' -> LETTER_R;
            case ' ' -> new int[8][5];
            default -> throw new IllegalArgumentException("no wordmark bitmap for '" + letter + "'");
        };
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "src/main/resources/films");
        Files.createDirectories(out);
        List<String> names = new ArrayList<>();

        String swirl = bakeSwirl();
        Files.writeString(out.resolve("phosphor_swirl.pfilm"), swirl, StandardCharsets.UTF_8);
        System.out.printf("phosphor_swirl.pfilm  %d frames, %,d bytes%n",
                SWIRL_FRAMES, swirl.getBytes(StandardCharsets.UTF_8).length);
        names.add("phosphor_swirl.pfilm");

        String splash = bakeArtDecoSplash();
        Files.writeString(out.resolve("art_deco_splash.pfilm"), splash, StandardCharsets.UTF_8);
        System.out.printf("art_deco_splash.pfilm %d frames, %,d bytes%n",
                SPLASH_FRAMES, splash.getBytes(StandardCharsets.UTF_8).length);
        names.add("art_deco_splash.pfilm");

        Files.writeString(out.resolve("index.txt"), String.join("\n", names) + "\n", StandardCharsets.UTF_8);
        System.out.println("index.txt written with " + names.size() + " film(s).");
    }

    private FilmBake() {
    }
}
