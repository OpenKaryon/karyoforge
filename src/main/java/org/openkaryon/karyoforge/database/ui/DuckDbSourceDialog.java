package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;
import org.openkaryon.karyoforge.database.core.DuckDbSource;

import javax.swing.JComponent;
import java.nio.file.Path;

final class DuckDbSourceDialog extends DialogWrapper {
    private final JBTextField name = new JBTextField(34);
    private final TextFieldWithBrowseButton database = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton driver = new TextFieldWithBrowseButton();
    private final JBCheckBox writable = new JBCheckBox("Allow writes and creation of a new database file");

    DuckDbSourceDialog(Project project, DuckDbSource existing) {
        super(project);
        setTitle(existing == null ? "Add DuckDB Data Source" : "Edit DuckDB Data Source");
        setOKButtonText("Save");
        database.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile().withTitle("Choose a DuckDB Database"));
        driver.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile()
                .withTitle("Choose the Verified DuckDB JDBC 1.5.6.0 JAR")
                .withFileFilter(file -> "jar".equalsIgnoreCase(file.getExtension())));
        if (existing != null) {
            name.setText(existing.name());
            database.setText(existing.database().toString());
            driver.setText(existing.driver().toString());
            writable.setSelected(existing.writable());
        }
        init();
    }

    DuckDbSource source() {
        return new DuckDbSource(name.getText(), Path.of(database.getText().strip()), Path.of(driver.getText().strip()), writable.isSelected());
    }

    @Override protected @Nullable ValidationInfo doValidate() {
        try {
            source();
            return null;
        } catch (java.nio.file.InvalidPathException invalid) {
            return new ValidationInfo("Choose valid local paths for the database and driver.");
        } catch (IllegalArgumentException invalid) {
            return new ValidationInfo(invalid.getMessage());
        }
    }

    @Override protected JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Name", name)
                .addLabeledComponent("DuckDB database file", database)
                .addLabeledComponent("DuckDB JDBC 1.5.6.0", driver)
                .addComponent(writable)
                .getPanel();
    }

    @Override public JComponent getPreferredFocusedComponent() { return name; }
}
