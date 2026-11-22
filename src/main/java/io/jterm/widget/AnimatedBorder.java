package io.jterm.widget;

import io.jterm.animation.AnimatedBorderEffect;
import io.jterm.animation.AnimationTimer;
import io.jterm.animation.BorderContext;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.style.Color;
import io.jterm.style.TextCell;
import io.jterm.style.Theme;
import io.jterm.style.ThemeManager;

/**
 * A border that animates its edge/corner characters on a timer.
 *
 * <p>On each frame tick (default ~10fps), the registered
 * {@link AnimatedBorderEffect} receives a mutable {@link BorderContext}
 * and can override individual corner and edge characters. After the effect
 * updates the context, the border redraws using the (possibly modified)
 * characters.</p>
 *
 * <p>Use {@link #start()} / {@link #stop()} to control the animation
 * lifecycle, and {@link #isAnimating()} to query the current state.</p>
 */
public class AnimatedBorder extends Border {

    private static final int DEFAULT_FPS = 10;

    private final AnimatedBorderEffect effect;
    private final BorderStyle storedStyle;
    private final int fps;
    private AnimationTimer timer;
    private long frameCounter;

    /**
     * Creates an animated border with the default single-line style.
     *
     * @param contents the component to wrap
     * @param effect   the animation effect
     */
    public AnimatedBorder(Component contents, AnimatedBorderEffect effect) {
        this(contents, BorderStyle.SINGLE_LINE, effect);
    }

    /**
     * Creates an animated border with the given style and effect.
     *
     * @param contents the component to wrap
     * @param style    the border style
     * @param effect   the animation effect
     */
    public AnimatedBorder(Component contents, BorderStyle style, AnimatedBorderEffect effect) {
        this(contents, style, effect, DEFAULT_FPS);
    }

    /**
     * Creates an animated border with the given style, effect, and frame rate.
     *
     * @param contents the component to wrap
     * @param style    the border style
     * @param effect   the animation effect
     * @param fps      the target frames per second
     */
    public AnimatedBorder(Component contents, BorderStyle style, AnimatedBorderEffect effect, int fps) {
        super(contents, style);
        this.storedStyle = style;
        this.effect = effect;
        this.fps = fps;
    }

    /**
     * Returns the border style.
     *
     * @return the border style
     */
    public BorderStyle getBorderStyle() {
        return storedStyle;
    }

    /** Starts the animation timer. Idempotent — safe to call when already running. */
    public void start() {
        if (timer != null && timer.isRunning()) return;
        timer = new AnimationTimer(fps, this::onFrame);
        timer.start();
    }

    /** Stops the animation timer. Idempotent — safe to call when not running. */
    public void stop() {
        if (timer != null) {
            timer.stop();
        }
    }

    /**
     * Returns whether the animation timer is currently running.
     *
     * @return true if the animation timer is currently running
     */
    public boolean isAnimating() {
        return timer != null && timer.isRunning();
    }

    /**
     * Returns the animation effect.
     *
     * @return the animation effect
     */
    public AnimatedBorderEffect getEffect() {
        return effect;
    }

    /**
     * Creates a fresh {@link BorderContext} for the current border dimensions
     * and style, initialized to the style defaults. Callers (including
     * effects and tests) can use this to prepare a context before calling
     * {@link #drawWithBorderContext(TextGraphics, BorderContext)}.
     *
     * @return a new border context initialized to the style defaults
     */
    public BorderContext createContext() {
        return new BorderContext(getSize(), storedStyle);
    }

    /**
     * Draws the border using a pre-built {@link BorderContext}.
     *
     * <p>This is the rendering entry point for tests and for the
     * animation timer: the effect updates the context, then this
     * method renders the border with those (possibly overridden)
     * characters.</p>
     *
     * <p>Per-cell foreground color overrides take precedence over the
     * border-wide color (from {@link BorderContext#getBorderColor()}),
     * which in turn takes precedence over the theme default.</p>
     *
     * @param graphics the text-graphics target
     * @param ctx      the border context with (possibly custom) characters
     */
    public void drawWithBorderContext(TextGraphics graphics, BorderContext ctx) {
        var size = getSize();
        if (size.columns() < 2 || size.rows() < 2) return;

        var theme = ThemeManager.active();
        // Fill background like Border.drawComponent does
        var bgCell = new TextCell(' ', theme.foreground(), theme.background());
        graphics.fillRectangle(0, 0, size.columns(), size.rows(), bgCell);
        // Base border foreground: per-cell > border color > theme default
        var baseBorderFg = ctx.getBorderColor() != null ? ctx.getBorderColor() : theme.border();

        // ---- Top row ----
        // TL corner
        drawBorderCell(graphics, 0, 0, ctx.getCorner(BorderContext.Corner.TL),
                ctx.getCornerColor(BorderContext.Corner.TL), baseBorderFg, theme);
        // Top edge
        for (int c = 1; c < size.columns() - 1; c++) {
            drawBorderCell(graphics, c, 0, ctx.getEdge(BorderContext.Side.TOP, c - 1),
                    ctx.getEdgeColor(BorderContext.Side.TOP, c - 1), baseBorderFg, theme);
        }
        // TR corner
        drawBorderCell(graphics, size.columns() - 1, 0, ctx.getCorner(BorderContext.Corner.TR),
                ctx.getCornerColor(BorderContext.Corner.TR), baseBorderFg, theme);

        // ---- Side edges ----
        for (int r = 1; r < size.rows() - 1; r++) {
            drawBorderCell(graphics, 0, r, ctx.getEdge(BorderContext.Side.LEFT, r - 1),
                    ctx.getEdgeColor(BorderContext.Side.LEFT, r - 1), baseBorderFg, theme);
            drawBorderCell(graphics, size.columns() - 1, r, ctx.getEdge(BorderContext.Side.RIGHT, r - 1),
                    ctx.getEdgeColor(BorderContext.Side.RIGHT, r - 1), baseBorderFg, theme);
        }

        // ---- Bottom row ----
        // BL corner
        drawBorderCell(graphics, 0, size.rows() - 1, ctx.getCorner(BorderContext.Corner.BL),
                ctx.getCornerColor(BorderContext.Corner.BL), baseBorderFg, theme);
        // Bottom edge
        for (int c = 1; c < size.columns() - 1; c++) {
            drawBorderCell(graphics, c, size.rows() - 1, ctx.getEdge(BorderContext.Side.BOTTOM, c - 1),
                    ctx.getEdgeColor(BorderContext.Side.BOTTOM, c - 1), baseBorderFg, theme);
        }
        // BR corner
        drawBorderCell(graphics, size.columns() - 1, size.rows() - 1, ctx.getCorner(BorderContext.Corner.BR),
                ctx.getCornerColor(BorderContext.Corner.BR), baseBorderFg, theme);

        // Draw children only (skip Border.drawComponent which would overwrite)
        drawChildren(graphics);
    }

    /**
     * Draws a single border cell, using per-cell color if set, falling back to the base border fg.
     */
    private void drawBorderCell(TextGraphics graphics, int col, int row, String ch,
                                Color cellColor, Color baseFg, Theme theme) {
        var fg = cellColor != null ? cellColor : baseFg;
        var cell = new TextCell(ch.charAt(0), fg, theme.background());
        graphics.setCell(col, row, cell);
    }

    /**
     * Draws only child components, bypassing Border's own border rendering.
     * This is used by drawWithBorderContext to avoid overwriting animated chars.
     */
    private void drawChildren(TextGraphics graphics) {
        for (var child : childrenView()) {
            if (!child.isVisible()) continue;
            var pos = child.getPosition();
            var childSize = child.getSize();
            if (childSize.columns() <= 0 || childSize.rows() <= 0) continue;
            var sub = io.jterm.graphics.TextGraphicsExtensions.subGraphics(graphics, pos, childSize);
            child.draw(sub);
        }
    }

    /**
     * Override parent drawComponent to use animated rendering when animating,
     * or fall back to standard border rendering when stopped.
     */
    @Override
    protected void drawComponent(TextGraphics graphics) {
        if (storedStyle.isEmpty()) {
            super.drawComponent(graphics);
            return;
        }
        // When animating, render with the latest border context (custom chars from the effect)
        var ctx = currentContext;
        if (ctx != null) {
            drawWithBorderContext(graphics, ctx);
        } else {
            super.drawComponent(graphics);
        }
    }

    /** The latest border context produced by the effect, or null if not animating. */
    private volatile BorderContext currentContext;

    // ---- Internal ----

    private void onFrame() {
        var ctx = createContext();
        effect.update(frameCounter++, ctx);
        currentContext = ctx;
        invalidate();
    }
}