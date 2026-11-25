package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;
import org.openkaryon.karyoforge.database.core.ClickHouseSource;

import javax.swing.JComponent;
import java.nio.file.Path;

final class ClickHouseSourceDialog extends DialogWrapper {
    private final JBTextField name = new JBTextField(30);
    private final JBTextField port = new JBTextField("8123", 30);
    private final JBTextField database = new JBTextField("default", 30);
    private final JBTextField user = new JBTextField(30);
    private final TextFieldWithBrowseButton driver = new TextFieldWithBrowseButton();

    ClickHouseSourceDialog(Project project, ClickHouseSource existing) {
        super(project);
        setTitle(existing == null ? "Add ClickHouse Data Source" : "Edit ClickHouse Data Source");
        setOKButtonText("Save");
        driver.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile()
                .withTitle("Choose the Verified ClickHouse JDBC V2 JAR")
                .withFileFilter(file -> "jar".equalsIgnoreCase(file.getExtension())));
        if (existing != null) {
            name.setText(existing.name());
            port.setText(Integer.toString(existing.port()));
            database.setText(existing.database());
            user.setText(existing.user());
            driver.setText(existing.driver().toString());
        }
        init();
    }

    ClickHouseSource source() {
        try {
            return new ClickHouseSource(name.getText(), Integer.parseInt(port.getText().strip()), database.getText(),
                    user.getText(), Path.of(driver.getText().strip()));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Enter a valid ClickHouse port.");
        } catch (java.nio.file.InvalidPathException invalid) {
            throw new IllegalArgumentException("Choose a valid ClickHouse JDBC JAR path.");
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
                .addLabeledComponent("Host", new JBLabel("127.0.0.1 (HTTP, read-only)"))
                .addLabeledComponent("Port", port)
                .addLabeledComponent("Database", database)
                .addLabeledComponent("SELECT-only user", user)
                .addLabeledComponent("ClickHouse JDBC V2 0.10.0-all", driver)
                .addComponent(new JBLabel("Keep the two verified SLF4J 2.0.7 JARs beside the driver."))
                .getPanel();
    }

    @Override public JComponent getPreferredFocusedComponent() { return name; }
}
