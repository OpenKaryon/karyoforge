package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;

import javax.swing.JComponent;
import javax.swing.JPasswordField;

final class ConnectionPasswordDialog extends DialogWrapper {
    private final JPasswordField password = new JPasswordField(25);
    private final JBCheckBox remember = new JBCheckBox("Remember password in PasswordSafe");
    private final boolean test;

    ConnectionPasswordDialog(Project project, String engine, boolean test) {
        super(project);
        this.test = test;
        setTitle(engine + " Password");
        setOKButtonText(test ? "Test Connection" : "Connect");
        password.getAccessibleContext().setAccessibleName(engine + " password");
        init();
    }

    Input takeInput() {
        char[] value = password.getPassword();
        password.setText("");
        return new Input(value, !test && remember.isSelected());
    }

    @Override protected JComponent createCenterPanel() {
        var form = FormBuilder.createFormBuilder()
                .addLabeledComponent("Password", password)
                .addComponent(new JBLabel("Leave blank to use a remembered password."));
        if (!test) form.addComponent(remember);
        return form.getPanel();
    }

    @Override public void doCancelAction() {
        password.setText("");
        super.doCancelAction();
    }

    @Override public JComponent getPreferredFocusedComponent() { return password; }

    record Input(char[] password, boolean remember) {}
}
