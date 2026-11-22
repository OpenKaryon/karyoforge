package org.openkaryon.karyoforge.notebook.runtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import junit.framework.TestCase;

public final class NotebookOutputBufferTest extends TestCase {
    public void testStreamsCoalesceAndSnapshotsRemainIndependent() {
        var buffer = new NotebookOutputBuffer();
        buffer.accept(event("{\"event\":\"count\",\"count\":3}"));
        buffer.accept(stream("first"));
        var old = buffer.snapshot();
        buffer.accept(stream(" second"));
        assertEquals(1, buffer.snapshot().outputs().size());
        assertEquals("first second", buffer.snapshot().outputs().get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("first", old.outputs().get(0).getAsJsonObject().get("text").getAsString());
        old.outputs().get(0).getAsJsonObject().addProperty("text", "external mutation");
        assertEquals(Integer.valueOf(3), buffer.snapshot().count());
        assertNull(buffer.snapshotAfter(buffer.snapshot().revision()));
        assertEquals("first second", buffer.snapshot().outputs().get(0).getAsJsonObject().get("text").getAsString());
    }

    public void testDisplayUpdatesReplaceEveryMatchingLiveBundleWithoutSavingRoutingIds() {
        var buffer = new NotebookOutputBuffer();
        JsonObject output = event("{\"event\":\"output\",\"displayId\":\"live-id\",\"output\":{\"output_type\":\"display_data\",\"data\":{\"text/plain\":\"first\"},\"metadata\":{}}}");
        buffer.accept(output);
        buffer.accept(output);
        buffer.accept(event("{\"event\":\"display_update\",\"displayId\":\"other\",\"data\":{\"text/plain\":\"wrong\"},\"metadata\":{}}"));
        assertTrue(buffer.snapshot().outputs().toString().contains("first"));
        buffer.accept(event("{\"event\":\"display_update\",\"displayId\":\"live-id\",\"data\":{\"text/plain\":\"updated\",\"text/html\":\"<script>literal</script>\",\"application/x-future\":{\"retained\":true}},\"metadata\":{\"custom\":1}}"));
        var result = buffer.snapshot().outputs();
        assertEquals(2, result.size());
        for (var item : result) {
            assertEquals("updated", item.getAsJsonObject().getAsJsonObject("data").get("text/plain").getAsString());
            assertTrue(item.toString().contains("application/x-future"));
            assertFalse(item.toString().contains("live-id"));
        }
    }

    public void testDeferredClearWaitsForNextOutputAndDropsOldDisplayRoutes() {
        var buffer = new NotebookOutputBuffer();
        buffer.accept(stream("old"));
        buffer.accept(event("{\"event\":\"clear\",\"wait\":true}"));
        assertTrue(buffer.snapshot().outputs().toString().contains("old"));
        buffer.accept(stream("new"));
        assertEquals(1, buffer.snapshot().outputs().size());
        assertFalse(buffer.snapshot().outputs().toString().contains("old"));
        buffer.accept(event("{\"event\":\"clear\",\"wait\":false}"));
        assertTrue(buffer.snapshot().outputs().isEmpty());
    }

    private static JsonObject stream(String text) {
        var result = event("{\"event\":\"output\",\"output\":{\"output_type\":\"stream\",\"name\":\"stdout\"}}");
        result.getAsJsonObject("output").addProperty("text", text);
        return result;
    }
    private static JsonObject event(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
}
