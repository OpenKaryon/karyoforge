# Databases

Open **KaryoForge Databases** in a project and choose **Add SQLite Source**.
Name the source and select an existing SQLite file and a trusted local Xerial
SQLite JDBC JAR. The name and paths are saved in application settings. The IDE
opens the file and loads the driver only when you choose **Connect**. A JDBC
driver runs executable code in the IDE process. Use an artifact whose origin
and checksum you trust. The IDE neither ships nor downloads JDBC drivers.

SQLite opens the file in read-only mode and verifies the connection's
query-only setting. Select a table or view and choose **Show Rows**, or enter
a SELECT or WITH query. **Run SQL** and Ctrl+Enter run one statement, using
the selection or the statement at the caret. The connection also enforces
read-only access for WITH statements.

Use named `:name` or positional `?` parameters in one statement. The SQL
Parameters dialog binds text, integers, decimals, booleans, ISO dates, times,
timestamps and explicit NULL values. Repeated names share a value. Literal and
comment text is left intact. Cancelling the dialog leaves the existing result
and database session untouched.

Results show 200 rows per page. **Next Result Page** continues the active cursor.
The preview is bounded to 10,000 rows, 8 MiB of detached cell text, 256 columns
and 16,384 characters per cell. Large text and BLOB cells show clipped previews.
NULL is distinct from empty text, BLOBs appear as hexadecimal, and SQLite
integers retain their full precision. The catalog shows up to 2,000 tables and
views. **Stop** cancels the active query or page request. **Disconnect** releases
the cursor, connection and driver lease.

## Checks

The SQLite tests use an owned database and an explicit local JDBC JAR. The
validated fixture is Xerial SQLite JDBC 3.53.2.0 (SHA-256
`dc320e4102884c135ccc30c3c6fc3fb190b750e1586a100e3aba3be783cf33a9`).

```sh
make test-database SQLITE_DRIVER="/path/to/sqlite-jdbc-3.53.2.0.jar"
make gui-bundle SQLITE_DRIVER="/path/to/sqlite-jdbc-3.53.2.0.jar"
```

The optional JAR travels with the VM test bundle, separately from the IDE
distribution. The VM test seeds its own SQLite file and checks schema browsing,
result paging and cancellation. See the [GUI suite runner](../uitest/README.md).
