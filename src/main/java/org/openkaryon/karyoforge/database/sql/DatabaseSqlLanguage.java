package org.openkaryon.karyoforge.database.sql;

import com.intellij.lang.Language;

public final class DatabaseSqlLanguage extends Language {
    public static final DatabaseSqlLanguage INSTANCE = new DatabaseSqlLanguage();

    private DatabaseSqlLanguage() { super("KaryoForgeSQL"); }

    @Override public String getDisplayName() { return "SQL"; }
}
