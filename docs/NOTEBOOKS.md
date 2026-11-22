# Jupyter notebook files

Open a local `.ipynb` file to see its **Notebook** tab. Code, Markdown and Raw
sources have read-only native previews with highlighting from their installed
language providers. Saved text and PNG/JPEG output is displayed without starting
Python. **Tools > KaryoForge > New Jupyter Notebook...** creates a UTF-8 notebook
with one empty Code cell in an existing directory. Existing files are refused,
including a file that appears after the save chooser closes.

The native **Text** tab edits the same backing JSON Document. Its normal save,
undo, reload and conflict handling remain authoritative. Editing JSON and
returning to Notebook refreshes the cell view; malformed input shows an error
while retaining the original source for repair. The effective JSON file type
is provided through a public extension without persisting file associations.
The pinned JSON provider is an explicit dependency already bundled in the IDE.

Opening, paging and displaying saved outputs do not choose an interpreter,
install packages, start a kernel or execute saved cells. HTML/SVG/JavaScript
MIME data is retained and described rather than executed. Stream text containing
markup is displayed literally. Kernel execution is not provided by this viewer.

## View boundaries

The native view accepts files up to 32 MiB and displays 20 cells per page. Source
previews are limited to 200,000 characters per cell; full source remains in the
Text tab. Each cell shows at most 50 outputs and 200,000 text characters. A page
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
No-op edits do not rewrite or upgrade the document.

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
