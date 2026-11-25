package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;

import javax.swing.JComponent;
import javax.swing.JPasswordField;

final class ConnectionPasswordDialog extends DialogWrapper {
    private final JPasswordField password = new JPasswordField(25);

    ConnectionPasswordDialog(Project project) {
        super(project);
        setTitle("PostgreSQL Password");
        setOKButtonText("Connect");
        password.getAccessibleContext().setAccessibleName("PostgreSQL password");
        init();
    }

    char[] takePassword() {
        char[] value = password.getPassword();
        password.setText("");
        return value;
    }

    @Override protected JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("Password", password)
                .addComponent(new JBLabel("Used for this connection only."))
                .getPanel();
    }

    @Override public JComponent getPreferredFocusedComponent() { return password; }
}
