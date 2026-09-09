package io.jterm.core;

import java.io.IOException;
import java.io.InputStream;

/** Shared helpers for SocketTerminal coverage tests. */
final class SocketTerminalReaderLifecycleHelpers {

    private SocketTerminalReaderLifecycleHelpers() {}

    /** Growable in-memory input stream whose available() reflects pending bytes. */
    static final class Pump extends InputStream {
        private final byte[] buf = new byte[64];
        private int pos, len;
        private final Object lock = new Object();

        void push(int b) {
            synchronized (lock) { buf[len++] = (byte) b; }
        }

        @Override public int available() {
            synchronized (lock) { return len - pos; }
        }

        @Override public int read() throws IOException {
            synchronized (lock) {
                if (pos >= len) return -1;
                return buf[pos++] & 0xFF;
            }
        }
    }
}