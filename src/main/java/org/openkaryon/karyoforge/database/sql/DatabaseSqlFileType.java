package org.openkaryon.karyoforge.database.sql;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

public final class DatabaseSqlFileType extends LanguageFileType {
    public static final DatabaseSqlFileType INSTANCE = new DatabaseSqlFileType();

    private DatabaseSqlFileType() { super(DatabaseSqlLanguage.INSTANCE); }

    @Override public @NotNull String getName() { return "KaryoForge SQL"; }
    @Override public @NotNull String getDescription() { return "SQL statement file"; }
    @Override public @NotNull String getDefaultExtension() { return "sql"; }
    @Override public Icon getIcon() { return AllIcons.FileTypes.Text; }
}
