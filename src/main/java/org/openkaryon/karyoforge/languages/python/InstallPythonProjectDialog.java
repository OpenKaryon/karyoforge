package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.FormBuilder;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class InstallPythonProjectDialog extends DialogWrapper {
    private final StandardPythonProject metadata;
    private final PythonDependencyOperations.Environment environment;
    private final JCheckBox editable = new JCheckBox("Editable install (use the project's source files)", true);
    private final JCheckBox isolatedBuild = new JCheckBox("Isolate build dependencies (pip default)", true);
    private final Map<String, JCheckBox> extras = new LinkedHashMap<>();

    InstallPythonProjectDialog(Project project, StandardPythonProject metadata, PythonDependencyOperations.Environment environment) {
        super(project);
        this.metadata = metadata;
        this.environment = environment;
        metadata.extras().forEach(name -> extras.put(name, new JCheckBox(name)));
        setTitle("Install Python Project");
        setOKButtonText("Install");
        init();
    }

    boolean editable() { return editable.isSelected(); }
    boolean buildIsolation() { return isolatedBuild.isSelected(); }
    List<String> extras() { return extras.entrySet().stream().filter(entry -> entry.getValue().isSelected()).map(Map.Entry::getKey).toList(); }

    @Override protected JComponent createCenterPanel() {
        var form = FormBuilder.createFormBuilder().addLabeledComponent("Project:", new JLabel(metadata.name()))
                .addLabeledComponent("Directory:", new JLabel(metadata.directory().toString()))
                .addLabeledComponent("Interpreter:", new JLabel(environment.python().toString()))
                .addComponent(editable).addComponent(isolatedBuild);
        if (!extras.isEmpty()) {
            form.addComponent(new JLabel("Optional dependency groups:"));
            extras.values().forEach(form::addComponent);
        }
        form.addComponent(new JLabel("Installation runs project build code and may download dependencies."));
        form.addComponent(new JLabel("Without build isolation, the build backend must already exist in this environment."));
        return form.getPanel();
    }
}
