# Local validation evidence

Updated: 2026-11-22 on Windows 11 x64, Temurin JDK 21.0.11+10.
The public plugin ID is `org.openkaryon.karyoforge`, version `0.1.0-SNAPSHOT`.

## Executed checks

| Check | Result |
| --- | --- |
| Source lint, tool tests and namespace/inventory audit | Passed; 44 Python tool tests, including Windows process ownership, branding and distribution input boundaries |
| Plugin ZIP build and structure validation | Passed against the pinned SDK |
| Native headless integration tests | 89 tests passed, no failures/errors/skips: platform, Python workflows, notebook model/editor/selection and real Python probe/kernel cases |
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

## Notebook interpreter acceptance

The targeted notebook suite passed 37 native tests with no failures, errors or
skips, including four workspace/selection cases and three real dependency-probe
cases. The selected isolated Python 3.14.6 environment reported jupyter_client
8.10.0 and ipykernel 7.4.0. Probe tests cover the exact executable, output bounds,
timeout and cancellation. Selection tests cover passive opening, independent
workspace persistence, module ownership, explicit disable and missing paths.

The same run passed the existing native environment SDK reuse regression after
waiting for the owned SDK's separate package-initialization coroutine before
fixture cleanup. Strict plugin compatibility, archive structure and compilation
of the VM-only GUI bundle also passed. Notebook GUI scenarios have not yet been
executed on the VM; compiled coverage is not GUI acceptance.

## Local notebook kernel acceptance

The complete native suite passed 89 tests with no failures, errors or skips,
using separate explicit Python and notebook runtime paths. Four real-kernel
checks cover selected executable/cwd, retained namespace, execution counts,
stdout/stderr/expression/error results, native document persistence, stale raw
JSON rejection, busy closure and observed-child cleanup. An independently
launched process stayed alive while the kernel's owned processes were stopped.
Strict compatibility and archive structure passed. Notebook GUI execution on the
separate Windows VM remains pending.

## Notebook queue and kernel controls

All 45 targeted notebook native checks passed without failures, errors or
skips. Additional real-kernel cases cover ordered Run All, non-Code skipping,
queue termination on error, cancellation on queued-source edits, responsive
interrupt with retained variables, fresh Restart and explicit Shutdown. A kernel
that deliberately ignores SIGINT was reset after the five-second watchdog; its
next execution started with count one and no old variables. Strict compatibility
and structure passed. This is native acceptance; notebook VM GUI coverage still
requires an executed run.

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
AppData directories. Sixty-three native tests are available without runtime opt-in, including
two consent regression cases and six existing-environment cases. The full
explicit-runtime invocation passed all 78 tests, including seventeen notebook
document cases, thirteen notebook editor/output cases and fifteen Python
process/debugger acceptance cases.

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

## Notebook document acceptance

The document-model acceptance build passed sixteen model tests: 64 tests completed
without failures, errors or skips. Strict plugin verification and structure
checks passed with the pinned platform. The model adds no process or network
startup and has no graphical editor or kernel registration.

Tests cover exact untouched text, source string/line-array preservation, cell
IDs and minor-version handling, unknown cells and fields, attachments, MIME
bundles, metadata locks and rejected conversions. They check duplicate-key and
malformed JSON rejection, resource bounds, cyclic incoming JSON, defensive
ownership and explicit serialization overflow. Numeric tests preserve large
values, scientific notation and signed zero and distinguish output/metadata
updates beyond the finite double range. UTF-8 round trips retain supplementary
characters and escaped unpaired surrogates in both keys and values.

These are document-model checks. The Python GUI evidence below covers the
previously accepted Python distribution, including its console/terminal fixes;
it does not establish notebook GUI or kernel acceptance.

## Notebook view and creation acceptance

The view/creation acceptance build passed five native editor tests and three
output-preview tests. Its complete explicit-Python native suite passed 72 tests without failures, errors or skips;
plugin structure and strict compatibility checks passed. Native tests check the
registered file/editor/action entries, raw JSON ownership, unchanged source on
opening, page bounds, replaced-editor disposal, document reload, repair fallback
and exclusive creation without overwriting an existing file. Output tests check
literal markup, retained MIME data, text/output limits, local PNG decoding,
shared page image limits and invalid-image notices.

The broader run exposed a Python fixture teardown race: synchronous SDK setup
can leave a provider refresh holding the temporary environment's executable.
Runtime cleanup now waits for scheduled/active updates only for SDKs beneath
that fixture's owned source root before deleting it. It does not cancel global
provider work or weaken the deletion failure check.

The notebook GUI sources include passive opening/paging and native JSON-repair
scenarios. Compilation does not establish that these new scenarios passed on
the Windows VM; notebook GUI acceptance is not claimed by these native checks.

## Native notebook cell editing acceptance

The full explicit-Python suite passed 78 tests. A subsequent targeted notebook
run passed all 30 document/editor cases, and structure/strict compatibility
checks passed after the final preview update. Native editing tests check actual
backing JSON writes, saved bytes, two distinct source edits, native Undo/Redo,
restoration of untouched older-format JSON, output-preserving conversion guards,
cell creation/movement/deletion, metadata locks and read-only documents.

A stale-projection test changes JSON before a pending source edit and verifies
that the external data wins. Snapshot tests preserve virtual IDs and isolate
source, metadata, type, output-append and structural mutations in both directions.
Markdown tests check plain-text rendering, heading styles, literal markup and
bounded work. Mixed-cell previews retain original CRLF source text on opening.

VM GUI sources for keyboard Save/Undo/Redo and structural/locked-cell workflows
compile. These native checks do not claim executed notebook GUI acceptance.

## Windows VM GUI acceptance

All 15 unique GUI methods passed in completed JUnit reports from the public
portable distribution on a separate Windows 11 VMware guest. The guest had
4 GiB RAM and reported two logical processors; the sequential runner was configured
with a 512 MiB heap and each IDE with a 1024 MiB heap. The guest used Python 3.14.6 and the
transferred pinned offline package wheelhouse. No graphical IDE was launched
on the development desktop.

| GUI coverage | Passed methods |
| --- | ---: |
| Distribution startup, About identity and consent regression | 1 |
| Native Python project wizard, explicit open and module isolation | 2 |
| Environment creation, selection, cancellation, overwrite rejection, restart, completion and indentation | 2 |
| Requirements installation, upgrade, native removal, pip diagnostics and owned-process cancellation | 3 |
| Editable/ordinary project installation, extras and invalid TOML rejection | 2 |
| unittest/pytest method, class, file, folder and last runs; breakpoint, evaluation and resume | 3 |
| Native Python consoles and cmd/PowerShell terminals across environment changes | 2 |

The result combines a complete discovery run with targeted reruns after concrete
GUI and product fixes; it is not a claim that one initial full run was green.
Only completed per-method JUnit successes count. Interrupted, skipped and failing
cases are excluded. Exhaustive comparison of all 37 shipped plugin JAR entries
across the acceptance archives showed changes confined to the session helper,
console action and terminal action. The debugger passed on the archive that
reveals initialized consoles; both shell/session scenarios passed on the final
archive with the cmd activation fix. Unrelated completed workflow evidence was
retained, with each method's archive identity recorded in its environment report.

GUI tests check actual script/import output and interpreter paths as well as
visible native controls. Test-runner acceptance requires a visible results panel
whose model contains exactly the expected passed leaf tests. Session acceptance
requires the newly opened console to be visible and usable, the new terminal to
activate the selected environment, and existing sessions to retain their original
runtime. This exposed and fixed a hidden console tool window after native startup
and an empty cmd activation cache after the provider's environment-reader timeout.
The cmd action queues the selected environment's standard activation script in
the new tab through the native terminal API. The provider's activation opt-out
and custom cmd startup commands remain respected.

Session teardown waits for native interpreter updates before disposing its
project. A diagnostic shutdown hit the platform's ten-second project-scope
deadline while two skeleton generators were active. The successful acceptance
covers orderly closure after provider initialization; closure during active
skeleton generation remains outside that claim.

The Python GUI acceptance build also passed all 48 native tests, strict compatibility and
plugin structure checks. Plugin Verifier retained five scheduled-removal and two
deprecation diagnostics; its final verdict was Compatible. The rebuilt portable
ZIP contains the Python session fixes. The separately recorded installer predates these
fixes and was not rebuilt or accepted through its wizard by this GUI run.

Completed XML, environment details, screenshots, IDE logs and controller output
remain under ignored `build/ui-test-results/<run-id>`. The GUI harness and its
reproducible commands are described in [the VM acceptance guide](../uitest/README.md).
Raw guest credentials and machine-specific diagnostics are not product files.

## Scope

This plugin snapshot contains the platform foundation, reversible spelling
preferences and native Python editing/run/test/debug integration, without
specialized viewers, presets or workflow execution.
The checks do not establish installer/uninstaller interaction, Linux/macOS
runtime behavior, pixel-by-pixel splash fidelity or notebook/genomic behavior.
Historical reference reports remain separate from these executed public-plugin
checks.
