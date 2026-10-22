package io.jterm.animation;

/**
 * Simple fixed-rate timer for animation loops. Runs the supplied callback on
 * a virtual thread and sleeps to honour the requested frame rate.
 */
public class AnimationTimer {
    private final int targetFps;
    private final Runnable frameCallback;
    private volatile boolean running;
    private Thread animationThread;

    /** Creates a timer with the specified target FPS and frame callback.
 * @param targetFps the desired frames per second
 * @param frameCallback the callback to invoke on each frame */
    public AnimationTimer(int targetFps, Runnable frameCallback) {
        if (targetFps <= 0) {
            throw new IllegalArgumentException("targetFps must be positive");
        }
        if (frameCallback == null) {
            throw new IllegalArgumentException("frameCallback must not be null");
        }
        this.targetFps = targetFps;
        this.frameCallback = frameCallback;
    }

    /** Starts the animation timer loop. */
    public void start() {
        if (running) return;
        running = true;
        animationThread = Thread.ofVirtual().name("animation").start(() -> {
            long frameNanos = 1_000_000_000L / targetFps;
            while (running) {
                long start = System.nanoTime();
                // FAULT-ISOLATION LAW (2026-10-26): a single throwing callback
                // used to kill this thread silently — the tape froze mid-scroll
                // while everything looked "running". Log and keep animating.
                try {
                    frameCallback.run();
                } catch (RuntimeException e) {
                    java.util.logging.Logger.getLogger(AnimationTimer.class.getName())
                            .log(java.util.logging.Level.WARNING, "animation frame fault", e);
                }
                long elapsed = System.nanoTime() - start;
                long sleep = frameNanos - elapsed;
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep / 1_000_000, (int)(sleep % 1_000_000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
    }

    /** Stops the animation timer loop. */
    public void stop() {
        running = false;
        if (animationThread != null) {
            animationThread.interrupt();
        }
    }

    /** Returns whether the timer is currently running.
 * @return true if running */
    public boolean isRunning() {
        return running;
    }

    /** Returns the target frame rate.
 * @return the target FPS */
    public int getTargetFps() {
        return targetFps;
    }
}
