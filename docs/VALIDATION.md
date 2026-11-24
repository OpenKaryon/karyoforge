# Local validation evidence

Updated: 2026-11-25 on Windows 11 x64, Temurin JDK 21.0.11+10.
The public plugin ID is `org.openkaryon.karyoforge`, version `0.1.0-SNAPSHOT`.

## Executed checks

| Check | Result |
| --- | --- |
| Source lint, tool tests and namespace/inventory audit | Passed; 55 Python tool tests, including Windows process ownership, branding and distribution input boundaries |
| Plugin ZIP build and structure validation | Passed against the pinned SDK |
| Native headless integration tests | 121 tests passed, no failures/errors/skips: 48 platform/Python, 58 notebook and 15 local file preview cases |
| IntelliJ Plugin Verifier 1.410 | Compatible with IC-252.28539.97, offline dependency resolution |
| Archive contents | Correct identity/build bounds/notices; project classes under the public namespace; PythonCore declared as a separate dependency |
| Consent regression | The fresh-JVM probe reproduced the original `consentMap` failure before the fix, then passed with the vendor resource; two branded native tests cover permissions and Python inspections without accepted-consent writes |
| Python project workflows | Three native checks passed: Python wizard registration, explicit SDK/content-root creation and preservation of existing source/dependency/environment files |
| Product branding | Native product/vendor/build metadata, both splash images and 16/32 px SVG rendering checked in a fresh headless JVM |
| Standalone Windows IDE | Bundled Java startup passed with 187 configurables, including KaryoForge and Python Integrated Tools |
| Windows installer | NSIS 3.13 compiled the per-user EXE; adjacent SHA-256 matches the completed executable |
| Settings search index | Generated successfully; includes `org.openkaryon.karyoforge.settings` |

The strict verifier configuration treats incompatible APIs, invalid packaging,
missing dependencies, internal APIs and override-only API usage as failures.
The stock SDK reports missing layout components in its product metadata; those
warnings remain visible and the final plugin verdict is Compatible. The native
test compilation reports deprecated test APIs and the test JVM emits a class
data-sharing warning for the platform classloader; neither is a passing claim
for unexecuted behavior.
Headless settings-index generation also retains stock shared-index, theme/grid
and local recent-projects IPC warnings. It completed successfully without using
the owner's normal IDE profile. These warnings are not hidden by editing SDK JARs.

## Notebook acceptance

All 58 notebook native tests passed within the complete 121-test regression
suite. The same notebook suite passed through `make test-notebook` against an
environment created by `make notebook-runtime`. Its CPython 3.14.6 runtime uses
jupyter_client 8.10.0, ipykernel 7.4.0 and a complete graph of 28 SHA-256-locked
offline wheels. Creation/reuse passed `pip check` without changing the base
interpreter. Tool tests reject corrupt downloads/manifests, unowned directories,
different lock identities and nested links/Windows junctions. Lock files retain
their exact bytes in both LF and CRLF checkouts.

| Native notebook coverage | Cases |
| --- | ---: |
| Strict lossless document model, exact numbers, metadata, IDs, limits and snapshots | 17 |
| Native open/create, paging, raw JSON, cell edits/history/locks, literal Markdown and bounded previews | 14 |
| Passive per-file workspace selection, module ownership and explicit missing/disabled choices | 4 |
| Stream coalescing, deferred clear, live display updates and independent snapshots | 3 |
| Real dependency probes, kernel execution/lifetimes/queue, input and native result history | 20 |

Real-process checks assert the selected executable and working directory,
retained namespace/counts, streams/results/errors and absence of execution on
opening. Queue tests cover order, non-Code skipping, errors, rejected result
writes and edited/raw JSON invalidation. Responsive Stop preserves variables; deliberate ignored SIGINT
triggers the five-second reset, and Restart/Shutdown retain explicit startup.
Closing a busy kernel stops its observed child identities while an independently
launched process remains alive. A forked real-process regression restricts Java 21
to two carrier threads and verifies virtual-worker startup, both output streams,
execution results, repeated fresh sessions and complete owned-process cleanup.
Dedicated daemon pipe readers prevent blocking process I/O from occupying those
carriers for the lifetime of the kernel. Isolated real Python environments also
delay bridge startup beyond 40 seconds and kernel readiness beyond 30 seconds,
verifying successful cold starts with the 90-second overall and 60-second
readiness bounds. Cancellation during delayed startup releases the waiting
worker, owned process and temporary runtime promptly.

Input/getpass checks cover masked passwords, cleared transient fields, no
automatic echo/persistence, cancelled queued cells and rejected late replies.
Live previews retain source-editor identities and do not modify accepted JSON
until completion. Native Undo/Redo restores one completed result without
executing code. Oversized output becomes an explicit bounded notice; saved/live
HTML and JavaScript stay passive. GUI evidence below covers the same public
notebook interactions on the separate Windows VM.

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

The Windows distribution was assembled from the pinned SDK/PythonCore archives
with an explicit bundled-plugin allowlist. Its packaged Java started the native
headless settings-index command in a disposable profile, reported KaryoForge
IC-252.28539.97 and loaded both PythonCore and the public plugin. The generated
indices contain their actual settings IDs. The per-user installer compiled with
NSIS 3.13 using zlib compression and is 627,578,267 bytes. Native launcher resource
metadata reports KaryoForge/OpenKaryon; the local executable is unsigned.
Setup and uninstall were not run on the owner's Windows installation.

Existing-environment acceptance additionally created a disposable real virtual
environment, initialized it through PythonCore's native SDK setup, reused the
same SDK on repeated selection and ran a native Python configuration whose
`sys.executable` matched that environment. Bounded-discovery tests exercised
missing markers/executables, dotenv exclusion, nested-directory exclusion,
normalized path identity and preservation of module/project SDK state. The
strict verifier reported Compatible. Package refresh retains a deprecated
public provider bridge; internal APIs remain rejected.

Reports are written to `build/reports/tests/test` and
`build/reports/pluginVerifier`; native XML results are in `build/test-results`.
The default native test task now runs with the branding overlay and isolated
AppData directories. Eighty-six native tests are available without runtime
opt-in. The complete explicit-runtime invocation passed 121 cases, including fifteen Python runtime
cases and twenty real notebook probe/kernel/editor cases. The remaining checks
cover passive models, native editing, scope, settings and provider integration.

Real-process console lifecycle regressions check that completed and failed output
can be disposed while post-processing remains active, without late cancellation.
Disposing active output still cancels and terminates its owned process. These
cases preserve the selected module interpreter and verify the actual failure
output for a nonzero exit.

Creation acceptance created a real environment, ran its bundled pip and assigned
its native SDK only when the original module selection was still current. A
cancellable owned Python process exited after cancellation. Console resources
were disposed explicitly by their owner; native test teardown found no leaked
editors. Validation rejects pre-existing directories and missing parents before
starting a process. Interrupted directories are preserved for manual inspection.

The project workflow checks use PythonCore's registered new-project step and
native Python module builder. They create module roots with spaces, preserve the
existing project SDK and confirm that source/dependency/environment files survive
setup. These native checks do not simulate clicking through the wizard or the
platform trust/window-selection dialogs; the separate GUI suite covers those
project workflows.

Dependency acceptance installed an owned wheel through a requirements file with
spaces using `--no-index`, imported its value with the selected real interpreter,
removed it and verified both native package state and import removal. A missing
wheel produced a real pip failure without changing the module SDK. Base-Python
and missing-file guards passed. The final package boundary passed targeted tests
and strict verification after replacing internal provider calls with its public
compatibility bridge; deprecation diagnostics remain visible.

Standard project acceptance used real offline pip with a dependency-free owned
PEP 517/660 backend. Both editable and ordinary installations included a declared
extra and a `src` module. A source edit was visible immediately in editable mode;
after an ordinary install, another source edit did not change the installed copy.
Native metadata tests covered tables, inline/dotted keys, quoted extra names,
unterminated strings and rejection of tool-only configuration. Strict verification
accepted the explicit TOML dependency without internal API usage.

Session acceptance launched two real native Python Consoles with different
interpreters, executed input in both, changed the module SDK and verified that
the first console kept its original runtime. A native terminal customizer
activated each chosen virtual environment; real `cmd` children reported the
expected `sys.executable`. Saved console preferences were preserved. Native
project save/dispose/reload restored the module SDK and source files. Terminal
widget interaction and PowerShell activation also passed in the separate VM
GUI scenarios described below.
Forced console cleanup can leave native RPC/socket shutdown warnings in test
logs; all owned processes terminated and the native suite passed.

The latest installer includes the vendor consent resource and disabled usage
collection/sending properties. Its SHA-256 is
`f54a3222ece9d59ba17cdef451fee37ee6bad4ccc6c379b1ddd6d7cbd2812727`.
The packaged-Java smoke run used isolated AppData directories as well as native
config/cache/log paths and exposed both public and Python settings successfully.

Raw local diagnostics and cache paths are not committed as product evidence.

## Local file preview acceptance

Fifteen parser and native editor checks cover CSV/TSV, FASTA, FASTQ and VCF.
Tests include byte-aligned pagination, complete sequence lengths, raw quality
and sample fields, metadata bounds, malformed UTF-8 or records, source changes,
cancellation and disposal. The native editor manager opened the preview beside
Text, and the preview alone did not create a source document or change file bytes.

The Windows portable ZIP SHA-256 is
`eec758168c3b4829279dc638d79884952775ac7abaa91fe8e4c7e437eda8ce6a`.
The archive contains the four readers and both native editor registrations. It
passed structure checks and headless startup with bundled Java and PythonCore.
Six GUI scenarios passed on a separate Windows 11 VMware guest using this
archive. The scenarios covered CSV/TSV pagination and the native Text tab,
long FASTA records, raw FASTQ quality and length errors, VCF metadata and
sample values, distribution startup and passive notebook opening. The four
preview scenarios verified that browsing left their source files unchanged.
JUnit reports for runs `058530451606`, `2b5ef94ee676` and `5be53407b240` are
stored in ignored build output.

## Notebook distribution GUI acceptance

All 24 unique GUI methods passed in completed per-method JUnit reports from the
same public portable IDE archive on a separate Windows 11 VMware guest. The
guest had 4 GiB RAM and reported two logical processors. The sequential run
configuration requested 512 MiB for JUnit and 768 MiB for each IDE. Its base Python was
3.14.6; notebook cases used a fresh owned environment installed from the
transferred locked offline wheelhouse. No graphical IDE ran on the development
desktop.

| GUI coverage | Passed methods |
| --- | ---: |
| Distribution startup, About and consent | 1 |
| Native Python project wizard and module isolation | 2 |
| Environment creation/selection/cancellation/reopen and editor intelligence | 2 |
| Requirements/package operations, diagnostics and owned cancellation | 3 |
| Project installation, extras and invalid metadata | 2 |
| unittest/pytest scopes and debugger breakpoint/evaluation/resume | 3 |
| Native Python consoles and cmd/PowerShell sessions | 2 |
| Notebook creation/cancellation, passive paging and raw JSON repair | 3 |
| Notebook source/structure editing, locks and keyboard Save/Undo/Redo | 2 |
| Notebook workspace Python/dependencies/reopen and queue/kernel controls | 2 |
| Notebook input/getpass, rich PNG/text/passive HTML, result history and input cancellation | 2 |

Only completed leaf-test successes count; interrupted, skipped and failed
attempts do not. The recorded methods assert real notebook bytes, interpreter
identity and result models as well as visible controls. A real execution counter
proves notebook result Undo/Redo does not execute code again. Ordinary/password
input is synthetic, masked where requested and absent from saved notebook data.
Input cancellation prevents the later queued marker, including after reopening.

The notebook distribution archive tested here has SHA-256 `8f7ff2e0cf91cc2ba2b7bcb098ec1c4dfcd3db0a7919da90dca3c9d7a705af0a`.
Completed evidence is recorded under ignored `build/ui-test-results/<run-id>`;
accepted run IDs are `29df737be56b`, `8c7d3301d43a`, `ab18904c5eee`, `d45d836ddd84`, `df3b61200b1e`. Each environment report identifies the same
SHA-256-owned IDE cache, the selected payload, memory limits and runtime inputs.
The controller uses hidden PowerShell transports and identity-checked cleanup.
VM login, desktop unlocking and system/firewall prompts remain external desktop
prerequisites; a run blocked by such a prompt is not acceptance.

Native regression, strict compatibility and plugin structure also passed.
The verifier retains its existing scheduled-removal/deprecation diagnostics
with a final Compatible verdict. The portable ZIP is the accepted artifact;
the separately recorded installer was not rebuilt or accepted through its
wizard by this GUI run. Raw guest credentials, machine-specific diagnostics,
screenshots and IDE logs are not product source files. See
[VM GUI acceptance](../uitest/README.md) for reproducible commands and isolation.

## Scope

This snapshot supports the platform foundation, editing preferences, native
Python workflows and the local Python notebook interactions described above.
These checks do not establish installer/uninstaller interaction, Linux/macOS
runtime behavior, pixel-by-pixel splash fidelity, remote notebook kernels,
interactive widgets or genomic/workflow capabilities. Historical reference
reports remain separate from executed public-plugin acceptance.
