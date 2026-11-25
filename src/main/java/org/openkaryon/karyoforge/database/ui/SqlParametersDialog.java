package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.Nullable;
import org.openkaryon.karyoforge.database.core.SqlValue;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SqlParametersDialog extends DialogWrapper {
    private final List<Row> rows;

    SqlParametersDialog(Project project, List<String> names) {
        super(project);
        setTitle("SQL Parameters");
        setOKButtonText("Run");
        rows = names.stream().map(Row::new).toList();
        init();
    }

    Map<String, SqlValue> values() {
        var result = new LinkedHashMap<String, SqlValue>();
        for (var row : rows) result.put(row.name, row.value());
        return Map.copyOf(result);
    }

    @Override protected @Nullable ValidationInfo doValidate() {
        for (var row : rows) {
            try {
                row.value();
            } catch (IllegalArgumentException error) {
                return new ValidationInfo(error.getMessage(), row.value);
            }
        }
        return null;
    }

    @Override protected JComponent createCenterPanel() {
        var form = new JPanel(new GridLayout(0, 1, 0, 4));
        for (var row : rows) form.add(row.panel());
        var scroll = new JBScrollPane(form);
        Dimension size = form.getPreferredSize();
        scroll.setPreferredSize(new Dimension(size.width + 30, Math.min(size.height + 8, 320)));
        return scroll;
    }

    @Override public JComponent getPreferredFocusedComponent() { return rows.getFirst().value; }

    private static final class Row {
        private final String name;
        private final ComboBox<SqlValue.Type> type = new ComboBox<>(SqlValue.Type.values());
        private final JBTextField value = new JBTextField(24);
        private final JBCheckBox nullValue = new JBCheckBox("NULL");

        private Row(String name) {
            this.name = name;
            type.getAccessibleContext().setAccessibleName("Type for " + name);
            value.getAccessibleContext().setAccessibleName("Value for " + name);
            nullValue.getAccessibleContext().setAccessibleName("NULL for " + name);
            nullValue.addActionListener(event -> value.setEnabled(!nullValue.isSelected()));
        }

        private JPanel panel() {
            var line = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
            String label = Character.isDigit(name.charAt(0)) ? "Parameter " + name : ":" + name;
            var title = new JBLabel(label.length() <= 36 ? label : label.substring(0, 33) + "...");
            title.putClientProperty("html.disable", true);
            title.setToolTipText(label);
            title.setLabelFor(value);
            line.add(title);
            line.add(type);
            line.add(value);
            line.add(nullValue);
            return line;
        }

        private SqlValue value() {
            return SqlValue.of((SqlValue.Type) type.getSelectedItem(), value.getText(), nullValue.isSelected());
        }
    }
}
