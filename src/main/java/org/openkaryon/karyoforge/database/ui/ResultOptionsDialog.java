package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;
import org.openkaryon.karyoforge.database.core.QueryPage;
import org.openkaryon.karyoforge.database.results.FetchedResults;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComponent;
import java.util.Arrays;
import java.util.List;

final class ResultOptionsDialog extends DialogWrapper {
    private final boolean filtering;
    private final ComboBox<String> column;
    private final ComboBox<FetchedResults.Operator> operator = new ComboBox<>(FetchedResults.Operator.values());
    private final ComboBox<QueryPage.Cell.Kind> type = new ComboBox<>(Arrays.stream(QueryPage.Cell.Kind.values())
            .filter(kind -> kind != QueryPage.Cell.Kind.NULL).toArray(QueryPage.Cell.Kind[]::new));
    private final JBTextField value = new JBTextField(25);
    private final JBCheckBox caseSensitive = new JBCheckBox("Match text case", true);
    private final JBCheckBox descending = new JBCheckBox("Descending");
    private final JBCheckBox nullsFirst = new JBCheckBox("Show NULL values first");

    ResultOptionsDialog(Project project, List<QueryPage.Column> columns, boolean filtering) {
        super(project);
        this.filtering = filtering;
        setTitle(filtering ? "Filter Fetched Rows" : "Sort Fetched Rows");
        setOKButtonText(filtering ? "Add Filter" : "Add Sort Key");
        var names = new String[columns.size()];
        for (int index = 0; index < columns.size(); index++) names[index] = (index + 1) + " · " + columns.get(index).label();
        column = new ComboBox<>(names);
        var renderer = new DefaultListCellRenderer();
        renderer.putClientProperty("html.disable", true);
        column.setRenderer(renderer);
        type.setSelectedItem(QueryPage.Cell.Kind.TEXT);
        operator.addActionListener(event -> updateOperands());
        updateOperands();
        init();
    }

    FetchedResults.Sort sort() {
        return new FetchedResults.Sort(column.getSelectedIndex(), descending.isSelected(), nullsFirst.isSelected());
    }

    FetchedResults.Filter filter() {
        var operation = (FetchedResults.Operator) operator.getSelectedItem();
        var kind = (QueryPage.Cell.Kind) type.getSelectedItem();
        var operand = operation.needsValue() ? new QueryPage.Cell(kind, value.getText(), false) : null;
        return new FetchedResults.Filter(column.getSelectedIndex(), operation, operand, caseSensitive.isSelected());
    }

    private void updateOperands() {
        var operation = (FetchedResults.Operator) operator.getSelectedItem();
        boolean binary = operation.needsValue();
        type.setEnabled(binary);
        value.setEnabled(binary);
        caseSensitive.setEnabled(binary);
        if (operation == FetchedResults.Operator.CONTAINS) type.setSelectedItem(QueryPage.Cell.Kind.TEXT);
    }

    @Override protected @Nullable ValidationInfo doValidate() {
        if (filtering) {
            try {
                filter();
            } catch (IllegalArgumentException invalid) {
                return new ValidationInfo(invalid.getMessage(), value);
            }
        }
        return null;
    }

    @Override protected JComponent createCenterPanel() {
        var form = FormBuilder.createFormBuilder().addLabeledComponent("Column", column);
        if (filtering) {
            form.addLabeledComponent("Operator", operator).addLabeledComponent("Value type", type)
                    .addLabeledComponent("Value", value).addComponent(caseSensitive)
                    .addComponent(new JBLabel("All active filters must match."));
        } else {
            form.addComponent(descending).addComponent(nullsFirst);
        }
        return form.addComponent(new JBLabel("Applies to all fetched pages.")).getPanel();
    }
}
