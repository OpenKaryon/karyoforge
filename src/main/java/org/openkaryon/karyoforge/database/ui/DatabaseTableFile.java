package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.testFramework.LightVirtualFile;
import org.openkaryon.karyoforge.database.core.JdbcSession.SchemaObject;

final class DatabaseTableFile extends LightVirtualFile {
    private final DatabaseSessionPanel workspace;
    private final SchemaObject object;

    DatabaseTableFile(DatabaseSessionPanel workspace, SchemaObject object) {
        super(title(object), PlainTextFileType.INSTANCE, "");
        this.workspace = workspace;
        this.object = object;
        setWritable(false);
    }

    private static String title(SchemaObject object) {
        String name = (object.schema() + "." + object.name()).replaceAll("[\\p{Cc}\\p{Cf}/\\\\]", "_");
        return name.length() > 160 ? name.substring(0, 160) : name;
    }

    DatabaseSessionPanel workspace() { return workspace; }
    SchemaObject object() { return object; }
}
