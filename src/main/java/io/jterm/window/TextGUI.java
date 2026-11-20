package io.jterm.window;

import io.jterm.screen.Screen;
import io.jterm.core.input.KeyStroke;

import java.io.IOException;
import java.util.Collection;

/**
 * A text-mode GUI managing a stack of windows on a screen. Implementations own
 * the input loop: they decode keystrokes, route them to the active window, and
 * redraw the screen when windows change.
 */
public interface TextGUI extends AutoCloseable {
    /**
     * Returns the screen this GUI draws on.
     *
     * @return the backing screen
     */
    Screen getScreen();

    /**
     * Adds a window to this GUI and makes it active.
     *
     * @param window the window to add
     */
    void addWindow(Window window);

    /**
     * Removes a window from this GUI. If it was the active window, another
     * window becomes active if any remain.
     *
     * @param window the window to remove
     */
    void removeWindow(Window window);

    /**
     * Returns the currently active window, or {@code null} if none is active.
     *
     * @return the active window, or {@code null}
     */
    Window getActiveWindow();

    /**
     * Sets the active window, which receives keyboard input.
     *
     * @param window the window to activate, or {@code null} to clear
     */
    void setActiveWindow(Window window);

    /**
     * Polls and processes pending input non-blockingly.
     *
     * @return {@code true} if any keystrokes were processed
     * @throws IOException if reading input fails
     */
    boolean processInput() throws IOException;

    /**
     * Blocks until input is available and processes it.
     *
     * @throws IOException if reading input fails
     */
    void waitForInput() throws IOException;

    /**
     * Redraws the screen from the current window contents.
     *
     * @throws IOException if writing to the screen fails
     */
    void updateScreen() throws IOException;

    /**
     * Requests a screen refresh on the next {@link #updateScreen()} call.
     * Call before {@code updateScreen()} after handling keys directly.
     */
    void requestRefresh();

    /**
     * Closes the GUI and releases the screen.
     *
     * @throws IOException if closing the screen fails
     */
    @Override
    void close() throws IOException;

    /**
     * Returns all windows currently managed by this GUI.
     *
     * @return an unmodifiable view of the managed windows
     */
    java.util.Collection<Window> getWindows();

    /**
     * Returns the topmost window with the {@link WindowHint#MODAL} hint, or
     * {@code null} when no modal overlays the stack. Animated background
     * windows consult this for the OVERLAY-HOLD pause law (ambient scenes
     * hold their frame while a modal is active).
     *
     * @return the modal window, or {@code null}
     */
    default Window modalWindow() {
        return null;
    }
}