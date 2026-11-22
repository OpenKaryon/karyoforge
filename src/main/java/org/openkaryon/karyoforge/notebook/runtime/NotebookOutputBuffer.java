package org.openkaryon.karyoforge.notebook.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Current-execution display state. Transient display IDs never enter portable notebook JSON. */
public final class NotebookOutputBuffer {
    private JsonArray outputs = new JsonArray();
    private final Map<String, List<Integer>> displays = new HashMap<>();
    private boolean clearBeforeNext;
    private Integer count;
    private long revision;

    public record Snapshot(JsonArray outputs, Integer count, long revision) {}

    public synchronized void accept(JsonObject event) {
        switch (event.get("event").getAsString()) {
            case "count" -> { count = event.get("count").getAsInt(); revision++; }
            case "clear" -> {
                clearBeforeNext = event.has("wait") && event.get("wait").getAsBoolean();
                if (!clearBeforeNext) clear();
            }
            case "output" -> {
                flushClear();
                JsonObject output = event.getAsJsonObject("output").deepCopy();
                String display = event.has("displayId") ? event.get("displayId").getAsString() : null;
                if (display != null && !display.isBlank()) displays.computeIfAbsent(display, ignored -> new ArrayList<>()).add(outputs.size());
                // Merge adjacent stream fragments to keep native previews and serialized outputs compact.
                if (display == null && outputs.size() > 0 && "stream".equals(output.get("output_type").getAsString())) {
                    JsonObject previous = outputs.get(outputs.size() - 1).getAsJsonObject();
                    if ("stream".equals(previous.get("output_type").getAsString()) && previous.get("name").equals(output.get("name"))) {
                        previous.addProperty("text", previous.get("text").getAsString() + output.get("text").getAsString());
                        revision++;
                        return;
                    }
                }
                outputs.add(output);
                revision++;
            }
            case "display_update" -> {
                flushClear();
                String display = event.get("displayId").getAsString();
                for (int index : displays.getOrDefault(display, List.of())) {
                    JsonObject previous = outputs.get(index).getAsJsonObject();
                    previous.add("data", event.getAsJsonObject("data").deepCopy());
                    previous.add("metadata", event.getAsJsonObject("metadata").deepCopy());
                    revision++;
                }
            }
            default -> throw new IllegalArgumentException("Unsupported output-state event");
        }
    }

    private void clear() { outputs = new JsonArray(); displays.clear(); revision++; }
    private void flushClear() { if (clearBeforeNext) { clearBeforeNext = false; clear(); } }
    public synchronized Snapshot snapshot() { return new Snapshot(outputs.deepCopy(), count, revision); }
    public synchronized Snapshot snapshotAfter(long previous) { return revision == previous ? null : snapshot(); }
}
