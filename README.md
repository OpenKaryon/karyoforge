# KaryoForge

KaryoForge is an OpenKaryon project for a local-first development, science/data
and machine-learning workbench based on IntelliJ IDEA Community.

## Available in this repository

This source snapshot provides native Python editing, run/test/debug support,
native Python project creation, existing/created local environments,
requirements and standard project installation, module consoles and terminals,
explicit module interpreter setup, notebook creation/cell editing with shared
JSON Save/Undo, explicit local Python kernels, ordered execution, input/getpass
and bounded passive rich-output previews, reversible spell-checking preferences,
bounded local CSV/TSV, FASTA and FASTQ previews, a pinned IntelliJ plugin foundation
and local validation tools. The plugin identity is
`org.openkaryon.karyoforge`. Usage presets and workflow execution are not included. The reference implementation is supplied separately
when running its audit.

- [Architecture and package ownership](docs/ARCHITECTURE.md)
- [Windows distribution and installer](docs/WINDOWS_DISTRIBUTION.md)
- [Product branding](docs/BRANDING.md)
- [Python development](docs/PYTHON.md)
- [Jupyter notebook files](docs/NOTEBOOKS.md)
- [Local data previews](docs/DATA_PREVIEWS.md)
- [Editing preferences](docs/EDITING.md)
- [Build and validation commands](docs/DEVELOPMENT.md)
- [Executed local validation](docs/VALIDATION.md)
- [Reference interface and dependency audit](docs/REFERENCE_AUDIT.md)
- [Runtime baseline evidence and measurement protocol](docs/RUNTIME_BASELINE.md)
- [Generated reference inventory](docs/audit/reference-inventory.json)
- [Contribution conventions](CONTRIBUTING.md)

## Local commands

Python 3.10 or newer and GNU Make run the source checks without third-party
Python dependencies. Install JDK 21 and set `JAVA_HOME` to build the plugin.
The checked-in verified Gradle wrapper obtains the pinned build tool itself.

```sh
make help
make check
make build
make test-ide
make verify
make audit-reference-check REFERENCE_DIR="/path/to/reference-ide"
```

`make lint`, `make test` and `make audit` also run independently. On systems
without Make, use `python tools/tasks.py help` and the equivalent task name.
Set `PYTHON` to select another Python executable.

`make build` creates the plugin ZIP under `build/distributions`; `make run`
opens a separate development IDE profile with KaryoForge application branding.
`make branding` builds its resource overlay; `make test-branding` checks native
metadata, splash resources and SVG scaling without opening a window. The first build downloads Gradle,
the pinned IntelliJ Community distribution, PythonCore and verification/test dependencies.
Later builds reuse their caches. On Windows x64, `make package-windows` builds
a standalone IDE installer with bundled Java/PythonCore; `make zip-windows`
creates a portable application ZIP. See the Windows distribution guide for
output paths and separate user profiles. Linux packaging is not implemented.

Notebook acceptance has separate explicit commands on Windows x64:

```sh
make notebook-runtime PYTHON_EXECUTABLE="C:/path/to/base-python-3.14.exe"
make test-notebook PYTHON_EXECUTABLE="D:/path/to/karyoforge/build/notebook-runtime/venv/Scripts/python.exe"
make gui-bundle
```

The runtime target creates an owned virtual environment from SHA-256-locked
offline wheels. It installs no packages into the supplied base Python. The GUI
suite runs only on an explicitly configured Windows VM; see its acceptance guide.
Notebook files themselves never install packages or start Python on opening.

All project-owned packages use `org.openkaryon.karyoforge`. Reference audit
identifiers are explicitly projected into that namespace; their fingerprints
still describe original reference bytes. See the audit's technical contract.

The project uses [Apache-2.0](LICENSE). See [NOTICE](NOTICE) for attribution.
