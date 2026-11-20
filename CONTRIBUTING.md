# Contributing to KaryoForge

Run `make check` before committing source/tooling changes. A reference inventory
change also requires regeneration and `make audit-reference-check` with an
authorized local reference checkout. IDE implementation changes require their
applicable build, compatibility and runtime checks; audit-tool tests alone do
not establish IDE compatibility.

Use `org.openkaryon.karyoforge` for project-owned Java/Kotlin packages, the
Gradle group and plugin identity. Keep existing third-party namespaces and
copyright/license notices intact. Do not publish personal profiles, credentials,
service endpoints, real research datasets, caches or private Git history.

Commit subjects and bodies are in English. Include two substantive body
paragraphs explaining the reason, resulting behavior and validation or limits.
Codex-assisted commits include this trailer:

```text
Co-authored-by: Codex <codex@openai.com>
```

Use coherent reviewable changes, with roughly 100-200 lines as a lower guide
where useful. Ordinary hand-maintained code stays within a 2,000-3,000-line
upper review budget, counting additions and deletions together. Do not pad a
change to meet a quota. Generated code/JSON can exceed that budget when its
generation is reproducible and its size is accounted for separately.

Product documentation describes available behavior, architecture, interfaces,
commands and reproducible evidence. Keep delivery schedules, numbered delivery
codes and implementation task queues outside repository documentation. Tests,
technical procedures and versioned data schemas retain their normal numbering.
