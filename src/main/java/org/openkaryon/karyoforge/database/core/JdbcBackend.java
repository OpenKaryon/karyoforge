package org.openkaryon.karyoforge.database.core;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

interface JdbcBackend {
    int CATALOG_LIMIT = 2000;
    int COLUMN_LIMIT = 256;

    String driverClass();
    Connection connect(Driver driver, char[] password) throws SQLException;
    void initialize(Connection connection) throws SQLException;
    List<JdbcSession.SchemaObject> schema(Connection connection) throws SQLException;
    TableDefinition describe(Connection connection, JdbcSession.SchemaObject object) throws SQLException;
    void validate(SqlText.Command command);
    TableSnapshot openTable(Connection connection, TableDefinition definition, QueryRequest request) throws SQLException, IOException;
    int applyTableChanges(Connection connection, TableSnapshot snapshot, List<TableSnapshot.Change> changes,
                          QueryRequest request, Runnable quarantine) throws SQLException;

    default void beforeStatement(Connection connection) throws SQLException {}
    default void configureStatement(PreparedStatement statement) throws SQLException {}
    default boolean statementSavepoints() { return false; }

    static JdbcBackend forSource(DatabaseSource source) {
        return switch (source) {
            case SqliteSource sqlite -> new SqliteBackend(sqlite);
            case PostgresqlSource postgres -> new PostgresqlBackend(postgres);
        };
    }
}
