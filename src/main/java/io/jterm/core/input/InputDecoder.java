package io.jterm.core.input;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/** Parses raw terminal input bytes into keyboard events. */
public class InputDecoder {

    private final InputStream input;
    private int pushback = -1;  // single-byte pushback buffer for \r\n handling
    private boolean skipNextNulOrLf = false;  // set after \r to skip trailing \n/\0

    /**
     * Create a decoder that reads from the given input stream.
     *
     * @param input the input stream to decode
     */
    public InputDecoder(InputStream input) {
        this.input = input;
    }

    /**
     * Poll for the next key stroke without blocking.
     *
     * @return an Optional containing the next key stroke, or empty if none available
     *
     * @throws IOException if an I/O error or other failure occurs
     */
    public Optional<KeyStroke> poll() throws IOException {
        if (input.available() == 0 && pushback == -1) {
            return Optional.empty();
        }
        int first = pushback != -1 ? pushback : input.read();
        pushback = -1;
        if (first == -1) {
            return Optional.of(new KeyStroke(KeyType.EOF));
        }

        // After \r, Telnet clients send \n or \0 which we already consumed
        // if it arrived in the same packet. If it arrives in the next packet,
        // skip it here.
        if (skipNextNulOrLf) {
            skipNextNulOrLf = false;
            if (first == '\n' || first == '\0') {
                return poll();  // skip this byte, continue
            }
            // Not \n or \0 — fall through and process normally
        }

        if (first == 0x1b) { // ESC
            return readEscapeSequence(first);
        }

        if (first == '\r') {
            // Consume optional \n or \0 that follows \r
            // Telnet clients send \r\n, \r\0, or just \r
            if (input.available() > 0) {
                int next = input.read();
                if (next != '\n' && next != '\0' && next != -1) {
                    pushback = next;  // save for next poll()
                }
            } else {
                // \n or \0 might arrive in next packet — flag to skip it
                skipNextNulOrLf = true;
            }
            return Optional.of(new KeyStroke(KeyType.ENTER));
        }
        if (first == '\n') {
            // Standalone \n — treat as ENTER
            return Optional.of(new KeyStroke(KeyType.ENTER));
        }
        if (first == '\0') {
            // Telnet NUL — skip silently
            return poll();
        }
        if (first == '\t') {
            return Optional.of(new KeyStroke(KeyType.TAB));
        }
        if (first == 0x7f || first == 0x08) {
            return Optional.of(new KeyStroke(KeyType.BACKSPACE));
        }
        if (first >= 1 && first <= 26) { // Ctrl+A..Ctrl+Z, treat as letters
            return Optional.of(KeyStroke.character((char) ('A' + first - 1), true, false, false));
        }

        return Optional.of(KeyStroke.character((char) first, false, false, false));
    }

    private Optional<KeyStroke> readEscapeSequence(int esc) throws IOException {
        if (input.available() == 0) {
            // Wait briefly to distinguish standalone Escape from Alt+key sequences.
            // Over network connections (e.g. SSH), ESC and the following byte
            // can arrive in separate TCP packets. A 50ms wait gives the next byte
            // time to arrive without noticeable latency for real Escape presses.
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (input.available() == 0) {
                // Standalone Escape
                return Optional.of(new KeyStroke(KeyType.ESCAPE));
            }
        }
        int second = input.read();
        if (second == -1) {
            return Optional.of(new KeyStroke(KeyType.ESCAPE));
        }

        if (second == '[') {
            return readCsiSequence();
        }
        if (second == 'O') {
            return readOSequence();
        }
        // Standalone control byte (Ctrl-A..Ctrl+Z etc.) right after ESC — it is
        // a NEW key press (e.g. ESC closes the chat overlay, user immediately
        // presses Ctrl-T to reopen), not part of any escape sequence. Push it
        // back so the next poll() delivers it, and deliver the ESCAPE now.
        // The old parser dropped such bytes as UNKNOWN, silently eating keys.
        if (second >= 1 && second <= 26) {
            pushback = second;
            return Optional.of(new KeyStroke(KeyType.ESCAPE));
        }
        if (Character.isLetterOrDigit(second) || second >= 32 && second < 127) {
            return Optional.of(KeyStroke.character((char) second, false, true, false));
        }
        return Optional.of(new KeyStroke(KeyType.UNKNOWN));
    }

    private Optional<KeyStroke> readCsiSequence() throws IOException {
        StringBuilder params = new StringBuilder();
        int ch;
        while ((ch = input.read()) != -1) {
            char c = (char) ch;
            if (c >= '0' && c <= '9' || c == ';' || c == '?') {
                params.append(c);
            } else if (c >= 0x40 && c <= 0x7e) { // final byte
                return Optional.of(mapCsi(params.toString(), c));
            } else {
                return Optional.of(new KeyStroke(KeyType.UNKNOWN));
            }
        }
        return Optional.of(new KeyStroke(KeyType.UNKNOWN));
    }

    private Optional<KeyStroke> readOSequence() throws IOException {
        int ch = input.read();
        if (ch == -1) return Optional.of(new KeyStroke(KeyType.UNKNOWN));
        char c = (char) ch;
        KeyType type = switch (c) {
            case 'P' -> KeyType.F1;
            case 'Q' -> KeyType.F2;
            case 'R' -> KeyType.F3;
            case 'S' -> KeyType.F4;
            default -> KeyType.UNKNOWN;
        };
        return Optional.of(new KeyStroke(type));
    }

    private KeyStroke mapCsi(String params, char finalByte) {
        // Shift+Tab (ESC [ Z) is a special case — it needs shift=true
        if (finalByte == 'Z') {
            return new KeyStroke(KeyType.TAB, '\0', false, false, true);
        }

        // Parse modifier code from params. Modern terminals encode modifiers as
        // ESC[keyId;modifierCode finalByte where modifierCode 2=Shift, 3=Alt,
        // 4=Alt+Shift, 5=Ctrl, 6=Ctrl+Shift, 7=Ctrl+Alt, 8=Ctrl+Alt+Shift.
        // (modifierCode - 1) is a bitmask: bit 0 = Shift, bit 1 = Alt, bit 2 = Ctrl.
        String keyParam = params;
        boolean shift = false, alt = false, ctrl = false;
        int semicolon = params.indexOf(';');
        if (semicolon >= 0) {
            keyParam = params.substring(0, semicolon);
            String modStr = params.substring(semicolon + 1);
            int modCode = parseModCode(modStr);
            if (modCode > 0) {
                int mask = modCode - 1;
                shift = (mask & 1) != 0;
                alt = (mask & 2) != 0;
                ctrl = (mask & 4) != 0;
            }
        }

        KeyType type = switch (finalByte) {
            case 'A' -> KeyType.ARROW_UP;
            case 'B' -> KeyType.ARROW_DOWN;
            case 'C' -> KeyType.ARROW_RIGHT;
            case 'D' -> KeyType.ARROW_LEFT;
            case 'H' -> KeyType.HOME;
            case 'F' -> KeyType.END;
            case '~' -> mapTilde(keyParam);
            default -> KeyType.UNKNOWN;
        };
        return new KeyStroke(type, '\0', ctrl, alt, shift);
    }

    /**
     * Parse the modifier code substring (the part after the semicolon in CSI params).
     *
     * @param modStr the modifier code string, e.g. {@code "2"} for Shift
     * @return the modifier code, or 0 if the string is empty or not a valid number
     */
    private int parseModCode(String modStr) {
        if (modStr.isEmpty()) return 0;
        try {
            return Integer.parseInt(modStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private KeyType mapTilde(String params) {
        return switch (params) {
            case "1", "7" -> KeyType.HOME;
            case "2" -> KeyType.INSERT;
            case "3" -> KeyType.DELETE;
            case "4", "8" -> KeyType.END;
            case "5" -> KeyType.PAGE_UP;
            case "6" -> KeyType.PAGE_DOWN;
            case "11" -> KeyType.F1;
            case "12" -> KeyType.F2;
            case "13" -> KeyType.F3;
            case "14" -> KeyType.F4;
            case "15" -> KeyType.F5;
            case "17" -> KeyType.F6;
            case "18" -> KeyType.F7;
            case "19" -> KeyType.F8;
            case "20" -> KeyType.F9;
            case "21" -> KeyType.F10;
            case "23" -> KeyType.F11;
            case "24" -> KeyType.F12;
            default -> KeyType.UNKNOWN;
        };
    }
}
