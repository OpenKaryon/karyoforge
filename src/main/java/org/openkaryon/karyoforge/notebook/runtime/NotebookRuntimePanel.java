package org.openkaryon.karyoforge.notebook.runtime;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.vfs.VirtualFile;
import com.jetbrains.python.sdk.PythonSdkUtil;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local, explicit interpreter selection and metadata checks; construction is passive. */
public final class NotebookRuntimePanel extends JPanel implements Disposable {
    private final Project project;
    private final VirtualFile file;
    private final JComboBox<String> choices = new JComboBox<>();
    private final Map<String, String> paths = new LinkedHashMap<>();
    private final JLabel status = new JLabel();
    private final JButton check = new JButton("Check dependencies");
    private NotebookDependencyCheck pending;
    private long generation;
    private boolean disposed;
    private boolean refreshing;

    public NotebookRuntimePanel(Project project, VirtualFile file) {
        super(new BorderLayout());
        this.project = project;
        this.file = file;
        choices.getAccessibleContext().setAccessibleName("Notebook Python interpreter");
        choices.putClientProperty("html.disable", true);
        status.putClientProperty("html.disable", true);
        status.getAccessibleContext().setAccessibleName("Notebook dependency status");
        var actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
        actions.add(new JLabel("Python:"));
        actions.add(choices);
        var choose = new JButton("Choose executable...");
        choose.addActionListener(event -> {
            var descriptor = new FileChooserDescriptor(true, false, false, false, false, false);
            descriptor.setTitle("Choose Notebook Python Executable");
            VirtualFile selected = FileChooser.chooseFile(descriptor, project, null);
            if (selected != null) select(selected.getPath());
        });
        actions.add(choose);
        actions.add(check);
        check.addActionListener(event -> checkDependencies());
        choices.addActionListener(event -> {
            if (!refreshing) select(paths.get((String) choices.getSelectedItem()));
        });
        add(actions, BorderLayout.NORTH);
        add(status, BorderLayout.SOUTH);
        refresh();
    }

    private void select(String path) {
        cancel();
        project.getService(NotebookRuntimePreferences.class).select(file.getUrl(), path);
        refresh();
    }

    private void refresh() {
        refreshing = true;
        try {
            paths.clear();
            paths.put("Follow module SDK", null);
            paths.put("No interpreter", NotebookRuntimePreferences.NONE);
            for (var sdk : ProjectJdkTable.getInstance().getAllJdks()) {
                if (PythonSdkUtil.isPythonSdk(sdk) && !PythonSdkUtil.isRemote(sdk) && sdk.getHomePath() != null) {
                    paths.put("SDK: " + sdk.getName() + " · " + sdk.getHomePath(), sdk.getHomePath());
                }
            }
            String selected = project.getService(NotebookRuntimePreferences.class).selection(file.getUrl());
            String label = paths.entrySet().stream().filter(entry -> java.util.Objects.equals(entry.getValue(), selected))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            if (label == null) {
                label = "Selected: " + selected;
                paths.put(label, selected);
            }
            choices.setModel(new DefaultComboBoxModel<>(paths.keySet().toArray(String[]::new)));
            choices.setSelectedItem(label);
            var interpreter = interpreter();
            check.setEnabled(interpreter.available());
            status.setText(interpreter.available() ? "Dependencies not checked. Opening this file starts no Python process."
                    : interpreter.problem());
        } finally { refreshing = false; }
    }

    public NotebookInterpreter interpreter() { return NotebookInterpreter.resolve(project, file); }

    private void checkDependencies() {
        cancel();
        var interpreter = interpreter();
        if (!interpreter.available()) { status.setText(interpreter.problem()); return; }
        long expected = generation;
        var operation = pending = new NotebookDependencyCheck();
        check.setEnabled(false);
        status.setText("Checking package metadata in the selected Python...");
        Thread.ofVirtual().name("notebook-dependency-check").start(() -> {
            String result;
            try {
                result = operation.run(interpreter.executable(), Path.of(file.getParent().getPath()), Duration.ofSeconds(20)).summary();
            } catch (Exception failure) {
                result = "Dependency check failed: " + failure.getMessage();
            } finally { operation.close(); }
            String message = result;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (disposed || project.isDisposed() || expected != generation) return;
                pending = null;
                check.setEnabled(interpreter().available());
                status.setText(message);
            });
        });
    }

    private void cancel() {
        generation++;
        if (pending != null) pending.close();
        pending = null;
    }

    @Override public void dispose() { disposed = true; cancel(); }
}
