package org.openkaryon.karyoforge.database.ui;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import org.openkaryon.karyoforge.database.core.ClickHouseSource;
import org.openkaryon.karyoforge.database.core.MysqlSource;
import org.openkaryon.karyoforge.database.core.PostgresqlSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Binds an opt-in PasswordSafe entry to one saved profile and its effective destination. */
final class DatabaseCredentials {
    private DatabaseCredentials() {}

    static char[] recall(DatabaseSources.Profile profile) {
        var saved = PasswordSafe.getInstance().get(key(profile));
        if (saved == null || saved.getPassword() == null) return new char[0];
        return saved.getPassword().toCharArray(false).clone();
    }

    static void remember(DatabaseSources.Profile profile, char[] password) {
        PasswordSafe.getInstance().set(key(profile), new Credentials(user(profile), password.clone()));
    }

    static void forget(DatabaseSources.Profile profile) {
        PasswordSafe.getInstance().set(key(profile), null);
    }

    static boolean sameDestination(DatabaseSources.Profile first, DatabaseSources.Profile second) {
        return first.source().usesPassword() && second.source().usesPassword() && key(first).equals(key(second));
    }

    private static String user(DatabaseSources.Profile profile) {
        return switch (profile.source()) {
            case PostgresqlSource postgres -> postgres.user();
            case MysqlSource mysql -> mysql.user();
            case ClickHouseSource click -> click.user();
            default -> throw new IllegalArgumentException("This data source does not accept a database password.");
        };
    }

    private static CredentialAttributes key(DatabaseSources.Profile profile) {
        var identity = new StringBuilder();
        var source = profile.source();
        append(identity, profile.id(), source.engine(), source.driver(), user(profile));
        switch (source) {
            case PostgresqlSource postgres -> {
                append(identity, postgres.host(), postgres.port(), postgres.database(), postgres.caFile());
                var ssh = postgres.forwarding();
                if (ssh != null) {
                    append(identity, ssh.host(), ssh.port(), ssh.user(), ssh.executable(), ssh.identity(),
                            ssh.knownHosts(), ssh.target(), ssh.targetPort());
                }
            }
            case MysqlSource mysql -> append(identity, mysql.host(), mysql.port(), mysql.database());
            case ClickHouseSource click -> append(identity, "127.0.0.1", click.port(), click.database());
            default -> throw new IllegalArgumentException("This data source does not accept a database password.");
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.toString().getBytes(StandardCharsets.UTF_8));
            return new CredentialAttributes("org.openkaryon.karyoforge.database/" + HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable.", unavailable);
        }
    }

    private static void append(StringBuilder identity, Object... values) {
        for (Object value : values) {
            if (value == null) {
                identity.append("-:");
            } else {
                String text = value.toString();
                identity.append(text.length()).append(':').append(text);
            }
        }
    }
}
