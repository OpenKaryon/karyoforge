package org.openkaryon.karyoforge.database.driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class PinnedJdbcBundle implements AutoCloseable {
    private final Path stage;
    private final List<Path> staged = new ArrayList<>();

    private PinnedJdbcBundle(Path stage) { this.stage = stage; }

    public static PinnedJdbcBundle open(Path selected, List<Jar> artifacts) throws IOException {
        var bundle = new PinnedJdbcBundle(Files.createTempDirectory("karyoforge-jdbc-"));
        try {
            for (int index = 0; index < artifacts.size(); index++) {
                var jar = artifacts.get(index);
                Path original = index == 0 ? selected : selected.resolveSibling(jar.filename());
                Path copy = bundle.stage.resolve(jar.filename());
                bundle.staged.add(copy);
                copyVerified(original, copy, jar);
            }
            return bundle;
        } catch (IOException | RuntimeException failure) {
            try {
                bundle.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public List<Path> paths() { return List.copyOf(staged); }

    public static void verify(Path source, Jar expected) throws IOException { copyVerified(source, null, expected); }

    private static void copyVerified(Path source, Path target, Jar expected) throws IOException {
        if (!Files.isRegularFile(source) || Files.size(source) != expected.size()) throw invalid(expected);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("Could not verify the JDBC artifact.");
        }
        long length = 0;
        byte[] buffer = new byte[65_536];
        try (var input = Files.newInputStream(source); var output = target == null ? java.io.OutputStream.nullOutputStream() : Files.newOutputStream(target)) {
            for (int count; (count = input.read(buffer)) >= 0;) {
                length += count;
                if (length > expected.size()) throw invalid(expected);
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }
        if (length != expected.size() || !HexFormat.of().formatHex(digest.digest()).equals(expected.sha256())) {
            throw invalid(expected);
        }
    }

    private static IOException invalid(Jar artifact) {
        return new IOException("Select the verified JDBC bundle with " + artifact.filename() + ".");
    }

    @Override public void close() throws IOException {
        for (Path copy : staged) Files.deleteIfExists(copy);
        Files.deleteIfExists(stage);
    }

    public record Jar(String filename, long size, String sha256) {
        public Jar {
            if (!filename.matches("[A-Za-z0-9._-]+\\.jar") || size <= 0 || !sha256.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid JDBC artifact identity.");
            }
        }
    }
}
