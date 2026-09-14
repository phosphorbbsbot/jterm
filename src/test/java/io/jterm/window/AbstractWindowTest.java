package io.jterm.window;

import io.jterm.core.MockTerminal;
import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import io.jterm.style.TextCell;
import io.jterm.style.Theme;
import io.jterm.style.ThemeManager;
import io.jterm.widget.Button;
import io.jterm.widget.Component;
import io.jterm.widget.Label;
import io.jterm.widget.Panel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive JUnit 5 tests for {@link AbstractWindow}.
 *
 * <p>Covers constructors, title management, the contents panel, default
 * position/size, hints (with defensive-copy semantics), {@code setBounds}
 * decoration-aware content layout, {@code draw()} rendering of title bar,
 * borders and content, and focused-component management.
 *
 * <p>For draw() tests we render into a {@link ScreenBuffer} via
 * {@link TextGraphics} and inspect individual cells, mirroring the pattern
 * used by {@code WindowContentOffsetTest}.
 */
class AbstractWindowTest {

    /** Concrete subclass so we can instantiate AbstractWindow under test. */
    private static final class TestWindow extends AbstractWindow {
        TestWindow() { super(); }
        TestWindow(String title) { super(title); }
    }

    @BeforeEach
    void ensureDarkTheme() {
        ThemeManager.setActive(Theme.DARK);
    }

    @AfterEach
    void resetTheme() {
        ThemeManager.setActive(Theme.DARK);
    }

    // ------------------------------------------------------------------
    // Constructors / title
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Constructors and title")
    class TitleTests {

        @Test
        @DisplayName("constructor with title stores it in getTitle()")
        void constructorWithTitleStoresTitle() {
            var w = new TestWindow("Hello");
            assertEquals("Hello", w.getTitle());
        }

        @Test
        @DisplayName("constructor with empty title returns empty string")
        void constructorWithEmptyTitleReturnsEmptyString() {
            var w = new TestWindow("");
            assertEquals("", w.getTitle());
        }

        @Test
        @DisplayName("default constructor returns empty title")
        void defaultConstructorReturnsEmptyTitle() {
            var w = new TestWindow();
            assertEquals("", w.getTitle());
        }

        @Test
        @DisplayName("setTitle changes the title returned by getTitle()")
        void setTitleChangesTitle() {
            var w = new TestWindow("old");
            w.setTitle("new");
            assertEquals("new", w.getTitle());
        }

        @Test
        @DisplayName("setTitle(null) stores null")
        void setTitleNullStoresNull() {
            var w = new TestWindow("x");
            w.setTitle(null);
            assertNull(w.getTitle());
        }
    }

    // ------------------------------------------------------------------
    // Contents panel
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Contents panel")
    class ContentsTests {

        @Test
        @DisplayName("getContents returns a non-null Panel")
        void getContentsReturnsNonNullPanel() {
            var w = new TestWindow();
            Panel contents = w.getContents();
            assertNotNull(contents, "contents panel should never be null");
        }

        @Test
        @DisplayName("getContents returns the same panel instance across calls")
        void getContentsIsStableInstance() {
            var w = new TestWindow();
            assertSame(w.getContents(), w.getContents(),
                    "getContents should return the same panel instance");
        }
    }

    // ------------------------------------------------------------------
    // Defaults: position / size
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Default position and size")
    class DefaultsTests {

        @Test
        @DisplayName("default position is TerminalPosition.TOP_LEFT (0,0)")
        void defaultPositionIsTopLeft() {
            var w = new TestWindow();
            assertEquals(TerminalPosition.TOP_LEFT, w.getPosition());
            assertEquals(0, w.getPosition().column());
            assertEquals(0, w.getPosition().row());
        }

        @Test
        @DisplayName("default size is 40 columns by 20 rows")
        void defaultSizeIs40x20() {
            var w = new TestWindow();
            var size = w.getSize();
            assertEquals(new TerminalSize(40, 20), size);
            assertEquals(40, size.columns());
            assertEquals(20, size.rows());
        }
    }

    // ------------------------------------------------------------------
    // Hints
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Hints")
    class HintsTests {

        @Test
        @DisplayName("getHints on a fresh window is empty")
        void defaultHintsEmpty() {
            var w = new TestWindow();
            assertTrue(w.getHints().isEmpty(), "fresh window should have no hints");
        }

        @Test
        @DisplayName("setHints with FULLSCREEN is reflected in getHints")
        void setHintsFullscreenIsContained() {
            var w = new TestWindow();
            w.setHints(List.of(WindowHint.FULLSCREEN));
            var hints = w.getHints();
            assertTrue(hints.contains(WindowHint.FULLSCREEN),
                    "FULLSCREEN should be present after setHints");
            assertEquals(1, hints.size());
        }

        @Test
        @DisplayName("setHints replaces the previous hint list")
        void setHintsReplacesPreviousHints() {
            var w = new TestWindow();
            w.setHints(List.of(WindowHint.FULLSCREEN, WindowHint.MODAL));
            w.setHints(List.of(WindowHint.NO_DECORATIONS));
            var hints = w.getHints();
            assertEquals(List.of(WindowHint.NO_DECORATIONS), hints,
                    "second setHints should fully replace the first");
            assertFalse(hints.contains(WindowHint.FULLSCREEN));
            assertFalse(hints.contains(WindowHint.MODAL));
        }

        @Test
        @DisplayName("getHints returns a defensive copy")
        void getHintsReturnsDefensiveCopy() {
            var w = new TestWindow();
            w.setHints(List.of(WindowHint.FULLSCREEN));
            List<WindowHint> first = w.getHints();
            // Mutate the returned list — must not affect the window's state.
            first.clear();
            first.add(WindowHint.MODAL);

            List<WindowHint> second = w.getHints();
            assertTrue(second.contains(WindowHint.FULLSCREEN),
                    "mutating a returned hints list must not affect the window");
            assertEquals(1, second.size(),
                    "window hints should be unchanged after external mutation");
            assertFalse(second.contains(WindowHint.MODAL));
        }

        @Test
        @DisplayName("setHints with an empty list clears prior hints")
        void setHintsEmptyClearsPrior() {
            var w = new TestWindow();
            w.setHints(List.of(WindowHint.FULLSCREEN));
            w.setHints(List.of());
            assertTrue(w.getHints().isEmpty());
        }

        @Test
        @DisplayName("mutating the list passed to setHints does not affect the window")
        void setHintsDefensiveCopyOfInput() {
            var w = new TestWindow();
            var input = new ArrayList<>(List.of(WindowHint.FULLSCREEN));
            w.setHints(input);
            input.clear(); // mutate the caller's list
            assertEquals(List.of(WindowHint.FULLSCREEN), w.getHints(),
                    "mutating the input list after setHints must not affect the window");
        }
    }

    // ------------------------------------------------------------------
    // setBounds
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("setBounds")
    class SetBoundsTests {

        @Test
        @DisplayName("setBounds changes position and size")
        void setBoundsChangesPositionAndSize() {
            var w = new TestWindow();
            w.setBounds(new TerminalPosition(5, 3), new TerminalSize(30, 12));
            assertEquals(new TerminalPosition(5, 3), w.getPosition());
            assertEquals(new TerminalSize(30, 12), w.getSize());
        }

        @Test
        @DisplayName("setBounds without NO_DECORATIONS insets contents by 1 col left, 1 row top, 1 row bottom")
        void setBoundsInsetsContentsWithDecorations() {
            var w = new TestWindow("T");
            w.setBounds(new TerminalPosition(2, 2), new TerminalSize(20, 10));
            var c = w.getContents();
            // +1 column (left border), +1 row (title bar)
            assertEquals(new TerminalPosition(3, 3), c.getPosition(),
                    "contents should be inset by (1, titleBarHeight=1) from window origin");
            // 20 - 2 (left+right borders) = 18 columns; 10 - 1 (title) - 1 (bottom) = 8 rows
            assertEquals(new TerminalSize(18, 8), c.getSize(),
                    "contents size should subtract 2 columns and 2 rows for decorations");
        }

        @Test
        @DisplayName("setBounds with NO_DECORATIONS gives contents the full area")
        void setBoundsNoDecorationsGivesFullArea() {
            var w = new TestWindow("T");
            w.setHints(List.of(WindowHint.NO_DECORATIONS));
            var pos = new TerminalPosition(4, 5);
            var size = new TerminalSize(24, 8);
            w.setBounds(pos, size);
            var c = w.getContents();
            assertEquals(pos, c.getPosition(),
                    "with NO_DECORATIONS contents position should equal window position");
            assertEquals(size, c.getSize(),
                    "with NO_DECORATIONS contents size should equal window size");
        }

        @Test
        @DisplayName("setBounds with empty title still reserves a title bar row")
        void setBoundsEmptyTitleStillReservesTitleBar() {
            var w = new TestWindow(); // empty title
            w.setBounds(new TerminalPosition(0, 0), new TerminalSize(20, 10));
            var c = w.getContents();
            // titleBarHeight is 1 even when title is empty
            assertEquals(new TerminalPosition(1, 1), c.getPosition());
            assertEquals(new TerminalSize(18, 8), c.getSize());
        }
    }

    // ------------------------------------------------------------------
    // draw()
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("draw()")
    class DrawTests {

        /** Render a window of the given size at (0,0) into a fresh buffer. */
        private ScreenBuffer drawWindow(AbstractWindow w, TerminalSize size) {
            w.setBounds(TerminalPosition.TOP_LEFT, size);
            var buf = new ScreenBuffer(size);
            var g = new TextGraphics(buf);
            w.draw(g);
            return buf;
        }

        @Test
        @DisplayName("draw() with a title renders the title text in the title bar")
        void drawRendersTitleInTitleBar() {
            var w = new TestWindow("MyTitle");
            var buf = drawWindow(w, new TerminalSize(30, 8));
            // Title is drawn at column 2 with surrounding spaces: " MyTitle "
            // so 'M' should be at column 3, row 0.
            char atM = buf.getCell(3, 0).character().charAt(0);
            assertEquals('M', atM,
                    "first char of title should be at col 3 (2 + 1 leading space), row 0");
            // Make sure the full title text is present in row 0.
            StringBuilder row0 = new StringBuilder();
            for (int c = 0; c < 30; c++) row0.append(buf.getCell(c, 0).character());
            assertTrue(row0.toString().contains("MyTitle"),
                    "title bar row should contain the title text; was: " + row0);
        }

        @Test
        @DisplayName("draw() with empty title renders a title bar but no title text")
        void drawEmptyTitleRendersTitleBarWithoutTitleText() {
            var w = new TestWindow();
            var buf = drawWindow(w, new TerminalSize(20, 6));
            // Row 0 should still be a filled title bar (not default empty cells),
            // but should not contain any non-space title characters.
            // We verify it is populated with the title background color.
            var theme = ThemeManager.active();
            for (int c = 0; c < 20; c++) {
                var cell = buf.getCell(c, 0);
                assertEquals(theme.titleBg(), cell.bg(),
                        "title bar row 0 should use title background at col " + c);
            }
            // And no alphabetic title text should appear.
            boolean foundNonSpace = false;
            for (int c = 0; c < 20; c++) {
                char ch = buf.getCell(c, 0).character().charAt(0);
                if (Character.isLetterOrDigit(ch)) {
                    foundNonSpace = true;
                    break;
                }
            }
            assertFalse(foundNonSpace, "empty title should not render any letter/digit text");
        }

        @Test
        @DisplayName("draw() with NO_DECORATIONS renders contents directly (no border/title)")
        void drawNoDecorationsRendersContentsDirectly() {
            var w = new TestWindow("IgnoredTitle");
            w.setHints(List.of(WindowHint.NO_DECORATIONS));
            // Add a label so we can see content appear at (0,0).
            var contents = w.getContents();
            contents.addComponent(new Label("X", AnsiColor.BRIGHT_RED, AnsiColor.BLACK));
            // No layout manager → children keep default bounds; we set them
            // explicitly so the label has a known area to draw into.
            var label = (Label) contents.getChildren().get(0);
            label.setBounds(new TerminalPosition(0, 0), new TerminalSize(1, 1));

            var buf = drawWindow(w, new TerminalSize(10, 3));
            // With NO_DECORATIONS there should be no title bar/border at (0,0):
            // the label 'X' should be at col 0, row 0.
            char topLeft = buf.getCell(0, 0).character().charAt(0);
            assertEquals('X', topLeft,
                    "with NO_DECORATIONS content should render at (0,0); got: " + topLeft);
            // And the bottom-right corner should NOT be a decoration corner char.
            char br = buf.getCell(9, 2).character().charAt(0);
            assertNotEquals('┘', br, "NO_DECORATIONS should not draw bottom-right corner");
        }

        @Test
        @DisplayName("draw() with TRANSPARENT hint skips window-level background fill")
        void drawTransparentSkipsBackgroundFill() {
            // Verify that a TRANSPARENT window does NOT fill the buffer with
            // theme background before drawing content. We pre-fill with 'X'
            // and check that cells outside the content panel survive.
            var size = new TerminalSize(10, 5);
            var buf = new ScreenBuffer(size, new TextCell('X', AnsiColor.WHITE, AnsiColor.BLACK));
            var g = new TextGraphics(buf);

            var w = new TestWindow("T");
            w.setHints(List.of(WindowHint.NO_DECORATIONS, WindowHint.TRANSPARENT));
            w.setBounds(TerminalPosition.TOP_LEFT, size);
            // Shrink the content panel so it only covers part of the window.
            w.getContents().setBounds(new TerminalPosition(0, 0), new TerminalSize(4, 2));

            w.draw(g);

            // Cell at (8,4) is outside the content panel (4x2 at origin).
            // With TRANSPARENT, the window does not fill, so this cell should
            // still be 'X' from the pre-fill.
            assertEquals('X', buf.getCell(8, 4).character().charAt(0),
                    "TRANSPARENT window should not fill cells outside content panel");
            // Cell at (0,0) is inside the content panel. The content panel is
            // a Panel (no fill), so it won't overwrite 'X' either — but the
            // window's draw() calls contents.draw() which just draws children.
            // With no children added, the cell stays 'X'.
            assertEquals('X', buf.getCell(0, 0).character().charAt(0),
                    "Panel without children should not fill its area");
        }

        @Test
        @DisplayName("draw() without TRANSPARENT hint fills background, overwriting prior content")
        void drawNonTransparentFillsBackground() {
            var size = new TerminalSize(10, 5);
            var buf = new ScreenBuffer(size, new TextCell('X', AnsiColor.WHITE, AnsiColor.BLACK));
            var g = new TextGraphics(buf);

            var w = new TestWindow("T");
            w.setHints(List.of(WindowHint.NO_DECORATIONS)); // no TRANSPARENT
            w.setBounds(TerminalPosition.TOP_LEFT, size);
            w.draw(g);

            // The background fill should have replaced 'X' with ' '.
            assertEquals(' ', buf.getCell(0, 0).character().charAt(0),
                    "non-TRANSPARENT window should fill background");
        }

        @Test
        @DisplayName("draw() draws left and right side border characters (│)")
        void drawSideBorders() {
            var w = new TestWindow("T");
            var buf = drawWindow(w, new TerminalSize(10, 5));
            // Left border at column 0 for rows 1..(rows-2); bottom row is └...┘
            for (int r = 1; r < 4; r++) {
                assertEquals('│', buf.getCell(0, r).character().charAt(0),
                        "left border at (0," + r + ") should be │");
                assertEquals('│', buf.getCell(9, r).character().charAt(0),
                        "right border at (9," + r + ") should be │");
            }
        }

        @Test
        @DisplayName("draw() draws bottom border corners └ and ┘")
        void drawBottomCorners() {
            var w = new TestWindow("T");
            var buf = drawWindow(w, new TerminalSize(10, 5));
            int bottom = 4;
            assertEquals('└', buf.getCell(0, bottom).character().charAt(0),
                    "bottom-left corner should be └");
            assertEquals('┘', buf.getCell(9, bottom).character().charAt(0),
                    "bottom-right corner should be ┘");
        }

        @Test
        @DisplayName("draw() draws bottom border horizontal fill ─ between corners")
        void drawBottomFill() {
            var w = new TestWindow("T");
            var buf = drawWindow(w, new TerminalSize(10, 5));
            int bottom = 4;
            for (int c = 1; c < 9; c++) {
                assertEquals('─', buf.getCell(c, bottom).character().charAt(0),
                        "bottom border fill at (" + c + "," + bottom + ") should be ─");
            }
        }

        @Test
        @DisplayName("draw() does not throw when size is very small")
        void drawSmallSizeDoesNotThrow() {
            var w = new TestWindow("T");
            // 2 columns is too small for the bottom border string; ensure no crash.
            assertDoesNotThrow(() -> drawWindow(w, new TerminalSize(2, 3)));
        }

        @Test
        @DisplayName("draw() content is inset and does not overwrite the left border")
        void drawContentDoesNotOverwriteLeftBorder() {
            var w = new TestWindow("T");
            var contents = w.getContents();
            // Add a label that would overwrite the border if not inset.
            contents.addComponent(new Label("ABCDEF", AnsiColor.BRIGHT_CYAN, AnsiColor.BLACK));
            var label = (Label) contents.getChildren().get(0);
            label.setBounds(new TerminalPosition(0, 0), new TerminalSize(6, 1));

            var buf = drawWindow(w, new TerminalSize(12, 5));
            // Column 0 on content rows must remain the border │.
            for (int r = 1; r < 4; r++) {
                assertEquals('│', buf.getCell(0, r).character().charAt(0),
                        "left border must remain intact at (0," + r + ")");
            }
        }
    }

    // ------------------------------------------------------------------
    // Focus management
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Focused component management")
    class FocusTests {

        @Test
        @DisplayName("default focused component is null")
        void defaultFocusedIsNull() {
            var w = new TestWindow();
            assertNull(w.getFocusedComponent());
        }

        @Test
        @DisplayName("setFocusedComponent sets focus and marks component focused")
        void setFocusedComponentSetsFocus() {
            var w = new TestWindow();
            var btn = new Button("OK");
            w.setFocusedComponent(btn);
            assertSame(btn, w.getFocusedComponent());
            assertTrue(btn.isFocused(), "component should be focused after setFocusedComponent");
        }

        @Test
        @DisplayName("setFocusedComponent(null) clears focus on previous component")
        void setFocusedComponentNullClearsFocus() {
            var w = new TestWindow();
            var btn = new Button("OK");
            w.setFocusedComponent(btn);
            assertTrue(btn.isFocused());
            w.setFocusedComponent(null);
            assertNull(w.getFocusedComponent());
            assertFalse(btn.isFocused(), "previous component should lose focus when cleared");
        }

        @Test
        @DisplayName("switching focused component: old loses focus, new gains focus")
        void switchingFocusedComponentTransfersFocus() {
            var w = new TestWindow();
            var a = new Button("A");
            var b = new Button("B");
            w.setFocusedComponent(a);
            assertTrue(a.isFocused());
            assertFalse(b.isFocused());

            w.setFocusedComponent(b);
            assertSame(b, w.getFocusedComponent());
            assertFalse(a.isFocused(), "old component should lose focus on switch");
            assertTrue(b.isFocused(), "new component should gain focus on switch");
        }

        @Test
        @DisplayName("setting the same component twice keeps it focused")
        void settingSameComponentTwiceKeepsFocused() {
            var w = new TestWindow();
            var btn = new Button("OK");
            w.setFocusedComponent(btn);
            w.setFocusedComponent(btn);
            assertTrue(btn.isFocused());
            assertSame(btn, w.getFocusedComponent());
        }

        @Test
        @DisplayName("getFocusedComponent returns what was set")
        void getFocusedComponentReturnsSetComponent() {
            var w = new TestWindow();
            Component c = new Label("x");
            w.setFocusedComponent(c);
            assertSame(c, w.getFocusedComponent());
        }
    }

    @Nested
    @DisplayName("preferred size")
    class PreferredSizeTests {

        @Test
        @DisplayName("getPreferredSize derives from contents, not the 40x20 default size")
        void getPreferredSizeDerivesFromContents() {
            var w = new TestWindow("win");
            // Default size is 40x20; a 60-column label must be reported so
            // CENTERED windows size to their content instead of clipping.
            w.getContents().setLayoutManager(new io.jterm.layout.LinearLayout(io.jterm.layout.LinearLayout.Direction.VERTICAL, 0));
            w.getContents().addComponent(new Label("x".repeat(60)));
            assertEquals(60, w.getPreferredSize().columns() - 2,
                    "content width (window pref minus insets) must follow the widest content line");
        }

        @Test
        @DisplayName("getPreferredSize accounts for decorations (border/title insets)")
        void getPreferredSizeAccountsForDecorations() {
            var w = new TestWindow("win");
            w.getContents().setLayoutManager(new io.jterm.layout.LinearLayout(io.jterm.layout.LinearLayout.Direction.VERTICAL, 0));
            w.getContents().addComponent(new Label("x".repeat(30)));
            // contents needs 30 columns; window adds 1 left + 1 right inset
            assertEquals(32, w.getPreferredSize().columns(),
                    "preferred width must include decoration insets");
        }

        @Test
        @DisplayName("getPreferredSize respects explicit setPreferredSize override")
        void getPreferredSizeRespectsOverride() {
            var w = new TestWindow();
            // Override on the root contents panel propagates through the
            // window's decoration math (77+2 cols, 13+2 rows).
            w.getContents().setPreferredSizeOverride(new TerminalSize(77, 13));
            assertEquals(new TerminalSize(79, 15), w.getPreferredSize());
        }
    }
}