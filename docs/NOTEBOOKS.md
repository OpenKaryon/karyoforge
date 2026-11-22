# Jupyter notebook files

Open a local `.ipynb` file to see its **Notebook** tab. Code, Markdown and Raw
sources use native cell editors with highlighting from their installed language
providers. Saved text and PNG/JPEG output is displayed without starting
Python. **Tools > KaryoForge > New Jupyter Notebook...** creates a UTF-8 notebook
with one empty Code cell in an existing directory. Existing files are refused,
including a file that appears after the save chooser closes.

The native **Text** tab edits the same backing JSON Document as the cell editors.
**Ctrl+S**, ordinary IDE **Undo/Redo**, reload and conflict handling operate on
that shared document. Editing JSON and
returning to Notebook refreshes the cell view; malformed input shows an error
while retaining the original source for repair. The effective JSON file type
is provided through a public extension without persisting file associations.
The pinned JSON provider is an explicit dependency already bundled in the IDE.

Opening, paging and displaying saved outputs do not choose an interpreter,
install packages, start a kernel or execute saved cells. HTML/SVG/JavaScript
MIME data is retained and described rather than executed. Stream text containing
markup is displayed literally. Kernel execution is not provided by this viewer.

## Local Python selection

The notebook header can follow the owning module's cached Python SDK, select an
existing local Python SDK, choose a local executable, or explicitly select no
interpreter. Selection is stored per notebook URL in the project's workspace
file. It changes neither module/project SDKs nor portable notebook metadata.
A missing explicit executable is reported without falling back to another SDK.
Remote SDKs are unavailable for notebook execution.

**Check dependencies** explicitly launches the selected executable in isolated
mode to inspect metadata for `jupyter_client` and `ipykernel`. It does not import
or start a kernel, register a kernelspec, install packages or modify an
environment. Install missing packages into that interpreter yourself, then
check again. Reported package presence is not a guarantee that every installed
version will execute successfully. Probe errors remain visible.

The check has a 20-second deadline and separate 64 KiB stdout/stderr bounds.
Switching the selection or closing the editor cancels the owned probe; pending
results cannot update a newer selection or disposed editor. No executable is
probed on open, paging or interpreter selection.

## Cell editing

**Add Code**, **Add Markdown** and **Add Raw** append cells. Each cell offers type
selection, movement and deletion; Code cells also offer **Clear outputs**.
Conversion refuses to discard saved results or attachments. Source/type editing
and deletion respect cell metadata locks; read-only files remain read-only.
Unknown cell types are retained with disabled editing controls.

Each edit uses an independent model snapshot and writes only the changed JSON
range in a native command. Cell source projections share the backing document's
Undo identity and do not create a second history. An invalid or excessive edit
is rejected before writing the JSON document. A stale cell view cannot overwrite
an external/raw-JSON change; it reloads the authoritative content and reports
that the view changed. Untouched cells retain their source representation and
metadata. Native cell documents normalize their displayed line endings without
rewriting an untouched notebook.

Markdown cells include a literal styled-text preview for headings, quotes and
fenced code. It has a 20,000-character/500-line bound, does not interpret HTML,
load URLs or execute scripts, and keeps the complete source in the cell/JSON.

## View boundaries

The native view accepts files up to 32 MiB and displays 20 cells per page. Source
previews are limited to 200,000 characters per cell; cells above this source
preview bound remain read-only in the Notebook tab, with full source in Text. Each cell shows at most 50 outputs and 200,000 text characters. A page
renders at most four PNG/JPEG images, each with at most 4 MiB encoded data,
16,777,216 source pixels and 2,097,152 decoded preview pixels. Images are decoded
from local saved MIME bytes with bounded subsampling. Invalid or excessive
images produce a notice and retain their original data. Other saved output
types remain in JSON with an explicit unsupported-preview notice.

The view releases owned source editors on page changes, document reload and
closure. Pending reloads check editor/project lifetime and document generation.
The parser and serializer have their own limits described below; the native Text
editor remains available for files outside the notebook view's bounds.

## Document model

`notebook.model` provides an IDE-independent nbformat 4 model. Operations are in
memory; `notebook.editor` owns the view and the platform owns the backing Document.

## Reading and preservation

`NotebookDocument.parse` accepts a JSON object containing `nbformat: 4`, a
nonnegative minor version, object metadata and an array of cells. Code,
Markdown and Raw cells expose their sources; unknown cell types remain opaque
and read-only. Unknown notebook, cell, output and MIME fields remain in the
underlying JSON tree. This is a bounded editing model, not a complete JSON
schema validator for every vendor metadata extension.

An untouched document returns its exact original text, including whitespace,
line endings and source string/line-array representation. A real edit writes
complete JSON, retaining unrelated source representations, attachments,
metadata, outputs and exact numeric values. Accessors return owned snapshots;
modifying returned JSON or incoming mutable numbers cannot alter the model.
Unpaired Unicode surrogates remain escaped when serializing, preserving their
values through UTF-8 file writes as well as supplementary Unicode characters.

Input must be strict JSON. Duplicate object keys, malformed known fields,
duplicate/invalid cell IDs, unsupported major versions and exceeded limits
produce `NotebookFormatException` with the original text available for a raw
editor. The parser never repairs an invalid input or replaces it with an empty
notebook.

## Explicit mutations

The API supports inserting, moving and removing cells; changing source and
cell type; updating notebook metadata; and replacing, appending or clearing
Code outputs and execution counts. Stable in-memory IDs are assigned to cells
that lack them. The first real mutation persists those IDs and advances older
documents to minor version 5; it never downgrades a newer minor version.
No-op edits do not rewrite or upgrade the document. Transaction snapshots retain
unpersisted in-memory cell IDs. Unchanged nested JSON is shared immutably;
mutations replace owned containers so snapshots remain independent without
copying large saved MIME payloads on each keystroke.

Cell `editable` and `deletable` metadata control source/type edits and deletion.
Conversion refuses to discard existing outputs, attachments or unrecognized
execution fields. Execution output updates do not rewrite cell source or
silently remove unrelated metadata. Unknown MIME bundles and output types are
retained without interpreting, fetching or executing their contents.

## Resource boundaries

Input and serialized output are limited to 33,554,432 UTF-16 code units. The
model accepts at most 10,000 cells, 128 levels of JSON nesting and 2,000,000 JSON
values. Numeric literals have a 1,024-character bound and retain their spelling,
precision and signed zero; exponents must be supported by Java BigDecimal.
Known execution counts and format minor versions are nonnegative 32-bit integers.
Cyclic incoming JSON is rejected before copying it into the
model. Output serialization uses a bounded writer: an oversized edit fails
explicitly rather than returning truncated JSON; the caller must retain the
last accepted IDE Document until serialization succeeds.

The format contract follows the [official notebook format description](https://nbformat.readthedocs.io/en/latest/format_description.html)
and [nbformat 4.5 schema](https://github.com/jupyter/nbformat/blob/main/nbformat/v4/nbformat.v4.5.schema.json).
