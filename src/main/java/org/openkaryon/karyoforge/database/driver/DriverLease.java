package org.openkaryon.karyoforge.database.driver;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.Locale;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/** Loads an explicit trusted driver without adding it to the IDE classpath. */
public final class DriverLease implements AutoCloseable {
    private static final String BRIDGE = DriverRegistration.class.getName();
    private final Loader loader;
    private final Class<?> registration;
    private final Driver driver;
    private boolean closed;

    private DriverLease(Loader loader, Class<?> registration, Driver driver) {
        this.loader = loader;
        this.registration = registration;
        this.driver = driver;
    }

    public static DriverLease open(Path path, String driverClass) throws IOException {
        Path file = path.toRealPath();
        if (!Files.isRegularFile(file) || !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("Choose an existing JDBC driver JAR.");
        }
        try (var jar = new JarFile(file.toFile())) {
            var manifest = jar.getManifest();
            if (manifest != null && manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH) != null) {
                throw new IOException("The driver JAR must not declare an implicit classpath.");
            }
        }
        var loader = new Loader(file.toUri().toURL());
        try {
            Class<?> bridge = loader.loadClass(BRIDGE);
            Driver driver = (Driver) bridge.getMethod("create", String.class).invoke(null, driverClass);
            return new DriverLease(loader, bridge, driver);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            loader.close();
            throw new IOException("The selected JDBC driver could not be loaded.");
        }
    }

    public Driver driver() {
        if (closed) throw new IllegalStateException("The driver lease is closed.");
        return driver;
    }

    @Override public void close() throws IOException {
        if (closed) return;
        try {
            registration.getMethod("clear").invoke(null);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IOException("The driver registrations could not be released.");
        }
        loader.close();
        closed = true;
    }

    private static final class Loader extends URLClassLoader {
        private Loader(URL jar) {
            super(new URL[]{jar}, ClassLoader.getPlatformClassLoader());
        }

        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!name.equals(BRIDGE)) return super.findClass(name);
            try (var source = DriverRegistration.class.getResourceAsStream("DriverRegistration.class")) {
                if (source == null) throw new ClassNotFoundException(name);
                byte[] bytes = source.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException error) {
                throw new ClassNotFoundException(name, error);
            }
        }
    }
}
