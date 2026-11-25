package org.openkaryon.karyoforge.database.driver;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Loaded beside the driver so DriverManager exposes only registrations visible to that loader. */
public final class DriverRegistration {
    private DriverRegistration() {}

    public static Driver create(String name) throws ReflectiveOperationException, SQLException {
        try {
            return Class.forName(name, true, DriverRegistration.class.getClassLoader())
                    .asSubclass(Driver.class).getDeclaredConstructor().newInstance();
        } finally {
            clear();
        }
    }

    public static void shutdown(String driverClass) throws ReflectiveOperationException, InterruptedException {
        if (!driverClass.equals("com.mysql.cj.jdbc.Driver")) return;
        Class<?> cleanup = Class.forName("com.mysql.cj.jdbc.AbandonedConnectionCleanupThread", false,
                DriverRegistration.class.getClassLoader());
        cleanup.getMethod("uncheckedShutdown").invoke(null);
        var alive = cleanup.getMethod("isAlive");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        try {
            while (Boolean.TRUE.equals(alive.invoke(null)) && System.nanoTime() < deadline) Thread.sleep(10);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (Boolean.TRUE.equals(alive.invoke(null))) throw new IllegalStateException("The owned MySQL cleanup thread is still running.");
    }

    public static void clear() throws SQLException {
        ClassLoader owner = DriverRegistration.class.getClassLoader();
        var drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() == owner) DriverManager.deregisterDriver(driver);
        }
    }
}
