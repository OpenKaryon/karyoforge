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
        try {
            switch (driverClass) {
                case "com.mysql.cj.jdbc.Driver" -> shutdownMysql();
                case "org.mariadb.jdbc.Driver" -> shutdownMaria();
                default -> {}
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static void shutdownMysql() throws ReflectiveOperationException, InterruptedException {
        Class<?> cleanup = Class.forName("com.mysql.cj.jdbc.AbandonedConnectionCleanupThread", false,
                DriverRegistration.class.getClassLoader());
        cleanup.getMethod("uncheckedShutdown").invoke(null);
        var alive = cleanup.getMethod("isAlive");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (Boolean.TRUE.equals(alive.invoke(null)) && System.nanoTime() < deadline) Thread.sleep(10);
        if (Boolean.TRUE.equals(alive.invoke(null))) throw new IllegalStateException("The owned MySQL cleanup thread is still running.");
    }

    private static void shutdownMaria() throws ReflectiveOperationException, InterruptedException {
        ClassLoader owner = DriverRegistration.class.getClassLoader();
        Class<?> lock = Class.forName("org.mariadb.jdbc.client.util.ClosableLock", true, owner);
        Class<?> provider = Class.forName("org.mariadb.jdbc.client.util.SchedulerProvider", true, owner);
        var timeout = (java.util.concurrent.ScheduledThreadPoolExecutor) provider.getMethod("getTimeoutScheduler", lock)
                .invoke(null, lock.getConstructor().newInstance());
        timeout.shutdownNow();
        if (!timeout.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("The owned MariaDB timeout executor is still running.");
        }
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
