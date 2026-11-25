package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;

final class TableReviewDialog extends DialogWrapper {
    private final String preview;

    TableReviewDialog(Project project, String preview) {
        super(project);
        this.preview = preview;
        setTitle("Review Table Changes");
        setOKButtonText("Apply Pending Changes");
        init();
    }

    @Override protected JComponent createCenterPanel() {
        var area = new JBTextArea(preview, 14, 62);
        area.setEditable(false);
        area.setCaretPosition(0);
        area.getAccessibleContext().setAccessibleName("Table change preview");
        var panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JBLabel("Apply the entire batch to the current transaction. Commit saves it to the database."),
                BorderLayout.NORTH);
        panel.add(new JBScrollPane(area), BorderLayout.CENTER);
        return panel;
    }
}
