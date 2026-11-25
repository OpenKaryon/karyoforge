package org.openkaryon.karyoforge.database.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

final class OpenSshTunnel implements AutoCloseable {
    private static final Pattern BOUND = Pattern.compile(
            "debug1: channel [0-9]+: new (?:port-listener )?\\[port listener\\](?: \\(inactive timeout: [0-9]+\\))?");
    private final SshForwarding profile;
    private final int connectSeconds;
    private final CompletableFuture<Void> listening = new CompletableFuture<>();
    private volatile Process process;
    private volatile boolean diagnosticsEnded;
    private Thread reader;
    private int port;

    OpenSshTunnel(SshForwarding profile, int connectSeconds) {
        this.profile = profile;
        this.connectSeconds = connectSeconds;
    }

    void open() {
        profile.verifyFiles();
        try (var reservation = new ServerSocket()) {
            reservation.setReuseAddress(false);
            reservation.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0));
            port = reservation.getLocalPort();
        } catch (IOException failure) {
            throw new DatabaseProblem("Could not select a loopback port for SSH forwarding.");
        }
        try {
            var launch = new ProcessBuilder(arguments());
            launch.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            var environment = launch.environment();
            environment.clear();
            for (String key : List.of("SystemRoot", "WINDIR", "PATH", "PATHEXT", "TEMP", "TMP",
                    "USERPROFILE", "PROGRAMDATA", "LOCALAPPDATA", "APPDATA", "USERNAME", "USERDOMAIN")) {
                String value = System.getenv(key);
                if (value != null) environment.put(key, value);
            }
            environment.put("LC_ALL", "C");
            process = launch.start();
            process.getOutputStream().close();
            reader = Thread.ofPlatform().daemon().name("KaryoForge OpenSSH diagnostics").start(this::readReadiness);
            listening.get(connectSeconds, TimeUnit.SECONDS);
            if (healthy()) return;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | ExecutionException | TimeoutException unavailable) {
            // Retain the process so the session can close it.
        }
        throw new DatabaseProblem("The SSH forward did not start. Check the explicit host key, identity and endpoint.");
    }

    boolean healthy() {
        Process owned = process;
        return owned != null && owned.isAlive() && !diagnosticsEnded && listening.isDone() && !listening.isCompletedExceptionally();
    }

    int port() {
        if (!healthy()) throw new DatabaseProblem("The owned SSH tunnel is unavailable.");
        return port;
    }

    private List<String> arguments() {
        String hostFile = profile.knownHosts().toString();
        if (java.io.File.separatorChar == '\\') hostFile = hostFile.replace('\\', '/');
        hostFile = hostFile.replace("%", "%%").replace("\\", "\\\\").replace("\"", "\\\"");
        String target = profile.target().indexOf(':') < 0 ? profile.target() : "[" + profile.target() + "]";
        return List.of(profile.executable().toString(), "-F", "none", "-v", "-N", "-T",
                "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "UpdateHostKeys=no",
                "-o", "UserKnownHostsFile=\"" + hostFile + "\"", "-o", "GlobalKnownHostsFile=none",
                "-o", "IdentitiesOnly=yes", "-o", "IdentityAgent=none", "-o", "CertificateFile=none",
                "-o", "PreferredAuthentications=publickey", "-o", "PasswordAuthentication=no",
                "-o", "KbdInteractiveAuthentication=no", "-o", "ForwardAgent=no", "-o", "ControlMaster=no",
                "-o", "ControlPath=none", "-o", "ProxyCommand=none", "-o", "ProxyJump=none",
                "-o", "ExitOnForwardFailure=yes", "-o", "ConnectTimeout=" + connectSeconds, "-o", "ServerAliveInterval=5",
                "-o", "ServerAliveCountMax=2",
                "-L", "127.0.0.1:" + port + ":" + target + ":" + profile.targetPort(),
                "-p", Integer.toString(profile.port()), "-l", profile.user(), "-i", profile.identity().toString(),
                "--", profile.host());
    }

    private void readReadiness() {
        byte[] buffer = new byte[4096];
        String expected = "debug1: Local forwarding listening on 127.0.0.1 port " + port + ".";
        boolean announced = false;
        int used = 0;
        try (var input = new java.io.BufferedInputStream(process.getErrorStream())) {
            for (int next; (next = input.read()) >= 0;) {
                if (next == '\n') {
                    if (used >= 0 && !listening.isDone()) {
                        int size = used > 0 && buffer[used - 1] == '\r' ? used - 1 : used;
                        String line = new String(buffer, 0, size, StandardCharsets.US_ASCII);
                        if (line.equals(expected)) announced = true;
                        else if (announced && BOUND.matcher(line).matches()) listening.complete(null);
                    }
                    used = 0;
                } else if (used >= 0) {
                    if (used < buffer.length) buffer[used++] = (byte) next;
                    else used = -1;
                }
            }
        } catch (IOException unavailable) {
            // Diagnostics are discarded after the owner stops.
        } finally {
            diagnosticsEnded = true;
            java.util.Arrays.fill(buffer, (byte) 0);
            listening.completeExceptionally(new IOException("The OpenSSH process did not retain its listener."));
        }
    }

    @Override public void close() throws IOException {
        Process owned = process;
        if (owned == null) return;
        try {
            if (owned.isAlive()) {
                owned.destroy();
                if (!owned.waitFor(2, TimeUnit.SECONDS)) {
                    owned.destroyForcibly();
                    if (!owned.waitFor(2, TimeUnit.SECONDS)) throw new IOException("The OpenSSH process is still owned.");
                }
            }
            if (reader != null) {
                reader.join(2000);
                if (reader.isAlive()) throw new IOException("The OpenSSH diagnostics reader is still owned.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("OpenSSH cleanup was interrupted.");
        }
        process = null;
        reader = null;
    }
}
