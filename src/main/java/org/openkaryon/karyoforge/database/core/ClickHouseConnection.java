package org.openkaryon.karyoforge.database.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** A JDBC connection and its independent cancellation client share a guarded, owned route. */
final class ClickHouseConnection implements AutoCloseable {
    private final ClickHouseSource source;
    private final DatabaseTimeouts timeouts;
    private final ThreadLocal<ClickHouseGateway.Permit> dispatch = new ThreadLocal<>();
    private ClickHouseGateway gateway;
    private Connection client;
    private Connection control;
    private Connection exposed;
    private boolean opening = true;
    private volatile boolean quarantined;
    private volatile ClickHouseGateway.Permit unconfirmed;

    ClickHouseConnection(ClickHouseSource source, DatabaseTimeouts timeouts) {
        this.source = source;
        this.timeouts = timeouts;
    }

    Connection open(Driver driver, char[] password) throws SQLException {
        if (!System.getProperty("jdk.httpclient.HttpClient.log", "").isBlank()) {
            throw new DatabaseProblem("Disable JVM HTTP debug logging before using ClickHouse credentials.");
        }
        try {
            gateway = new ClickHouseGateway(source.port(), timeouts);
        } catch (java.io.IOException unavailable) {
            throw new DatabaseProblem("Could not open the owned ClickHouse HTTP route.");
        }
        client = connect(driver, password, this::queryId);
        control = connect(driver, password, () -> gateway.issue(true).id());
        verify(client, null);
        verify(control, null);
        if (!client.getAutoCommit() || client.getMetaData().supportsTransactions()) {
            throw new DatabaseProblem("The ClickHouse driver did not establish its nontransactional mode.");
        }
        opening = false;
        exposed = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, this::connectionCall);
        return exposed;
    }

    private Connection connect(Driver driver, char[] password, Supplier<String> ids) throws SQLException {
        var properties = new Properties();
        properties.setProperty("user", source.user());
        properties.setProperty("password", password == null ? "" : new String(password));
        properties.setProperty("server_time_zone", "UTC");
        properties.setProperty("jdbc_ignore_unsupported_values", "false");
        properties.setProperty("async", "false");
        properties.setProperty("retry", "0");
        properties.setProperty("connection_timeout", Integer.toString(timeouts.connectSeconds() * 1000));
        properties.setProperty("connection_request_timeout", Integer.toString(timeouts.connectSeconds() * 1000));
        properties.setProperty("socket_timeout", Integer.toString((timeouts.querySeconds() + 5) * 1000));
        properties.setProperty("clickhouse_setting_readonly", "1");
        properties.setProperty("clickhouse_setting_max_execution_time", Integer.toString(timeouts.querySeconds()));
        properties.put("jdbc_query_id_generator", ids);
        try {
            String url = "jdbc:clickhouse:http://127.0.0.1:" + gateway.localPort() + "/" + source.database();
            Connection opened = driver.connect(url, properties);
            if (opened == null) throw new SQLException("The ClickHouse driver rejected the source.");
            return opened;
        } finally {
            properties.clear();
        }
    }

    private String queryId() {
        var permit = dispatch.get();
        if (permit != null) return permit.id();
        if (opening) return gateway.issue(true).id();
        throw new IllegalStateException("The ClickHouse operation has no owned request.");
    }

    void verify(Connection connection, QueryRequest request) throws SQLException {
        int verified = 0;
        try (var statement = connection.createStatement()) {
            if (request != null) request.attach(statement);
            try (var rows = statement.executeQuery("""
                    SELECT name,value,readonly FROM system.settings
                    WHERE name IN ('readonly','max_execution_time') ORDER BY name
                    """)) {
                while (rows.next()) {
                    if (request != null) request.check();
                    String name = rows.getString(1);
                    if (name.equals("readonly") && "1".equals(rows.getString(2)) && rows.getInt(3) == 1) verified++;
                    if (name.equals("max_execution_time")) {
                        try {
                            var seconds = new BigDecimal(rows.getString(2));
                            if (seconds.signum() > 0 && seconds.compareTo(BigDecimal.valueOf(timeouts.querySeconds())) <= 0) verified++;
                        } catch (NumberFormatException ignored) {
                            // Leave the execution budget unverified.
                        }
                    }
                }
            } finally {
                if (request != null) request.detach(statement);
            }
        }
        if (verified != 2) throw new DatabaseProblem("ClickHouse requires readonly=1 and a finite request budget.");
        int count = 0;
        try (var statement = connection.createStatement()) {
            if (request != null) request.attach(statement);
            try (var rows = statement.executeQuery("SHOW GRANTS")) {
                while (rows.next()) {
                    if (request != null) request.check();
                    String grant = rows.getString(1);
                    if (++count > 64 || grant == null || grant.length() > 4096
                            || !grant.startsWith("GRANT SELECT ON ") || grant.contains(" WITH GRANT OPTION")) {
                        throw new DatabaseProblem("ClickHouse requires an account with direct SELECT grants.");
                    }
                }
            } finally {
                if (request != null) request.detach(statement);
            }
        }
        if (count == 0) throw new DatabaseProblem("ClickHouse SELECT grants could not be verified.");
    }

    private Object connectionCall(Object proxy, Method method, Object[] args) throws Throwable {
        return switch (method.getName()) {
            case "prepareStatement", "createStatement" -> {
                checkHealthy();
                yield statement((Statement) invoke(client, method, args));
            }
            case "unwrap" -> throw new SQLException("Only the owned ClickHouse JDBC connection is available.");
            case "isWrapperFor" -> false;
            case "toString" -> "Owned ClickHouse connection";
            case "close" -> {
                if (client != null) {
                    client.close();
                    client = null;
                }
                yield null;
            }
            case "isClosed" -> client == null || client.isClosed();
            default -> invoke(client, method, args);
        };
    }

    private Statement statement(Statement delegate) {
        Class<?> api = delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{api}, new StatementCalls(delegate)::invokeOwned);
    }

    void checkHealthy() {
        if (quarantined) throw new DatabaseProblem("An owned ClickHouse request could not be settled. Disconnect this source.");
    }

    private void cancel(ClickHouseGateway.Permit permit) throws SQLException {
        gateway.retire(permit);
        if (!permit.dispatched()) return;
        String id = permit.id();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(9);
        try {
            do {
                try (var statement = control.createStatement()) {
                    statement.execute("KILL QUERY WHERE query_id='" + id + "' AND user=currentUser() SYNC");
                }
                if (!permit.inFlight() && absent(id)) return;
                Thread.sleep(60);
            } while (System.nanoTime() < deadline);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (SQLException unavailable) {
            // Require independent confirmation before the session can be reused.
        }
        quarantined = true;
        unconfirmed = permit;
        throw new SQLException("ClickHouse request cancellation could not be confirmed.", "HY000");
    }

    private boolean absent(String id) throws SQLException {
        try (var statement = control.prepareStatement("SELECT count() FROM system.processes WHERE query_id=? AND user=currentUser()")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() && rows.getLong(1) == 0;
            }
        }
    }

    @Override public void close() throws SQLException {
        var request = unconfirmed;
        if (request != null) {
            cancel(request);
            unconfirmed = null;
        }
        if (client != null) {
            client.close();
            client = null;
        }
        if (control != null) {
            control.close();
            control = null;
        }
        if (gateway != null) {
            gateway.close();
            if (!gateway.awaitClosed()) throw new SQLException("The owned ClickHouse HTTP route is still closing.");
            gateway = null;
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause();
        }
    }

    private final class StatementCalls {
        private final Statement delegate;
        private ClickHouseGateway.Permit permit;
        private boolean stopped;

        private StatementCalls(Statement delegate) { this.delegate = delegate; }

        private Object invokeOwned(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            return switch (name) {
                case "execute", "executeQuery", "executeUpdate", "executeLargeUpdate" -> execute(method, args);
                case "cancel" -> {
                    stop();
                    yield null;
                }
                case "close" -> {
                    try {
                        if (permit != null && permit.needsProof() && !stopped) stop();
                        if (permit != null) {
                            gateway.retire(permit);
                            verifyTransfer(permit);
                        }
                    } finally {
                        delegate.close();
                    }
                    yield null;
                }
                case "getConnection" -> exposed;
                case "unwrap" -> throw new SQLException("Only the owned ClickHouse JDBC statement is available.");
                case "isWrapperFor" -> false;
                case "toString" -> "Owned ClickHouse statement";
                default -> invoke(delegate, method, args);
            };
        }

        private Object execute(Method method, Object[] args) throws Throwable {
            synchronized (this) {
                if (stopped) throw new SQLException("The owned ClickHouse request was stopped.", "57014");
                checkHealthy();
                if (permit != null) gateway.retire(permit);
                permit = gateway.issue(false);
            }
            dispatch.set(permit);
            try {
                Object result = invoke(delegate, method, args);
                verifyTransfer(permit);
                return result;
            } finally {
                dispatch.remove();
            }
        }

        private void verifyTransfer(ClickHouseGateway.Permit recorded) throws SQLException {
            if (recorded.failed()) {
                quarantined = true;
                throw new SQLException("The ClickHouse request exceeded its transport budget.", "HY000");
            }
        }

        private void stop() throws SQLException {
            ClickHouseGateway.Permit active;
            synchronized (this) {
                if (stopped) return;
                stopped = true;
                active = permit;
            }
            if (active != null) cancel(active);
        }
    }
}
