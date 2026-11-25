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
import org.openkaryon.karyoforge.database.core.PostgresqlSource;

import javax.swing.JComponent;
import java.nio.file.Path;

final class PostgresqlSourceDialog extends DialogWrapper {
    private final JBTextField name = new JBTextField(30);
    private final JBTextField host = new JBTextField("127.0.0.1", 30);
    private final JBTextField port = new JBTextField("5432", 30);
    private final JBTextField database = new JBTextField("postgres", 30);
    private final JBTextField user = new JBTextField(30);
    private final TextFieldWithBrowseButton driver = new TextFieldWithBrowseButton();
    private final JBCheckBox writable = new JBCheckBox("Allow writes (manual Commit and Rollback)");

    PostgresqlSourceDialog(Project project, PostgresqlSource existing) {
        super(project);
        setTitle(existing == null ? "Add PostgreSQL Data Source" : "Edit PostgreSQL Data Source");
        setOKButtonText("Save");
        driver.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile()
                .withTitle("Choose a Trusted PostgreSQL JDBC JAR").withFileFilter(file -> "jar".equalsIgnoreCase(file.getExtension())));
        if (existing != null) {
            name.setText(existing.name());
            host.setText(existing.host());
            port.setText(Integer.toString(existing.port()));
            database.setText(existing.database());
            user.setText(existing.user());
            driver.setText(existing.driver().toString());
            writable.setSelected(existing.writable());
        }
        init();
    }

    PostgresqlSource source() {
        int number;
        try {
            number = Integer.parseInt(port.getText().strip());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Enter an integer port.");
        }
        return new PostgresqlSource(name.getText(), host.getText(), number, database.getText(), user.getText(),
                Path.of(driver.getText().strip()), writable.isSelected());
    }

    @Override protected @Nullable ValidationInfo doValidate() {
        try {
            source();
            return null;
        } catch (java.nio.file.InvalidPathException invalid) {
            return new ValidationInfo("Choose a valid JDBC JAR path.", driver);
        } catch (IllegalArgumentException invalid) {
            return new ValidationInfo(invalid.getMessage());
        }
    }

    @Override protected JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Name", name)
                .addLabeledComponent("Host", host)
                .addLabeledComponent("Port", port)
                .addLabeledComponent("Database", database)
                .addLabeledComponent("User", user)
                .addLabeledComponent("Trusted JDBC JAR", driver)
                .addComponent(writable)
                .addComponent(new JBLabel("A direct connection uses a loopback address. Saving the source does not connect."))
                .getPanel();
    }

    @Override public JComponent getPreferredFocusedComponent() { return name; }
}
