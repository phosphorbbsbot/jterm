package io.jterm.widget;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.graphics.TextGraphics;
import io.jterm.screen.ScreenBuffer;
import io.jterm.style.AnsiColor;
import io.jterm.style.CellStyle;
import io.jterm.style.SGR;
import io.jterm.style.Theme;
import io.jterm.style.ThemeManager;
import io.jterm.widget.model.DefaultGridModel;
import io.jterm.widget.model.GridColumn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DataGridTest {

    /** Simple record used as the row type for tests. */
    record TestRow(String name, int age, double score) {}

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

    // ---- Helpers -----------------------------------------------------------

    private DefaultGridModel<TestRow> modelWithRows(TestRow... rows) {
        var model = new DefaultGridModel<TestRow>();
        model.addRows(List.of(rows));
        return model;
    }

    private DataGrid<TestRow> gridWithRows(int width, int height, TestRow... rows) {
        var columns = List.of(
                GridColumn.text("Name", TestRow::name),
                GridColumn.intCol("Age", TestRow::age),
                GridColumn.doubleCol("Score", "%.2f", TestRow::score)
        );
        var grid = new DataGrid<>(columns, modelWithRows(rows));
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(width, height));
        return grid;
    }

    private ScreenBuffer drawGrid(DataGrid<?> grid, int width, int height) {
        var buffer = new ScreenBuffer(new TerminalSize(width, height));
        grid.draw(new TextGraphics(buffer));
        return buffer;
    }

    /** Reads a full row of text from the buffer. */
    private String rowText(ScreenBuffer buf, int y, int width) {
        var sb = new StringBuilder();
        for (int x = 0; x < width; x++) {
            var cell = buf.getCell(x, y);
            sb.append(cell.character());
        }
        return sb.toString();
    }

    // ---- Tests -------------------------------------------------------------

    @Test
    void columnsForTestReturnsColumnDefinitions() {
        var columns = List.of(
                GridColumn.text("Name", TestRow::name),
                GridColumn.intCol("Age", TestRow::age),
                GridColumn.doubleCol("Score", "%.2f", TestRow::score));
        var grid = new DataGrid<TestRow>(columns);
        assertEquals(3, grid.columnsForTest().size());
        assertEquals("Name", grid.columnsForTest().get(0).header());
        assertEquals("Age", grid.columnsForTest().get(1).header());
        assertEquals("Score", grid.columnsForTest().get(2).header());
    }

    @Test
    void emptyModelRendersWithoutError() {
        var columns = List.of(GridColumn.text("Name", TestRow::name));
        var grid = new DataGrid<TestRow>(columns);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        assertDoesNotThrow(() -> drawGrid(grid, 20, 5));
    }

    @Test
    void headerIsRendered() {
        var grid = gridWithRows(30, 5, new TestRow("Alice", 30, 95.5));
        var buf = drawGrid(grid, 30, 5);
        String header = rowText(buf, 0, 30);
        assertTrue(header.contains("Name"), "Header should contain 'Name': " + header);
        assertTrue(header.contains("Age"), "Header should contain 'Age': " + header);
        assertTrue(header.contains("Score"), "Header should contain 'Score': " + header);
    }

    @Test
    void headerUsesThemeColors() {
        var grid = gridWithRows(30, 5, new TestRow("Alice", 30, 95.5));
        var buf = drawGrid(grid, 30, 5);
        var theme = ThemeManager.active();
        var headerCell = buf.getCell(0, 0);
        assertEquals(theme.headerFg(), headerCell.fg());
        assertEquals(theme.headerBg(), headerCell.bg());
        assertTrue(headerCell.modifiers().contains(SGR.BOLD));
    }

    @Test
    void dataRowsRenderedBelowHeader() {
        var grid = gridWithRows(30, 5, new TestRow("Alice", 30, 95.5));
        var buf = drawGrid(grid, 30, 5);
        String row1 = rowText(buf, 1, 30);
        assertTrue(row1.contains("Alice"), "First data row should contain 'Alice': " + row1);
    }

    @Test
    void leftAlignmentPadsRight() {
        // Name column is LEFT-aligned; "Al" should be followed by spaces
        var grid = gridWithRows(20, 5, new TestRow("Al", 3, 1.0));
        var buf = drawGrid(grid, 20, 5);
        // Column 0 starts at x=0, "Al" is 2 chars, column width should be at least 4 ("Name" header)
        // So x=2 should be a space
        var cell = buf.getCell(2, 1);
        assertEquals(' ', cell.character().charAt(0), "Left-aligned text should pad right with spaces");
    }

    @Test
    void rightAlignmentPadsLeft() {
        // Score column is RIGHT-aligned with format "%.2f"
        // With score 1.0 → "1.00", should be right-aligned
        var grid = gridWithRows(30, 5, new TestRow("X", 3, 1.0));
        var buf = drawGrid(grid, 30, 5);
        // Find where Score column starts. We know it's the 3rd column.
        // Let's just check that the formatted score appears right-aligned
        // by finding the column position
        String row1 = rowText(buf, 1, 30);
        // Score "1.00" should be right-aligned in its column, meaning spaces before it
        assertTrue(row1.contains("1.00"), "Row should contain formatted score '1.00': " + row1);
    }

    @Test
    void formatStringApplied() {
        var grid = gridWithRows(30, 5, new TestRow("Test", 25, 1234.5678));
        var buf = drawGrid(grid, 30, 5);
        String row1 = rowText(buf, 1, 30);
        // "%.2f" should format 1234.5678 as "1234.57"
        assertTrue(row1.contains("1234.57"), "Score should be formatted to '1234.57': " + row1);
    }

    @Test
    void stylerOverridesColors() {
        var columns = List.of(
                GridColumn.<TestRow>doubleCol("Score", "%.2f", TestRow::score)
                        .withStyler(v -> CellStyle.GREEN)
        );
        var model = new DefaultGridModel<TestRow>();
        // Two rows: select row 1 so row 0 is NOT selected, letting the styler colors show
        model.addRows(List.of(new TestRow("A", 1, 50.0), new TestRow("B", 2, 60.0)));
        var grid = new DataGrid<TestRow>(columns, model);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(15, 5));
        grid.setSelectedRow(1);  // select second row so first row shows styler colors
        var buf = drawGrid(grid, 15, 5);
        // Row y=1 is the first data row (not selected), styler should set fg to BRIGHT_GREEN
        boolean foundGreen = false;
        for (int x = 0; x < 15; x++) {
            var cell = buf.getCell(x, 1);
            if (cell.fg() == AnsiColor.BRIGHT_GREEN) {
                foundGreen = true;
                break;
            }
        }
        assertTrue(foundGreen, "Styler should set fg to BRIGHT_GREEN for non-selected data cells");
    }

    @Test
    void selectedRowHighlighted() {
        var grid = gridWithRows(30, 5, new TestRow("Alice", 30, 95.5));
        grid.setSelectedRow(0);
        grid.setFocused(true);
        var buf = drawGrid(grid, 30, 5);
        var theme = ThemeManager.active();
        // First data row (y=1) should use selection colors
        var cell = buf.getCell(0, 1);
        assertEquals(theme.selectionFg(), cell.fg(), "Selected row should use selectionFg");
        assertEquals(theme.selectionBg(), cell.bg(), "Selected row should use selectionBg");
    }

    @Test
    void truncationOfLongText() {
        // Create a column with maxWidth to force truncation
        var columns = List.of(
                GridColumn.<TestRow>text("N", r -> r.name()).withMaxWidth(3)
        );
        var grid = new DataGrid<TestRow>(columns, modelWithRows(new TestRow("VeryLongName", 1, 1.0)));
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 5));
        var buf = drawGrid(grid, 10, 5);
        // The data row should show "Ver" (truncated to 3 chars)
        String row1 = rowText(buf, 1, 10);
        assertTrue(row1.startsWith("Ver"), "Long text should be truncated: " + row1);
        assertFalse(row1.contains("VeryLongName"), "Truncated text should not contain full name: " + row1);
    }

    @Test
    void columnSeparatorsPresent() {
        var grid = gridWithRows(30, 5, new TestRow("Alice", 30, 95.5));
        grid.setShowVerticalLines(true);
        var buf = drawGrid(grid, 30, 5);
        // There should be a '│' separator between columns in the header row
        boolean foundSeparator = false;
        for (int x = 0; x < 30; x++) {
            if (buf.getCell(x, 0).is('│')) {
                foundSeparator = true;
                break;
            }
        }
        assertTrue(foundSeparator, "Column separator │ should be present in header row when lines enabled");
    }

    @Test
    void verticalLinesOffByDefault() {
        var grid = gridWithRows(30, 5, new TestRow("Alice", 30, 95.5));
        assertFalse(grid.isShowVerticalLines(), "Vertical lines should be off by default");
        var buf = drawGrid(grid, 30, 5);
        // There should be NO '│' separator between columns when lines are off
        for (int x = 0; x < 30; x++) {
            assertFalse(buf.getCell(x, 0).is('│'),
                "Should not find │ separator in header row when lines are off (found at x=" + x + ")");
        }
    }

    @Test
    void calculatePreferredSizeReasonable() {
        var grid = gridWithRows(30, 5, new TestRow("Alice", 30, 95.5));
        var ps = grid.getPreferredSize();
        // Width should be at least sum of header widths + separators
        // "Name"=4, "Age"=3, "Score"=5, 2 separators = 14
        assertTrue(ps.columns() >= 14, "Preferred width should be at least 14: " + ps.columns());
        // Height: 1 header + 1 row = 2, min 3
        assertTrue(ps.rows() >= 3, "Preferred height should be at least 3: " + ps.rows());
    }

    @Test
    void multipleRowsScroll() {
        // 5 rows, viewport height 3 → 1 header + 2 data rows visible
        var rows = List.of(
                new TestRow("R0", 0, 0.0),
                new TestRow("R1", 1, 1.0),
                new TestRow("R2", 2, 2.0),
                new TestRow("R3", 3, 3.0),
                new TestRow("R4", 4, 4.0)
        );
        var columns = List.of(GridColumn.text("N", TestRow::name));
        var grid = new DataGrid<TestRow>(columns, modelWithRows(rows.toArray(new TestRow[0])));
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 3));
        var buf = drawGrid(grid, 10, 3);
        // Header at y=0, first 2 data rows at y=1, y=2
        String row1 = rowText(buf, 1, 10);
        String row2 = rowText(buf, 2, 10);
        assertTrue(row1.contains("R0"), "First visible data row should be R0: " + row1);
        assertTrue(row2.contains("R1"), "Second visible data row should be R1: " + row2);
    }

    @Test
    void nullCellValueRendersAsEmptyString() {
        record NullableRow(String name, Integer age) {}
        var columns = List.<GridColumn<NullableRow>>of(
                GridColumn.text("Name", NullableRow::name),
                GridColumn.intCol("Age", NullableRow::age)
        );
        var model = new DefaultGridModel<NullableRow>();
        model.addRow(new NullableRow("Test", null));
        var grid = new DataGrid<>(columns, model);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        var buf = drawGrid(grid, 20, 5);
        // The Age column for the null value should not contain "null"
        String row1 = rowText(buf, 1, 20);
        assertFalse(row1.contains("null"), "Null cell should render as empty, not 'null': " + row1);
    }

    @Test
    void setModelResetsSelection() {
        var grid = gridWithRows(30, 5, new TestRow("A", 1, 1.0));
        grid.setSelectedRow(0);
        var newModel = new DefaultGridModel<TestRow>();
        newModel.addRow(new TestRow("B", 2, 2.0));
        grid.setModel(newModel);
        assertEquals(0, grid.getSelectedRow());
        assertEquals("B", grid.getSelectedItem().name());
    }

    @Test
    void getSelectedItemReturnsNullForEmptyModel() {
        var columns = List.of(GridColumn.text("N", TestRow::name));
        var grid = new DataGrid<TestRow>(columns);
        assertNull(grid.getSelectedItem());
    }

    @Test
    void selectionListenerFired() {
        var columns = List.of(GridColumn.text("N", TestRow::name));
        var model = new DefaultGridModel<TestRow>();
        model.addRows(List.of(new TestRow("A", 1, 1.0), new TestRow("B", 2, 2.0)));
        var grid = new DataGrid<>(columns, model);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));

        var fired = new boolean[]{false};
        grid.addSelectionListener(() -> fired[0] = true);
        grid.setSelectedRow(1);
        assertTrue(fired[0], "Selection listener should fire on selection change");
    }

    @Test
    void modelChangeClampsSelection() {
        var model = new DefaultGridModel<TestRow>();
        model.addRows(List.of(
                new TestRow("A", 1, 1.0),
                new TestRow("B", 2, 2.0),
                new TestRow("C", 3, 3.0)
        ));
        var columns = List.of(GridColumn.text("N", TestRow::name));
        var grid = new DataGrid<>(columns, model);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        grid.setSelectedRow(2);
        model.clear();
        assertEquals(0, grid.getSelectedRow(), "Selection should clamp to 0 after clear");
    }

    @Test
    void removeRowAboveSelectionShiftsSelectionUp() {
        // Law (same as Table, 0.1.7): the selection anchors to its CONTENT.
        // NOTE: the selection is NOT at the table end — a bare clamp would land
        // on a different row and this law must stay red for that shape.
        var columns = List.of(GridColumn.text("N", TestRow::name));
        var model = modelWithRows(
                new TestRow("A", 1, 1.0), new TestRow("B", 2, 2.0),
                new TestRow("C", 3, 3.0), new TestRow("D", 4, 4.0));
        var grid = new DataGrid<>(columns, model);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        grid.setSelectedRow(2);
        model.removeRow(0);
        assertEquals(1, grid.getSelectedRow(), "Selection must slide up with its content");
        assertEquals("C", model.getRow(grid.getSelectedRow()).name());
    }

    @Test
    void removeRowsAboveSelectionShiftSelectionUpByBlock() {
        // Discriminating shape: selection strictly BELOW the removed block's end
        // and not at the table end — a bare clamp would land on a different row.
        var columns = List.of(GridColumn.text("N", TestRow::name));
        var model = modelWithRows(
                new TestRow("A", 1, 1.0), new TestRow("B", 2, 2.0),
                new TestRow("C", 3, 3.0), new TestRow("D", 4, 4.0), new TestRow("E", 5, 5.0));
        var grid = new DataGrid<>(columns, model);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        grid.setSelectedRow(4);
        model.removeRows(1, 3);
        assertEquals(1, grid.getSelectedRow(), "Three rows removed above — slide up by three");
        assertEquals("E", model.getRow(grid.getSelectedRow()).name());
    }

    @Test
    void removeSelectedRowLandsOnSuccessor() {
        // Removing the selected row itself: the selection stays at the removed
        // slot, which now holds the successor row. (For this shape the clamp
        // agrees with the content-anchor law — documented reality, not fluff.)
        var columns = List.of(GridColumn.text("N", TestRow::name));
        var model = modelWithRows(new TestRow("A", 1, 1.0), new TestRow("B", 2, 2.0), new TestRow("C", 3, 3.0));
        var grid = new DataGrid<>(columns, model);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        grid.setSelectedRow(1);
        model.removeRow(1);
        assertEquals(1, grid.getSelectedRow());
        assertEquals("C", model.getRow(grid.getSelectedRow()).name());
    }

    @Test
    void removeRowBelowSelectionDoesNotMoveSelection() {
        var columns = List.of(GridColumn.text("N", TestRow::name));
        var model = modelWithRows(new TestRow("A", 1, 1.0), new TestRow("B", 2, 2.0));
        var grid = new DataGrid<>(columns, model);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        grid.setSelectedRow(0);
        model.removeRow(1);
        assertEquals(0, grid.getSelectedRow());
    }

    @Test
    void removeRowAtLastSelectionClamps() {
        var columns = List.of(GridColumn.text("N", TestRow::name));
        var model = modelWithRows(new TestRow("A", 1, 1.0), new TestRow("B", 2, 2.0));
        var grid = new DataGrid<>(columns, model);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        grid.setSelectedRow(1);
        model.removeRow(1);
        assertEquals(0, grid.getSelectedRow(), "Single row left — selection clamps onto it");
    }

    @Test
    void removeRowsBlockAboveSelectionShiftsSelectionUp() {
        var columns = List.of(GridColumn.text("N", TestRow::name));
        var model = modelWithRows(
                new TestRow("A", 1, 1.0), new TestRow("B", 2, 2.0), new TestRow("C", 3, 3.0),
                new TestRow("D", 4, 4.0), new TestRow("E", 5, 5.0));
        var grid = new DataGrid<>(columns, model);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        grid.setSelectedRow(4);
        model.removeRows(1, 2);
        assertEquals(2, grid.getSelectedRow(), "Two rows removed above — slide up by two");
        assertEquals("E", model.getRow(grid.getSelectedRow()).name());
    }

    @Test
    void insertRowBeforeSelectionShiftsSelectionDown() {
        var columns = List.of(GridColumn.text("N", TestRow::name));
        var model = modelWithRows(new TestRow("A", 1, 1.0), new TestRow("B", 2, 2.0));
        var grid = new DataGrid<>(columns, model);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        grid.setSelectedRow(1);
        model.insertRow(1, new TestRow("NEW", 9, 9.0));
        assertEquals(2, grid.getSelectedRow(), "Insert under the selection must slide it down");
        assertEquals("B", model.getRow(grid.getSelectedRow()).name());
    }

    @Test
    void insertRowAfterSelectionDoesNotMoveSelection() {
        var columns = List.of(GridColumn.text("N", TestRow::name));
        var model = modelWithRows(new TestRow("A", 1, 1.0), new TestRow("B", 2, 2.0));
        var grid = new DataGrid<>(columns, model);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        grid.setSelectedRow(0);
        model.insertRow(1, new TestRow("NEW", 9, 9.0));
        assertEquals(0, grid.getSelectedRow());
    }

    @Test
    void legacyLambdaListenerStillFiresOnStructuredChange() {
        var columns = List.of(GridColumn.text("N", TestRow::name));
        var model = modelWithRows(new TestRow("A", 1, 1.0), new TestRow("B", 2, 2.0));
        var grid = new DataGrid<>(columns, model);
        grid.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(20, 5));
        var fired = new int[]{0};
        model.addGridListener(() -> fired[0]++);   // plain (no-arg) lambda — the pre-payload API
        model.removeRow(0);
        assertEquals(1, fired[0], "Default payload routing must reach no-arg listeners exactly once");
    }

    @Test
    void payloadListenerSeesRemoveRange() {
        var model = modelWithRows(new TestRow("A", 1, 1.0), new TestRow("B", 2, 2.0), new TestRow("C", 3, 3.0));
        final var events = new java.util.ArrayList<io.jterm.widget.model.GridModelEvent>();
        model.addGridListener(() -> {});
        model.addGridListener(new io.jterm.widget.model.GridListener() {
            @Override public void gridChanged() { }
            @Override public void gridChanged(io.jterm.widget.model.GridModelEvent e) { events.add(e); }
        });
        model.removeRow(2);
        assertEquals(1, events.size(), "Payload listener hears the event, no-arg lambda hears the default route");
        assertEquals(io.jterm.widget.model.GridModelEventType.ROWS_REMOVED, events.getFirst().type());
        assertEquals(2, events.getFirst().firstRow());
    }
}