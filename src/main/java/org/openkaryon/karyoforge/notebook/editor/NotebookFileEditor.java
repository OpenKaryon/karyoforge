package org.openkaryon.karyoforge.notebook.editor;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileEditor.TextEditorWithPreview;
import com.intellij.openapi.project.DumbAwareToggleAction;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/** A real JSON TextEditor owns the platform contract; cells are its passive native preview. */
public final class NotebookFileEditor extends TextEditorWithPreview {
    NotebookFileEditor(TextEditor text, NotebookEditor notebook) {
        super(text, notebook, "Notebook", Layout.SHOW_PREVIEW, false, Layout.SHOW_PREVIEW);
        // The stock wrapper posts lazy UI initialization without a disposal guard.
        getComponent();
    }

    @Override protected boolean isShowActionsInTabs() { return false; }
    @Override protected boolean isShowFloatingToolbar() { return false; }

    @Override protected @NotNull ToggleAction getShowEditorAction() {
        return viewAction("Text", "Show the complete notebook JSON", AllIcons.Actions.ShowCode, Layout.SHOW_EDITOR);
    }

    @Override protected @NotNull ToggleAction getShowEditorAndPreviewAction() {
        return viewAction("Split", "Show notebook cells and JSON together", AllIcons.Actions.SplitVertically, Layout.SHOW_EDITOR_AND_PREVIEW);
    }

    @Override protected @NotNull ToggleAction getShowPreviewAction() {
        return viewAction("Notebook", "Show native notebook cells", AllIcons.Actions.Preview, Layout.SHOW_PREVIEW);
    }

    @Override protected void onLayoutChange(Layout previous, Layout current) {
        if (current == Layout.SHOW_EDITOR) getPreviewEditor().deselectNotify();
        else getPreviewEditor().selectNotify();
    }

    @Override public void selectNotify() {
        super.selectNotify();
        if (getLayout() == Layout.SHOW_EDITOR) getPreviewEditor().deselectNotify();
    }

    @Override protected @NotNull String getSplitterProportionKey() {
        return "org.openkaryon.karyoforge.notebook.split";
    }

    private ToggleAction viewAction(String name, String description, Icon icon, Layout target) {
        return new DumbAwareToggleAction(name, description, icon) {
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
            @Override public boolean isSelected(@NotNull AnActionEvent event) { return getLayout() == target; }
            @Override public void setSelected(@NotNull AnActionEvent event, boolean selected) { if (selected) setLayout(target); }
        };
    }
}
