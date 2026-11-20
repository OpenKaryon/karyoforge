# Local build and validation interface

## Requirements

Source/audit checks use Python 3.10+ and GNU Make. The commands work without a
reference IDE checkout unless one is explicitly requested. `tools/tasks.py`
provides the same interface without Make and does not invoke a shell for
user-supplied reference paths. Paths containing spaces are passed as one argument.

IDE compilation requires the application's Gradle definition, pinned wrapper,
Java 21 and platform dependencies. Packaging also requires the corresponding
installer scripts and their documented host tools. Those IDE build inputs are
not present in this repository snapshot. Missing inputs cause a nonzero exit;
source validation is not substituted for an IDE build.

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
| `test-ide` | Delegate to Gradle `test`; fail if build prerequisites are absent |
| `package-windows` | Run `installer/build-installer.ps1`; fail if it is absent |
| `package-linux` | Run `installer/build-linux.ps1`; fail if it is absent |

```sh
make check
make audit-reference REFERENCE_DIR="/path/with spaces/reference-ide"
make audit-reference-check REFERENCE_DIR="/path/with spaces/reference-ide"
```

`AUDIT_OUTPUT` selects the output JSON file. `PYTHON` selects a Python executable;
pass an executable path rather than an executable-plus-shell-options expression.
On Windows, invoke GNU Make from PowerShell normally. No Bash installation is
needed for the source checks. Packaging scripts use PowerShell; Gradle uses its
native wrapper for the host OS.

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
