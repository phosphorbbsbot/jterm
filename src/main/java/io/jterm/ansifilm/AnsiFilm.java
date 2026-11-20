package io.jterm.ansifilm;

import io.jterm.core.TerminalSize;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import io.jterm.style.Color;
import io.jterm.style.IndexedColor;
import io.jterm.style.RgbColor;
import io.jterm.style.SGR;
import io.jterm.style.TextCell;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The pfilm frame pack (animation plan card 1, 2026-11-20): a TEXT film
 * format, git-friendly, that plays on the {@link AnsiFilmScreen}.
 *
 * <p><b>Format (pfilm1):</b></p>
 *
 * <pre>
 *   FORMAT pfilm1
 *   COUNT &lt;frames&gt;
 *   COLS &lt;c&gt; ROWS &lt;r&gt;
 *   FRAME &lt;dur_ms&gt;        (repeat to COUNT; optionally a SUBCELL line next)
 *   SUBCELL               (optional; reserved for the half-block card)
 *   &lt;raw ANSI body&gt;       (SGR-only: truecolor 38;2/48;2 allowed)
 *   END
 * </pre>
 *
 * <p><b>Laws (tests: {@code AnsiFilmTest}):</b></p>
 * <ul>
 *   <li><b>PURE TEXT</b> — the parser takes the parse input and nothing else:
 *       no filesystem, no clock, no terminal.</li>
 *   <li><b>HEADER LAW</b> — the three header lines are mandatory and ordered;
 *       dims must be positive; COUNT may be 0 (an empty film). The actual
 *       frame count must equal COUNT.</li>
 *   <li><b>DIMS LAW</b> — a body PAINT outside the declared COLS/ROWS is an
 *       error. The cursor may walk past the margin freely (a trailing advance
 *       paints nothing) — the law binds paints, not walks.</li>
 *   <li><b>STRICT-DIRECTIVE LAW</b> — an unknown directive line is an ERROR,
 *       never skipped silently.</li>
 *   <li><b>DURATION LAW</b> — a frame duration is a positive integer.</li>
 *   <li><b>PAINTER LAW</b> — bodies are painterly: SGR styling sequences and
 *       printable glyphs (plus CR/LF/TAB cursor motion). Any other CSI final
 *       byte (cursor addressing, erase, mode set), a lone or truncated ESC,
 *       and every remaining control character are rejected — art cannot
 *       reposition the player's cursor or inject control traffic onto the
 *       wire.</li>
 *   <li><b>RESERVED LINES</b> — a body line that is exactly {@code END}
 *       terminates the frame and a body line exactly {@code SUBCELL} is an
 *       error (the half-block reserve): art needing either as text puts a
 *       space after it.</li>
 * </ul>
 *
 * <p>Frame bodies rasterize into a {@link ScreenBuffer} of the declared dims
 * at parse time; unpainted cells stay {@link TextCell#EMPTY}. Playback deltas
 * are then pure buffer diffs (see {@code AnsiFilmScreen}).</p>
 */
public final class AnsiFilm {

    /** The one format tag this parser accepts. */
    public static final String FORMAT_TAG = "pfilm1";

    private final TerminalSize size;
    private final List<Frame> frames;

    private AnsiFilm(TerminalSize size, List<Frame> frames) {
        this.size = size;
        this.frames = List.copyOf(frames);
    }

    /**
     * A single film frame: its declared duration, the half-block reserve
     * marker, and the rasterized cell grid (a {@link ScreenBuffer} of the
     * film's declared dims).
     *
     * @param durationMs the frame's declared display duration in milliseconds
     * @param subcell    true when the pack marked this frame SUBCELL (the
     *                   half-block card's sub-cell resolution contract)
     * @param cells      the rasterized frame body, sized to the film dims
     */
    public record Frame(int durationMs, boolean subcell, ScreenBuffer cells) {

        /**
         * Returns the cell at the given position of this frame's grid.
         *
         * @param col zero-indexed column
         * @param row zero-indexed row
         * @return the cell, or {@link TextCell#EMPTY} when out of bounds
         */
        public TextCell cell(int col, int row) {
            return cells.getCell(col, row);
        }
    }

    /**
     * Raised for every malformed pack: unknown directives, header/count/dims
     * mismatches, bad durations, non-painterly escapes. Never a silent skip.
     */
    public static final class FormatException extends IllegalArgumentException {
        FormatException(int lineNo, String message) {
            super("pfilm line " + lineNo + ": " + message);
        }
    }

    /**
     * Parses a full pack text into a film.
     *
     * @param source the entire pack file as text
     * @return the parsed film (immutable)
     * @throws FormatException on any law violation above
     */
    public static AnsiFilm parse(String source) {
        if (source == null) {
            throw new FormatException(0, "missing pack source");
        }
        var lines = source.split("\n", -1);
        int lineNo = 0;
        int count;
        TerminalSize size;

        // ── the three header lines, in order ──
        expectDirective(lines, lineNo, "FORMAT");
        if (!FORMAT_TAG.equals(headerArgument(lines[0]))) {
            throw new FormatException(lineNo + 1, "FORMAT must be " + FORMAT_TAG);
        }
        lineNo++;
        count = intValue(headerArgumentOrDie(lines, lineNo, "COUNT"), lineNo + 1);
        lineNo++;
        size = parseHeaderDims(lines[lineNo], lineNo + 1);
        lineNo++;

        // ── frames ──
        List<Frame> frames = new ArrayList<>();
        FrameBuilder open = null;
        while (lineNo < lines.length) {
            String raw = lines[lineNo];
            String trimmed = stripTrailingCr(raw).strip();

            if (open != null) {
                // ── inside a frame body ──
                if (trimmed.equals("END")) {
                    frames.add(open.build());
                    open = null;
                    lineNo++;
                    continue;
                }
                if (trimmed.equals("SUBCELL")) {
                    open.markSubcell(lineNo + 1);
                    lineNo++;
                    continue;
                }
                if (open.bodyLineCount() == 0 && directiveShape(trimmed) != null) {
                    throw new FormatException(lineNo + 1,
                            "directive '" + directiveShape(trimmed)
                                    + "' inside a frame body (empty body before the next directive)");
                }
                open.bodyLine(stripTrailingCr(raw), lineNo);
                lineNo++;
                continue;
            }

            // ── between frames ──
            if (trimmed.isEmpty()) {
                lineNo++; // blank gap between frames
                continue;
            }
            String directive = directiveShape(trimmed);
            if (directive == null) {
                throw new FormatException(lineNo + 1, "unknown directive: '" + trimmed + "'");
            }
            if (!"FRAME".equals(directive)) {
                throw new FormatException(lineNo + 1,
                        "directive '" + directive + "' outside the header or a frame body");
            }
            if (frames.size() >= count) {
                throw new FormatException(lineNo + 1,
                        "COUNT mismatch: header declares " + count
                                + " frame(s) but a frame " + (frames.size() + 1) + " begins");
            }
            String[] tokens = trimmed.split("\\s+");
            int dur = intValue(tokens.length > 1 ? tokens[1] : "", lineNo + 1);
            if (dur <= 0) {
                throw new FormatException(lineNo + 1, "frame duration must be positive, got: " + dur);
            }
            open = new FrameBuilder(size, dur);
            lineNo++;
        }
        if (open != null) {
            throw new FormatException(lineNo, "frame " + (frames.size() + 1)
                    + " never ends with an END line");
        }
        if (frames.size() != count) {
            throw new FormatException(lineNo, "COUNT mismatch: header declares " + count
                    + " frame(s) but " + frames.size() + " are present");
        }
        return new AnsiFilm(size, frames);
    }

    // ── accessors ───────────────────────────────────────────────────────

    /** @return the film's declared dims. */
    public TerminalSize size() {
        return size;
    }

    /** @return the number of frames (equal to the header COUNT). */
    public int frameCount() {
        return frames.size();
    }

    /**
     * Returns one frame.
     *
     * @param index zero-indexed frame number
     * @return the frame
     * @throws IndexOutOfBoundsException past the frame count
     */
    public Frame frame(int index) {
        return frames.get(index);
    }

    /** @return all frames, in play order. */
    public List<Frame> frames() {
        return frames;
    }

    // ── parse internals ─────────────────────────────────────────────────

    private static void expectDirective(String[] lines, int lineNo, String key) {
        if (lineNo >= lines.length || !key.equals(lines[lineNo].strip().split("\\s+")[0])) {
            throw new FormatException(lineNo + 1, "the required " + key + " line is missing");
        }
    }

    private static String headerArgument(String headerLine) {
        String[] tokens = headerLine.strip().split("\\s+");
        return tokens.length > 1 ? tokens[1] : "";
    }

    private static String headerArgumentOrDie(String[] lines, int lineNo, String key) {
        expectDirective(lines, lineNo, key);
        return headerArgument(lines[lineNo]);
    }

    /**
     * Parses "COLS c ROWS r" by KEY (never by position): both keys must be
     * present with positive integer values; anything else is a format error.
     */
    private static TerminalSize parseHeaderDims(String rawLine, int lineNo) {
        String[] tokens = rawLine.strip().split("\\s+");
        if (tokens.length < 2 || !"COLS".equals(tokens[0])) {
            throw new FormatException(lineNo, "the required COLS/ROWS line is missing");
        }
        String rowsKey = tokens.length >= 4 ? tokens[2] : "";
        if (!"ROWS".equals(rowsKey)) {
            throw new FormatException(lineNo, "the required COLS/ROWS line is missing (ROWS)");
        }
        int cols = intValue(tokens[1], lineNo);
        int rows = intValue(tokens[3], lineNo);
        if (cols <= 0 || rows <= 0) {
            throw new FormatException(lineNo, "dims must be positive: " + cols + "x" + rows);
        }
        return new TerminalSize(cols, rows);
    }

    /**
     * The directive word a line begins with, or null when the line is not
     * directive-shaped: FRAME / FORMAT / COUNT / COLS / SUBCELL / END.
     * Header directives are header-only; inside bodies the reserve
     * (first-line) law applies — see the class javadoc.
     */
    private static String directiveShape(String trimmedLine) {
        String[] tokens = trimmedLine.split("\\s+");
        if (tokens.length == 0) {
            return null;
        }
        return switch (tokens[0]) {
            case "FRAME", "FORMAT", "COUNT", "COLS", "SUBCELL", "END" -> tokens[0];
            default -> null;
        };
    }

    private static int intValue(String token, int lineNo) {
        if (token == null || token.isBlank()) {
            throw new FormatException(lineNo, "expected an integer, got nothing");
        }
        try {
            return Integer.parseInt(token.trim());
        } catch (NumberFormatException e) {
            throw new FormatException(lineNo, "expected an integer, got: '" + token + "'");
        }
    }

    private static String stripTrailingCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    // ── frame body rasterizer ───────────────────────────────────────────

    private static final class FrameBuilder {
        private final ScreenBuffer cells;
        private final int cols;
        private final int rows;
        private final int durationMs;
        private int col;
        private int row;
        private Color fg = AnsiColor.DEFAULT;
        private Color bg = AnsiColor.DEFAULT;
        private EnumSet<SGR> mods = EnumSet.noneOf(SGR.class);
        private boolean subcell;
        private int bodyLineCount;
        private int lineNo;

        FrameBuilder(TerminalSize size, int durationMs) {
            this.cells = new ScreenBuffer(size);
            this.cols = size.columns();
            this.rows = size.rows();
            this.durationMs = durationMs;
        }

        int bodyLineCount() {
            return bodyLineCount;
        }

        void markSubcell(int atLine) {
            if (subcell || bodyLineCount > 0) {
                throw new FormatException(atLine + 1,
                        "SUBCELL must be the line right after FRAME");
            }
            subcell = true;
        }

        void bodyLine(String line, int atLine) {
            lineNo = atLine;
            bodyLineCount++;
            int i = 0;
            int len = line.length();
            while (i < len) {
                char ch = line.charAt(i);
                if (ch == 0x1B) {
                    i = readEscape(line, i);
                    continue;
                }
                if (ch == '\r') {
                    col = 0;
                    i++;
                    continue;
                }
                if (ch == '\t') {
                    col = ((col / 8) + 1) * 8;
                    i++;
                    continue;
                }
                if (ch < 32 || ch == 127) {
                    throw new FormatException(atLine + 1,
                            "control character 0x" + Integer.toHexString(ch)
                                    + " in a frame body is not painterly");
                }
                paint(String.valueOf(ch));
                col++;
                i++;
            }
            // Each body line paints ONE art row; the cursor always resumes on
            // the next row's start (the line-split stream law: an implicit LF
            // ends every body line).
            row++;
            col = 0;
        }

        /** Consumes one escape starting at {@code at}; returns the index past it. */
        private int readEscape(String line, int at) {
            int i = at + 1;
            if (i >= line.length()) {
                throw new FormatException(lineNo + 1, "lone ESC at end of a frame body");
            }
            if (line.charAt(i) != '[') {
                throw new FormatException(lineNo + 1,
                        "escape sequence not CSI (ESC followed by '"
                                + line.charAt(i) + "') is not painterly");
            }
            i++;
            while (i < line.length()) {
                char c = line.charAt(i);
                if (c >= 0x40 && c <= 0x7E) {
                    break;
                }
                i++;
            }
            if (i >= line.length()) {
                throw new FormatException(lineNo + 1, "truncated escape sequence");
            }
            if (line.charAt(i) != 'm') {
                throw new FormatException(lineNo + 1,
                        "CSI sequence ending in '" + line.charAt(i)
                                + "' is not painterly (SGR styling only)");
            }
            applySgr(line.substring(at + 2, i));
            return i + 1;
        }

        private void applySgr(String params) {
            if (params.isBlank()) {
                params = "0";
            }
            String[] parts = params.split(";");
            int idx = 0;
            while (idx < parts.length) {
                int code;
                String token = parts[idx].trim();
                try {
                    code = token.isEmpty() ? 0 : Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    throw new FormatException(lineNo + 1, "bad SGR parameter: '" + token + "'");
                }
                switch (code) {
                    case 0 -> {
                        fg = AnsiColor.DEFAULT;
                        bg = AnsiColor.DEFAULT;
                        mods = EnumSet.noneOf(SGR.class);
                    }
                    case 1 -> mods.add(SGR.BOLD);
                    case 2 -> mods.add(SGR.DIM);
                    case 3 -> mods.add(SGR.ITALIC);
                    case 4 -> mods.add(SGR.UNDERLINE);
                    case 5 -> mods.add(SGR.BLINK);
                    case 7 -> mods.add(SGR.REVERSE);
                    case 8 -> mods.add(SGR.HIDDEN);
                    case 9 -> mods.add(SGR.STRIKETHROUGH);
                    case 22 -> mods.removeAll(EnumSet.of(SGR.BOLD, SGR.DIM));
                    case 23 -> mods.removeAll(EnumSet.of(SGR.ITALIC, SGR.STRIKETHROUGH));
                    case 24 -> mods.remove(SGR.UNDERLINE);
                    case 25 -> mods.remove(SGR.BLINK);
                    case 27 -> mods.remove(SGR.REVERSE);
                    case 28 -> mods.remove(SGR.HIDDEN);
                    case 29 -> mods.remove(SGR.STRIKETHROUGH);
                    case 30, 31, 32, 33, 34, 35, 36, 37,
                         90, 91, 92, 93, 94, 95, 96, 97 -> fg = ansiForCode(code);
                    case 39 -> fg = AnsiColor.DEFAULT;
                    case 40, 41, 42, 43, 44, 45, 46, 47,
                         100, 101, 102, 103, 104, 105, 106, 107 -> bg = ansiForCode(code);
                    case 49 -> bg = AnsiColor.DEFAULT;
                    case 38, 48 -> {
                        if (idx + 1 >= parts.length) {
                            throw new FormatException(lineNo + 1, "extended color missing its mode");
                        }
                        int mode;
                        try {
                            mode = Integer.parseInt(parts[idx + 1].trim());
                        } catch (NumberFormatException e) {
                            throw new FormatException(lineNo + 1,
                                    "bad extended-color mode: '" + parts[idx + 1] + "'");
                        }
                        if (mode == 5 && idx + 2 < parts.length) {
                            int n = intValue(parts[idx + 2], lineNo + 1);
                            if (n < 0 || n > 255) {
                                throw new FormatException(lineNo + 1, "256-color index out of range: " + n);
                            }
                            IndexedColor c = new IndexedColor(n);
                            if (code == 38) {
                                fg = c;
                            } else {
                                bg = c;
                            }
                            idx += 2;
                        } else if (mode == 2 && idx + 4 < parts.length) {
                            int r = intValue(parts[idx + 2], lineNo + 1);
                            int g = intValue(parts[idx + 3], lineNo + 1);
                            int b = intValue(parts[idx + 4], lineNo + 1);
                            RgbColor c = new RgbColor(r, g, b);
                            if (code == 38) {
                                fg = c;
                            } else {
                                bg = c;
                            }
                            idx += 4;
                        } else {
                            throw new FormatException(lineNo + 1,
                                    "extended color " + mode + " lacks its parameters");
                        }
                    }
                    default -> throw new FormatException(lineNo + 1, "unknown SGR code: " + code);
                }
                idx++;
            }
        }

        private static Color ansiForCode(int code) {
            // 30-37 fg basic → 0-7, 90-97 fg bright → 8-15,
            // 40-47 bg basic → 0-7, 100-107 bg bright → 8-15.
            int idx = switch (code) {
                case 30, 40 -> 0;
                case 31, 41 -> 1;
                case 32, 42 -> 2;
                case 33, 43 -> 3;
                case 34, 44 -> 4;
                case 35, 45 -> 5;
                case 36, 46 -> 6;
                case 37, 47 -> 7;
                case 90, 100 -> 8;
                case 91, 101 -> 9;
                case 92, 102 -> 10;
                case 93, 103 -> 11;
                case 94, 104 -> 12;
                case 95, 105 -> 13;
                case 96, 106 -> 14;
                default -> 15; // 97, 107
            };
            return AnsiColor.values()[idx];
        }

        private void paint(String ch) {
            if (col < cols && row < rows && col >= 0 && row >= 0) {
                cells.setCell(col, row,
                        new TextCell(ch, fg, bg, mods));
            } else {
                throw new FormatException(lineNo + 1,
                        "dims mismatch: painting '" + ch + "' at column " + col + ", row " + row
                                + " outside the declared " + cols + "x" + rows);
            }
        }

        Frame build() {
            return new Frame(durationMs, subcell, cells);
        }
    }
}
