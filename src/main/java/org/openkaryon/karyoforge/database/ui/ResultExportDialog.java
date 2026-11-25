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
import org.openkaryon.karyoforge.database.results.FetchedResults;
import org.openkaryon.karyoforge.database.results.ResultExport;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.nio.charset.Charset;
import java.nio.file.Path;

final class ResultExportDialog extends DialogWrapper {
    private final JBTextField path = new JBTextField(36);
    private final JBTextField nullToken = new JBTextField("\\N");
    private final ComboBox<ResultExport.Format> format = new ComboBox<>(ResultExport.Format.values());
    private final ComboBox<ResultExport.Spreadsheet> spreadsheet = new ComboBox<>(ResultExport.Spreadsheet.values());
    private final ComboBox<String> encoding = new ComboBox<>(new String[]{"UTF-8", "UTF-16LE", "windows-1251"});
    private final JBCheckBox header = new JBCheckBox("Include column names", true);
    private final JBCheckBox previews = new JBCheckBox("Allow clipped previews");
    private final FetchedResults.View view;

    ResultExportDialog(Project project, FetchedResults.View view) {
        super(project);
        this.view = view;
        setTitle("Export Fetched Rows");
        setOKButtonText("Export");
        path.getAccessibleContext().setAccessibleName("Export destination");
        init();
    }

    Path destination() {
        Path selected;
        try {
            selected = Path.of(path.getText().strip());
        } catch (java.nio.file.InvalidPathException invalid) {
            throw new IllegalArgumentException("Choose a valid absolute output path.");
        }
        if (!selected.isAbsolute()) throw new IllegalArgumentException("Choose an absolute output path.");
        return selected;
    }

    ResultExport.Options options() {
        return new ResultExport.Options((ResultExport.Format) format.getSelectedItem(),
                Charset.forName((String) encoding.getSelectedItem()), nullToken.getText(),
                (ResultExport.Spreadsheet) spreadsheet.getSelectedItem(), header.isSelected(), previews.isSelected());
    }

    @Override protected @Nullable ValidationInfo doValidate() {
        try {
            destination();
        } catch (IllegalArgumentException invalid) {
            return new ValidationInfo(invalid.getMessage(), path);
        }
        try {
            options();
            return null;
        } catch (IllegalArgumentException invalid) {
            return new ValidationInfo(invalid.getMessage(), nullToken);
        }
    }

    @Override protected JComponent createCenterPanel() {
        var browse = new JButton("Browse…");
        browse.addActionListener(event -> {
            var chooser = new JFileChooser();
            chooser.setSelectedFile(new java.io.File(format.getSelectedItem() == ResultExport.Format.CSV ? "query.csv" : "query.tsv"));
            if (chooser.showSaveDialog(path) == JFileChooser.APPROVE_OPTION) path.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        var output = new JPanel(new BorderLayout(4, 0));
        output.add(path, BorderLayout.CENTER);
        output.add(browse, BorderLayout.EAST);
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("New output file", output)
                .addLabeledComponent("Format", format)
                .addLabeledComponent("Encoding", encoding)
                .addLabeledComponent("NULL token", nullToken)
                .addLabeledComponent("Spreadsheet policy", spreadsheet)
                .addComponent(header)
                .addComponent(previews)
                .addComponent(new JBLabel(view.rows().size() + " visible rows from " + view.fetchedCount() + " fetched rows."))
                .getPanel();
    }
}
