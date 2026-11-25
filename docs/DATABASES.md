# Databases

Open **KaryoForge Databases** in a project and choose **Add SQLite Source**.
Name the source and select an existing SQLite file and a trusted local Xerial
SQLite JDBC JAR. The name and paths are saved in application settings. The IDE
opens the file and loads the driver only when you choose **Connect**. A JDBC
driver runs executable code in the IDE process. Use an artifact whose origin
and checksum you trust. The IDE neither ships nor downloads JDBC drivers.
Use **Test Connection** to open and close a temporary session for the selected
profile. It leaves the project's active connections and catalogs in place.
Select another profile to connect while existing sessions stay open. Each
connected source keeps its own results, cursor, row drafts, transaction and
Stop control. A query running on one source leaves the other sources available.
The project remembers the last selected profile.

Server profiles can save their password with **Remember password in PasswordSafe**
when connecting. This choice starts unchecked. Leave the field blank to reuse
a remembered password. The entry belongs to the profile and its destination,
including the selected driver, TLS trust file and SSH configuration. Renaming
a profile keeps that entry. Changing the destination or removing the profile
clears its prior entry. Database settings contain no passwords.

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

## SQL editor

**Open SQL Console** opens a native editor tab bound to its data source.
Use **Run SQL Selection / Current Statement** in that editor or in a
project's `.sql` file, or press Ctrl+Enter. A console selects and uses its bound
source even when a different source was selected in the tool window.
A regular SQL file uses the connected source selected in the Databases
tool window. Run, Stop, bindings and result pages share that window's
connection. The editor highlights SQL tokens and completes keywords.
After opening or refreshing a connection's catalog, SQL completion can
suggest its tables and views. **Inspect** or **Open Table** loads column
names that the editor can complete for a simple SELECT, including a
qualified relation or alias. **Go to Declaration** opens a read-only
description of an unambiguous loaded object. Completion and navigation
use that detached catalog and issue no queries. Complex scopes
such as CTEs, subqueries and UNIONs use keyword completion only.
Refresh Schema clears cached column definitions.

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

Choose **Add Server Source → PostgreSQL** and an explicitly selected pgJDBC JAR.
Connections without TLS are limited to loopback. For verified TLS, enable
**Verify server TLS certificate and host name** and select the server's CA
certificate. The connection requires that CA and that the certificate match
the configured host name or IP. The profile stores the endpoint, database,
user name and CA path. **Connect** prompts for the session password.
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

## MySQL

Choose **Add Server Source → MySQL**, a loopback endpoint and a verified
MySQL Connector/J 26.7.0 JAR. The connection verifies the server's transaction
access mode and SQL quoting mode. By default it opens read-only. A writable
profile supports transaction-safe SQL with explicit **Commit** or
**Rollback**. The table grid remains a read-only view. A read-only connection
uses the server's transaction isolation; reconnect to start a fresh snapshot.

The catalog shows tables and views in the selected database. **Inspect**
uses declared keys, columns and the server's SHOW CREATE definition.
Use EXPLAIN SELECT or EXPLAIN WITH for a query plan. The SQL console accepts
one statement and refuses session changes, executable comments and
implicit-commit commands. Direct connections use loopback without TLS.

The owned test fixture runs MySQL 8.4.11 with Connector/J 26.7.0
(SHA-256 `69084713593a4aa8d07c383619b9639276f08bccf8faf1c562178147d389b1e1`).

```sh
make test-mysql MYSQL_DRIVER="/path/to/mysql-connector-j-26.7.0.jar"
```

## MariaDB

Choose **Add Server Source → MariaDB** for a loopback MariaDB connection.
Select MariaDB Connector/J 3.5.10 and keep `slf4j-api-2.0.17.jar` and
`slf4j-nop-2.0.17.jar` beside it. The IDE verifies all three files before
loading the driver.

The MariaDB catalog, typed SQL pages, plan-only EXPLAIN and transaction
controls follow the MySQL behavior above. MariaDB uses its own server-side
read-only setting. The owned fixture runs MariaDB 11.8.9 and exercises
read-only access, cancellation and transaction visibility.

```sh
make test-mariadb MARIADB_DRIVER="/path/to/mariadb-java-client-3.5.10.jar"
```

## DuckDB

Choose **Add DuckDB Source** and select the verified DuckDB JDBC 1.5.6.0 JAR
and a local database file. Read-only sources require an existing database.
A writable source can create a file in an existing folder and uses manual
**Commit** and **Rollback**. After a failed statement that aborts the
transaction, use **Rollback** before running more SQL.

The connection verifies that external file and network access and automatic
extension installation and loading are disabled. The catalog shows local
tables and views with column definitions and stored CREATE statements.
SQL results are bounded after the driver reads a cell, and the table grid
is view-only. Restart the IDE to select a different DuckDB driver file.

The owned test uses DuckDB JDBC 1.5.6.0 (SHA-256
`04ae3375d7c40dc88dc9bf0014b82ba5e66715564584af4e3abea39c7ba144d3`).

```sh
make test-duckdb DUCKDB_DRIVER="/path/to/duckdb_jdbc-1.5.6.0.jar"
```

## ClickHouse

Choose **Add Server Source → ClickHouse** and supply the loopback HTTP port.
Use ClickHouse JDBC V2 0.10.0-all with `slf4j-api-2.0.7.jar` and
`slf4j-nop-2.0.7.jar` beside it. KaryoForge verifies the bundle and requires
a server account with direct SELECT grants. The connection checks
`readonly=1` and the server's finite request budget before running SQL.

Browse the selected database, inspect columns and server-produced CREATE
definitions, and read bounded typed pages. This profile supports read-only
queries. **Stop** retires the active request's UUID and independently checks
that the operation has stopped on the server. The transport limits request
and response bytes and time on its configured loopback authority.

The owned fixture uses ClickHouse 26.8.20.9 with the verified JDBC bundle.

```sh
make test-clickhouse CLICKHOUSE_DRIVER="/path/to/clickhouse-jdbc-0.10.0-all.jar"
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
