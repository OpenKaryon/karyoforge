package org.openkaryon.karyoforge.database.ui;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;
import org.openkaryon.karyoforge.database.core.DatabaseTimeouts;
import org.openkaryon.karyoforge.database.core.ResultLimits;
import org.openkaryon.karyoforge.database.core.SqliteSource;
import org.openkaryon.karyoforge.database.driver.DriverLease;

import javax.swing.JButton;
import javax.swing.JTable;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class DatabaseEditorResultsTest extends BasePlatformTestCase {
    public void testEditorsKeepDetachedResultsAndOnlyTheOriginalEditorCanPage() throws Exception {
        var database = Path.of(getProject().getBasePath()).resolve("owned.sqlite");
        var driver = Path.of(System.getProperty("karyoforge.database.sqlite.driver"));
        try (var lease = DriverLease.open(driver, "org.sqlite.JDBC");
             var connection = lease.driver().connect("jdbc:sqlite:" + database, new Properties());
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE samples (id INTEGER PRIMARY KEY)");
            statement.executeUpdate("INSERT INTO samples VALUES (10),(20),(30)");
        }
        var sources = new DatabaseSources();
        String id = sources.save(null, new SqliteSource("Owned SQLite", database, driver));
        sources.saveLimits(id, new ResultLimits(2, 6, 8192, 128, 8, 16),
                DatabaseTimeouts.DEFAULT);
        var bridge = getProject().getService(DatabaseEditorBridge.class);
        var workspace = new DatabaseSessionPanel(getProject(), sources.profiles().getFirst(), bridge, () -> {});
        try {
            find(workspace.actionButtons(), JButton.class, button -> button.getText().equals("Connect")).doClick();
            await(workspace::connected);
            var table = find(workspace, JTable.class, grid -> "Database query results".equals(grid.getAccessibleContext().getAccessibleName()));
            var next = find(workspace, JButton.class, button -> button.getText().equals("Next Result Page"));
            var first = bridge.console(id, "First", "");
            var second = bridge.console(id, "Second", "");
            String query = "SELECT id FROM samples ORDER BY id";
            workspace.runFromEditor(first, query, 0, 0, false);
            await(() -> !workspace.busy() && next.isEnabled());
            assertEquals("10", table.getValueAt(0, 0));

            workspace.selectEditorResult(second);
            assertFalse(next.isEnabled());
            assertEquals(0, table.getRowCount());
            workspace.selectEditorResult(first);
            assertTrue(next.isEnabled());
            assertEquals("10", table.getValueAt(0, 0));
            next.doClick();
            await(() -> !workspace.busy() && table.getRowCount() == 1);
            assertEquals("30", table.getValueAt(0, 0));

            workspace.runFromEditor(second, query, 0, 0, false);
            await(() -> !workspace.busy() && next.isEnabled());
            workspace.selectEditorResult(first);
            assertFalse(next.isEnabled());
            assertEquals("30", table.getValueAt(0, 0));
            workspace.selectEditorResult(second);
            assertTrue(next.isEnabled());

            find(workspace.actionButtons(), JButton.class, button -> button.getText().equals("Disconnect")).doClick();
            await(() -> !workspace.connected());
        } finally {
            workspace.dispose();
            bridge.forgetCatalog(id);
        }
    }

    private static void await(BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents();
            Thread.sleep(10);
        }
        assertTrue("The database workspace did not reach the expected state.", ready.getAsBoolean());
    }

    private static <T extends Component> T find(Component component, Class<T> type, Predicate<T> test) {
        if (type.isInstance(component) && test.test(type.cast(component))) return type.cast(component);
        if (component instanceof Container container) {
            for (var child : container.getComponents()) {
                T result = find(child, type, test);
                if (result != null) return result;
            }
        }
        return null;
    }
}
