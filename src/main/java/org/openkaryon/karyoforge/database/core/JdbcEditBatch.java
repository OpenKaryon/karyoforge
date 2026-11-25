package org.openkaryon.karyoforge.database.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

final class JdbcEditBatch {
    private JdbcEditBatch() {}

    static int apply(Connection connection, QueryRequest request, Runnable quarantine, Plans load) throws SQLException {
        request.check();
        var checkpoint = connection.setSavepoint();
        try {
            List<Mutation> plans = load.create();
            for (var plan : plans) {
                request.check();
                try (var statement = connection.prepareStatement(plan.sql())) {
                    request.attach(statement);
                    try {
                        int index = 1;
                        for (var value : plan.bindings()) value.bind(statement, index++);
                        if (statement.executeUpdate() != 1) {
                            throw new DatabaseProblem("An original row changed or no longer exists. The edit batch was rolled back.");
                        }
                    } finally {
                        request.detach(statement);
                    }
                }
            }
            if (request.finish()) throw new DatabaseProblem("Stop could not be confirmed. Disconnect this data source.");
            request.check();
            connection.releaseSavepoint(checkpoint);
            return plans.size();
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback(checkpoint);
                connection.releaseSavepoint(checkpoint);
            } catch (SQLException cleanupFailure) {
                quarantine.run();
                throw new DatabaseProblem("The edit savepoint could not be released. Disconnect this data source.");
            }
            throw failure;
        }
    }

    @FunctionalInterface interface Plans { List<Mutation> create() throws SQLException; }

    record Mutation(String sql, List<SqlValue> bindings) {
        Mutation { bindings = List.copyOf(bindings); }
    }
}
