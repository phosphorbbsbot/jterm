package io.jterm.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Terminal size discovery via ANSI ESC[18t query.
 * Sends ESC[18t and parses the response ESC[8;rows;cols t.
 *
 * <p>This is a stateless utility class — thread-safe by design.
 */
public final class TerminalSizeQuery {
    private static final byte[] QUERY = "\033[18t".getBytes(StandardCharsets.UTF_8);
    // Response: ESC[8;<rows>;<cols>t  (e.g. \033[8;24;80t)
    private static final Pattern RESPONSE = Pattern.compile("\\033\\[8;(\\d+);(\\d+)t");

    private TerminalSizeQuery() {}

    /**
     * Query terminal size via ESC[18t. Sends the query over the output
     * stream and reads the response from the input stream with a timeout.
     *
     * @param in  the input stream to read the response from
     * @param out the output stream to send the query to
     * @param timeoutMs maximum time to wait for a response
     * @return the terminal size, or null if the query failed or timed out
     */
    public static TerminalSize query(InputStream in, OutputStream out, int timeoutMs) {
        try {
            // Flush any pending output
            out.flush();
            // Send ESC[18t
            out.write(QUERY);
            out.flush();

            // Read response with timeout. The response is ESC[8;rows;cols t
            // We read bytes until we see the 't' terminator or timeout.
            long deadline = System.currentTimeMillis() + timeoutMs;
            var sb = new StringBuilder();
            int bytesRead = 0;
            while (System.currentTimeMillis() < deadline) {
                if (in.available() > 0) {
                    int b = in.read();
                    if (b < 0) break;
                    sb.append((char) b);
                    bytesRead++;
                    // Check if we have a complete response
                    if (b == 't' && sb.indexOf("\033[8;") >= 0) {
                        break;
                    }
                    // Safety limit — don't read forever
                    if (bytesRead > 100) break;
                } else {
                    Thread.sleep(1);
                }
            }
            // Parse the response
            var m = RESPONSE.matcher(sb);
            if (m.find()) {
                int rows = Integer.parseInt(m.group(1));
                int cols = Integer.parseInt(m.group(2));
                if (rows > 0 && cols > 0) {
                    return new TerminalSize(cols, rows);
                }
            }
        } catch (IOException | InterruptedException | NumberFormatException e) {
            // Query failed — caller falls back
        }
        return null;
    }
}