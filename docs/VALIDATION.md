# Local validation evidence

Recorded: 2026-11-20 on Windows 11 x64, Temurin JDK 21.0.11+10.
The public plugin ID is `org.openkaryon.karyoforge`, version `0.1.0-SNAPSHOT`.

## Executed checks

| Check | Result |
| --- | --- |
| Source lint, tool tests and namespace/inventory audit | Passed; 18 synthetic Python tool tests |
| Plugin ZIP build and structure validation | Passed against the pinned SDK |
| Native headless integration tests | 14 tests passed, no failures/errors/skips: platform, editing, Python editor/module ownership and four native Python runtime cases |
| IntelliJ Plugin Verifier 1.410 | Compatible with IC-252.28539.97, offline dependency resolution |
| Archive contents | Correct identity/build bounds/notices; project classes under the public namespace; PythonCore declared as a separate dependency |
| Settings search index | Generated successfully; includes `org.openkaryon.karyoforge.settings` |

The strict verifier configuration treats incompatible APIs, invalid packaging,
missing dependencies, internal APIs and override-only API usage as failures.
The stock SDK reports missing layout components in its product metadata; those
warnings remain visible and the final plugin verdict is Compatible. The native
test compilation reports a deprecated test API and the test JVM emits a class
data-sharing warning for the platform classloader; neither is a passing claim
for unexecuted behavior.
Headless settings-index generation also retains stock shared-index, theme/grid
and local recent-projects IPC warnings. It completed successfully without using
the owner's normal IDE profile. These warnings are not hidden by editing SDK JARs.

## Verified build inputs

- Gradle 9.1.0 wrapper JAR SHA-256:
  `76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3`.
- Gradle 9.1.0 binary distribution SHA-256:
  `a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806`.
- PythonCore 252.28539.97 official Marketplace archive SHA-256:
  `7001e8fd19b93e732b6c9ec3af7f24950f419e0e6c0d9dea621086cc4b2a092c`.
- IntelliJ Community 2025.2.6.3 Windows archive SHA-256:
  `ee3bb2bf315298da0758f5d72ee97a9c5e017f22d129c12db80b69a392f4673e`.

Official checksums were obtained from Gradle/JetBrains and matched the downloaded
inputs. The platform archive is 1,349,276,875 bytes. Downloading/extracting it and
initial dependency resolution accounted for most of the first successful build,
which took 13 minutes 30 seconds on this network/host. This is not a benchmark
of compiling a populated KaryoForge implementation or of normal IDE startup.
A subsequent `make build` of the unchanged minimal plugin took 12.3 seconds
including Make/Gradle startup, with most tasks up-to-date. The cached native
platform test command completed in 12 seconds. These are individual observations,
not performance guarantees for larger feature sets or clean compilation.
Those foundation measurements refer to source revision `98f28f7`. With editing
preferences present, an observed plugin/structure/index build took 35 seconds,
the native test command 26 seconds including compilation, and verification
50 seconds. These are separate single-run observations on the cached SDK.

Python runtime acceptance used an explicitly selected disposable Python 3.14.6
virtual environment with pytest 9.0.2. Script and module execution preserved an
explicit environment value. Native unittest/pytest gutter contexts each ran
exactly one selected test; a failing neighbour was not executed. The debugger
paused at the requested line, evaluated `value` as `42`, resumed and exited
successfully. All owned children terminated. Editor tests checked PSI/tokens,
cross-file resolution, completion, formatting and interpreter notification scope.
A heavy multi-module fixture checked preservation of project/other-module Java
SDKs, cancellation and non-Python rejection. Strict verification resolved the
pinned PythonCore locally and downloaded zero dependency bytes during the check.

Reports are written to `build/reports/tests/test` and
`build/reports/pluginVerifier`; native XML results are in `build/test-results`.
Raw local diagnostics and cache paths are not committed as product evidence.

## Scope

This plugin snapshot contains the platform foundation, reversible spelling
preferences and native Python editing/run/test/debug integration, without
specialized viewers, presets or workflow execution.
The checks do not establish interactive
GUI acceptance, Linux/macOS runtime behavior, full installer packaging or the
reference implementation's notebook/genomic behavior. Historical reference
reports remain separate from these executed public-plugin checks.
