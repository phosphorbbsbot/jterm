package io.jterm.layout;

import io.jterm.core.TerminalPosition;
import io.jterm.core.TerminalSize;
import io.jterm.widget.EmptySpace;
import io.jterm.widget.Panel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-completion tests for {@link GridLayout}: the auto-sizing branches
 * of {@code effectiveDimensions} (both dims auto, only columns auto, only
 * rows auto), the zero-cell early return, and remainder distribution to the
 * last row/column. Complements GridLayoutTest.
 */
class GridLayoutAutoSizingTest {

    private static Panel panelWith(int cols, int rows, int childCount, TerminalSize pref) {
        var panel = new Panel(new GridLayout(cols, rows));
        for (int i = 0; i < childCount; i++) panel.addComponent(new EmptySpace(pref));
        return panel;
    }

    // ===== auto-sizing branches =====

    @Test
    void bothAutoProducesSquareArrangement() {
        // 7 children, both dims auto → ceil(sqrt(7))=3 cols, ceil(7/3)=3 rows.
        var p = panelWith(0, 0, 7, new TerminalSize(2, 1));
        p.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(9, 3));
        var kids = p.getChildren();
        // First child at origin, second in column 1.
        assertEquals(TerminalPosition.TOP_LEFT, kids.get(0).getPosition());
        assertEquals(new TerminalSize(3, 1), kids.get(0).getSize());
        assertEquals(new TerminalPosition(3, 0), kids.get(1).getPosition());
    }

    @Test
    void singleChildAutoGivesOneCell() {
        var p = panelWith(0, 0, 1, new TerminalSize(4, 2));
        p.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(8, 4));
        var kid = p.getChildren().get(0);
        // 1×1 auto grid → the single cell fills the entire area.
        assertEquals(TerminalPosition.TOP_LEFT, kid.getPosition());
        assertEquals(new TerminalSize(8, 4), kid.getSize());
    }

    @Test
    void rowsAutoFitsChildrenAcrossColumns() {
        // 5 children in a 4-col grid: ceil(5/4)=2 rows.
        var p = panelWith(4, 0, 5, new TerminalSize(2, 2));
        p.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(8, 4));
        var kids = p.getChildren();
        // Row-major: child 4 (index 4) is on row 1, col 0.
        assertEquals(new TerminalPosition(0, 2), kids.get(4).getPosition());
        assertEquals(new TerminalSize(2, 2), kids.get(0).getSize());
    }

    @Test
    void columnsAutoFitsChildrenAcrossRows() {
        // 5 children in a 2-row grid: ceil(5/2)=3 cols. Row-major layout:
        // child 2 sits in column index 2 (third column).
        var p = panelWith(0, 2, 5, new TerminalSize(1, 1));
        p.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(6, 2));
        var kids = p.getChildren();
        assertEquals(new TerminalPosition(4, 0), kids.get(2).getPosition());
        assertEquals(new TerminalSize(2, 1), kids.get(0).getSize());
    }

    // ===== zero-cell guard =====

    @Test
    void zeroChildrenMeansNoLayoutWork() {
        // With 0 children effectiveDimensions may still yield cols/rows > 0,
        // but there is nothing to lay out; must not throw.
        var p = new Panel(new GridLayout(2, 2));
        assertDoesNotThrow(() -> p.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(6, 6)));
    }

    // ===== remainder distribution =====

    @Test
    void remainderWidthGoesToLastColumn() {
        // 10 cols / 3 children → base 3, extra 1 → last column 4 wide.
        var p = panelWith(3, 1, 3, new TerminalSize(1, 1));
        p.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(10, 2));
        var kids = p.getChildren();
        assertEquals(new TerminalSize(3, 2), kids.get(0).getSize());
        assertEquals(new TerminalSize(3, 2), kids.get(1).getSize());
        assertEquals(new TerminalSize(4, 2), kids.get(2).getSize(), "last column absorbs remainder");
        // Positions verify the packing: 0, 3, 6.
        assertEquals(new TerminalPosition(0, 0), kids.get(0).getPosition());
        assertEquals(new TerminalPosition(3, 0), kids.get(1).getPosition());
        assertEquals(new TerminalPosition(6, 0), kids.get(2).getPosition());
    }

    @Test
    void remainderHeightGoesToLastRow() {
        // 5 rows / 2 children-per-col... 2 children stacked: base 2, extra 1.
        var p = panelWith(1, 2, 2, new TerminalSize(1, 1));
        p.setBounds(TerminalPosition.TOP_LEFT, new TerminalSize(2, 5));
        var kids = p.getChildren();
        assertEquals(new TerminalSize(2, 2), kids.get(0).getSize());
        assertEquals(new TerminalSize(2, 3), kids.get(1).getSize(), "last row absorbs remainder");
        assertEquals(new TerminalPosition(0, 2), kids.get(1).getPosition());
    }

    // ===== preferred size with auto dims =====

    @Test
    void preferredSizeAutoDimsSquare() {
        var layout = new GridLayout(0, 0);
        var p = new Panel(layout);
        for (int i = 0; i < 4; i++) p.addComponent(new EmptySpace(new TerminalSize(3, 2)));
        // ceil(sqrt(4))=2 cols × 2 rows of (3,2) cells → 6×4.
        assertEquals(new TerminalSize(6, 4), p.getPreferredSize());
    }
}