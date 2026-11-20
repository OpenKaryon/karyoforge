# Reference interface and dependency audit

Recorded: 2026-11-20. Evidence: static source inspection. The audited application
is an explicitly supplied reference, not the runtime content of this repository.

## Inventory format and namespace projection

[`tools/audit_reference.py`](../tools/audit_reference.py) reads the main plugin
descriptor and both referenced optional descriptors, production Java sources,
and pinned build metadata. It does not execute source code, read Git history or
inspect personal IDE profiles. The
[JSON inventory](audit/reference-inventory.json) uses schema version 2.

All project-owned descriptor identifiers, classes and paths are projected to
`org.openkaryon.karyoforge`. The tool derives the reference prefix locally from
the supplied main descriptor and does not emit that original prefix. This is
an explicit namespace-only alias mapping, not evidence that the reference or
public application source was renamed. Responsibility regrouping described in
[architecture](ARCHITECTURE.md) is not falsely encoded as an existing file move.

Fingerprints hash unmodified reference file bytes. To resolve a projected Java
path against a supplied reference, replace the public namespace directory prefix
with the reference descriptor ID's directory form. Original source hashes and
relative suffixes make this check reproducible without publishing obsolete
project identity, machine paths, remotes or configuration values.

## Registration coverage

The inventory contains 103 classified registrations, nine plugin/module
dependencies, 10 persistent components, 26 direct Python/LSP provider imports,
77 process/network construction-symbol mentions and 102 source fingerprints.

| Registration family | Count | Ownership boundary |
| --- | ---: | --- |
| Actions and action groups | 16 + 1 | Shared entries, explicit file context or selected workflows |
| Tool windows | 9 | Three optional workflow panels, explicit DAG inspection and five empty placeholders |
| Application/project configurables | 5 + 1 | Shared settings root, project imports and selected provider settings |
| File editors | 6 | Four common file editors and two explicit database editors |
| Run configuration types | 5 | Saved run identity and explicit execution |
| Project startup activities | 2 | Defaults and bounded technology-setup offers |
| Application listeners | 2 | Early reversible import/UI/startup defaults |
| LSP servers | 13 | Explicitly enabled language/runtime providers |

Other entries cover passive language/file ownership, server mappings,
inspection behavior, one extension point and the optional Maven initializer.
Each JSON row identifies descriptor, namespace, identity, owning class, policy,
rule, attributes and applicable action placements/shortcuts. These policies are
architectural classifications, not an implemented preset resolver.

Unknown registration/action kinds fail audit generation. The narrow Java scan
found no direct dynamic action/tool-window registration candidates. This does
not prove the host platform or third-party plugins create no dynamic UI.
Nested controls inside feature panels inherit their owner and still require
runtime testing. Process/network symbol matches are inspection leads, not proof
that a process launched or a complete lifecycle trace.

## Interface ownership

The reference registers Databases, Agents and Remote as persistent panels.
Their public ownership is explicit workflow use plus retained active-operation
controls. DAG viewing is an explicit DOT-file operation and does not require an
unconditional stripe button. Five registered panels contain only Coming soon
content: Environments, Genome, Sequences, Molecular Structure and Variants.
They do not represent usable feature implementations.

Common file providers are `BiologicalFileEditorProvider`,
`GenomeFileEditorProvider`, `VariantFilterEditorProvider` and
`NotebookFileEditorProvider`. They are separate from those persistent panels.
Database console/table providers own explicit database virtual files. Markdown
is supplied by the bundled provider selected in the Gradle platform dependency
list, rather than a custom Forge file editor.

`RunSqlAction.update` and `ExplainSqlAction.update` already require a project,
editor and SQL file. Keep keyboard/direct invocation consistent with that context.
The interpreter action currently requires only a project and has a broader
surface. The workflow palette reads the same Tools action group, so its child
visibility must agree with menus. Three agent actions are pinned in the reference
toolbar; they are distinct from user-selected custom pins.

The shared application settings root owns reversible spelling/import/run/UI
defaults. Database, remote and language settings have application scope; project
imports have project scope. Application Settings must not follow the last focused
project. Hiding a Forge-owned entry does not unload upstream settings/plugins.

## State and provider coupling

The 10 persistent components cover common defaults, project import overrides,
agents, notebooks, databases, remote profiles, Slurm receipts, language packs,
Nextflow settings and file-local variant filters. Project-local stores use the
platform workspace file; application stores retain their own XML files.
Database credentials use PasswordSafe separately. SDKs, native saved runs,
toolbar customization and setup dismissal also live outside a usage-role model.
No personal values from those stores are included in the inventory.

The reference descriptor requires platform/lang, PythonCore, LSP4IJ, R4Intellij,
Terminal and TextMate, with Maven and YAML through optional descriptors.
The platform pin is IDEA Community 2025.2.6.3 / IC-252.28539.97, Java 21,
Python/R 252.28539.97 and LSP4IJ 0.21.0.

Notebook UI directly imports `PythonFileType` and `PythonSdkUtil`; a role switch
cannot safely remove PythonCore. Language/Nextflow factories, matchers,
connection providers and settings import LSP4IJ, including a descriptor-level
structure-view provider. R is mandatory even without a direct R import match;
absence of imports does not establish optional class loading.

The notebook constructor inspection found document/UI setup rather than kernel
startup. Explicit cell execution reaches the kernel start path. Biological
previews retain bounded readers and text fallback; genomic and filter providers
have independent filename acceptance. These are source observations, not fresh
runtime passes. See [runtime evidence](RUNTIME_BASELINE.md).

## Validation scope

Synthetic tool tests cover descriptor traversal, classification failures,
source-boundary escapes, namespace aliases, original-byte hashes, omission of
Git/personal configuration and deterministic drift checks. They are executable
without an IDE or private reference checkout. The real inventory can be
regenerated and compared only with an authorized matching reference source.
Tool tests do not certify IDE startup, platform compatibility or file behavior.
