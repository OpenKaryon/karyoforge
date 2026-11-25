# Local build and validation interface

## Requirements

Source/audit checks use Python 3.10+ and GNU Make. The commands work without a
reference IDE checkout unless one is explicitly requested. `tools/tasks.py`
provides the same interface without Make and does not invoke a shell for
user-supplied reference paths. Paths containing spaces are passed as one argument.

Plugin compilation uses JDK 21, Gradle 9.1.0 and IntelliJ Platform Gradle Plugin
2.19.0. It targets IntelliJ IDEA Community 2025.2.6.3, exactly IC-252.28539.97.
The checked-in wrapper selects the smaller Gradle binary distribution and checks
its official SHA-256. The task runner independently checks the bootstrap JAR
against Gradle's published wrapper checksum before executing it.

Set `JAVA_HOME` to an installed JDK 21. The first invocation downloads Gradle and
the pinned platform; later invocations reuse the standard Gradle user cache.
`GRADLE_USER_HOME` can select another local cache directory. No machine path,
proxy setting or credential belongs in the checked-in build configuration.
The build uses direct official JetBrains repositories, disables tooling update
checks and does not request platform sources during project import by default.

Windows x64 standalone packaging uses the pinned SDK archives, PythonCore and
portable NSIS/rcedit build tools. Checksums are verified before extraction or
execution. See [Windows distribution](WINDOWS_DISTRIBUTION.md) for commands,
outputs and profile locations. Linux packaging is not implemented.

## Commands

| Make target | Supported behavior |
| --- | --- |
| `help` | Show the available local interfaces and prerequisites |
| `lint` | Parse Python and JSON; check whitespace, documentation links and documentation policy |
| `test` | Run synthetic unit tests for the audit/development tools |
| `audit` | Validate inventory classification/fingerprints and public namespace rules |
| `check` | Run lint, tool tests and public audit |
| `audit-reference` | Regenerate the reference inventory from explicit `REFERENCE_DIR` |
| `audit-reference-check` | Check the same inputs for drift without rewriting the inventory |
| `build` | Delegate to Gradle `buildPlugin verifyPluginStructure`; fail if build prerequisites are absent |
| `verify` | Delegate to Gradle `verifyPlugin`; fail if build prerequisites are absent |
| `test-postgresql` | Run PostgreSQL in an owned Docker container with an explicit local `POSTGRES_DRIVER` |
| `test-database` | Run SQLite fixture integration tests with an explicit local `SQLITE_DRIVER` |
| `test-ide` | Delegate to Gradle `test`; fail if build prerequisites are absent |
| `test-python` | Run native Python script/module, selected unittest/pytest and debugger checks with explicit `PYTHON_EXECUTABLE` |
| `notebook-fixtures` | Prepare and verify the locked offline Windows notebook wheelhouse |
| `notebook-runtime` | Use explicit base `PYTHON_EXECUTABLE` to create `build/notebook-runtime/venv`; Windows x64 CPython 3.14 required |
| `test-notebook` | Run native notebook model/editor/probe/kernel/input tests with explicit notebook `PYTHON_EXECUTABLE` |
| `branding` | Build a resource-only KaryoForge application branding JAR |
| `test-branding` | Check native branded metadata, both splash images and small SVG rendering in a fresh headless JVM |
| `run` | Launch the branded development IDE with a separate sandbox configuration |
| `dist-windows` | Assemble the standalone Windows IDE with bundled Java/PythonCore |
| `zip-windows` | Create the portable Windows application ZIP and checksum |
| `verify-dist-windows` | Check standalone headless startup with a disposable profile |
| `package-windows` | Run source checks and compile the per-user Windows installer and checksum |
| `package-linux` | Run `installer/build-linux.ps1`; fail if it is absent |

```sh
make check
make build
make test-ide
make verify
make audit-reference REFERENCE_DIR="/path/with spaces/reference-ide"
make audit-reference-check REFERENCE_DIR="/path/with spaces/reference-ide"
```

`AUDIT_OUTPUT` selects the output JSON file. `PYTHON` selects a Python executable;
pass an executable path rather than an executable-plus-shell-options expression.
On Windows, invoke GNU Make from PowerShell normally. No Bash installation is
needed for the source checks. Windows packaging uses Python and portable NSIS;
Gradle uses its native wrapper for the host OS.

`make check` covers source/audit tools. `make test-ide` loads the public plugin
in the headless platform test environment and checks its ID, enabled state and
the exact platform build. Editing tests also exercise the registered spelling
overlay, state round-trip and settings Apply/Reset/disposal behavior. The test
environment does not use the owner's regular IDE configuration.
Python editor and module-isolation checks run with `make test-ide`. Real process
and debugger checks require `make test-python PYTHON_EXECUTABLE="/path/to/python"`
and the pinned dependencies in `tools/python-test-requirements.txt`. See
[Python development](PYTHON.md). This suite uses 2 GB of test heap for SDK
indexing; default platform tests retain the 1 GB limit.

## Notebook test environments

`make notebook-fixtures` downloads only wheel URLs recorded with SHA-256 hashes
in `tools/notebook-test-wheels.json`; a valid cache is verified and reused.
The lock is copied byte for byte, keeping its manifest and ownership hashes
consistent in both LF and CRLF checkouts.
The lock covers the complete Windows x64 CPython 3.14 dependency graph, including
jupyter_client 8.10.0 and ipykernel 7.4.0. These are acceptance inputs, not bundled
application dependencies or a restriction on every user-selected Python setup.

`make notebook-runtime PYTHON_EXECUTABLE="C:/path/to/base-python.exe"` validates
the base runtime and creates an owned environment under `build/notebook-runtime`.
Installation uses only the verified local wheelhouse, `--no-index`,
`--require-hashes`, pinned transitive packages and a final `pip check`. Existing
unowned/nonempty directories or different lock identities are refused. The
base interpreter and global package stores are not modified. The standalone
helper accepts explicit `--fixtures` and `--runtime` paths; `--offline` with
`--prepare` fails on absent or mismatched wheels rather than downloading them.

`make test-notebook PYTHON_EXECUTABLE="D:/path/to/build/notebook-runtime/venv/Scripts/python.exe"`
runs the notebook suite, including real local kernels, without starting a
graphical IDE. Default `make test-ide` excludes real Python/kernel tests; no
interpreter is discovered or downloaded implicitly. To run both explicit
runtime suites in one native test invocation, pass `-PpythonRuntimeTests=true`,
`-PpythonExecutable=...`, `-PnotebookRuntimeTests=true` and
`-PnotebookPythonExecutable=...` to Gradle `test`.

## Interactive development

`make run` is an explicit interactive action, with development configuration,
plugins, caches and logs below `.intellijPlatform`; close its IDE to end the task.
Application branding comes from a separate resource overlay; see
[product branding](BRANDING.md). `make branding` creates that JAR and
`make test-branding` checks it without launching an interactive IDE.

The plugin ZIP is written under `build/distributions`. Structure checks inspect
that ZIP; Plugin Verifier 1.410 checks the exact pinned platform offline after
the declared tools/platform/PythonCore inputs have been downloaded. Reports live under
`build/reports`. Neither test execution nor archive creation is a GUI acceptance
claim. This minimal plugin adds no automatic startup workflow or external service.

The task runner never commits or pushes. Audit/lint/tool tests make no network
requests. Gradle and installer commands may obtain explicitly declared build
dependencies according to their own configuration; prerequisite checks do not
start a download or create missing application files automatically.

## Source and evidence checks

The reference inventory uses explicit public namespace aliases and original-byte
fingerprints. Verify against the same source bytes; working-copy line-ending
changes count as input changes. Regeneration is deliberate and reviewable.
Never replace this check with a raw copy of reference identifiers or Git history.

Product documentation has descriptive names and describes technical behavior.
The local linter rejects delivery-code labels and plan/roadmap filenames. It
checks local links without fetching web pages. Test reports distinguish the
tool tests from JVM/platform/GUI tests and identify missing runtime evidence.

## GUI acceptance on Windows

`make gui-bundle` compiles the isolated GUI suite. `make test-gui` requires
explicit connection settings for a separate Windows VM and never falls back to
the development desktop. See [VM GUI acceptance](../uitest/README.md) for
prerequisites, isolation, commands and result locations.
