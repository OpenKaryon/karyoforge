package org.openkaryon.karyoforge.settings;

import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A reversible spelling-only overlay; error and code inspections remain authoritative. */
public final class SpellingInspectionSuppressor implements InspectionSuppressor, DumbAware {
    @Override
    public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String inspectionId) {
        if (!"SpellCheckingInspection".equals(inspectionId)) {
            return false;
        }
        return !EditorPreferences.getInstance().isSpellingEnabled();
    }

    @Override
    public SuppressQuickFix @NotNull [] getSuppressActions(@Nullable PsiElement element,
                                                         @NotNull String inspectionId) {
        return new SuppressQuickFix[0];
    }
}
