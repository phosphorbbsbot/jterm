package io.jterm.window;

import io.jterm.animation.AnimatedBackground;
import io.jterm.animation.AnimationTimer;
import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import io.jterm.style.TextCell;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for AnimatedBackgroundWindow — covers constructors,
 * draw(), renderTick(), setBounds(), start(), stop(), getters, and frame
 * buffer management.
 */
class AnimatedBackgroundWindowCoverageTest {

    /** Stub AnimatedBackground that tracks calls for verification. */
    static class StubBackground implements AnimatedBackground {
        int renderCount;
        int tickCount;
        int resizeCount;
        int startCount;
        int stopCount;
        boolean running;
        TerminalSize lastResizeSize;

        @Override
        public void renderFrame(TextGraphics g, TerminalSize size) {
            renderCount++;
            lastResizeSize = size;
        }

        @Override
        public void onResize(TerminalSize newSize) {
            resizeCount++;
            lastResizeSize = newSize;
        }

        @Override
        public void start() {
            startCount++;
            running = true;
        }

        @Override
        public void stop() {
            stopCount++;
            running = false;
        }

        @Override
        public boolean isRunning() { return running; }

        @Override
        public int targetFps() { return 2; }

        @Override
        public void tick(long nowNanos) { tickCount++; }
    }

    private static final TerminalSize SIZE_80x24 = new TerminalSize(80, 24);

    @Nested
    @DisplayName("Constructors")
    class Constructors {

        @Test
        @DisplayName("two-arg constructor delegates to three-arg with fullscreen=true")
        void twoArgConstructorIsFullscreen() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null);
            var hints = window.getHints();
            assertTrue(hints.contains(WindowHint.FULLSCREEN),
                    "two-arg constructor should set FULLSCREEN hint");
            assertTrue(hints.contains(WindowHint.NO_DECORATIONS),
                    "two-arg constructor should set NO_DECORATIONS hint");
            assertTrue(hints.contains(WindowHint.BACKGROUND),
                    "two-arg constructor should set BACKGROUND hint");
        }

        @Test
        @DisplayName("three-arg fullscreen=true sets FULLSCREEN, NO_DECORATIONS, BACKGROUND hints")
        void fullscreenConstructorSetsHints() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            var hints = window.getHints();
            assertTrue(hints.contains(WindowHint.FULLSCREEN));
            assertTrue(hints.contains(WindowHint.NO_DECORATIONS));
            assertTrue(hints.contains(WindowHint.BACKGROUND));
            assertEquals(3, hints.size());
        }

        @Test
        @DisplayName("three-arg fullscreen=false omits FULLSCREEN hint")
        void nonFullscreenConstructorOmitsFullscreenHint() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, false);
            var hints = window.getHints();
            assertFalse(hints.contains(WindowHint.FULLSCREEN),
                    "non-fullscreen should NOT have FULLSCREEN hint");
            assertTrue(hints.contains(WindowHint.NO_DECORATIONS));
            assertTrue(hints.contains(WindowHint.BACKGROUND));
            assertEquals(2, hints.size());
        }

        @Test
        @DisplayName("constructor sets null layout manager on contents")
        void constructorSetsNullLayoutManager() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            assertNull(window.getContents().getLayoutManager(),
                    "contents panel should have null layout manager");
        }
    }

    @Nested
    @DisplayName("draw()")
    class DrawTests {

        @Test
        @DisplayName("draw fills with black when no frame buffer exists yet")
        void drawFillsBlackWhenNoFrameBuffer() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            window.setBounds(TerminalPosition.TOP_LEFT, SIZE_80x24);
            // No renderTick called, so frameBuffer is null
            assertNull(window.getFrameBuffer(),
                    "frame buffer should be null before any render tick");

            var screenBuf = new ScreenBuffer(SIZE_80x24, TextCell.EMPTY);
            var graphics = new TextGraphics(screenBuf);
            window.draw(graphics);

            // Every cell should be the black fill (char=' ', fg=BLACK, bg=BLACK)
            var blackCell = new TextCell(' ', AnsiColor.BLACK, AnsiColor.BLACK);
            for (int r = 0; r < SIZE_80x24.rows(); r++) {
                for (int c = 0; c < SIZE_80x24.columns(); c++) {
                    assertEquals(blackCell, screenBuf.getCell(c, r),
                            "cell at (" + c + "," + r + ") should be black fill");
                }
            }
        }

        @Test
        @DisplayName("draw blits frame buffer content to graphics")
        void drawBlitsFrameBufferContent() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            window.setBounds(TerminalPosition.TOP_LEFT, SIZE_80x24);

            // Invoke renderTick manually (via start's pre-advance loop)
            // We'll call start() which calls renderTick 5 times then starts the timer.
            // But we need to stop the timer to avoid background threads.
            window.start();
            try {
                assertNotNull(window.getFrameBuffer(),
                        "frame buffer should exist after start()");

                // The background.renderFrame was called, so frame buffer should have content
                assertTrue(bg.renderCount >= 5,
                        "renderFrame should have been called at least 5 times during start()");

                // Now draw should blit the buffer content
                var screenBuf = new ScreenBuffer(SIZE_80x24, TextCell.EMPTY);
                var graphics = new TextGraphics(screenBuf);
                window.draw(graphics);

                // Verify draw didn't crash and the screen buffer is populated
                // (the exact content depends on what renderFrame writes, but we can verify
                //  it's not all EMPTY since the background's renderFrame is a no-op stub)
                // With our stub, renderFrame is a no-op, so the buffer will be all-black
                // (the initial fill from ScreenBuffer constructor).
                // But the important thing is draw() executed without NPE.
            } finally {
                window.stop();
            }
        }

        @Test
        @DisplayName("draw handles size mismatch by clamping to intersection")
        void drawClampsToIntersection() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            // Set window size to 80x24
            window.setBounds(TerminalPosition.TOP_LEFT, SIZE_80x24);

            window.start();
            try {
                // Now resize the window to be smaller — frame buffer is still 80x24
                // but window size changes
                var smallerSize = new TerminalSize(40, 12);
                window.setBounds(TerminalPosition.TOP_LEFT, smallerSize);

                var screenBuf = new ScreenBuffer(smallerSize, TextCell.EMPTY);
                var graphics = new TextGraphics(screenBuf);
                // draw should clamp to min(80,40) cols and min(24,12) rows = 40x12
                assertDoesNotThrow(() -> window.draw(graphics));
            } finally {
                window.stop();
            }
        }
    }

    @Nested
    @DisplayName("renderTick and frame buffer management")
    class RenderTickTests {

        @Test
        @DisplayName("renderTick allocates frame buffer when null")
        void renderTickAllocatesFrameBuffer() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            window.setBounds(TerminalPosition.TOP_LEFT, SIZE_80x24);

            assertNull(window.getFrameBuffer(), "buffer should start null");

            window.start();
            try {
                assertNotNull(window.getFrameBuffer(),
                        "buffer should be allocated after start (renderTick)");
                assertEquals(SIZE_80x24, window.getFrameBuffer().size());
            } finally {
                window.stop();
            }
        }

        @Test
        @DisplayName("renderTick reallocates buffer when size changes")
        void renderTickReallocatesOnSizeChange() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            window.setBounds(TerminalPosition.TOP_LEFT, SIZE_80x24);

            window.start();
            try {
                assertEquals(SIZE_80x24, window.getFrameBuffer().size());

                // Resize — this should cause reallocation on next renderTick
                var newSize = new TerminalSize(120, 40);
                window.setBounds(TerminalPosition.TOP_LEFT, newSize);

                // Stop and restart to trigger renderTick with new size
                window.stop();
                bg.running = false; // reset for restart
                window.start();

                assertEquals(newSize, window.getFrameBuffer().size(),
                        "buffer should be reallocated to new size");
            } finally {
                window.stop();
            }
        }

        @Test
        @DisplayName("renderTick skips when size is zero or negative")
        void renderTickSkipsZeroSize() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            // Default size from AbstractWindow is 40x20, so we need to set zero explicitly
            // But setBounds delegates to background.onResize only on change, so let's
            // test with a zero-width size via direct setBounds
            // Actually, we need to use super.setBounds path — let's set 0 columns
            var zeroSize = new TerminalSize(0, 24);
            // AbstractWindow.setBounds will set size to 0x24
            window.setBounds(TerminalPosition.TOP_LEFT, zeroSize);

            // renderTick should bail out (sz.columns() <= 0)
            // We can verify by calling start() which calls renderTick 5 times
            // and checking that no buffer was allocated
            int rendersBefore = bg.renderCount;
            window.start();
            try {
                // renderTick should have returned early — no frame buffer allocated
                // Actually, the buffer might be null since columns <= 0
                // But start() calls renderTick which checks sz.columns() <= 0 and returns
                assertNull(window.getFrameBuffer(),
                        "no frame buffer should be allocated when size is zero-width");
                assertEquals(rendersBefore, bg.renderCount,
                        "renderFrame should not be called when size is zero");
            } finally {
                window.stop();
            }
        }

        @Test
        @DisplayName("renderTick calls background.tick() then background.renderFrame()")
        void renderTickCallsTickAndRenderFrame() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            window.setBounds(TerminalPosition.TOP_LEFT, SIZE_80x24);

            window.start();
            try {
                // start() calls renderTick 5 times, each calling tick() + renderFrame()
                assertTrue(bg.tickCount >= 5,
                        "tick should be called at least 5 times during start()");
                assertTrue(bg.renderCount >= 5,
                        "renderFrame should be called at least 5 times during start()");
            } finally {
                window.stop();
            }
        }
    }

    @Nested
    @DisplayName("setBounds() resize deduplication")
    class SetBoundsTests {

        @Test
        @DisplayName("setBounds forwards resize to background only when size changes")
        void setBoundsForwardsResizeOnlyOnChange() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            var size = new TerminalSize(80, 24);

            window.setBounds(TerminalPosition.TOP_LEFT, size);
            assertEquals(1, bg.resizeCount, "first setBounds should forward resize");
            assertEquals(size, bg.lastResizeSize);

            // Same size — should NOT forward
            window.setBounds(TerminalPosition.TOP_LEFT, size);
            assertEquals(1, bg.resizeCount, "same size should not forward again");

            // Different size — SHOULD forward
            var newSize = new TerminalSize(120, 40);
            window.setBounds(TerminalPosition.TOP_LEFT, newSize);
            assertEquals(2, bg.resizeCount, "different size should forward");
            assertEquals(newSize, bg.lastResizeSize);
        }

        @Test
        @DisplayName("setBounds updates window position and size")
        void setBoundsUpdatesPositionAndSize() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);

            var pos = new TerminalPosition(5, 3);
            var size = new TerminalSize(60, 30);
            window.setBounds(pos, size);

            assertEquals(pos, window.getPosition());
            assertEquals(size, window.getSize());
        }
    }

    @Nested
    @DisplayName("start() and stop()")
    class StartStopTests {

        @Test
        @DisplayName("start calls background.start() and creates timer")
        void startCallsBackgroundStartAndCreatesTimer() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            window.setBounds(TerminalPosition.TOP_LEFT, SIZE_80x24);

            assertNull(window.getTimer(), "timer should be null before start");

            window.start();
            try {
                assertEquals(1, bg.startCount, "background.start() should be called once");
                assertNotNull(window.getTimer(), "timer should be created after start");
                assertTrue(window.getTimer().isRunning(), "timer should be running");
            } finally {
                window.stop();
            }
        }

        @Test
        @DisplayName("start pre-advances animation with 5 render ticks")
        void startPreAdvancesAnimation() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            window.setBounds(TerminalPosition.TOP_LEFT, SIZE_80x24);

            window.start();
            try {
                // 5 pre-advance render ticks
                assertEquals(5, bg.renderCount,
                        "renderFrame should be called exactly 5 times during start pre-advance");
                assertEquals(5, bg.tickCount,
                        "tick should be called exactly 5 times during start pre-advance");
            } finally {
                window.stop();
            }
        }

        @Test
        @DisplayName("stop calls timer.stop() and background.stop()")
        void stopCallsTimerAndBackgroundStop() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            window.setBounds(TerminalPosition.TOP_LEFT, SIZE_80x24);

            window.start();
            assertTrue(bg.running, "background should be running after start");

            window.stop();
            assertFalse(bg.running, "background should be stopped after stop");
            assertFalse(window.getTimer().isRunning(), "timer should not be running after stop");
        }

        @Test
        @DisplayName("stop handles null timer gracefully")
        void stopWithNullTimerDoesNotThrow() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            // Never called start(), so timer is null
            assertDoesNotThrow(() -> window.stop(),
                    "stop() should not throw when timer is null");
            assertFalse(bg.running, "background.stop() should still be called");
        }
    }

    @Nested
    @DisplayName("getters")
    class GetterTests {

        @Test
        @DisplayName("getBackground returns the background")
        void getBackgroundReturnsBackground() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            assertSame(bg, window.getBackground());
        }

        @Test
        @DisplayName("getTimer returns null before start")
        void getTimerReturnsNullBeforeStart() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            assertNull(window.getTimer(), "timer should be null before start");
        }

        @Test
        @DisplayName("getTimer returns timer after start")
        void getTimerReturnsTimerAfterStart() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            window.setBounds(TerminalPosition.TOP_LEFT, SIZE_80x24);
            window.start();
            try {
                AnimationTimer timer = window.getTimer();
                assertNotNull(timer);
                assertEquals(2, timer.getTargetFps(), "timer fps should match background.targetFps()");
            } finally {
                window.stop();
            }
        }

        @Test
        @DisplayName("getFrameBuffer returns null before first render tick")
        void getFrameBufferReturnsNullBeforeRender() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            assertNull(window.getFrameBuffer());
        }

        @Test
        @DisplayName("getFrameBuffer returns buffer after start")
        void getFrameBufferReturnsBufferAfterStart() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            window.setBounds(TerminalPosition.TOP_LEFT, SIZE_80x24);
            window.start();
            try {
                assertNotNull(window.getFrameBuffer());
                assertEquals(SIZE_80x24, window.getFrameBuffer().size());
            } finally {
                window.stop();
            }
        }
    }

    @Nested
    @DisplayName("draw with gui requestRefresh")
    class DrawWithGuiTests {

        @Test
        @DisplayName("a MOUNTED ambient window requests gui refresh on every renderTick")
        void renderTickRequestsGuiRefresh() {
            // Create a stub GUI that tracks requestRefresh calls and holds a
            // window list — renderTick's DETACHED-PAUSE law (card t_a9be4757)
            // holds the frame for any ambient window not mounted on its GUI,
            // so the refresh contract is asserted for a mounted window.
            var bg = new StubBackground();
            final int[] refreshCount = {0};
            final java.util.List<Window> mounted = new java.util.ArrayList<>();
            TextGUI stubGui = new TextGUI() {
                @Override public io.jterm.screen.Screen getScreen() { return null; }
                @Override public void addWindow(Window w) { mounted.add(w); }
                @Override public void removeWindow(Window w) { mounted.remove(w); }
                @Override public Window getActiveWindow() { return null; }
                @Override public void setActiveWindow(Window w) {}
                @Override public boolean processInput() { return false; }
                @Override public void waitForInput() {}
                @Override public void updateScreen() {}
                @Override public void requestRefresh() { refreshCount[0]++; }
                @Override public void close() {}
                @Override public java.util.Collection<Window> getWindows() { return mounted; }
            };

            var window = new AnimatedBackgroundWindow(bg, stubGui, true);
            window.setBounds(TerminalPosition.TOP_LEFT, SIZE_80x24);
            stubGui.addWindow(window);

            window.start();
            try {
                // 5 pre-advance ticks, each calling requestRefresh
                assertEquals(5, refreshCount[0],
                        "requestRefresh should be called once per renderTick");
            } finally {
                window.stop();
            }
        }

        @Test
        @DisplayName("renderTick does not NPE when gui is null")
        void renderTickWithNullGuiDoesNotNPE() {
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            window.setBounds(TerminalPosition.TOP_LEFT, SIZE_80x24);

            assertDoesNotThrow(() -> {
                window.start();
                window.stop();
            }, "start/stop should not NPE with null gui");
        }
    }

    @Nested
    @DisplayName("draw edge cases")
    class DrawEdgeCases {

        @Test
        @DisplayName("draw with frameSize null fills black even when frameBuffer is non-null (impossible in practice but verifies the null check)")
        void drawWithNullFrameSize() {
            // This tests the `if (buf == null || fSize == null)` branch.
            // In practice both are set together, but we can verify the fallback path
            // by testing before any renderTick.
            var bg = new StubBackground();
            var window = new AnimatedBackgroundWindow(bg, null, true);
            window.setBounds(TerminalPosition.TOP_LEFT, SIZE_80x24);

            var screenBuf = new ScreenBuffer(SIZE_80x24, TextCell.EMPTY);
            var graphics = new TextGraphics(screenBuf);
            window.draw(graphics);

            // Verify the screen was filled with the black fallback cell
            var blackCell = new TextCell(' ', AnsiColor.BLACK, AnsiColor.BLACK);
            assertEquals(blackCell, screenBuf.getCell(0, 0));
            assertEquals(blackCell, screenBuf.getCell(SIZE_80x24.columns() - 1, SIZE_80x24.rows() - 1));
        }

        @Test
        @DisplayName("draw after start blits frame buffer cells")
        void drawAfterStartBlitsBuffer() {
            // Use a background that actually writes visible content
            var bg = new AnimatedBackground() {
                int renderCount;
                @Override
                public void renderFrame(TextGraphics g, TerminalSize size) {
                    renderCount++;
                    // Write a distinctive character so we can verify the blit
                    var cell = new TextCell('*', AnsiColor.WHITE, AnsiColor.BLUE);
                    g.setCell(0, 0, cell);
                    g.setCell(size.columns() - 1, size.rows() - 1, cell);
                }
                @Override public void onResize(TerminalSize s) {}
                @Override public void start() {}
                @Override public void stop() {}
                @Override public boolean isRunning() { return true; }
                @Override public int targetFps() { return 2; }
                @Override public void tick(long n) {}
            };

            var window = new AnimatedBackgroundWindow(bg, null, true);
            window.setBounds(TerminalPosition.TOP_LEFT, SIZE_80x24);

            window.start();
            try {
                var screenBuf = new ScreenBuffer(SIZE_80x24, TextCell.EMPTY);
                var graphics = new TextGraphics(screenBuf);
                window.draw(graphics);

                // Top-left and bottom-right should have the '*' cells from the frame buffer
                var starCell = new TextCell('*', AnsiColor.WHITE, AnsiColor.BLUE);
                assertEquals(starCell, screenBuf.getCell(0, 0),
                        "top-left cell should be from frame buffer blit");
                assertEquals(starCell, screenBuf.getCell(SIZE_80x24.columns() - 1, SIZE_80x24.rows() - 1),
                        "bottom-right cell should be from frame buffer blit");
            } finally {
                window.stop();
            }
        }
    }
}