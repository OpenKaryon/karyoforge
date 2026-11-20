# Local validation evidence

Recorded: 2026-11-20 on Windows 11 x64, Temurin JDK 21.0.11+10.
The public plugin ID is `org.openkaryon.karyoforge`, version `0.1.0-SNAPSHOT`.

## Executed checks

| Check | Result |
| --- | --- |
| Source lint, tool tests and namespace/inventory audit | Passed; 16 synthetic Python tool tests |
| Plugin ZIP build and structure validation | Passed against the pinned SDK |
| Native headless platform test | One test passed, no failures/errors/skips; verifies plugin loading, identity and exact platform build |
| IntelliJ Plugin Verifier 1.410 | Compatible with IC-252.28539.97, offline dependency resolution |
| Archive contents | Correct plugin ID, vendor, version, exact build bounds and license/notice resources |

The strict verifier configuration treats incompatible APIs, invalid packaging,
missing dependencies, internal APIs and override-only API usage as failures.
The stock SDK reports missing layout components in its product metadata; those
warnings remain visible and the final plugin verdict is Compatible. The native
test compilation reports a deprecated test API and the test JVM emits a class
data-sharing warning for the platform classloader; neither is a passing claim
for unexecuted behavior.

## Verified build inputs

- Gradle 9.1.0 wrapper JAR SHA-256:
  `76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3`.
- Gradle 9.1.0 binary distribution SHA-256:
  `a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806`.
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

Reports are written to `build/reports/tests/test` and
`build/reports/pluginVerifier`; native XML results are in `build/test-results`.
Raw local diagnostics and cache paths are not committed as product evidence.

## Scope

This plugin snapshot contains the platform foundation, without specialized
editors, presets or workflow execution. The checks do not establish interactive
GUI acceptance, Linux/macOS runtime behavior, full installer packaging or the
reference implementation's notebook/genomic behavior. Historical reference
reports remain separate from these executed public-plugin checks.
