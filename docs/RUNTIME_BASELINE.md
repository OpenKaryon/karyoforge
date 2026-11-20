# Runtime evidence and baseline measurement

Recorded: 2026-11-20. Static/provider evidence exists; fresh application startup,
viewer execution and resource measurements have not been recorded on this host.

## Observed prerequisites

| Observation | Result and scope |
| --- | --- |
| Declared platform | IDEA Community 2025.2.6.3; exact build range 252.28539.97 |
| Declared tooling | Java 21, Gradle 9.1.0, platform Gradle plugin 2.19.0, Verifier 1.410 |
| Host Java | Temurin OpenJDK 21.0.11+10-LTS, 64-bit |
| Host Python | Python 3.14.6; available for developer tools, not a Jupyter compatibility verdict |
| Reference wrapper invocation | `gradlew.bat --offline --version` exited 1 because the wrapper JAR was absent |
| Reference build/SDK output | No prepared `build/` or `.intellijPlatform/` in the inspected checkout |
| Cached Gradle | No usable executable/distribution in the inspected PATH/wrapper cache |
| Fresh IDE timing, memory/CPU and external-process observations | Not measured |
| Live notebook/table/Markdown/sequence/VCF behavior | Not exercised in an IDE during this audit |

The missing wrapper is a build prerequisite, not evidence that an application
feature is broken. The declared Gradle distribution hash remains part of the
reference fingerprinted inputs. Source-tool checks do not install a wrapper,
resolve SDKs, start an IDE or create a synthetic runtime success result.
Historical test counts and measurements are not fresh results on this host.

## Static observations

The reference notebook provider is independent of role selection. Its editor
initializes the saved document/UI without a kernel-start call in the inspected
constructor; explicit cell execution starts the selected runtime. This needs
independent process tracing for a runtime verdict.

Generic previews preserve a default text editor alongside bounded preview
controls. Indexed genomic and variant-filter editors are independent file
providers. Supported suffixes differ between generic compressed previews and
the variant-filter provider; reproduce actual cases rather than extending claims
from one provider to another. The Markdown provider is declared in the build
dependencies but its packaged behavior was not exercised here.

Existing reference test seams cover notebook document preservation, native
preview/editor integration, genomic queries and variant filtering. GUI seams
cover notebook editing, sequence/variant views and explicit panel/query timings.
A panel-opening timer is not a measurement from process creation to a usable IDE.

## Reproducible application measurements

Identify the tested artifact separately from source-tool output: record the
platform build, plugin and dependency hashes, source-input fingerprint set,
fixture hashes, host/VM CPU and RAM, OS, heap options and installed providers.
Use isolated configuration, system/cache, plugins and logs. Never reuse a
personal active profile or control the owner's active desktop for acceptance.

| Scenario | Evidence to record |
| --- | --- |
| Fresh-profile empty project | Process start and defined usable-frame/project-ready point; indexing state; errors; idle CPU/memory; child processes and connections |
| Warm restart | Same readiness definition and metrics, labeled separately |
| Python and frontend/text projects | Actual setup offers, panel/settings surfaces and external-process behavior |
| CSV/TSV and Markdown | Actual providers; literal values, editing/preview, paging and fallback where supported |
| Notebook opening | Code/Markdown/Raw cells, saved outputs and unchanged round-trip; no unrequested kernel or connection |
| Notebook explicit execution | Selected supported runtime; only requested code; Stop/shutdown and no automatic installation |
| FASTA/VCF and compressed fixtures | Real local preview/index/filter controls; bounded work, explicit reference inputs and source preservation |
| Explicit database/DOT use | Actual connection/action ownership and separately labeled panel/query timings |

Use synthetic redistributable fixtures and retain raw results outside source.
Record at least three separate cold and warm observations, individual results
and median/range. Define cold as fresh IDE system/configuration for this method,
and state whether the OS filesystem cache is warm. Do not imply a host reboot or
cache flush unless one actually occurred. Compare equivalent artifacts, feature
sets and hardware; no timing threshold is inferred from an earlier machine.

Distinguish host/third-party services from Forge-owned launches. Explicit file
opening can legitimately use bounded local readers/helpers. Keep their work
separate from idle startup observations. Sanitize process/network logs before
publication so they expose no credentials, environments or personal data.

Each result identifies its command/test filter, artifact, fixtures, cache policy,
timestamps, raw-evidence location, warnings, pass/fail/unavailable status and
limits of the conclusion. Missing binary prerequisites and unexecuted cases
remain visible rather than being counted as passing tests.
