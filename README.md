# KaryoForge

KaryoForge is an OpenKaryon project for a local-first development, science/data
and machine-learning workbench based on IntelliJ IDEA Community.

## Available in this repository

This source snapshot provides a minimal IntelliJ plugin foundation, a native
platform integration test, reference-registration auditing and local validation
commands. Its plugin identity is `org.openkaryon.karyoforge`; it contributes no
specialized editors, presets, tool windows or runtime workflows yet. The
reference implementation is supplied separately when running its audit.

- [Architecture and package ownership](docs/ARCHITECTURE.md)
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
opens a separate development IDE profile. The first build downloads Gradle,
the pinned IntelliJ Community distribution and verification/test dependencies.
Later builds reuse their caches. `make package-windows` and `make package-linux`
still fail explicitly because full-product installer scripts are not included.

All project-owned packages use `org.openkaryon.karyoforge`. Reference audit
identifiers are explicitly projected into that namespace; their fingerprints
still describe original reference bytes. See the audit's technical contract.

The project uses [Apache-2.0](LICENSE). See [NOTICE](NOTICE) for attribution.
