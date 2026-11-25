package org.openkaryon.karyoforge.database.ui;

import com.intellij.ui.treeStructure.Tree;
import org.openkaryon.karyoforge.database.core.JdbcSession.SchemaObject;
import org.openkaryon.karyoforge.database.core.TableDefinition;

import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.Component;
import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/** A catalog snapshot tree. Only a user's expansion may request new column metadata. */
final class DatabaseSchemaTree extends Tree {
    private static final String LOAD_COLUMNS = "Load columns";
    private final Map<SchemaObject, DefaultMutableTreeNode> objects = new LinkedHashMap<>();
    private final Map<SchemaObject, List<TableDefinition.Column>> columns = new LinkedHashMap<>();
    private List<SchemaObject> snapshot = List.of();
    private String filter = "";
    private boolean restoring;

    DatabaseSchemaTree(Predicate<SchemaObject> loadColumns) {
        setModel(new DefaultTreeModel(new DefaultMutableTreeNode()));
        setRootVisible(false);
        setShowsRootHandles(true);
        setToggleClickCount(0);
        getAccessibleContext().setAccessibleName("Database schema objects");
        getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        var renderer = new DefaultTreeCellRenderer() {
            @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                    boolean expanded, boolean leaf, int row, boolean focused) {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focused);
                if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof SchemaObject entry) {
                    String text = entry.name() + " (" + entry.type() + ")";
                    if (entry.estimatedRows() != null) {
                        text += " · about " + NumberFormat.getIntegerInstance(Locale.ENGLISH).format(entry.estimatedRows()) + " rows";
                    }
                    setText(text);
                }
                return this;
            }
        };
        renderer.putClientProperty("html.disable", true);
        setCellRenderer(renderer);
        addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
                if (restoring) return;
                var object = object(event.getPath().getLastPathComponent());
                if (object != null && !columns.containsKey(object) && !loadColumns.test(object)) {
                    throw new ExpandVetoException(event);
                }
            }

            @Override public void treeWillCollapse(TreeExpansionEvent event) {}
        });
    }

    SchemaObject selectedObject() { return object(getLastSelectedPathComponent()); }

    void reset(List<SchemaObject> updated) {
        snapshot = List.copyOf(updated);
        columns.clear();
        rebuild();
    }

    void filter(String text) {
        filter = text.strip().toLowerCase(Locale.ROOT);
        rebuild();
    }

    void learn(TableDefinition definition) {
        var object = definition.object();
        if (!snapshot.contains(object)) return;
        columns.remove(object);
        columns.put(object, definition.columns());
        if (columns.size() > 64) {
            var oldest = columns.keySet().iterator().next();
            columns.remove(oldest);
            updateChildren(oldest, false);
        }
        updateChildren(object, true);
    }

    private void updateChildren(SchemaObject object, boolean preserveExpansion) {
        var node = objects.get(object);
        if (node == null) return;
        var path = new TreePath(node.getPath());
        boolean expanded = isExpanded(path);
        node.removeAllChildren();
        addColumns(node, object);
        ((DefaultTreeModel) getModel()).nodeStructureChanged(node);
        if (expanded && preserveExpansion) {
            restoring = true;
            try {
                expandPath(path);
            } finally {
                restoring = false;
            }
        }
    }

    private void rebuild() {
        var selected = selectedObject();
        var schemas = new LinkedHashMap<String, DefaultMutableTreeNode>();
        var groups = new LinkedHashMap<Group, DefaultMutableTreeNode>();
        var root = new DefaultMutableTreeNode();
        objects.clear();
        for (var object : snapshot) {
            if (!(object.schema() + "." + object.name()).toLowerCase(Locale.ROOT).contains(filter)) continue;
            var schema = schemas.computeIfAbsent(object.schema(), name -> {
                var node = new DefaultMutableTreeNode(name);
                root.add(node);
                return node;
            });
            String title = object.type().equals("view") ? "Views" : "Tables";
            var group = groups.computeIfAbsent(new Group(object.schema(), title), ignored -> {
                var node = new DefaultMutableTreeNode(title);
                schema.add(node);
                return node;
            });
            var node = new DefaultMutableTreeNode(object);
            addColumns(node, object);
            objects.put(object, node);
            group.add(node);
        }
        restoring = true;
        try {
            setModel(new DefaultTreeModel(root));
            for (var schema : schemas.values()) expandPath(new TreePath(schema.getPath()));
            for (var group : groups.values()) expandPath(new TreePath(group.getPath()));
            if (selected != null) {
                objects.entrySet().stream().filter(entry -> sameIdentity(selected, entry.getKey())).findFirst()
                        .ifPresent(entry -> setSelectionPath(new TreePath(entry.getValue().getPath())));
            }
        } finally {
            restoring = false;
        }
    }

    private void addColumns(DefaultMutableTreeNode node, SchemaObject object) {
        var known = columns.get(object);
        if (known == null) {
            node.add(new DefaultMutableTreeNode(LOAD_COLUMNS, false));
            return;
        }
        for (var column : known) {
            String text = column.name() + " · " + column.type() + (column.keyPosition() > 0 ? " · Primary key" : "");
            node.add(new DefaultMutableTreeNode(text, false));
        }
    }

    private static SchemaObject object(Object value) {
        return value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof SchemaObject object ? object : null;
    }

    private static boolean sameIdentity(SchemaObject left, SchemaObject right) {
        return left.name().equals(right.name()) && left.schema().equals(right.schema()) && left.type().equals(right.type())
                && left.dialect() == right.dialect();
    }

    private record Group(String schema, String kind) {}
}
