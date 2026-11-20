# Editing preferences

## Spell checking

KaryoForge hides spelling typo underlines by default. In **Settings > Tools >
KaryoForge**, enable **Enable spell checking** to defer to the current IDE
inspection profile. The preference applies to all projects in that IDE profile.
Enabling it does not force an inspection that the profile has disabled.

Changes take effect after Apply. Reset discards draft changes, and closing the
settings page without applying does not alter the preference. The setting is
stored in the application's `karyoforge-editor.xml` component; no project source
or inspection-profile file is rewritten. Open projects are asked to refresh
diagnostics after an applied change.

## Implementation boundary

`EditorPreferences` is an application service with defensive state snapshots.
`EditingSettingsConfigurable` owns the common settings page, and
`SpellingInspectionSuppressor` supplies a spelling-only inspection overlay.
The suppressor is registered for the platform's common language identifier and
implements `DumbAware`, so it does not depend on a language pack or indexing.

Only `SpellCheckingInspection` is affected. Code/error inspections, compiler
diagnostics and existing per-project inspection choices remain authoritative.
The overlay contributes no suppression quick fixes and stores no per-file
directives. It adds no startup activity, tool window, network request or process.
The implementation uses public inspection APIs. Native tests explicitly depend
on the bundled `intellij.spellchecker` module; its binaries are not copied into
the KaryoForge plugin ZIP.

## Automated checks

Native platform tests exercise the registered inspection overlay, reversibility,
unrelated inspection IDs, XML state round-trip, defensive copies and settings
Apply/Reset/disposal behavior. They run through `make test-ide` in the isolated
test IDE. They do not establish Python-specific diagnostics behavior before a
Python provider has been included and tested.
