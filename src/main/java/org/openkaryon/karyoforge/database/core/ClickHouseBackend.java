package org.openkaryon.karyoforge.database.core;

import org.openkaryon.karyoforge.database.driver.PinnedJdbcBundle;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class ClickHouseBackend implements JdbcBackend {
    private static final List<PinnedJdbcBundle.Jar> DRIVER = List.of(
            new PinnedJdbcBundle.Jar("clickhouse-jdbc-0.10.0-all.jar", 12_309_579,
                    "d83736e24306e11929b0cf81b3306ae903c6f51c735c509ad5680c88640d99ce"),
            new PinnedJdbcBundle.Jar("slf4j-api-2.0.7.jar", 63_635,
                    "5d6298b93a1905c32cda6478808ac14c2d4a47e91535e53c41f7feeb85d946f4"),
            new PinnedJdbcBundle.Jar("slf4j-nop-2.0.7.jar", 4_848,
                    "5411a0d44e2725182271230b9fb4c2c4062c1b5fa7df2d83e00c0302733db173"));
    private static final Set<String> QUERIES = Set.of("SELECT", "WITH", "SHOW", "DESCRIBE", "DESC");
    private final ClickHouseSource source;
    private final ResultLimits limits;
    private final DatabaseTimeouts timeouts;
    private PinnedJdbcBundle bundle;
    private ClickHouseConnection transport;

    ClickHouseBackend(ClickHouseSource source, ResultLimits limits, DatabaseTimeouts timeouts) {
        this.source = source;
        this.limits = limits;
        this.timeouts = timeouts;
    }

    @Override public ResultLimits limits() { return limits; }
    @Override public DatabaseTimeouts timeouts() { return timeouts; }

    @Override public String driverClass() { return "com.clickhouse.jdbc.Driver"; }

    @Override public List<Path> selectDrivers(Path driver) throws IOException {
        bundle = PinnedJdbcBundle.open(driver, DRIVER);
        return bundle.paths();
    }

    @Override public Connection connect(Driver driver, char[] password) throws SQLException {
        transport = new ClickHouseConnection(source, timeouts);
        return transport.open(driver, password);
    }

    @Override public void initialize(Connection connection) {}

    @Override public void beforeStatement(Connection connection, QueryRequest request) throws SQLException {
        transport.checkHealthy();
        transport.verify(connection, request);
    }

    @Override public void closeTransport() throws IOException {
        if (transport != null) {
            try {
                transport.close();
                transport = null;
            } catch (SQLException uncertain) {
                throw new IOException("Could not release the owned ClickHouse transport.");
            }
        }
    }

    @Override public void releaseDriverFiles() throws IOException {
        if (bundle != null) {
            bundle.close();
            bundle = null;
        }
    }

    @Override public List<JdbcSession.SchemaObject> schema(Connection connection) throws SQLException {
        var objects = new ArrayList<JdbcSession.SchemaObject>();
        try (var statement = connection.prepareStatement("""
                SELECT database,name,if(engine IN ('View','MaterializedView','LiveView','WindowView'),'view','table'),total_rows
                FROM system.tables WHERE database=? AND is_temporary=0 ORDER BY name LIMIT
                """ + limits.catalogLimit())) {
            statement.setString(1, source.database());
            try (var rows = statement.executeQuery()) {
                while (rows.next() && objects.size() < limits.catalogLimit()) {
                    String name = rows.getString(2);
                    if (name.length() <= 1024 && name.indexOf('\0') < 0) {
                        objects.add(new JdbcSession.SchemaObject(name, rows.getString(3), rows.getString(1),
                                SqlText.Dialect.CLICKHOUSE, JdbcSession.SchemaObject.estimate(rows.getString(4))));
                    }
                }
            }
        }
        return List.copyOf(objects);
    }

    @Override public TableDefinition describe(Connection connection, JdbcSession.SchemaObject object) throws SQLException {
        if (!object.schema().equals(source.database())) throw new DatabaseProblem("Choose an object in the selected ClickHouse database.");
        var columns = new ArrayList<TableDefinition.Column>();
        try (var statement = connection.prepareStatement("""
                SELECT name,type,is_in_primary_key,default_kind FROM system.columns
                WHERE database=? AND table=? ORDER BY position
                """)) {
            statement.setString(1, object.schema());
            statement.setString(2, object.name());
            try (var rows = statement.executeQuery()) {
                int keys = 0;
                while (rows.next()) {
                    limits.checkColumns(columns.size() + 1);
                    String name = rows.getString(1);
                    String type = rows.getString(2);
                    String defaultKind = rows.getString(4);
                    if (name.length() > 1024 || type.length() > 1024) throw new DatabaseProblem("A column declaration exceeds 1024 characters.");
                    columns.add(new TableDefinition.Column(name, type, !type.startsWith("Nullable("),
                            rows.getInt(3) == 0 ? 0 : ++keys, defaultKind.equals("ALIAS") ? 2 : defaultKind.equals("MATERIALIZED") ? 3 : 0));
                }
            }
        }
        if (columns.isEmpty()) throw new DatabaseProblem("The selected ClickHouse object no longer has visible columns.");
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SHOW CREATE TABLE " + object.qualified())) {
            if (!rows.next()) throw new DatabaseProblem("The selected ClickHouse definition is unavailable.");
            return TableDefinition.snapshot(object, columns, rows.getString(1));
        }
    }

    @Override public void validate(SqlText.Command command) {
        if (!QUERIES.contains(command.keyword())) throw new IllegalArgumentException("Run a read-only ClickHouse query.");
    }
}
