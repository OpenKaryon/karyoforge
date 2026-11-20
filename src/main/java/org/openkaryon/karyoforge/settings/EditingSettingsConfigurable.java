package org.openkaryon.karyoforge.settings;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;

public final class EditingSettingsConfigurable implements SearchableConfigurable {
    private JBCheckBox spelling;

    @Override
    public @NotNull String getId() {
        return "org.openkaryon.karyoforge.settings";
    }

    @Override
    public String getDisplayName() {
        return "KaryoForge";
    }

    @Override
    public JComponent createComponent() {
        spelling = new JBCheckBox("Enable spell checking");
        var component = FormBuilder.createFormBuilder()
                .addComponent(spelling)
                .addComponent(new JBLabel("<html>Applies to all projects using your existing inspection profile.<br>"
                        + "Turn this off to hide spelling underlines without changing that profile.</html>"))
                .getPanel();
        reset();
        return component;
    }

    @Override
    public boolean isModified() {
        return spelling != null && spelling.isSelected() != EditorPreferences.getInstance().isSpellingEnabled();
    }

    @Override
    public void apply() {
        if (!isModified()) {
            return;
        }
        EditorPreferences.getInstance().setSpellingEnabled(spelling.isSelected());
        for (var project : ProjectManager.getInstance().getOpenProjects()) {
            if (!project.isDisposed()) {
                DaemonCodeAnalyzer.getInstance(project).restart();
            }
        }
    }

    @Override
    public void reset() {
        if (spelling != null) {
            spelling.setSelected(EditorPreferences.getInstance().isSpellingEnabled());
        }
    }

    @Override
    public void disposeUIResources() {
        spelling = null;
    }
}
