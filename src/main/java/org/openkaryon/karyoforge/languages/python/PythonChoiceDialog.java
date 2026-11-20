package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;

/** A module/environment choice with an explicit Apply and a side-effect-free Cancel. */
final class PythonChoiceDialog extends DialogWrapper {
    private final ComboBox<String> choices;
    private final String message;

    private PythonChoiceDialog(Project project, String title, String message, String[] labels) {
        super(project, false);
        this.message = message;
        choices = new ComboBox<>(labels);
        setTitle(title);
        setOKButtonText("Select");
        init();
    }

    static int choose(Project project, String title, String message, String[] labels) {
        if (labels.length == 0) return -1;
        var dialog = new PythonChoiceDialog(project, title, message, labels);
        return dialog.showAndGet() ? dialog.choices.getSelectedIndex() : -1;
    }

    @Override protected @NotNull JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder().addComponent(new JBLabel(message)).addComponent(choices).getPanel();
    }

    @Override public JComponent getPreferredFocusedComponent() { return choices; }
}
