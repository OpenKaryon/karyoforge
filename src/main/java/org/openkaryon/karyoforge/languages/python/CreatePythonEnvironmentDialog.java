package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.FormBuilder;
import com.jetbrains.python.sdk.PythonSdkUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

final class CreatePythonEnvironmentDialog extends DialogWrapper {
    private final TextFieldWithBrowseButton base = new TextFieldWithBrowseButton();
    private final TextFieldWithBrowseButton target = new TextFieldWithBrowseButton();

    CreatePythonEnvironmentDialog(Module module) {
        super(module.getProject());
        setTitle("Create Python Environment for " + module.getName());
        setOKButtonText("Create");
        base.addBrowseFolderListener(module.getProject(), FileChooserDescriptorFactory.singleFile());
        target.addBrowseFolderListener(module.getProject(), FileChooserDescriptorFactory.singleDir());
        var sdk = PythonSdkUtil.findPythonSdk(module);
        if (sdk != null && !PythonSdkUtil.isRemote(sdk) && sdk.getHomePath() != null) base.setText(sdk.getHomePath());
        var roots = ModuleRootManager.getInstance(module).getContentRoots();
        String directory = roots.length > 0 ? roots[0].getPath() : module.getProject().getBasePath();
        if (directory != null) target.setText(Path.of(directory).resolve(".venv").toString());
        init();
    }

    Path base() { return Path.of(base.getText().trim()).normalize(); }
    Path target() { return Path.of(target.getText().trim()).normalize(); }

    @Override protected JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder().addLabeledComponent("Base Python executable:", base)
                .addLabeledComponent("New environment directory:", target)
                .addComponent(new javax.swing.JLabel("Uses local CPython venv with bundled pip; no packages are downloaded."))
                .getPanel();
    }

    @Override public JComponent getPreferredFocusedComponent() { return base.getTextField(); }

    @Override protected @Nullable ValidationInfo doValidate() {
        try {
            VirtualEnvironmentCreation.validate(base(), target());
            return null;
        } catch (InvalidPathException failure) {
            return new ValidationInfo("Enter valid local paths.");
        } catch (IllegalArgumentException failure) {
            return new ValidationInfo(failure.getMessage());
        }
    }
}
