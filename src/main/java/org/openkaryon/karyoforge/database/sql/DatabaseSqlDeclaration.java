package org.openkaryon.karyoforge.database.sql;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.Nullable;
import org.openkaryon.karyoforge.database.ui.DatabaseEditorBridge;

public final class DatabaseSqlDeclaration implements GotoDeclarationHandler {
    @Override public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement source, int offset, Editor editor) {
        if (source == null || source.getLanguage() != DatabaseSqlLanguage.INSTANCE) return null;
        var file = source.getContainingFile();
        if (file.getVirtualFile() == null || !file.getVirtualFile().isWritable()) return null;
        var project = source.getProject();
        var catalog = project.getService(DatabaseEditorBridge.class).catalogFor(file.getVirtualFile());
        var object = DatabaseSqlReferences.resolve(file.getText(), offset, catalog);
        if (object == null) return null;
        var definition = catalog.definitions().get(object);
        String content = "-- Loaded " + object.type() + ": " + object.qualified() + "\n\n";
        if (definition != null && !definition.ddl().isBlank()) content += definition.ddl();
        else content += object.selectSql();
        var snapshot = new LightVirtualFile("Loaded Database Metadata.sql", DatabaseSqlFileType.INSTANCE, content);
        snapshot.setWritable(false);
        var target = PsiManager.getInstance(project).findFile(snapshot);
        return target == null ? null : new PsiElement[]{target};
    }
}
