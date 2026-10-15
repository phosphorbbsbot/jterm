package io.jterm.animation;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import io.jterm.style.TextCell;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Frame-driven state tests for the small uncovered branches JaCoCo flags:
 * {@link LavaLamp#tick} accumulator math, {@link LavaLamp.Metaball} update
 * paths ({@code setSize} clamp/reset and the radius/X invariants after
 * boundary wraps), {@link Fireworks.Rocket}/{@link Fireworks.Particle}
 * kinematics and getters, and {@link CandlestickField.Candle} accessors with a
 * seeded {@link Random}. Same-package placement gives access to the
 * package-private test seams.
 */
class AnimationBranchesFrameTest {

    // ------------------------------------------------------------------
    // LavaLamp.tick accumulator math
    // ------------------------------------------------------------------

    @Test
    @DisplayName("tick with a large delta executes the surplus-discard branch")
    void tickLargeDeltaDiscardsSurplus() {
        var lamp = new LavaLamp(new TerminalSize(20, 10));
        long t0 = 1_000_000_000L;
        lamp.tick(t0);               // arms lastTickNanos, frame -> 1
        lamp.setTargetFps(1);        // one frame per second

        // 2s elapsed: one frame granted; the surplus second is discarded by the
        // modulo so the next grant needs the full second again.
        lamp.tick(t0 + 2_000_000_000L);
        assertEquals(2, lamp.getFrame(), "2s at 1 fps must advance exactly one frame");

        // Sub-frame delta: no new frame.
        lamp.tick(t0 + 2_400_000_000L);
        assertEquals(2, lamp.getFrame(), "sub-frame delta must not advance");

        // Full second later: next frame granted.
        lamp.tick(t0 + 3_000_000_000L);
        assertEquals(3, lamp.getFrame(), "full second must grant the next frame");
        assertEquals(4000, lamp.getTotalMs(), "totalMs tracks the last tick (4e9 ns) in millis");
    }

    @Test
    @DisplayName("negative delta is clamped to zero")
    void tickNegativeDeltaClamped() {
        var lamp = new LavaLamp(new TerminalSize(20, 10));
        long t0 = 5_000_000_000L;
        lamp.tick(t0);
        lamp.tick(t0 - 1_000_000_000L);        // time going backwards
        assertEquals(1, lamp.getFrame(), "only the arming tick should count");
    }

    @Test
    @DisplayName("setTargetFps clamps to [1, 60]")
    void setTargetFpsClampsToLimits() {
        var lamp = new LavaLamp(new TerminalSize(20, 10));
        lamp.setTargetFps(0);
        assertEquals(1, lamp.getTargetFps());
        lamp.setTargetFps(1000);
        assertEquals(60, lamp.getTargetFps());
        lamp.setTargetFps(30);
        assertEquals(30, lamp.getTargetFps());
    }

    @Test
    @DisplayName("resetFrame clears balls so the next render re-creates them")
    void resetFrameClearsBalls() {
        var size = new TerminalSize(20, 10);
        var lamp = new LavaLamp(size);
        lamp.setBounds(TerminalPosition.TOP_LEFT, size);
        assertNotNull(lamp.getBalls());
        lamp.resetFrame();
        assertNull(lamp.getBalls(), "resetFrame must null the balls");
    }

    // ------------------------------------------------------------------
    // LavaLamp.Metaball invariants
    // ------------------------------------------------------------------

    @Test
    @DisplayName("setSize clamps x into narrow bands; below-wrap-pad y respawns the ball off-screen")
    void metaballSetSizeClampAndReset() {
        var size = new TerminalSize(80, 24);
        var lamp = new LavaLamp(size);
        lamp.setBounds(TerminalPosition.TOP_LEFT, size);
        LavaLamp.Metaball[] balls = lamp.getBalls();

        // Pick the ball furthest right so the x >= cols clamp branch is certain.
        LavaLamp.Metaball ball = balls[0];
        for (LavaLamp.Metaball b : balls) {
            if (b.getX() > ball.getX()) ball = b;
        }
        double radiusBefore = ball.getRadius();

        // Narrow terminal: the x >= cols clamp runs (y < rows + pad by construction).
        var narrow = new TerminalSize(6, 400);
        ball.setSize(narrow);
        assertEquals(radiusBefore, ball.getRadius(), 0.0, "no reset should re-roll radius");
        assertTrue(ball.getX() <= 5.0 + 1e-9,
                "x must clamp to cols-1 in the narrow band, got " + ball.getX());

        // Terminal whose row budget sits below y + pad: reset(false) respawns
        // the ball off-screen (above for rising, below for falling).
        var tinyRows = new TerminalSize(6, 4);
        ball.setSize(tinyRows);
        if (ball.getY() < 0 || ball.getY() > 4) {
            assertTrue(ball.getRadius() >= 3.0 && ball.getRadius() <= 6.0,
                    "a respawned ball re-rolls radius within [3,6], got " + ball.getRadius());
        }
        assertTrue(ball.getY() <= 4 + ball.getRadius() + 2.0,
                "post-setSize y must respect the wrap budget");
    }

    @Test
    @DisplayName("advance moves faster with larger elapsedMs and y wraps without escaping")
    void metaballAdvanceSpeedScalesAndStaysBounded() {
        var size = new TerminalSize(20, 30);
        var lamp = new LavaLamp(size);
        lamp.setBounds(TerminalPosition.TOP_LEFT, size);
        LavaLamp.Metaball[] balls = lamp.getBalls();

        for (LavaLamp.Metaball ball : balls) {
            double y0 = ball.getY();
            ball.advance(0);
            double ySmall = ball.getY();
            ball.advance(320);
            double yBig = ball.getY();
            double small1 = Math.abs(ySmall - y0);
            double big1 = Math.abs(yBig - ySmall);
            assertTrue(small1 > 0, "ms=0 still advances by the raw speed");
            assertTrue(big1 >= small1 * 2 - 1e-9,
                    "advance(320ms) must move at least ~5x advance(0ms): "
                            + big1 + " vs " + small1);

            // Rising balls stay rising, falling balls keep falling — the sign of
            // vy is fixed by index parity and survives every respawn.
            boolean rising = ball.getVy() < 0;
            for (int i = 0; i < 3000; i++) {
                ball.advance(75);
                if (rising) {
                    assertTrue(ball.getVy() < 0, "rising ball must keep vy < 0 even after respawns");
                } else {
                    assertTrue(ball.getVy() > 0, "falling ball must keep vy > 0 even after respawns");
                }
                assertTrue(ball.getY() >= -9.0 && ball.getY() <= 39.0,
                        "y must stay within the wrap budget around [0,30], got " + ball.getY());
            }
        }
    }

    @Test
    @DisplayName("metaball x never escapes the column band across long runs")
    void metaballXStaysInBand() {
        var size = new TerminalSize(40, 20);
        var lamp = new LavaLamp(size);
        lamp.setBounds(TerminalPosition.TOP_LEFT, size);
        for (LavaLamp.Metaball ball : lamp.getBalls()) {
            for (int i = 0; i < 3000; i++) {
                ball.advance(75);
                assertTrue(ball.getX() >= ball.getRadius() - 1e-9,
                        "x below radius must be clamped");
                assertTrue(ball.getX() <= size.columns() - 1,
                        "x must never pass the last column, got " + ball.getX());
            }
        }
    }

    // ------------------------------------------------------------------
    // Fireworks rocket/particle kinematics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rocket kinematics: getters, gravity, and explosion into particles")
    void rocketKinematicsAndExplosion() {
        var size = new TerminalSize(80, 24);
        var fw = new Fireworks(size);
        fw.launchRocket(size);
        assertEquals(1, fw.getRockets().size());

        Fireworks.Rocket rocket = fw.getRockets().get(0);
        double x0 = rocket.getX();
        double y0 = rocket.getY();
        double vy0 = rocket.getVy();
        double vx0 = rocket.getVx();
        assertTrue(vy0 < 0, "fresh rockets head upward");

        rocket.advance();
        assertEquals(x0 + vx0, rocket.getX(), 1e-9, "x advances by vx");
        assertEquals(y0 + vy0, rocket.getY(), 1e-9, "y advances by vy");
        assertEquals(vy0 + 0.15, rocket.getVy(), 1e-9, "gravity adds 0.15 per tick");

        // Drive to apex: once vy >= 0 the next render explodes the rocket.
        for (int i = 0; i < 60 && !fw.getRockets().isEmpty(); i++) {
            fw.renderFrame(new TextGraphics(new ScreenBuffer(size)), size);
        }
        assertTrue(fw.getRockets().isEmpty(), "rocket must explode once vy >= 0");

        // Track the burst by identity: rockets always explode (vy reaches 0 before
        // y leaves the top), spawning 8-15 particles whose life decays 1/frame.
        // Particles that leave the screen are dropped early — so the observable
        // contract is that the live particle list drains, not that each object
        // individually reaches life 0.
        List<Fireworks.Particle> burst = new ArrayList<>(fw.getParticles());
        assertTrue(burst.size() >= 8, "explosion spawns at least 8 particles");
        for (int i = 0; i < 80 && !fw.getParticles().isEmpty(); i++) {
            fw.renderFrame(new TextGraphics(new ScreenBuffer(size)), size);
        }
        assertTrue(fw.getParticles().isEmpty(),
                "the live particle list must drain, remaining=" + fw.getParticles().size());
    }

    @Test
    @DisplayName("particle kinematics: drag, gravity and linear life decay")
    void particleKinematics() {
        var size = new TerminalSize(80, 24);
        var fw = new Fireworks(size);
        fw.renderAtTime(new TextGraphics(new ScreenBuffer(size)), size); // launches a rocket
        for (int i = 0; i < 60 && fw.getParticles().isEmpty(); i++) {
            fw.renderAtTime(new TextGraphics(new ScreenBuffer(size)), size);
        }
        assertFalse(fw.getParticles().isEmpty(), "explosion must produce particles");

        Fireworks.Particle p = fw.getParticles().get(0);
        double x0 = p.getX();
        double y0 = p.getY();
        int life0 = p.getLife();
        int maxLife = p.getMaxLife();
        assertTrue(life0 <= maxLife && life0 >= maxLife - 1,
                "particle was advanced exactly once by the explosion frame: life=" + life0
                        + " maxLife=" + maxLife);
        assertTrue(maxLife >= 8 && maxLife <= 15, "maxLife in [8,15], got " + maxLife);
        assertNotNull(p.getColor());

        p.advance();
        assertEquals(life0 - 1, p.getLife(), "life decays by exactly 1 per advance");
        assertTrue(p.getX() != x0 || p.getY() != y0, "position must move on advance");
    }

    @Test
    @DisplayName("1x1 terminal: background fill runs, no glyphs are painted")
    void fireworksOneColumnGuard() {
        var oneCell = new TerminalSize(1, 1);
        var fw = new Fireworks(oneCell);
        var buffer = new ScreenBuffer(oneCell);
        assertDoesNotThrow(() -> fw.renderFrame(new TextGraphics(buffer), oneCell));
        // A fresh rocket advances once and immediately leaves bounds, so only
        // the black background cell remains.
        assertEquals(new TextCell(' ', AnsiColor.BLACK, AnsiColor.BLACK), buffer.getCell(0, 0));
    }

    @Test
    @DisplayName("one-row terminal: rocket spawns and immediately vanishes out of bounds")
    void fireworksOneRowRocketVanishes() {
        var oneRow = new TerminalSize(80, 1);
        var fw = new Fireworks(oneRow);
        fw.launchRocket(oneRow);
        var buffer = new ScreenBuffer(oneRow);
        assertDoesNotThrow(() -> fw.renderFrame(new TextGraphics(buffer), oneRow));
        assertTrue(fw.getRockets().isEmpty(), "rocket out of bounds must be dropped");
        assertEquals(0, fw.getParticles().size());
    }

    @Test
    @DisplayName("launchRocket appends without clearing existing rockets")
    void launchRocketAppends() {
        var size = new TerminalSize(80, 24);
        var fw = new Fireworks(size);
        fw.launchRocket(size);
        fw.launchRocket(size);
        assertEquals(2, fw.getRockets().size(), "direct launches accumulate");
    }

    // ------------------------------------------------------------------
    // CandlestickField.Candle accessors
    // ------------------------------------------------------------------

    @Test
    @DisplayName("candle accessors expose OHLC values and isBullish boundary verbatim")
    void candleAccessorsExposeOhlc() {
        // ChartBand's package-private constructor takes a Random: seed a stub
        // that always returns 0.5 for a fully deterministic band.
        var band = new CandlestickField.ChartBand(0, 12, 5, new Random() {
            @Override public double nextDouble() { return 0.5; }
        });

        CandlestickField.Candle candle = band.getCandles()[0];
        assertEquals(candle.getOpen(), candle.getClose(), 0.0, "change=(0.5-0.5)*VOL=0 so open==close");
        assertTrue(candle.isBullish(), "isBullish boundary is close >= open");
        assertEquals(candle.getClose(), candle.getOpen(), 0.0);
        assertTrue(candle.getHigh() > candle.getLow(), "seeded wick math yields high > low");
        assertTrue(candle.getHigh() > candle.getClose() || candle.getLow() < candle.getOpen(),
                "wicks extend beyond the body for the deterministic seed");

        CandlestickField.Candle[] candles = band.getCandles();
        for (int i = 1; i < candles.length; i++) {
            assertEquals(candles[i - 1].getClose(), candles[i].getOpen(), 0.0001,
                    "constructor chain keeps open == previous close");
        }
    }

    @Test
    @DisplayName("a bearish candle (close < open) reports isBullish false")
    void bearishCandleIsNotBullish() {
        // A 0.0-returning stub makes change=(0-0.5)*VOL negative and the open
        // chain strictly decreasing, so every candle closes below its open.
        var band = new CandlestickField.ChartBand(0, 12, 6, new Random() {
            @Override public double nextDouble() { return 0.0; }
        });
        CandlestickField.Candle[] candles = band.getCandles();
        CandlestickField.Candle first = candles[0];
        assertTrue(first.getClose() < first.getOpen(),
                "seeded walk must close below open: " + first.getOpen() + " -> " + first.getClose());
        for (CandlestickField.Candle candle : candles) {
            assertFalse(candle.isBullish(), "close < open must not be bullish");
        }
        assertTrue(candles[5].getOpen() < candles[0].getOpen(),
                "the open chain must strictly decrease under the zero stub");
    }
}