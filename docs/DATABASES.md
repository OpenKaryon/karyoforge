# Databases

Open **KaryoForge Databases** in a project and choose **Add SQLite Source**.
Name the source and select an existing SQLite file and a trusted local Xerial
SQLite JDBC JAR. The name and paths are saved in application settings. The IDE
opens the file and loads the driver only when you choose **Connect**. A JDBC
driver runs executable code in the IDE process. Use an artifact whose origin
and checksum you trust. The IDE neither ships nor downloads JDBC drivers.

SQLite opens an existing file read-only by default and verifies the
connection's query-only setting. Select a table or view and choose **Show
Rows**, or enter a SELECT or WITH query. **Run SQL** and Ctrl+Enter run one
statement, using the selection or the statement at the caret. The connection
also enforces read-only access for WITH statements.

To write to an existing SQLite file, enable **Allow writes** in its source
settings before connecting. Run SQL changes and finish with **Commit** or
**Rollback**. Disconnect rolls back pending writes. The console controls
transaction boundaries and connection settings, so use the buttons for
transaction control and **Refresh Schema** after schema changes.

Use named `:name` or positional `?` parameters in one statement. The SQL
Parameters dialog binds text, integers, decimals, booleans, ISO dates, times,
timestamps, hexadecimal BLOBs and explicit NULL values. Repeated names share
a value. Literal and comment text is left intact. Cancelling the dialog leaves the existing result
and database session untouched.

Results show 200 rows per page. **Next Result Page** continues the active cursor.
The preview is bounded to 10,000 rows, 8 MiB of detached cell text, 256 columns
and 16,384 characters per cell. Large text and BLOB cells show clipped previews.
NULL is distinct from empty text, BLOBs appear as hexadecimal, and SQLite
integers retain their full precision. The catalog shows up to 2,000 tables and
views. Select an object and choose **Inspect** to see declared column types,
null constraints, primary-key order and hidden/generated columns in **Structure**.
The **DDL** tab shows up to 65,536 characters of that object's stored CREATE
statement. **Stop** cancels the active query or page request. **Disconnect**
releases the cursor, connection and driver lease.

## Result views and export

Use **Result Options** to add typed filters and sort keys across all
fetched pages. Filters combine with AND. Sorting keeps exact numeric
values and supports explicit NULL placement. These controls use the
local result and issue no SQL. **Clear filters and sort** restores the
current result page.

**Export Results** writes the visible fetched rows as CSV or quoted TSV,
with a selected encoding, an unquoted NULL token and an optional header.
The default spreadsheet policy prefixes formula-like text while retaining
complete signed numbers. A clipped value requires explicit preview export.
Export never fetches another page. Publishing a new file requires a
destination with hard-link support.

## Table editing

On a writable source, **Open Table** loads a bounded first page. Select a row
in **Table Data** to stage changes with **Edit Row** or **Delete Row**.
**Review & Apply** lists the whole batch and applies it to the current
transaction. **Commit** saves the database changes and **Rollback** discards
them. Cancelling the review keeps local drafts.

Editing requires a declared primary key and complete original values. Key
fields, hidden and generated columns, unsupported values, views and virtual
tables are protected. The batch checks the saved key and original row content
before each mutation. A conflict or failed change rolls back the entire
batch while keeping earlier pending SQL changes.

## PostgreSQL

Choose **Add PostgreSQL Source** and an explicitly selected pgJDBC JAR.
Connections without TLS are limited to loopback. For verified TLS, enable
**Verify server TLS certificate and host name** and select the server's CA
certificate. The connection requires that CA and that the certificate match
the configured host name or IP. The profile stores the endpoint, database,
user name and CA path. **Connect** asks for the password for that session.
The default access mode is an engine-enforced read-only transaction.
A PostgreSQL profile configured for writes uses manual **Commit** and
**Rollback**. A failed or stopped statement rolls back to its own
savepoint, preserving earlier pending work.

The schema list shows visible PostgreSQL tables and views. **Inspect** shows
declared column and key metadata. PostgreSQL **DDL** is a description assembled
from catalog definitions. Use **Show Rows** or run typed SQL. For a PostgreSQL
JSON operator written as a bare question mark, use pgJDBC's `??` escape.

**Open Table** supports reviewed updates and deletes on writable ordinary
PostgreSQL tables with a complete declared key and supported column types.
The batch matches the original key, row values and PostgreSQL's `xmin`.
Views, foreign tables, partitioned or inherited tables and identity or
generated fields are protected.

For an SSH connection, choose **Configure SSH** in the PostgreSQL source.
Supply the jump host and user, a local OpenSSH executable, an identity file,
a known-hosts file and the target as seen by the jump host. KaryoForge opens
an owned loopback listener and checks the SSH host key against the selected
file. A non-loopback target requires verified PostgreSQL TLS. TLS validates
the database profile's host, including when the socket travels inside SSH.
This transport uses the verified pgJDBC artifact below.

The owned acceptance fixture uses PostgreSQL 17.11 and pgJDBC 42.7.14
(SHA-256 `73914527305a40cce504b0d3d90b23caf565136912d607ae8ae7c5895512332c`).
Run the test with an available Docker engine and the explicit driver.
Add `OPENSSH_EXECUTABLE` to include the SSH fixture, with a short-lived
SSH server and generated keys.

```sh
make test-postgresql POSTGRES_DRIVER="/path/to/postgresql-42.7.14.jar"
make test-postgresql POSTGRES_DRIVER="/path/to/postgresql-42.7.14.jar" OPENSSH_EXECUTABLE="/path/to/ssh"
```

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
