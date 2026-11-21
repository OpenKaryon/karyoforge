# Architecture and package ownership

## Identity and source layout

OpenKaryon owns KaryoForge. The project namespace is
`org.openkaryon.karyoforge`, with the corresponding Java/Kotlin source path
`org/openkaryon/karyoforge`. The Gradle group, plugin identity, project-owned
extension points and namespaced component identifiers use the same root.
Product names remain KaryoForge; upstream plugin IDs and namespaces remain
unchanged. Source packages do not depend on a country-specific namespace.

The repository currently implements reversible spelling preferences in `settings`,
with a common settings page and spelling-only inspection overlay. It also contains
native Python provider integration in `languages.python`, the pinned platform
foundation, native integration tests and developer/audit tools.
`notebook.model` owns strict bounded nbformat 4 JSON, lossless document snapshots,
explicit cell mutations and saved outputs independently of IDE services. It
registers no editor and starts no kernel. See [the document contract](NOTEBOOKS.md).

The boundaries below define ownership
for application code; they are not claims that those features are implemented
in this source snapshot. Create directories together with working code rather
than empty scaffolding.

`settings.EditorPreferences` owns application editing state independently of
usage roles and workflow modules. Its settings page refreshes diagnostics only
after Apply, and its inspection overlay does not rewrite inspection profiles.

| Package below the project namespace | Responsibility |
| --- | --- |
| `usage` | Usage roles, optional workflow catalog, capability resolution and scoped preferences |
| `settings` | Shared settings, explicit import/default behavior and reversible UI preferences |
| `setup` | Role/module selection and explicit technology/runtime configuration |
| `data` | Generic tables, text/log previews, paging and bounded shared input infrastructure |
| `notebook` | Notebook document model, editor, saved outputs and explicit kernel execution |
| `biology` | Sequence/variant formats, indexed genomic views and biological study workflows |
| `languages` | Language ownership, grammars, SDK integration and explicitly selected language servers |
| `execution` | Run configurations, process lifecycle, environments and container adapters |
| `workflows` | Pipeline-language integration and explicit graph inspection |
| `database` | Connection profiles, SQL context, consoles, tables and results |
| `remote` | SSH transport, Slurm jobs, logs and owned cancellation |
| `agents` | Explicit console-agent integration, session persistence and optional entry points |
| `ui` | Shared UI components, actions/palette infrastructure and icons |

`biology` is a peer of generic data/notebook/language/execution facilities. CSV,
TSV and log readers belong to generic data infrastructure even when they are
used by biological viewers. Pipeline execution is not intrinsically biological.
Language-specific children can live under `languages`, pipeline/graph support
under `workflows`, and container support under `execution`.

These ownership rules regroup reference responsibilities without requiring one
Gradle subproject or installable plugin per package. Optional plugin descriptors
must isolate direct provider-class linkage where necessary. A workflow switch
alone cannot unload a mandatory third-party plugin.

## Python provider integration

`languages.python` owns module/file context, explicit native interpreter selection
and a scoped setup notification. Bounded discovery of existing local virtual
environments remains separate from explicit native SDK setup and assignment. PythonCore owns parsing, editor intelligence,
run/test configurations, process consoles and the debugger. Its version matches
the pinned platform build. SDK state remains in platform stores; notification
collection does not probe executables or install dependencies.
Explicit environment creation uses CPython's standard `venv` module through an
owned background process. The module owns the native Run console lifetime, Stop
cancels that process tree, and completion preserves any newer interpreter choice.
Existing directories are never overwritten or automatically removed.
Dependency operations invoke the selected virtual environment's pip with explicit
inputs and native console ownership. Standard project metadata is read through
the pinned TOML provider; pip retains responsibility for PEP 517/660 backend
execution and editable installation. PythonCore's public compatibility bridge
owns installed-package refresh; internal provider APIs remain excluded.
New module console actions snapshot the local SDK on a fresh native runner.
The terminal action opens a fresh tab at the module root and delegates virtual
environment activation to PythonCore's terminal integration. Session actions
initialize missing native SDK data without changing saved console preferences
or existing processes. TOML and Terminal are explicit pinned-platform plugin
dependencies, already included in the standalone distribution.
See [Python development](PYTHON.md) for supported interactions and acceptance.

## Usage roles and capability boundaries

Usage roles describe workspace preferences: `everyday`, `development`,
`science-data` and `ml-ai`. They are configuration data, not four parallel source
trees. Create a domain-specific package only when there is actual functionality
with a distinct owner; an empty machine-learning dashboard is not an implementation.

CSV/TSV, Markdown, Jupyter, FASTA and VCF are common file capabilities in the
product contract. The optional Bioinformatics workflow controls study scaffolds
and workflow recommendations; it must not gate opening or operating an explicitly
opened common file. Notebooks retain explicit execution in every usage role.

The optional workflow IDs are `bioinformatics`, `databases`, `remote-hpc`,
`console-agents` and `containers`. Selecting a role does not select these modules,
install plugins, start servers, connect to services or execute code. Application
defaults and local project overrides have separate scopes. Common files, active
operation controls and explicit manual choices take precedence over unrelated
workflow recommendations.

## Persistence and provider ownership

Project-owned runtime/component identifiers use the public namespace. A source
package rename must be checked independently from persistence identity, saved
run configurations, credential lookup and agent-session restoration. Importing
older configuration requires an explicit conversion boundary; do not silently
discard data or retain obsolete project identity throughout the runtime.

SDKs, credentials, run configurations and sessions remain in their existing
platform stores rather than being copied into usage preferences. Active Stop
and management controls remain reachable after a role/module preference changes.
Application Settings must not follow whichever project last received focus.

The reference notebook imports Python APIs, so PythonCore remains a declared
common dependency in the reference baseline. R/LSP integrations require
descriptor and class-loading checks before they can become optional. Their
actual source coupling is described in [the reference audit](REFERENCE_AUDIT.md).

## Repository responsibilities

`src/main` owns runtime source/resources, `src/test` owns integration fixtures,
and `src/uiTest` owns isolated UI tests when those sources are present.
`installer` owns product packaging and branding. `tools` and the root Makefile
provide local checks and entry points; Gradle owns Java/platform compilation.

Documentation records actual availability, technical contracts and evidence.
Build output, installed IDEs, local profiles and raw performance recordings are
not source artifacts. Dependencies/assets retain their notices and provenance.
