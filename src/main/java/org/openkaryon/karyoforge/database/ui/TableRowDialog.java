package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import org.jetbrains.annotations.Nullable;
import org.openkaryon.karyoforge.database.core.QueryPage;
import org.openkaryon.karyoforge.database.core.SqlValue;
import org.openkaryon.karyoforge.database.core.TableSnapshot;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TableRowDialog extends DialogWrapper {
    private final List<Field> fields = new ArrayList<>();

    TableRowDialog(Project project, TableSnapshot table, int row, Map<Integer, DraftValue> draft) {
        super(project);
        setTitle("Edit Table Row");
        setOKButtonText("Stage");
        var cells = table.data().rows().get(row).cells();
        var columns = table.definition().columns();
        for (int index = 0; index < cells.size(); index++) {
            var type = table.columnType(index);
            if (type != null) fields.add(new Field(index, columns.get(index).name(), type, cells.get(index), draft.get(index)));
        }
        init();
    }

    @Override protected @Nullable ValidationInfo doValidate() {
        for (var field : fields) {
            try {
                if (field.changed()) field.draft();
            } catch (IllegalArgumentException failure) {
                return new ValidationInfo(failure.getMessage(), field.input);
            }
        }
        return null;
    }

    Map<Integer, DraftValue> values() {
        var values = new LinkedHashMap<Integer, DraftValue>();
        for (var field : fields) {
            if (field.changed()) values.put(field.index, field.draft());
        }
        return Map.copyOf(values);
    }

    @Override protected JComponent createCenterPanel() {
        var form = new JPanel(new GridLayout(0, 1, 0, 4));
        for (var field : fields) form.add(field.panel());
        var scroll = new JBScrollPane(form);
        scroll.setPreferredSize(new Dimension(680, Math.min(380, form.getPreferredSize().height + 8)));
        return scroll;
    }

    @Override public JComponent getPreferredFocusedComponent() {
        return fields.isEmpty() ? null : fields.getFirst().input;
    }

    record DraftValue(SqlValue value, String input, boolean isNull) {
        String display() { return isNull ? "NULL" : input; }
    }

    private static final class Field {
        private final int index;
        private final String title;
        private final QueryPage.Cell original;
        private final SqlValue.Type type;
        private final JBTextArea input = new JBTextArea(2, 26);
        private final JBCheckBox isNull = new JBCheckBox("NULL");

        private Field(int index, String title, SqlValue.Type type, QueryPage.Cell original, DraftValue draft) {
            this.index = index;
            this.title = title;
            this.type = type;
            this.original = original;
            input.getAccessibleContext().setAccessibleName("Value for " + title);
            isNull.getAccessibleContext().setAccessibleName("NULL for " + title);
            isNull.setSelected(draft == null ? original.value() == null : draft.isNull());
            input.setText(draft == null ? original.value() : draft.input());
            input.setEnabled(!isNull.isSelected());
            isNull.addActionListener(event -> input.setEnabled(!isNull.isSelected()));
        }

        private boolean changed() {
            if (isNull.isSelected()) return original.value() != null;
            return !input.getText().equals(original.value());
        }

        private DraftValue draft() {
            return new DraftValue(SqlValue.of(type, input.getText(), isNull.isSelected()), input.getText(), isNull.isSelected());
        }

        private JPanel panel() {
            var panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
            var label = new JBLabel(title.length() <= 24 ? title : title.substring(0, 22) + "…");
            label.putClientProperty("html.disable", true);
            label.setLabelFor(input);
            label.setToolTipText(String.valueOf(type));
            panel.add(label);
            panel.add(new JBScrollPane(input));
            panel.add(isNull);
            return panel;
        }
    }
}
