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
import org.openkaryon.karyoforge.database.core.MysqlSource;

import javax.swing.JComponent;
import java.nio.file.Path;

final class MysqlSourceDialog extends DialogWrapper {
    private final JBTextField name = new JBTextField(28);
    private final JBTextField host = new JBTextField("127.0.0.1", 28);
    private final JBTextField port = new JBTextField("3306", 28);
    private final JBTextField database = new JBTextField(28);
    private final JBTextField user = new JBTextField(28);
    private final TextFieldWithBrowseButton driver = new TextFieldWithBrowseButton();
    private final JBCheckBox writable = new JBCheckBox("Allow writes (manual Commit and Rollback)");

    private final MysqlSource.Variant variant;

    MysqlSourceDialog(Project project, MysqlSource existing, MysqlSource.Variant variant) {
        super(project);
        this.variant = existing == null ? variant : existing.variant();
        setTitle((existing == null ? "Add " : "Edit ") + this.variant.title() + " Data Source");
        setOKButtonText("Save");
        driver.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile()
                .withTitle("Choose " + this.variant.connector()).withFileFilter(file -> "jar".equalsIgnoreCase(file.getExtension())));
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

    MysqlSource source() {
        try {
            return new MysqlSource(name.getText(), host.getText(), Integer.parseInt(port.getText().strip()),
                    database.getText(), user.getText(), Path.of(driver.getText().strip()), writable.isSelected(), variant);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Enter a valid database port.");
        } catch (java.nio.file.InvalidPathException invalid) {
            throw new IllegalArgumentException("Choose a valid Connector/J path.");
        }
    }

    @Override protected @Nullable ValidationInfo doValidate() {
        try {
            source();
            return null;
        } catch (IllegalArgumentException invalid) {
            return new ValidationInfo(invalid.getMessage());
        }
    }

    @Override protected JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Name", name)
                .addLabeledComponent("Loopback host", host)
                .addLabeledComponent("Port", port)
                .addLabeledComponent("Database", database)
                .addLabeledComponent("User", user)
                .addLabeledComponent(variant.connector(), driver)
                .addComponent(writable)
                .addComponent(new JBLabel(variant == MysqlSource.Variant.MARIADB
                        ? "Keep the two verified SLF4J 2.0.17 JARs beside the MariaDB driver."
                        : "Saving the source does not open a connection."))
                .getPanel();
    }

    @Override public JComponent getPreferredFocusedComponent() { return name; }
}
