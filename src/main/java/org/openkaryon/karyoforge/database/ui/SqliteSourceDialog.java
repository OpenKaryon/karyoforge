package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;
import org.openkaryon.karyoforge.database.core.SqliteSource;

import javax.swing.JComponent;
import java.nio.file.Path;

final class SqliteSourceDialog extends DialogWrapper {
    private final JBTextField name = new JBTextField(35);
    private final TextFieldWithBrowseButton database = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton driver = new TextFieldWithBrowseButton();
    private final JBCheckBox writable = new JBCheckBox("Allow writes (manual Commit and Rollback)");

    SqliteSourceDialog(Project project, SqliteSource existing) {
        super(project);
        setTitle(existing == null ? "Add SQLite Data Source" : "Edit SQLite Data Source");
        setOKButtonText("Save");
        name.getAccessibleContext().setAccessibleName("Data source name");
        database.getTextField().getAccessibleContext().setAccessibleName("SQLite database file");
        driver.getTextField().getAccessibleContext().setAccessibleName("SQLite JDBC JAR");
        database.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile()
                .withTitle("Choose an Existing SQLite Database"));
        driver.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile()
                .withTitle("Choose a Trusted SQLite JDBC JAR").withFileFilter(file -> "jar".equalsIgnoreCase(file.getExtension())));
        if (existing != null) {
            name.setText(existing.name());
            database.setText(existing.database().toString());
            driver.setText(existing.driver().toString());
            writable.setSelected(existing.writable());
        }
        init();
    }

    SqliteSource source() {
        return new SqliteSource(name.getText(), Path.of(database.getText().strip()), Path.of(driver.getText().strip()),
                writable.isSelected());
    }

    @Override protected @Nullable ValidationInfo doValidate() {
        try {
            source();
            return null;
        } catch (IllegalArgumentException invalid) {
            return new ValidationInfo(invalid instanceof java.nio.file.InvalidPathException
                    ? "Choose valid local paths for the database and driver." : invalid.getMessage());
        }
    }

    @Override protected JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Name", name)
                .addLabeledComponent("Existing database file", database)
                .addLabeledComponent("Trusted JDBC JAR", driver)
                .addComponent(writable)
                .addComponent(new JBLabel("Saving a source does not connect."))
                .getPanel();
    }

    @Override public JComponent getPreferredFocusedComponent() { return name; }
}
