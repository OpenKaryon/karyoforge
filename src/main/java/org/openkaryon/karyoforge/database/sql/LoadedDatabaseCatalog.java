package org.openkaryon.karyoforge.database.sql;

import org.openkaryon.karyoforge.database.core.JdbcSession.SchemaObject;
import org.openkaryon.karyoforge.database.core.TableDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detached metadata made available by explicit catalog operations in the tool window. */
public record LoadedDatabaseCatalog(List<SchemaObject> objects, Map<SchemaObject, TableDefinition> definitions) {
    private static final int MAX_DEFINITIONS = 64;
    public static final LoadedDatabaseCatalog EMPTY = new LoadedDatabaseCatalog(List.of(), Map.of());

    public LoadedDatabaseCatalog {
        objects = List.copyOf(objects);
        definitions = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
    }

    public LoadedDatabaseCatalog withDefinition(TableDefinition definition) {
        if (!objects.contains(definition.object())) return this;
        var updated = new LinkedHashMap<>(definitions);
        updated.remove(definition.object());
        updated.put(definition.object(), definition);
        if (updated.size() > MAX_DEFINITIONS) updated.pollFirstEntry();
        return new LoadedDatabaseCatalog(objects, updated);
    }
}
