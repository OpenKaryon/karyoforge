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

    public static void clear() throws SQLException {
        ClassLoader owner = DriverRegistration.class.getClassLoader();
        var drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() == owner) DriverManager.deregisterDriver(driver);
        }
    }
}
