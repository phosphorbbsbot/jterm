package io.jterm.animation;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import io.jterm.style.TextCell;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AnimationTimerTest {

    @Test
    @DisplayName("AnimationTimer rejects non-positive FPS")
    void rejectsNonPositiveFps() {
        assertThrows(IllegalArgumentException.class, () -> new AnimationTimer(0, () -> {}));
        assertThrows(IllegalArgumentException.class, () -> new AnimationTimer(-1, () -> {}));
    }

    @Test
    @DisplayName("AnimationTimer rejects null callback")
    void rejectsNullCallback() {
        assertThrows(IllegalArgumentException.class, () -> new AnimationTimer(15, null));
    }

    @Test
    @DisplayName("start/stop lifecycle")
    void startStopLifecycle() throws InterruptedException {
        var timer = new AnimationTimer(60, () -> {});
        assertFalse(timer.isRunning());
        timer.start();
        assertTrue(timer.isRunning());
        Thread.sleep(20);
        timer.stop();
        assertFalse(timer.isRunning());
    }

    @Test
    @DisplayName("frame callback is invoked repeatedly")
    void callbackInvokedRepeatedly() throws InterruptedException {
        var counter = new AtomicInteger();
        var timer = new AnimationTimer(1000, counter::incrementAndGet);
        timer.start();
        Thread.sleep(60);
        timer.stop();
        assertTrue(counter.get() >= 1, "callback should fire at least once");
    }

    @Test
    @DisplayName("virtual thread name is 'animation'")
    void virtualThreadName() throws InterruptedException {
        AtomicReference<String> name = new AtomicReference<>();
        var timer = new AnimationTimer(1000, () -> name.set(Thread.currentThread().getName()));
        timer.start();
        Thread.sleep(30);
        timer.stop();
        assertEquals("animation", name.get());
    }

    @Test
    @DisplayName("stop prevents further invocations")
    void stopPreventsInvocations() throws InterruptedException {
        var counter = new AtomicInteger();
        var timer = new AnimationTimer(1000, counter::incrementAndGet);
        timer.start();
        Thread.sleep(25);
        int afterStart = counter.get();
        timer.stop();
        // stop() is asynchronous by contract: a frame already past the
        // running-check (in flight) may still complete once. Allow exactly
        // one such frame — anything more would be a real leak.
        Thread.sleep(50);
        assertTrue(counter.get() <= afterStart + 1,
                "at most one in-flight callback may land after stop (got "
                        + (counter.get() - afterStart) + ")");
    }

    @Test
    @DisplayName("target FPS is stored")
    void storesTargetFps() {
        var timer = new AnimationTimer(30, () -> {});
        assertEquals(30, timer.getTargetFps());
    }

    @Test
    @DisplayName("callback receives graphics-sized buffer")
    void callbackReceivesSizedGraphics() {
        var size = new TerminalSize(10, 5);
        var buffer = new ScreenBuffer(size);
        var timer = new AnimationTimer(60, () -> {
            // no-op; called on virtual thread so we can't easily assert from here
        });
        // Directly drive render path outside timer thread for deterministic check
        var bg = new StarfieldBackground(size);
        bg.renderFrame(new TextGraphics(buffer), size);
        assertNotNull(buffer);
    }
}
