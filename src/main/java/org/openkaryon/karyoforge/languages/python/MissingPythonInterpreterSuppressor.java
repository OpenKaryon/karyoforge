package org.openkaryon.karyoforge.languages.python;

import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Suppress only the missing-SDK warning covered by our actionable editor notification. */
public final class MissingPythonInterpreterSuppressor implements InspectionSuppressor, DumbAware {
    @Override
    public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String inspectionId) {
        if (!"PyInterpreter".equals(inspectionId) && !"PyInterpreterInspection".equals(inspectionId)) {
            return false;
        }
        var source = element.getContainingFile();
        var module = source == null ? null
                : PythonInterpreters.owner(element.getProject(), source.getOriginalFile().getVirtualFile());
        return module != null && PythonInterpreters.status(module) == PythonInterpreters.Status.MISSING;
    }

    @Override
    public SuppressQuickFix @NotNull [] getSuppressActions(@Nullable PsiElement element,
                                                         @NotNull String inspectionId) {
        return new SuppressQuickFix[0];
    }
}
