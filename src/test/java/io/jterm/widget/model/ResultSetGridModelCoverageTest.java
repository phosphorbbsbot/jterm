package io.jterm.widget.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-completion tests for {@link ResultSetGridModel}: the SQLException
 * fetch path, fetchAll, listener add/remove, close idempotence, null-statement
 * handling, and long-value truncation. Complements ResultSetGridModelTest.
 */
class ResultSetGridModelCoverageTest {

    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:h2:mem:testgridcov;DB_CLOSE_DELAY=-1");
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS cov (id INT, name VARCHAR(200))");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("DROP TABLE IF EXISTS cov");
        }
        conn.close();
    }

    @Test
    void getColumnCountMatchesMetadata() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT id, name FROM cov");
            var model = new ResultSetGridModel(rs, stmt);
            assertEquals(2, model.getColumnCount());
            model.close();
        }
    }

    @Test
    void fetchAllDrainsEveryRow() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (int i = 0; i < 7; i++) stmt.execute("INSERT INTO cov VALUES (" + i + ", 'n" + i + "')");
            ResultSet rs = stmt.executeQuery("SELECT id, name FROM cov");
            var model = new ResultSetGridModel(rs, stmt);
            model.fetchAll();
            assertEquals(7, model.getRowCount());
            assertEquals("n6", model.getRow(6).values().get(1));
            model.close();
        }
    }

    @Test
    void sqlErrorDuringFetchStopsFetching() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO cov VALUES (1, 'x')");
            ResultSet rs = stmt.executeQuery("SELECT id, name FROM cov");
            var model = new ResultSetGridModel(rs, stmt);
            // Close the underlying result set out from under the model: the
            // next fetch throws SQLException, which the model swallows and
            // marks exhausted.
            rs.close();
            assertDoesNotThrow(model::fetchAll);
            assertEquals(0, model.getRowCount());
            model.close();
        }
    }

    @Test
    void closeIsIdempotentAndNullStatementSafe() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO cov VALUES (1, 'x')");
            ResultSet rs = stmt.executeQuery("SELECT id, name FROM cov");
            // Null statement (allowed by contract) must not break closeResources.
            var model = new ResultSetGridModel(rs, null);
            model.close();
            assertDoesNotThrow(model::close);
        }
    }

    @Test
    void listenersAddAndRemove() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT id FROM cov");
            var model = new ResultSetGridModel(rs, stmt);
            var fired = new boolean[]{false};
            GridListener listener = () -> fired[0] = true;
            model.addGridListener(listener);
            model.removeGridListener(listener);
            model.close();
            assertFalse(fired[0], "removed listener never fires");
        }
    }

    @Test
    void longValuesTruncatedWithEllipsis() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            String big = "y".repeat(120);  // > MAX_CELL_WIDTH (50)
            stmt.execute("INSERT INTO cov VALUES (1, '" + big + "')");
            ResultSet rs = stmt.executeQuery("SELECT id, name FROM cov");
            var model = new ResultSetGridModel(rs, stmt);
            String stored = model.getRow(0).values().get(1);
            assertEquals(50, stored.length());
            assertTrue(stored.endsWith("..."));
            model.close();
        }
    }

    @Test
    void nullCellValuesBecomeEmptyStrings() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO cov VALUES (1, NULL)");
            ResultSet rs = stmt.executeQuery("SELECT id, name FROM cov");
            var model = new ResultSetGridModel(rs, stmt);
            assertEquals("", model.getRow(0).values().get(1));
            model.close();
        }
    }

    @Test
    void lazyFetchOnRandomAccessIndex() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (int i = 0; i < 60; i++) stmt.execute("INSERT INTO cov VALUES (" + i + ", 'n" + i + "')");
            ResultSet rs = stmt.executeQuery("SELECT id, name FROM cov");
            var model = new ResultSetGridModel(rs, stmt);
            // Jump straight past the first batch: triggers multiple fetches.
            assertEquals("n59", model.getRow(59).values().get(1));
            assertEquals(60, model.getRowCount());
            model.close();
        }
    }
}