package io.jterm.widget;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.core.input.KeyStroke;
import io.jterm.core.input.KeyType;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import io.jterm.style.CellStyle;
import io.jterm.style.Theme;
import io.jterm.style.ThemeManager;
import io.jterm.widget.model.DefaultGridModel;
import io.jterm.widget.model.GridColumn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers remaining {@link DataGrid} branches: empty/zero-viewport and
 * no-column drawing, selection listener firing on real changes only, selected
 * × focused style matrix, cell stylers (style with null fg/bg, null style),
 * preferred size branches, Emacs ctrl-key bindings and default rejection,
 * ENTER activation, and horizontal scrolling bounds.
 */
class DataGridBranchesCoverageTest {

    record TestRow(String name, Integer age, double score) {}

    private Theme savedTheme;

    @BeforeEach
    void setUp() {
        savedTheme = ThemeManager.active();
        ThemeManager.setActive(Theme.DARK);
    }

    @AfterEach
    void tearDown() {
        ThemeManager.setActive(savedTheme);
    }

    private DefaultGridModel<TestRow> model(TestRow... rows) {
        var model = new DefaultGridModel<TestRow>();
        model.addRows(List.of(rows));
        return model;
    }

    private DataGrid<TestRow> grid(DefaultGridModel<TestRow> model) {
        var columns = List.of(
                GridColumn.<TestRow>text("Name", TestRow::name),
                new GridColumn<TestRow>("Age", r -> r.age(), GridColumn.Alignment.RIGHT, "%d", 0, 0, null),
                GridColumn.<TestRow>doubleCol("Score", "%.2f", TestRow::score));
        var grid = new DataGrid<>(columns, model);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(40, 8));
        return grid;
    }

    private ScreenBuffer draw(DataGrid<?> grid, int width, int height) {
        var buf = new ScreenBuffer(new TerminalSize(width, height));
        grid.draw(new TextGraphics(buf));
        return buf;
    }

    // ---- degenerate draw paths --------------------------------------------------

    @Test
    void gridWithNoColumnsClearsViewport() {
        var grid = new DataGrid<TestRow>(List.of(), model(new TestRow("a", 1, 2.0)));
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        var buf = assertDoesNotThrow(() -> draw(grid, 20, 5));
        assertEquals(' ', buf.getCell(3, 3).character().charAt(0));
    }

    @Test
    void zeroRowsModelDrawsOnlyHeader() {
        var grid = grid(model());
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(30, 6));
        assertDoesNotThrow(() -> grid.setSelectedRow(5)); // clamps on empty model
        var buf = assertDoesNotThrow(() -> draw(grid, 30, 6));
        assertTrue(buf.getCell(0, 0).is('N'), "header still renders");
        assertEquals(' ', buf.getCell(0, 1).character().charAt(0), "no data rows below the header");
    }

    @Test
    void gridWithZeroViewportClearsSilently() {
        var grid = new DataGrid<TestRow>(List.of(GridColumn.text("N", (TestRow r) -> r.name())), model());
        grid.setBounds(TerminalPosition.TOP_LEFT, TerminalSize.ZERO);
        var buf = assertDoesNotThrow(() -> draw(grid, 1, 1));
        assertNotNull(buf);
    }

    @Test
    void columnsWithoutModelDrawsHeaderOnly() {
        var grid = new DataGrid<TestRow>(List.of(GridColumn.text("Name", TestRow::name)));
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        var buf = assertDoesNotThrow(() -> draw(grid, 20, 5));
        assertTrue(buf.getCell(0, 0).is('N'));
    }

    @Test
    void selectionListenerFiresOnlyOnActualChange() {
        var grid = grid(model(new TestRow("a", 1, 2.0), new TestRow("b", 2, 3.0)));
        grid.setFocusable(true);
        int[] fired = {0};
        grid.addSelectionListener(() -> fired[0]++);
        grid.setSelectedRow(1);
        assertEquals(1, fired[0]);
        grid.setSelectedRow(1); // same value: no event
        assertEquals(1, fired[0], "setting the same row must not fire");
        grid.setSelectedRow(0);
        assertEquals(2, fired[0]);
    }

    @Test
    void getSelectedItemReturnsNullForEmptyModel() {
        var grid = new DataGrid<TestRow>(List.of(GridColumn.text("N", TestRow::name)), model());
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        assertNull(grid.getSelectedItem());
        grid.setSelectedRow(3); // clamps, model empty
        assertEquals(0, grid.getSelectedRow());
        assertNull(grid.getSelectedItem());
    }

    @Test
    void nullModelGetSelectedItemIsNull() {
        var grid = new DataGrid<TestRow>(List.of(GridColumn.text("N", TestRow::name)), null);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        assertNull(grid.getSelectedItem());
        assertEquals(0, grid.getSelectedRow());
        assertDoesNotThrow(() -> draw(grid, 20, 5));
    }

    // ---- selection × focus style matrix -------------------------------------------

    @Test
    void selectedAndFocusedUsesSelectionColors() {
        var grid = grid(model(new TestRow("a", 1, 2.0)));
        grid.setFocused(true);
        grid.setSelectedRow(0);
        var theme = ThemeManager.active();
        var buf = draw(grid, 40, 8);
        assertEquals(theme.selectionFg(), buf.getCell(0, 1).fg());
    }

    @Test
    void selectedNotFocusedUsesThemeColors() {
        var grid = grid(model(new TestRow("a", 1, 2.0)));
        grid.setFocused(false);
        grid.setSelectedRow(0);
        var theme = ThemeManager.active();
        var buf = draw(grid, 40, 8);
        assertEquals(theme.foreground(), buf.getCell(0, 1).fg());
        assertNotEquals(theme.selectionFg(), buf.getCell(0, 1).fg());
    }

    @Test
    void cellStylerWithPartialStyleFillsMissingColorsFromTheme() {
        var columns = List.of(
                GridColumn.<TestRow>text("Name", TestRow::name)
                        .withStyler(v -> new CellStyle(AnsiColor.CYAN, null)),
                GridColumn.<TestRow>doubleCol("Score", "%.2f", TestRow::score));
        var grid = new DataGrid<>(columns, model(new TestRow("aa", 1, 3.0), new TestRow("bb", 2, 4.0)));
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(30, 6));
        var t = ThemeManager.active();
        // Select row 1 so the probed data row 0 is unselected and the styler
        // branch runs (selected rows bypass the styler with theme colors).
        grid.setSelectedRow(1);
        var buf = draw(grid, 30, 6);
        var cell = buf.getCell(0, 1);
        assertEquals(AnsiColor.CYAN, cell.fg(), "styler fg applied");
        assertEquals(t.background(), cell.bg(), "null styler bg falls to theme");
    }

    @Test
    void cellStylerReturningNullUsesThemeColors() {
        var columns = List.of(
                GridColumn.<TestRow>text("Name", TestRow::name).withStyler(v -> null));
        var grid = new DataGrid<>(columns, model(new TestRow("aa", 1, 3.0), new TestRow("bb", 2, 4.0)));
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(30, 6));
        grid.setSelectedRow(1);
        var buf = draw(grid, 30, 6);
        assertEquals(ThemeManager.active().foreground(), buf.getCell(0, 1).fg());
    }

    // ---- preferred size ------------------------------------------------------------

    @Test
    void preferredSizeWithoutColumnsIsOneByThree() {
        var grid = new DataGrid<TestRow>(List.of());
        var size = grid.getPreferredSize();
        assertEquals(1, size.columns());
        assertEquals(3, size.rows());
    }

    @Test
    void preferredSizeHonorsMinAndMaxWidth() {
        var columns = List.of(
                GridColumn.<TestRow>text("N", TestRow::name).withMinWidth(9).withMaxWidth(12));
        var grid = new DataGrid<>(columns, model(new TestRow("tiny", 1, 1.0)));
        var size = grid.getPreferredSize();
        assertEquals(9, size.columns(), "header width 1 lifted to min 9, plus 0 separators for 1 col");
    }

    @Test
    void preferredHeightClampedToThreeAndTwelve() {
        var grid = grid(model(new TestRow("a", 1, 1.0)));
        assertEquals(3, grid.getPreferredSize().rows(), "1 row + header clamps to 3");
        var big = grid(model(
                new TestRow("r0", 0, 0.0), new TestRow("r1", 1, 1.0), new TestRow("r2", 2, 2.0),
                new TestRow("r3", 3, 3.0), new TestRow("r4", 4, 4.0), new TestRow("r5", 5, 5.0),
                new TestRow("r6", 6, 6.0), new TestRow("r7", 7, 7.0), new TestRow("r8", 8, 8.0),
                new TestRow("r9", 9, 9.0), new TestRow("r10", 10, 10.0), new TestRow("r11", 11, 11.0),
                new TestRow("r12", 12, 12.0), new TestRow("r13", 13, 13.0)));
        assertEquals(12, big.getPreferredSize().rows(), "capped at 12");
    }

    // ---- keyboard -------------------------------------------------------------------

    @Test
    void emacsBindingsAndArrowsNavigate() {
        var grid = grid(model(
                new TestRow("a", 1, 1.0), new TestRow("b", 2, 2.0), new TestRow("c", 3, 3.0)));
        assertTrue(grid.handleKeyStroke(new KeyStroke(KeyType.CHARACTER, 'n', true, false, false)));
        assertEquals(1, grid.getSelectedRow());
        assertTrue(grid.handleKeyStroke(new KeyStroke(KeyType.ARROW_DOWN)));
        assertEquals(2, grid.getSelectedRow());
        assertTrue(grid.handleKeyStroke(new KeyStroke(KeyType.CHARACTER, 'p', true, false, false)));
        assertEquals(1, grid.getSelectedRow());
        assertTrue(grid.handleKeyStroke(new KeyStroke(KeyType.ARROW_UP)));
        assertEquals(0, grid.getSelectedRow());
        // Unbound ctrl combo is NOT consumed.
        assertFalse(grid.handleKeyStroke(new KeyStroke(KeyType.CHARACTER, 'x', true, false, false)));
    }

    @Test
    void enterFiresActivation() {
        var grid = grid(model(new TestRow("a", 1, 1.0)));
        int[] fired = {0};
        grid.addActivationListener(() -> fired[0]++);
        assertTrue(grid.handleKeyStroke(new KeyStroke(KeyType.ENTER)));
        assertEquals(1, fired[0]);
    }

    @Test
    void pageUpPageDownHomeAndEnd() {
        var rows = new DefaultGridModel<TestRow>();
        for (int i = 0; i < 12; i++) {
            rows.addRow(new TestRow("r" + i, i, i * 1.0));
        }
        var grid = grid(rows);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(40, 5));
        assertTrue(grid.handleKeyStroke(new KeyStroke(KeyType.PAGE_DOWN)));
        assertEquals(4, grid.getSelectedRow());
        assertTrue(grid.handleKeyStroke(new KeyStroke(KeyType.END)));
        assertEquals(11, grid.getSelectedRow());
        assertTrue(grid.handleKeyStroke(new KeyStroke(KeyType.HOME)));
        assertEquals(0, grid.getSelectedRow());
        assertTrue(grid.handleKeyStroke(new KeyStroke(KeyType.PAGE_UP)));
        assertEquals(0, grid.getSelectedRow());
    }

    @Test
    void endWithNullModelIsSafe() {
        var grid = new DataGrid<TestRow>(List.of(GridColumn.text("N", TestRow::name)), null);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        assertTrue(grid.handleKeyStroke(new KeyStroke(KeyType.END)));
        assertEquals(0, grid.getSelectedRow());
    }

    // ---- horizontal scrolling ---------------------------------------------------------

    @Test
    void scrollRightIsBoundedByMaxOffset() {
        var grid = grid(model(new TestRow("a", 1, 1.0)));
        for (int i = 0; i < 50; i++) {
            grid.scrollRight();
        }
        int max = grid.getScrollOffsetX();
        assertTrue(max >= 0);
        grid.scrollLeft();
        assertTrue(grid.getScrollOffsetX() <= max);
        // With no columns the max offset is zero.
        var empty = new DataGrid<TestRow>(List.of());
        empty.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        empty.scrollRight();
        assertEquals(0, empty.getScrollOffsetX());
    }

    // ---- formatting -------------------------------------------------------------------

    @Test
    void nullValueRendersAsEmptyCell() {
        var grid = grid(model(new TestRow("a", null, 1.0)));
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(40, 6));
        var buf = assertDoesNotThrow(() -> draw(grid, 40, 6));
        assertNotNull(buf);
        // The null value renders as blanks, not the string "null".
        boolean sawNullLiteral = false;
        for (int c = 0; c < 40; c++) {
            if (buf.getCell(c, 1).is('l') && c + 3 < 40
                    && buf.getCell(c + 1, 1).is('u') && buf.getCell(c + 2, 1).is('l') && buf.getCell(c + 3, 1).is('l')) {
                sawNullLiteral = true;
            }
        }
        assertFalse(sawNullLiteral, "null cell should render empty, not the literal 'null'");
    }

    @Test
    void formattedValueUsesColumnFormat() {
        var buf = draw(grid(model(new TestRow("a", 7, 1.5))), 40, 6);
        boolean sawSeven = false;
        for (int c = 0; c < 40; c++) {
            if (buf.getCell(c, 1).is('7')) sawSeven = true;
        }
        assertTrue(sawSeven, "formatted \"7\" should appear in the Age column");
    }
}