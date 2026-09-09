package io.jterm.demo;

import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.window.DefaultTextGUI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SpriteDemo's package-private seams: key handling, animation
 * ticking, frame rendering, and the sprite builders. No terminal needed —
 * the window draws to a plain ScreenBuffer.
 */
class SpriteDemoUnitTest {

    private SpriteDemo.SpriteWindow window() {
        return new SpriteDemo.SpriteWindow();
    }

    private static TextGraphics graphics(int w, int h) {
        var buf = new ScreenBuffer(new TerminalSize(w, h));
        return new TextGraphics(buf);
    }

    // ===== handleKey =====

    @Test
    void qKeySignalsQuit() {
        var w = window();
        assertTrue(SpriteDemo.handleKey(w, KeyStroke.character('q', false, false, false), null));
        assertTrue(SpriteDemo.handleKey(w, KeyStroke.character('Q', true, false, false), null));
    }

    @Test
    void eKeySpawnsExplosionEffect() {
        var w = window();
        int before = w.particleEffects.size();
        assertFalse(SpriteDemo.handleKey(w, KeyStroke.character('e', false, false, false), null));
        assertEquals(before + 1, w.particleEffects.size(), "explosion added");
    }

    @Test
    void sKeySpawnsSparkleEffect() {
        var w = window();
        int before = w.particleEffects.size();
        assertFalse(SpriteDemo.handleKey(w, KeyStroke.character('S', false, false, false), null));
        assertEquals(before + 1, w.particleEffects.size(), "sparkle spawned");
    }

    @Test
    void otherCharactersAreIgnored() {
        var w = window();
        int before = w.particleEffects.size();
        assertFalse(SpriteDemo.handleKey(w, KeyStroke.character('z', false, false, false), null));
        assertEquals(before, w.particleEffects.size());
        // Non-character keys bail immediately.
        assertFalse(SpriteDemo.handleKey(w, new KeyStroke(io.jterm.core.input.KeyType.ARROW_UP), null));
    }

    // ===== tickAnimations =====

    @Test
    void tickAdvancesAnimationsAndPrunesDeadEffects() {
        var w = window();
        // A short-lived explosion dies after its lifetime elapses.
        var fx = io.jterm.sprite.ParticleEffect.explosion(5, 5, 3);
        w.particleEffects.add(fx);
        for (int i = 0; i < 30; i++) SpriteDemo.tickAnimations(w, 100);
        assertFalse(w.particleEffects.contains(fx), "dead effect pruned");
        // Typewriter advanced: text eventually complete — at least it changed state.
        assertNotNull(w.typewriter);
        assertNotNull(w.marquee);
    }

    // ===== frame rendering =====

    @Test
    void renderFrameProducesInkOnBuffer() {
        var w = window();
        w.lastCols = 60;
        w.lastRows = 20;
        var buf = new ScreenBuffer(new TerminalSize(60, 20));
        var g = new TextGraphics(buf);
        SpriteDemo.tickAnimations(w, 16);
        SpriteDemo.renderFrame(w, g);
        int ink = 0;
        for (int r = 0; r < 20; r++)
            for (int c = 0; c < 60; c++)
                if (buf.getCell(c, r).character().charAt(0) != ' ') ink++;
        assertTrue(ink > 10, "rendered frame has visible content, got " + ink + " ink cells");
    }

    @Test
    void windowDrawDelegatesToRenderFrame() {
        var w = window();
        var g = graphics(40, 12);
        assertDoesNotThrow(() -> w.draw(g));
    }

    // ===== builders =====

    @Test
    void logoSpriteBuildsWithFrames() {
        var s = SpriteDemo.buildLogoSprite();
        assertNotNull(s);
    }

    @Test
    void spinnerAssetsBuild() {
        var sheet = SpriteDemo.buildSpinnerSheet();
        var sprite = SpriteDemo.buildSpinnerSprite();
        assertNotNull(sheet);
        assertNotNull(sprite);
    }

    @Test
    void animatedTextsBuild() {
        var type = SpriteDemo.buildTypewriterIntro();
        var marquee = SpriteDemo.buildMarqueeText();
        assertNotNull(type);
        assertNotNull(marquee);
    }

    @Test
    void logoFramesListNonEmpty() {
        var frames = SpriteDemo.buildLogoFrames();
        assertNotNull(frames);
        assertFalse(frames.isEmpty());
    }

    @Test
    void particleEffectsViewIsSafeOnEmptyWindow() {
        var w = window();
        assertTrue(SpriteDemo.getParticleEffects(w).isEmpty());
    }
}