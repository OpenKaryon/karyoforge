package org.openkaryon.karyoforge.notebook.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.openkaryon.karyoforge.notebook.model.NotebookJson.*;

/**
 * A mutable nbformat 4 document. All JSON fields are retained rather than mapped
 * through a fixed schema. The owner serializes explicit mutations into its IDE
 * Document, so native Save and Undo remain authoritative.
 *
 * This class is intended to be confined to the editor's event-dispatch thread.
 */
public final class NotebookDocument {
    public static final int MAX_INPUT_CHARACTERS = 32 * 1024 * 1024;
    public static final int MAX_CELLS = 10_000;

    private final String originalText;
    private final JsonObject root;
    private final ArrayList<Cell> cells;
    private boolean modified;

    private NotebookDocument(String originalText, JsonObject root, ArrayList<Cell> cells) {
        this.originalText = originalText;
        this.root = root;
        this.cells = cells;
    }

    public static NotebookDocument parse(String text) throws NotebookFormatException {
        if (text == null) throw new NotebookFormatException("Notebook text is missing", null);
        if (text.length() > MAX_INPUT_CHARACTERS) {
            throw new NotebookFormatException("Notebook exceeds the 32 million character text limit; open it as JSON instead", text);
        }
        try {
            JsonElement parsed = NotebookJson.read(text);
            if (!parsed.isJsonObject()) throw invalid("Notebook must be a JSON object");
            JsonObject root = parsed.getAsJsonObject();
            int major = integer(root.get("nbformat"), "nbformat", false);
            if (major != 4) throw invalid("Unsupported notebook format " + major + "; only nbformat 4 is editable");
            integer(root.get("nbformat_minor"), "nbformat_minor", false);
            object(root.get("metadata"), "Notebook metadata");
            JsonArray rawCells = array(root.get("cells"), "Notebook cells");
            if (rawCells.size() > MAX_CELLS) throw invalid("Notebook exceeds the " + MAX_CELLS + " cell limit");
            ArrayList<Cell> cells = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int index = 0; index < rawCells.size(); index++) {
                JsonObject value = object(rawCells.get(index), "Cell " + (index + 1));
                validateCell(value, index);
                String id = value.has("id") ? string(value.get("id"), "Cell id") : freshId(ids);
                if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw invalid("Cell " + (index + 1) + " has an invalid id");
                if (!ids.add(id)) throw invalid("Notebook has duplicate cell id: " + id);
                cells.add(new Cell(id, value));
            }
            return new NotebookDocument(text, root, cells);
        } catch (IOException | RuntimeException exception) {
            throw new NotebookFormatException("Cannot edit notebook: " + safeMessage(exception), text, exception);
        }
    }

    public static NotebookDocument create() {
        JsonObject root = new JsonObject();
        root.add("cells", new JsonArray());
        root.add("metadata", new JsonObject());
        root.addProperty("nbformat", 4);
        root.addProperty("nbformat_minor", 5);
        return new NotebookDocument(NotebookJson.write(root), root, new ArrayList<>());
    }

    public String originalText() { return originalText; }
    public boolean isModified() { return modified; }
    public int formatMinor() { return root.get("nbformat_minor").getAsInt(); }
    public List<Cell> cells() { return Collections.unmodifiableList(cells); }
    public Cell cellAt(int index) { return cells.get(index); }
    public JsonObject metadata() { return NotebookJson.copy(root.getAsJsonObject("metadata")); }
    /** Frontend state follows nbformat extensible metadata without changing source cells. */
    public void setMetadata(JsonObject metadata) {
        if (metadata == null) throw new IllegalArgumentException("Notebook metadata must be an object");
        JsonObject owned = NotebookJson.copy(metadata);
        if (NotebookJson.same(root.getAsJsonObject("metadata"), owned)) return;
        beginMutation();
        root.add("metadata", owned);
    }
    public JsonObject json() { return NotebookJson.copy(root); }

    /** Exact source when untouched; complete JSON when explicitly edited. */
    public String toJson() { return modified ? NotebookJson.write(root) : originalText; }

    public Cell cell(String id) {
        return cells.get(indexOf(id));
    }

    public int indexOf(String id) {
        for (int i = 0; i < cells.size(); i++) if (cells.get(i).id.equals(id)) return i;
        throw new IllegalArgumentException("Notebook cell no longer exists: " + id);
    }

    public Cell addCell(CellType type) { return insertCell(cells.size(), type, ""); }
    public Cell insertCell(int index, CellType type) { return insertCell(index, type, ""); }

    public Cell insertCell(int index, CellType type, String source) {
        Objects.requireNonNull(type, "Cell type");
        Objects.requireNonNull(source, "Cell source");
        if (type == CellType.UNKNOWN) throw new IllegalArgumentException("Cannot create an unknown cell type");
        if (index < 0 || index > cells.size()) throw new IndexOutOfBoundsException(index);
        if (cells.size() >= MAX_CELLS) throw new IllegalArgumentException("Notebook exceeds the " + MAX_CELLS + " cell limit");
        JsonObject value = new JsonObject();
        String id = freshId(cells.stream().map(Cell::id).collect(java.util.stream.Collectors.toSet()));
        value.addProperty("id", id);
        value.addProperty("cell_type", type.jsonName());
        value.add("metadata", new JsonObject());
        value.addProperty("source", source);
        if (type == CellType.CODE) {
            value.add("execution_count", JsonNull.INSTANCE);
            value.add("outputs", new JsonArray());
        }
        Cell cell = new Cell(id, value);
        beginMutation();
        cells.add(index, cell);
        rebuildCellArray();
        return cell;
    }

    public void setSource(String id, String source) {
        Objects.requireNonNull(source, "Cell source");
        Cell cell = cell(id);
        requireEditable(cell);
        if (cell.source().equals(source)) return;
        beginMutation();
        // Only the edited cell changes from a line array to a string.
        cell.value.addProperty("source", source);
    }

    public void removeCell(String id) {
        Cell cell = cell(id);
        if (!cell.isDeletable()) throw new IllegalArgumentException("This cell's metadata prevents deletion");
        beginMutation();
        cells.remove(cell);
        rebuildCellArray();
    }

    /** targetIndex is the desired index in the final ordering. */
    public void moveCell(String id, int targetIndex) {
        int oldIndex = indexOf(id);
        if (targetIndex < 0 || targetIndex >= cells.size()) throw new IndexOutOfBoundsException(targetIndex);
        if (oldIndex == targetIndex) return;
        beginMutation();
        Cell cell = cells.remove(oldIndex);
        cells.add(targetIndex, cell);
        rebuildCellArray();
    }

    /**
     * Conversion never silently discards rich output or attachments. Empty
     * type-specific fields can be removed; nonempty ones require an explicit
     * clear or retaining the compatible cell type.
     */
    public void setCellType(String id, CellType type) {
        Objects.requireNonNull(type, "Cell type");
        Cell cell = cell(id);
        requireEditable(cell);
        if (type == CellType.UNKNOWN) throw new IllegalArgumentException("Cannot convert to an unknown cell type");
        CellType previousType = cell.type();
        if (previousType == type) return;
        if (cell.type() == CellType.CODE && !cell.value.getAsJsonArray("outputs").isEmpty()) {
            throw new IllegalArgumentException("Clear this cell's outputs before converting it to " + type.jsonName());
        }
        if (type == CellType.CODE && cell.value.has("attachments") && !cell.value.getAsJsonObject("attachments").isEmpty()) {
            throw new IllegalArgumentException("This cell has attachments; keep it as Markdown or Raw to preserve them");
        }
        if (type == CellType.CODE && (cell.value.has("outputs") || cell.value.has("execution_count"))) {
            throw new IllegalArgumentException("This cell has unrecognized execution fields; resolve them in JSON before converting it to Code");
        }
        beginMutation();
        cell.value.addProperty("cell_type", type.jsonName());
        if (type == CellType.CODE) {
            cell.value.remove("attachments");
            cell.value.add("execution_count", JsonNull.INSTANCE);
            cell.value.add("outputs", new JsonArray());
        } else if (previousType == CellType.CODE) {
            cell.value.remove("outputs");
            cell.value.remove("execution_count");
        }
    }

    public void setExecution(String id, Integer executionCount, JsonArray outputs) {
        Cell cell = requireCode(id);
        validateExecutionCount(executionCount);
        JsonArray safeOutputs = validatedOutputs(outputs);
        JsonElement count = executionCount == null ? JsonNull.INSTANCE : new JsonPrimitive(executionCount);
        if (Objects.equals(cell.executionCount(), executionCount) && NotebookJson.same(cell.value.get("outputs"), safeOutputs)) return;
        beginMutation();
        cell.value.add("execution_count", count);
        cell.value.add("outputs", safeOutputs);
    }

    public void setExecutionCount(String id, Integer executionCount) {
        Cell cell = requireCode(id);
        setExecution(id, executionCount, cell.value.getAsJsonArray("outputs"));
    }

    public void replaceOutputs(String id, JsonArray outputs) {
        Cell cell = requireCode(id);
        setExecution(id, cell.executionCount(), outputs);
    }

    public void appendOutput(String id, JsonObject output) {
        Objects.requireNonNull(output, "Cell output");
        validateOutput(output, "New output");
        Cell cell = requireCode(id);
        JsonObject safeOutput = NotebookJson.copy(output);
        beginMutation();
        cell.value.getAsJsonArray("outputs").add(safeOutput);
    }

    public void clearOutputs(String id) { setExecution(id, null, new JsonArray()); }

    private Cell requireCode(String id) {
        Cell cell = cell(id);
        if (cell.type() != CellType.CODE) throw new IllegalArgumentException("Only Code cells can have execution outputs");
        return cell;
    }

    private static void requireEditable(Cell cell) {
        if (cell.type() == CellType.UNKNOWN) throw new IllegalArgumentException("Unsupported cell type is preserved read-only: " + cell.typeName());
        if (!cell.isEditable()) throw new IllegalArgumentException("This cell's metadata prevents editing");
    }

    private static void validateExecutionCount(Integer count) {
        if (count != null && count < 0) throw new IllegalArgumentException("Execution count must be nonnegative or null");
    }

    private static JsonArray validatedOutputs(JsonArray outputs) {
        Objects.requireNonNull(outputs, "Cell outputs");
        for (int index = 0; index < outputs.size(); index++) {
            validateOutput(object(outputs.get(index), "Output " + (index + 1)), "Output " + (index + 1));
        }
        return NotebookJson.copy(outputs);
    }

    private void beginMutation() {
        // Old nbformat versions and hand-written notebooks may lack IDs. Keep
        // untouched input intact; assign the already stable in-memory IDs only
        // when the owner commits a real edit, including an output update.
        for (Cell cell : cells) if (!cell.value.has("id")) cell.value.addProperty("id", cell.id);
        // IDs became a recognized schema property in 4.5. Keeping an older
        // minor after adding them would make known cells schema-invalid.
        // Never downgrade a newer notebook or upgrade untouched input.
        if (formatMinor() < 5) root.addProperty("nbformat_minor", 5);
        modified = true;
    }

    private void rebuildCellArray() {
        JsonArray values = new JsonArray();
        for (Cell cell : cells) values.add(cell.value);
        root.add("cells", values);
    }

    public static final class Cell {
        private final String id;
        private final JsonObject value;

        private Cell(String id, JsonObject value) { this.id = id; this.value = value; }
        public String id() { return id; }
        public String typeName() { return value.get("cell_type").getAsString(); }
        public CellType type() { return CellType.fromJsonName(typeName()); }
        public String source() {
            if (type() == CellType.UNKNOWN) return "";
            return multiline(value.get("source"), "Cell source");
        }
        public Integer executionCount() {
            return type() == CellType.CODE ? integer(value.get("execution_count"), "Execution count", true) : null;
        }
        public JsonArray outputs() { return type() == CellType.CODE ? NotebookJson.copy(value.getAsJsonArray("outputs")) : new JsonArray(); }
        public JsonObject metadata() { return NotebookJson.copy(value.getAsJsonObject("metadata")); }
        public JsonObject json() { return NotebookJson.copy(value); }
        public boolean isEditable() { return type() != CellType.UNKNOWN && !explicitFalse(value.getAsJsonObject("metadata").get("editable")); }
        public boolean isDeletable() {
            return isEditable() && !explicitFalse(value.getAsJsonObject("metadata").get("deletable"));
        }
    }

    private static void validateCell(JsonObject cell, int index) {
        String label = "Cell " + (index + 1);
        String typeName = string(cell.get("cell_type"), label + " type");
        if (typeName.isBlank()) throw invalid(label + " has an empty cell type");
        object(cell.get("metadata"), label + " metadata");
        CellType type = CellType.fromJsonName(typeName);
        // Future cell types own their payload, including fields with familiar names.
        if (type == CellType.UNKNOWN) return;
        multiline(cell.get("source"), label + " source");
        if (cell.has("attachments")) {
            JsonObject attachments = object(cell.get("attachments"), label + " attachments");
            for (var attachment : attachments.entrySet()) object(attachment.getValue(), label + " attachment " + attachment.getKey());
        }
        if (type == CellType.CODE) {
            integer(cell.get("execution_count"), label + " execution_count", true);
            JsonArray outputs = array(cell.get("outputs"), label + " outputs");
            for (int output = 0; output < outputs.size(); output++) {
                String outputLabel = label + ", output " + (output + 1);
                validateOutput(object(outputs.get(output), outputLabel), outputLabel);
            }
        }
    }

    private static void validateOutput(JsonObject output, String label) {
        String type = string(output.get("output_type"), label + " type");
        if (type.isBlank()) throw invalid(label + " has an empty output_type");
        switch (type) {
            case "stream" -> {
                string(output.get("name"), label + " stream name");
                multiline(output.get("text"), label + " stream text");
            }
            case "display_data", "execute_result" -> {
                object(output.get("data"), label + " MIME bundle");
                object(output.get("metadata"), label + " metadata");
                if (type.equals("execute_result")) integer(output.get("execution_count"), label + " execution_count", true);
            }
            case "error" -> {
                string(output.get("ename"), label + " error name");
                string(output.get("evalue"), label + " error value");
                JsonArray traceback = array(output.get("traceback"), label + " traceback");
                for (JsonElement frame : traceback) string(frame, label + " traceback frame");
            }
            default -> { /* Future output types remain intact and can be shown as unsupported. */ }
        }
    }

    private static String freshId(Set<String> ids) {
        String id;
        do { id = UUID.randomUUID().toString(); } while (ids.contains(id));
        return id;
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
