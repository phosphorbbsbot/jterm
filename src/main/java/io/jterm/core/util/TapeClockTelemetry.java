package io.jterm.core.util;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/** TEMPORARY telemetry for the 2026-10-26 tape-freeze hunt. */
public final class TapeClockTelemetry {
    private static final Logger LOG = Logger.getLogger(TapeClockTelemetry.class.getName());
    private static final AtomicLong started = new AtomicLong();
    private static final AtomicLong stopped = new AtomicLong();
    private static final AtomicLong frames = new AtomicLong();

    private TapeClockTelemetry() {}

    public static void clockStarted(long userId) {
        started.incrementAndGet();
        LOG.info("[TAPE-CLOCK] started user=" + userId + " startedTotal=" + started.get() + " stoppedTotal=" + stopped.get());
    }

    public static void clockStopped(long userId) {
        stopped.incrementAndGet();
        LOG.info("[TAPE-CLOCK] stopped user=" + userId + " startedTotal=" + started.get() + " stoppedTotal=" + stopped.get());
    }

    public static void frameFired(long userId) {
        long n = frames.incrementAndGet();
        if (n % 40 == 0) {
            LOG.info("[TAPE-CLOCK] frames firing user=" + userId + " frames=" + n);
        }
    }
}
