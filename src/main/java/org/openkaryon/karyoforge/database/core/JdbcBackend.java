package org.openkaryon.karyoforge.database.core;

import org.openkaryon.karyoforge.database.driver.DriverLease;

import java.io.IOException;
import java.nio.file.Path;
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
    default TableSnapshot openTable(Connection connection, TableDefinition definition, QueryRequest request) throws SQLException, IOException {
        return ReadOnlyTable.open(connection, definition, request);
    }

    default int applyTableChanges(Connection connection, TableSnapshot snapshot, List<TableSnapshot.Change> changes,
                                 QueryRequest request, Runnable quarantine) throws SQLException {
        throw new DatabaseProblem("This engine's table grid is read-only.");
    }

    default DriverLease openDriver(Path driver) throws IOException {
        return DriverLease.open(selectDrivers(driver), driverClass());
    }
    default void releaseDriver(DriverLease lease) throws IOException { lease.close(); }

    default Path selectDriver(Path driver) throws IOException { return driver; }
    default List<Path> selectDrivers(Path driver) throws IOException { return List.of(selectDriver(driver)); }
    default void closeTransport() throws IOException {}
    default void releaseDriverFiles() throws IOException {}

    default void beforeStatement(Connection connection) throws SQLException {}
    default void beforeStatement(Connection connection, QueryRequest request) throws SQLException {
        beforeStatement(connection);
    }
    default void configureStatement(PreparedStatement statement) throws SQLException {}
    default boolean statementSavepoints() { return false; }

    static JdbcBackend forSource(DatabaseSource source) {
        return switch (source) {
            case SqliteSource sqlite -> new SqliteBackend(sqlite);
            case PostgresqlSource postgres -> new PostgresqlBackend(postgres);
            case MysqlSource mysql -> new MysqlFamilyBackend(mysql);
            case DuckDbSource duck -> new DuckDbBackend(duck);
            case ClickHouseSource click -> new ClickHouseBackend(click);
        };
    }
}
