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
import org.openkaryon.karyoforge.database.core.SshForwarding;

import javax.swing.JComponent;
import java.nio.file.Path;

final class SshForwardingDialog extends DialogWrapper {
    private final JBTextField host = new JBTextField(28);
    private final JBTextField port = new JBTextField("22", 28);
    private final JBTextField user = new JBTextField(28);
    private final TextFieldWithBrowseButton executable = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton identity = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton knownHosts = new TextFieldWithBrowseButton();
    private final JBTextField target = new JBTextField("127.0.0.1", 28);
    private final JBTextField targetPort = new JBTextField("5432", 28);

    SshForwardingDialog(Project project, SshForwarding existing) {
        super(project);
        setTitle("Configure PostgreSQL SSH Forwarding");
        setOKButtonText("Use SSH Forward");
        executable.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile()
                .withTitle("Choose the OpenSSH Executable"));
        identity.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile()
                .withTitle("Choose the Existing SSH Identity File"));
        knownHosts.addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile()
                .withTitle("Choose the SSH Known-Hosts File"));
        if (existing != null) {
            host.setText(existing.host());
            port.setText(Integer.toString(existing.port()));
            user.setText(existing.user());
            executable.setText(existing.executable().toString());
            identity.setText(existing.identity().toString());
            knownHosts.setText(existing.knownHosts().toString());
            target.setText(existing.target());
            targetPort.setText(Integer.toString(existing.targetPort()));
        }
        init();
    }

    SshForwarding forwarding() {
        try {
            return new SshForwarding(host.getText(), Integer.parseInt(port.getText().strip()), user.getText().strip(),
                    Path.of(executable.getText().strip()), Path.of(identity.getText().strip()),
                    Path.of(knownHosts.getText().strip()), target.getText(), Integer.parseInt(targetPort.getText().strip()));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Enter valid SSH and target ports.");
        } catch (java.nio.file.InvalidPathException invalid) {
            throw new IllegalArgumentException("Choose valid paths for the OpenSSH executable, identity and known-hosts file.");
        }
    }

    @Override protected @Nullable ValidationInfo doValidate() {
        try {
            forwarding();
            return null;
        } catch (IllegalArgumentException invalid) {
            return new ValidationInfo(invalid.getMessage());
        }
    }

    @Override protected JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("SSH host", host)
                .addLabeledComponent("SSH port", port)
                .addLabeledComponent("SSH user", user)
                .addLabeledComponent("OpenSSH executable", executable)
                .addLabeledComponent("Identity file", identity)
                .addLabeledComponent("Known-hosts file", knownHosts)
                .addLabeledComponent("PostgreSQL target from the SSH host", target)
                .addLabeledComponent("PostgreSQL target port", targetPort)
                .addComponent(new JBLabel("The database profile's host supplies the PostgreSQL TLS identity."))
                .getPanel();
    }
}
