package org.openkaryon.karyoforge.database.ui;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.openkaryon.karyoforge.database.core.JdbcSession.SchemaObject;
import org.openkaryon.karyoforge.database.core.TableDefinition;

import javax.swing.tree.TreePath;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class DatabaseSchemaTreeTest extends BasePlatformTestCase {
    public void testFiltersLoadedObjectsAndExpandsOnlyAnExplicitTable() {
        var loads = new AtomicInteger();
        var tree = new DatabaseSchemaTree(object -> {
            loads.incrementAndGet();
            return true;
        });
        var samples = new SchemaObject("samples", "table", "main");
        var sampleView = new SchemaObject("sample_view", "view", "external");
        tree.reset(List.of(samples, sampleView));
        assertEquals(0, loads.get());
        var table = path(tree, samples);
        assertNotNull(table);
        assertNotNull(path(tree, sampleView));
        tree.setSelectionPath(table);
        assertEquals(samples, tree.selectedObject());

        tree.filter("external.");
        assertNull(path(tree, samples));
        assertNotNull(path(tree, sampleView));
        assertEquals(0, loads.get());

        tree.filter("");
        table = path(tree, samples);
        tree.expandPath(table);
        assertEquals(1, loads.get());
        var column = new TableDefinition.Column("sample_id", "INTEGER", true, 1, 0);
        tree.learn(new TableDefinition(samples, List.of(column), "CREATE TABLE samples (sample_id INTEGER PRIMARY KEY)", false));
        assertEquals(1, loads.get());
        assertTrue(tree.isExpanded(table));
        assertNotNull(path(tree, "sample_id · INTEGER · Primary key"));
    }

    private static TreePath path(DatabaseSchemaTree tree, Object item) {
        for (int row = 0; row < tree.getRowCount(); row++) {
            var path = tree.getPathForRow(row);
            var node = (javax.swing.tree.DefaultMutableTreeNode) path.getLastPathComponent();
            if (item.equals(node.getUserObject())) return path;
        }
        return null;
    }
}
