# KaryoForge

KaryoForge is an OpenKaryon project for a local-first development, science/data
and machine-learning workbench based on IntelliJ IDEA Community.

## Available in this repository

This source snapshot provides a reference-registration audit tool, public
namespace projection, local validation commands and technical documentation.
It does not contain the IDE runtime implementation or an installable product.
The reference implementation is supplied separately when running its audit.

- [Architecture and package ownership](docs/ARCHITECTURE.md)
- [Build and validation commands](docs/DEVELOPMENT.md)
- [Reference interface and dependency audit](docs/REFERENCE_AUDIT.md)
- [Runtime baseline evidence and measurement protocol](docs/RUNTIME_BASELINE.md)
- [Generated reference inventory](docs/audit/reference-inventory.json)
- [Contribution conventions](CONTRIBUTING.md)

## Local commands

Python 3.10 or newer and GNU Make are sufficient for the available source checks.
No third-party Python dependencies are required.

```sh
make help
make check
make audit-reference-check REFERENCE_DIR="/path/to/reference-ide"
```

`make lint`, `make test` and `make audit` also run independently. On systems
without Make, use `python tools/tasks.py help` and the equivalent task name.
Set `PYTHON` to select another Python executable.

`make build`, `make verify`, `make test-ide`, `make package-windows` and
`make package-linux` expose the IDE build interfaces. They fail with a precise
missing-prerequisite message in this snapshot because the Gradle application
definition, wrapper and installer scripts are not present. They do not report
a successful IDE build or create substitute product archives.

All project-owned packages use `org.openkaryon.karyoforge`. Reference audit
identifiers are explicitly projected into that namespace; their fingerprints
still describe original reference bytes. See the audit's technical contract.

The project uses [Apache-2.0](LICENSE). See [NOTICE](NOTICE) for attribution.
