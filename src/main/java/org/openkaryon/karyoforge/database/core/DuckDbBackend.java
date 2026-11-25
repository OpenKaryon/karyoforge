package org.openkaryon.karyoforge.database.core;

import org.openkaryon.karyoforge.database.driver.DriverLease;
import org.openkaryon.karyoforge.database.driver.DuckDbDriverOwner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

final class DuckDbBackend implements JdbcBackend {
    private static final Set<String> READ_COMMANDS = Set.of("SELECT", "WITH", "VALUES", "FROM", "TABLE", "SHOW", "DESCRIBE");
    private static final Set<String> WRITE_COMMANDS = Set.of("SELECT", "WITH", "VALUES", "FROM", "TABLE", "SHOW", "DESCRIBE",
            "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "MERGE", "TRUNCATE");
    private final DuckDbSource source;

    DuckDbBackend(DuckDbSource source) { this.source = source; }

    @Override public String driverClass() { return "org.duckdb.DuckDBDriver"; }

    @Override public DriverLease openDriver(Path path) throws IOException { return DuckDbDriverOwner.acquire(path); }

    @Override public void releaseDriver(DriverLease lease) throws IOException { DuckDbDriverOwner.release(lease); }

    @Override public Connection connect(Driver driver, char[] password) throws SQLException {
        Path path = source.database();
        if (!Files.isRegularFile(path) && (!source.writable() || Files.exists(path) || !Files.isDirectory(path.getParent()))) {
            throw new DatabaseProblem("Choose an existing DuckDB file or a new file in an existing folder for a writable source.");
        }
        var properties = new Properties();
        properties.setProperty("duckdb.read_only", Boolean.toString(!source.writable()));
        properties.setProperty("access_mode", source.writable() ? "READ_WRITE" : "READ_ONLY");
        properties.setProperty("jdbc_pin_db", "false");
        properties.setProperty("threads", "1");
        properties.setProperty("memory_limit", "128MB");
        properties.setProperty("enable_external_access", "false");
        properties.setProperty("autoinstall_known_extensions", "false");
        properties.setProperty("autoload_known_extensions", "false");
        try {
            return driver.connect(source.jdbcUrl(), properties);
        } finally {
            properties.clear();
        }
    }

    @Override public void initialize(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("""
                     SELECT current_setting('access_mode'), current_setting('enable_external_access'),
                            current_setting('autoinstall_known_extensions'), current_setting('autoload_known_extensions')
                     """)) {
            if (!row.next() || !(source.writable() ? "read_write" : "read_only").equalsIgnoreCase(row.getString(1))
                    || row.getBoolean(2) || row.getBoolean(3) || row.getBoolean(4)) {
                throw new DatabaseProblem("DuckDB did not establish the selected access mode and local-only settings.");
            }
        }
        if (source.writable()) connection.setAutoCommit(false);
    }

    @Override public void configureStatement(PreparedStatement statement) throws SQLException { statement.setQueryTimeout(30); }

    @Override public List<JdbcSession.SchemaObject> schema(Connection connection) throws SQLException {
        var objects = new ArrayList<JdbcSession.SchemaObject>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT schema_name, table_name AS object_name, 'table' AS object_type
                       FROM duckdb_tables() WHERE database_name=current_database() AND NOT internal AND NOT temporary
                     UNION ALL
                     SELECT schema_name, view_name AS object_name, 'view' AS object_type
                       FROM duckdb_views() WHERE database_name=current_database() AND NOT internal AND NOT temporary
                     ORDER BY 1,2 LIMIT 2000
                     """)) {
            while (rows.next() && objects.size() < CATALOG_LIMIT) {
                String name = rows.getString(2);
                String schema = rows.getString(1);
                if (name.length() > 1024 || schema.length() > 1024) continue;
                objects.add(new JdbcSession.SchemaObject(name, rows.getString(3), schema, SqlText.Dialect.DUCKDB));
            }
        }
        return List.copyOf(objects);
    }

    @Override public TableDefinition describe(Connection connection, JdbcSession.SchemaObject object) throws SQLException {
        var columns = new ArrayList<TableDefinition.Column>();
        try (var statement = connection.prepareStatement("""
                SELECT c.column_name,c.data_type,c.is_nullable,COALESCE(k.ordinal_position,0)
                FROM information_schema.columns c
                LEFT JOIN (
                    SELECT uc.table_catalog,uc.table_schema,uc.table_name,uc.column_name,uc.ordinal_position
                    FROM information_schema.key_column_usage uc
                    JOIN information_schema.table_constraints con
                        USING (constraint_catalog,constraint_schema,constraint_name,table_catalog,table_schema,table_name)
                    WHERE con.constraint_type='PRIMARY KEY'
                ) k USING (table_catalog,table_schema,table_name,column_name)
                WHERE c.table_catalog=current_database() AND c.table_schema=? AND c.table_name=?
                ORDER BY c.ordinal_position
                """)) {
            statement.setString(1, object.schema());
            statement.setString(2, object.name());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (columns.size() == COLUMN_LIMIT) throw new DatabaseProblem("The object exceeds 256 columns.");
                    String name = rows.getString(1);
                    String type = rows.getString(2);
                    if (name.length() > 1024 || type.length() > 1024) throw new DatabaseProblem("A column declaration exceeds 1024 characters.");
                    columns.add(new TableDefinition.Column(name, type, rows.getString(3).equals("NO"), rows.getInt(4), 0));
                }
            }
        }
        if (columns.isEmpty()) throw new DatabaseProblem("The selected DuckDB object no longer has visible columns.");
        boolean view = object.type().equals("view");
        String sql = "SELECT substr(sql,1,65537) FROM " + (view ? "duckdb_views()" : "duckdb_tables()")
                + " WHERE database_name=current_database() AND schema_name=? AND " + (view ? "view_name" : "table_name") + "=?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, object.schema());
            statement.setString(2, object.name());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new DatabaseProblem("The selected DuckDB definition is unavailable.");
                return TableDefinition.snapshot(object, columns, rows.getString(1));
            }
        }
    }

    @Override public void validate(SqlText.Command command) {
        var allowed = source.writable() ? WRITE_COMMANDS : READ_COMMANDS;
        if (command.keyword().equals("EXPLAIN") && Set.of("SELECT", "WITH").contains(SqlText.followingKeyword(command))) return;
        if (!allowed.contains(command.keyword())) {
            throw new IllegalArgumentException("Use local DuckDB SQL and the session transaction controls.");
        }
    }
}
