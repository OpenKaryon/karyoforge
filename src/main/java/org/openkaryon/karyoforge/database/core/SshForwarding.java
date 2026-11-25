package org.openkaryon.karyoforge.database.core;

import java.nio.file.Files;
import java.nio.file.Path;

public record SshForwarding(String host, int port, String user, Path executable, Path identity, Path knownHosts,
                            String target, int targetPort) {
    public SshForwarding {
        host = PostgresqlSource.validateHost(host, true);
        target = PostgresqlSource.validateHost(target, true);
        if (target.equalsIgnoreCase("localhost")) target = "127.0.0.1";
        if (port < 1 || port > 65535 || targetPort < 1 || targetPort > 65535) {
            throw new IllegalArgumentException("Enter SSH and forwarding ports between 1 and 65535.");
        }
        if (user == null || !user.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Enter an explicit SSH user.");
        }
        executable = DatabaseSource.absolute(executable, "OpenSSH executable");
        identity = DatabaseSource.absolute(identity, "SSH identity");
        knownHosts = DatabaseSource.absolute(knownHosts, "SSH known-hosts");
        String binary = executable.getFileName().toString();
        if (!binary.equals("ssh") && !binary.equalsIgnoreCase("ssh.exe")) {
            throw new IllegalArgumentException("Choose an OpenSSH ssh or ssh.exe executable.");
        }
    }

    void verifyFiles() {
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable) || !Files.isRegularFile(identity)
                || !Files.isRegularFile(knownHosts)) {
            throw new DatabaseProblem("Choose an existing OpenSSH executable, identity and known-hosts file.");
        }
    }

    boolean loopbackTarget() {
        return target.equals("127.0.0.1") || target.equals("::1");
    }
}
