package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.openkaryon.karyoforge.database.core.ClickHouseSource;
import org.openkaryon.karyoforge.database.core.DatabaseSource;
import org.openkaryon.karyoforge.database.core.DuckDbSource;
import org.openkaryon.karyoforge.database.core.MysqlSource;
import org.openkaryon.karyoforge.database.core.PostgresqlSource;
import org.openkaryon.karyoforge.database.core.ResultLimits;
import org.openkaryon.karyoforge.database.core.SqliteSource;
import org.openkaryon.karyoforge.database.core.SshForwarding;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Stores user-selected paths only. Loading or editing a profile does not open a driver or a database. */
@Service(Service.Level.APP)
@State(name = "org.openkaryon.karyoforge.database.sources", storages = @Storage("karyoforge-databases.xml"))
public final class DatabaseSources implements PersistentStateComponent<DatabaseSources.Snapshot> {
    private final LinkedHashMap<String, Profile> profiles = new LinkedHashMap<>();

    synchronized List<Profile> profiles() { return List.copyOf(profiles.values()); }

    synchronized String save(String id, DatabaseSource source) {
        String key = id == null ? UUID.randomUUID().toString() : id;
        var existing = profiles.get(key);
        profiles.put(key, new Profile(key, source, existing == null ? ResultLimits.DEFAULT : existing.limits()));
        return key;
    }

    synchronized void remove(String id) { profiles.remove(id); }

    synchronized void saveLimits(String id, ResultLimits limits) {
        profiles.computeIfPresent(id, (key, old) -> new Profile(key, old.source(), limits));
    }

    @Override public synchronized @NotNull Snapshot getState() {
        var state = new Snapshot();
        for (var profile : profiles.values()) {
            var row = new Entry();
            row.id = profile.id();
            row.name = profile.source().name();
            row.driver = profile.source().driver().toString();
            row.writable = profile.source().writable();
            if (!profile.limits().equals(ResultLimits.DEFAULT)) row.resultLimits = new LimitsEntry(profile.limits());
            switch (profile.source()) {
                case SqliteSource sqlite -> row.database = sqlite.database().toString();
                case DuckDbSource duck -> {
                    row.kind = "duckdb";
                    row.database = duck.database().toString();
                }
                case PostgresqlSource postgres -> {
                    row.kind = "postgresql";
                    row.host = postgres.host();
                    row.port = postgres.port();
                    row.database = postgres.database();
                    row.user = postgres.user();
                    if (postgres.verifiedTls()) row.caFile = postgres.caFile().toString();
                    if (postgres.forwarding() != null) row.ssh = new SshEntry(postgres.forwarding());
                }
                case ClickHouseSource click -> {
                    row.kind = "clickhouse";
                    row.port = click.port();
                    row.database = click.database();
                    row.user = click.user();
                }
                case MysqlSource mysql -> {
                    row.kind = mysql.variant() == MysqlSource.Variant.MARIADB ? "mariadb" : "mysql";
                    row.host = mysql.host();
                    row.port = mysql.port();
                    row.database = mysql.database();
                    row.user = mysql.user();
                }
            }
            state.entries.add(row);
        }
        return state;
    }

    @Override public synchronized void loadState(@NotNull Snapshot state) {
        profiles.clear();
        if (state.entries == null) return;
        for (var entry : state.entries) {
            if (entry == null || entry.id == null) continue;
            try {
                String id = UUID.fromString(entry.id).toString();
                DatabaseSource source = switch (entry.kind == null ? "sqlite" : entry.kind) {
                    case "sqlite" -> new SqliteSource(entry.name, Path.of(entry.database), Path.of(entry.driver), entry.writable);
                    case "duckdb" -> new DuckDbSource(entry.name, Path.of(entry.database), Path.of(entry.driver), entry.writable);
                    case "postgresql" -> new PostgresqlSource(entry.name, entry.host, entry.port, entry.database, entry.user,
                            Path.of(entry.driver), entry.writable, entry.caFile == null ? null : Path.of(entry.caFile),
                            entry.ssh == null ? null : entry.ssh.forwarding());
                    case "clickhouse" -> new ClickHouseSource(entry.name, entry.port, entry.database, entry.user, Path.of(entry.driver));
                    case "mysql", "mariadb" -> new MysqlSource(entry.name, entry.host, entry.port, entry.database, entry.user,
                            Path.of(entry.driver), entry.writable,
                            "mariadb".equals(entry.kind) ? MysqlSource.Variant.MARIADB : MysqlSource.Variant.MYSQL);
                    default -> throw new IllegalArgumentException("Unknown database source.");
                };
                ResultLimits limits = entry.resultLimits == null ? ResultLimits.DEFAULT : entry.resultLimits.value();
                profiles.putIfAbsent(id, new Profile(id, source, limits));
            } catch (IllegalArgumentException | NullPointerException invalid) {
                // A malformed saved profile must not prevent valid profiles from loading.
            }
        }
    }

    record Profile(String id, DatabaseSource source, ResultLimits limits) {
        Profile(String id, DatabaseSource source) { this(id, source, ResultLimits.DEFAULT); }
        @Override public String toString() { return source.name(); }
    }

    public static final class Snapshot {
        public List<Entry> entries = new ArrayList<>();
    }

    public static final class Entry {
        public String id;
        public String name;
        public String kind;
        public String host;
        public int port;
        public String user;
        public String caFile;
        public SshEntry ssh;
        public String database;
        public String driver;
        public boolean writable;
        public LimitsEntry resultLimits;
    }

    public static final class LimitsEntry {
        public int pageSize;
        public int maxRows;
        public int maxBytes;
        public int maxCellChars;
        public int maxColumns;
        public int catalogLimit;

        public LimitsEntry() {}

        LimitsEntry(ResultLimits limits) {
            pageSize = limits.pageSize();
            maxRows = limits.maxRows();
            maxBytes = limits.maxBytes();
            maxCellChars = limits.maxCellChars();
            maxColumns = limits.maxColumns();
            catalogLimit = limits.catalogLimit();
        }

        ResultLimits value() {
            try {
                return new ResultLimits(pageSize, maxRows, maxBytes, maxCellChars, maxColumns, catalogLimit);
            } catch (IllegalArgumentException malformed) {
                return ResultLimits.DEFAULT;
            }
        }
    }

    public static final class SshEntry {
        public String host;
        public int port;
        public String user;
        public String executable;
        public String identity;
        public String knownHosts;
        public String target;
        public int targetPort;

        public SshEntry() {}

        SshEntry(SshForwarding entry) {
            host = entry.host();
            port = entry.port();
            user = entry.user();
            executable = entry.executable().toString();
            identity = entry.identity().toString();
            knownHosts = entry.knownHosts().toString();
            target = entry.target();
            targetPort = entry.targetPort();
        }

        SshForwarding forwarding() {
            return new SshForwarding(host, port, user, Path.of(executable), Path.of(identity), Path.of(knownHosts), target, targetPort);
        }
    }
}
