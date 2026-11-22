package org.openkaryon.karyoforge.notebook.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import junit.framework.TestCase;

import java.util.List;

public final class NotebookDocumentTest extends TestCase {
    private static final String RICH_NOTEBOOK = """
            {
              "nbformat": 4,
              "nbformat_minor": 27,
              "vendor_extension": {"unchanged": [null, true, 1e2]},
              "metadata": {"kernelspec": {"name": "python3", "display_name": "Python 3"}, "custom": {"language": "Unicode λ"}},
              "cells": [
                {
                  "id": "markdown-existing",
                  "cell_type": "markdown",
                  "metadata": {"tags": ["custom"], "extra": {"kept": true}},
                  "source": ["# Heading\\n", "![image](attachment:diagram.png)"],
                  "attachments": {"diagram.png": {"image/png": ["AAAA", "BBBB"], "application/json": {"x": 1}}},
                  "unrecognized_cell_field": [1, 2, 3]
                },
                {
                  "id": "code-existing",
                  "cell_type": "code",
                  "metadata": {"execution": {"shell.execute_reply": "2026-10-13T12:00:00Z"}},
                  "source": "print('λ')\\n2 + 2",
                  "execution_count": 42,
                  "outputs": [
                    {"output_type": "stream", "name": "stdout", "text": ["λ", "\\n"], "extra": false},
                    {"output_type": "execute_result", "execution_count": 42, "metadata": {"image/png": {"width": 9}}, "data": {"text/plain": ["4"], "text/html": "<b>4</b>", "image/png": "AAAA", "application/json": {"a": [null, 4]}, "application/vnd.custom+json": {"opaque": true}}},
                    {"output_type": "future_output", "opaque": {"kept": [1, 2]}}
                  ]
                },
                {"id": "raw-existing", "cell_type": "raw", "metadata": {"format": "text/latex"}, "source": "\\\\alpha"},
                {"id": "future-existing", "cell_type": "future_cell", "metadata": {"custom": 3}, "vendor_payload": {"untouched": true}}
              ]
            }
            """;

    public void testUntouchedSourceAndEveryUnknownFieldRemainExactlyIntact() throws Exception {
        String original = " \r\n" + RICH_NOTEBOOK.replace("\n", "\r\n") + " \r\n";
        NotebookDocument document = NotebookDocument.parse(original);
        assertEquals(original, document.toJson());
        assertEquals(original, document.originalText());
        assertFalse(document.isModified());
        assertEquals(27, document.formatMinor());
        assertEquals("# Heading\n![image](attachment:diagram.png)", document.cell("markdown-existing").source());
        assertEquals(Integer.valueOf(42), document.cell("code-existing").executionCount());
        assertEquals(CellType.UNKNOWN, document.cell("future-existing").type());
        assertEquals("", document.cell("future-existing").source());
        assertFalse(document.cell("future-existing").isEditable());
        JsonObject snapshot = document.json();
        snapshot.addProperty("nbformat_minor", 0);
        snapshot.getAsJsonArray("cells").remove(0);
        document.metadata().addProperty("corrupt", true);
        document.cell("markdown-existing").metadata().addProperty("corrupt", true);
        document.cell("code-existing").outputs().remove(0);
        assertEquals(original, document.toJson());
        assertEquals(4, document.cells().size());
        assertEquals(3, document.cell("code-existing").outputs().size());
    }

    public void testEditingOneSourcePreservesOtherSourceFormsOutputsAndUnknownData() throws Exception {
        NotebookDocument document = NotebookDocument.parse(RICH_NOTEBOOK);
        JsonObject expected = JsonParser.parseString(RICH_NOTEBOOK).getAsJsonObject();
        document.setSource("code-existing", "print('<> & λ')\n5 + 5");
        expected.getAsJsonArray("cells").get(1).getAsJsonObject().addProperty("source", "print('<> & λ')\n5 + 5");
        assertEquals(expected, JsonParser.parseString(document.toJson()));
        assertTrue(document.cell("markdown-existing").json().get("source").isJsonArray());
        assertEquals(3, document.cell("code-existing").outputs().size());
        assertEquals(Integer.valueOf(42), document.cell("code-existing").executionCount());
        assertTrue(document.toJson().contains("<> & λ"));
        NotebookDocument reparsed = NotebookDocument.parse(document.toJson());
        assertEquals("code-existing", reparsed.cellAt(1).id());
        assertEquals("print('<> & λ')\n5 + 5", reparsed.cellAt(1).source());
    }

    public void testIdlessOldAndHandWrittenNotebooksGainStableIdsOnlyOnRealMutation() throws Exception {
        String original = notebook(5, """
                {"cell_type":"markdown","metadata":{},"source":["a\\n","b"]},
                {"cell_type":"code","metadata":{},"source":"n=1","outputs":[],"execution_count":null}
                """);
        NotebookDocument document = NotebookDocument.parse(original);
        String firstId = document.cellAt(0).id();
        String secondId = document.cellAt(1).id();
        assertFalse(firstId.equals(secondId));
        document.setSource(secondId, "n=1");
        document.moveCell(firstId, 0);
        assertEquals(original, document.toJson());
        assertFalse(document.cell(firstId).json().has("id"));
        document.setSource(secondId, "n=2");
        assertTrue(document.cell(firstId).json().get("source").isJsonArray());
        NotebookDocument reparsed = NotebookDocument.parse(document.toJson());
        assertEquals(firstId, reparsed.cellAt(0).id());
        assertEquals(secondId, reparsed.cellAt(1).id());
        assertEquals("n=2", reparsed.cell(secondId).source());
        assertEquals(5, reparsed.formatMinor());
        NotebookDocument old = NotebookDocument.parse(notebook(0, "{\"cell_type\":\"raw\",\"metadata\":{},\"source\":\"old\"}"));
        String untouchedOld = old.toJson();
        assertEquals(0, old.formatMinor());
        old.setSource(old.cellAt(0).id(), "old");
        assertEquals(untouchedOld, old.toJson());
        old.setSource(old.cellAt(0).id(), "new");
        NotebookDocument upgraded = NotebookDocument.parse(old.toJson());
        assertEquals(5, upgraded.formatMinor());
        // nbformat 4.0's known-cell schemas disallow additional properties and
        // do not recognize id; 4.5 recognizes and requires this property.
        assertEquals(4, upgraded.json().get("nbformat").getAsInt());
        assertTrue(upgraded.cellAt(0).json().get("id").getAsString().matches("[A-Za-z0-9_-]{1,64}"));
        assertEquals("new", upgraded.cellAt(0).source());
    }

    public void testAddMoveRemoveAndConversionsKeepIdentityMetadataAndSource() throws Exception {
        NotebookDocument document = NotebookDocument.create();
        var code = document.insertCell(0, CellType.CODE, "x = 1\n");
        var markdown = document.insertCell(1, CellType.MARKDOWN, "# note");
        var raw = document.insertCell(1, CellType.RAW, "\\begin{document}");
        document.moveCell(code.id(), 2);
        assertEquals(List.of(raw.id(), markdown.id(), code.id()), document.cells().stream().map(NotebookDocument.Cell::id).toList());
        document.setCellType(code.id(), CellType.MARKDOWN);
        assertEquals("x = 1\n", code.source());
        assertFalse(code.json().has("outputs"));
        assertFalse(code.json().has("execution_count"));
        document.setCellType(code.id(), CellType.CODE);
        assertTrue(code.outputs().isEmpty());
        assertNull(code.executionCount());
        document.removeCell(markdown.id());
        NotebookDocument reparsed = NotebookDocument.parse(document.toJson());
        assertEquals(List.of(raw.id(), code.id()), reparsed.cells().stream().map(NotebookDocument.Cell::id).toList());
        assertEquals("\\begin{document}", reparsed.cell(raw.id()).source());
        assertEquals(CellType.CODE, reparsed.cell(code.id()).type());
        assertEquals(5, reparsed.formatMinor());
    }

    public void testConversionRefusesToSilentlyLoseRichOutputsOrAttachments() throws Exception {
        NotebookDocument document = NotebookDocument.parse(RICH_NOTEBOOK);
        expectMutationError(() -> document.setCellType("code-existing", CellType.MARKDOWN), "Clear");
        expectMutationError(() -> document.setCellType("markdown-existing", CellType.CODE), "attachments");
        expectMutationError(() -> document.setSource("future-existing", "replace"), "Unsupported");
        assertEquals(RICH_NOTEBOOK, document.toJson());
        document.setCellType("markdown-existing", CellType.RAW);
        assertTrue(document.cell("markdown-existing").json().has("attachments"));
        assertEquals("custom", document.cell("markdown-existing").metadata().getAsJsonArray("tags").get(0).getAsString());
        document.clearOutputs("code-existing");
        document.setCellType("code-existing", CellType.MARKDOWN);
        assertEquals("2026-10-13T12:00:00Z", document.cell("code-existing").metadata().getAsJsonObject("execution").get("shell.execute_reply").getAsString());
        NotebookDocument.parse(document.toJson());
    }

    public void testExecutionMutationsPreserveCompleteMimeBundlesAndDefensiveCopies() throws Exception {
        NotebookDocument document = NotebookDocument.parse(RICH_NOTEBOOK);
        JsonArray outputs = document.cell("code-existing").outputs();
        JsonObject largeOutput = JsonParser.parseString("{\"output_type\":\"display_data\",\"metadata\":{},\"data\":{\"text/plain\":\"plot\",\"application/vnd.custom+json\":{\"values\":[1,2,3]}}}").getAsJsonObject();
        String image = "QUJD".repeat(350_000);
        largeOutput.getAsJsonObject("data").addProperty("image/png", image);
        outputs.add(largeOutput);
        document.setExecution("code-existing", 43, outputs);
        outputs.remove(0);
        largeOutput.getAsJsonObject("data").remove("image/png");
        assertEquals(4, document.cell("code-existing").outputs().size());
        NotebookDocument reparsed = NotebookDocument.parse(document.toJson());
        assertEquals(Integer.valueOf(43), reparsed.cell("code-existing").executionCount());
        assertEquals(image, reparsed.cell("code-existing").outputs().get(3).getAsJsonObject().getAsJsonObject("data").get("image/png").getAsString());
        assertTrue(reparsed.cell("code-existing").outputs().get(2).getAsJsonObject().has("opaque"));
        JsonObject error = JsonParser.parseString("{\"output_type\":\"error\",\"ename\":\"KeyboardInterrupt\",\"evalue\":\"\",\"traceback\":[\"Traceback\"]}").getAsJsonObject();
        reparsed.appendOutput("code-existing", error);
        error.addProperty("ename", "corrupt");
        assertEquals("KeyboardInterrupt", reparsed.cell("code-existing").outputs().get(4).getAsJsonObject().get("ename").getAsString());
        reparsed.setExecutionCount("code-existing", 44);
        assertEquals(5, reparsed.cell("code-existing").outputs().size());
        reparsed.replaceOutputs("code-existing", new JsonArray());
        assertEquals(Integer.valueOf(44), reparsed.cell("code-existing").executionCount());
        reparsed.clearOutputs("code-existing");
        assertNull(reparsed.cell("code-existing").executionCount());
        assertTrue(reparsed.cell("code-existing").outputs().isEmpty());
    }

    public void testMalformedUnsupportedAndAmbiguousJsonRetainTheirOriginalSource() throws Exception {
        String[] invalid = {
                "", "[]", "{\"nbformat\": 3, \"nbformat_minor\":0,\"metadata\":{},\"cells\":[]}",
                "{\"nbformat\":4,\"nbformat_minor\":0,\"metadata\":{},\"cells\":[]} garbage",
                "{nbformat:4,\"nbformat_minor\":0,\"metadata\":{},\"cells\":[]}",
                "{\"nbformat\":4,\"nbformat_minor\":0,\"metadata\":{},\"cells\":[],\"cells\":[{}]}",
                notebook(5, "{\"id\":\"a\",\"cell_type\":\"raw\",\"metadata\":{\"x\":1,\"x\":2},\"source\":\"x\"}"),
                notebook(5, "{\"id\":\"a\",\"cell_type\":\"raw\",\"metadata\":{},\"source\":[\"x\",null]}"),
                notebook(5, "{\"id\":\"a\",\"cell_type\":\"code\",\"metadata\":{},\"source\":\"x\",\"outputs\":[],\"execution_count\":1.5}"),
                notebook(5, "{\"id\":\"a\",\"cell_type\":\"code\",\"metadata\":{},\"source\":\"x\",\"outputs\":[{\"output_type\":\"stream\",\"name\":\"stdout\",\"text\":false}],\"execution_count\":null}"),
                notebook(5, "{\"id\":\"a\",\"cell_type\":\"raw\",\"metadata\":{},\"source\":\"unescaped\nnewline\"}"),
                "{\"nbformat\":4,\"nbformat_minor\":0,//comment\n\"metadata\":{},\"cells\":[]}",
                "{\"nbformat\":4,\"nbformat_minor\":0,\"metadata\":{\"number\":NaN},\"cells\":[]}"
        };
        for (String original : invalid) {
            try { NotebookDocument.parse(original); fail("Accepted invalid notebook: " + original); }
            catch (NotebookFormatException expected) { assertEquals(original, expected.originalText()); assertFalse(expected.getMessage().isBlank()); }
        }
        assertInvalid(notebook(5, "{\"id\":\"same\",\"cell_type\":\"raw\",\"metadata\":{},\"source\":\"a\"},{\"id\":\"same\",\"cell_type\":\"raw\",\"metadata\":{},\"source\":\"b\"}"), "duplicate");
        assertInvalid(notebook(5, "{\"id\":\"bad id!\",\"cell_type\":\"raw\",\"metadata\":{},\"source\":\"a\"}"), "invalid id");
    }

    public void testRejectedMutationsAreAtomicAndRespectCellMetadataLocks() throws Exception {
        String original = notebook(5, "{\"id\":\"locked\",\"cell_type\":\"code\",\"metadata\":{\"editable\":false},\"source\":\"x=1\",\"outputs\":[],\"execution_count\":null},{\"id\":\"retained\",\"cell_type\":\"raw\",\"metadata\":{\"deletable\":false},\"source\":\"x\"}");
        NotebookDocument document = NotebookDocument.parse(original);
        assertFalse(document.cell("locked").isEditable());
        assertFalse(document.cell("locked").isDeletable());
        assertTrue(document.cell("retained").isEditable());
        assertFalse(document.cell("retained").isDeletable());
        expectMutationError(() -> document.setSource("locked", "x=2"), "editing");
        expectMutationError(() -> document.removeCell("locked"), "deletion");
        expectMutationError(() -> document.removeCell("retained"), "deletion");
        expectMutationError(() -> document.setExecutionCount("locked", -1), "nonnegative");
        JsonArray invalid = JsonParser.parseString("[{\"output_type\":\"execute_result\",\"data\":{},\"metadata\":{},\"execution_count\":false}]").getAsJsonArray();
        expectMutationError(() -> document.replaceOutputs("locked", invalid), "integer");
        assertEquals(original, document.toJson());
        assertFalse(document.isModified());
        document.setExecutionCount("locked", 1);
        assertEquals(Integer.valueOf(1), document.cell("locked").executionCount());
    }

    public void testLargeOrDeepNotebooksFailExplicitlyRatherThanTruncate() throws Exception {
        String tooLarge = " ".repeat(NotebookDocument.MAX_INPUT_CHARACTERS + 1);
        assertInvalid(tooLarge, "character text limit");
        String tooDeep = "{\"nbformat\":4,\"nbformat_minor\":5,\"cells\":[],\"metadata\":{\"deep\":" + "[".repeat(260) + "0" + "]".repeat(260) + "}}";
        assertInvalid(tooDeep, "nesting");
        String cell = "{\"cell_type\":\"raw\",\"metadata\":{},\"source\":\"x\"}";
        assertInvalid(notebook(0, (cell + ",").repeat(NotebookDocument.MAX_CELLS) + cell), "cell limit");
        assertInvalid("{\"nbformat\":4,\"nbformat_minor\":5,\"cells\":[],\"metadata\":{\"number\":" + "1".repeat(2048) + "}}", "number");
    }

    public void testFutureCellPayloadIsOpaqueAndRetainedWhileKnownCellsChange() throws Exception {
        String original = notebook(27, """
                {"id":"future","cell_type":"future_cell","metadata":{},
                 "source":{"vendor":"opaque"},"attachments":[1,2],"outputs":false},
                {"id":"known","cell_type":"raw","metadata":{},"source":"before"}
                """);
        NotebookDocument document = NotebookDocument.parse(original);
        JsonObject future = document.cell("future").json();
        assertEquals("", document.cell("future").source());
        assertFalse(document.cell("future").isEditable());
        assertFalse(document.cell("future").isDeletable());
        document.setSource("known", "after");
        assertEquals(future, NotebookDocument.parse(document.toJson()).cell("future").json());
        assertEquals(27, document.formatMinor());
    }

    public void testNonCodeConversionsPreserveUnrecognizedExecutionFields() throws Exception {
        String original = notebook(5, """
                {"id":"raw","cell_type":"raw","metadata":{},"source":"text",
                 "outputs":{"vendor":"preserved"},"execution_count":"opaque"}
                """);
        NotebookDocument document = NotebookDocument.parse(original);
        document.setCellType("raw", CellType.MARKDOWN);
        JsonObject changed = document.cell("raw").json();
        assertEquals("preserved", changed.getAsJsonObject("outputs").get("vendor").getAsString());
        assertEquals("opaque", changed.get("execution_count").getAsString());
        String beforeRejectedConversion = document.toJson();
        expectMutationError(() -> document.setCellType("raw", CellType.CODE), "unrecognized execution");
        assertEquals(beforeRejectedConversion, document.toJson());
    }

    public void testSchemaCompatibleNamedStreamsAndExactNumbersSurviveAnUnrelatedEdit() throws Exception {
        String original = notebook(5, """
                {"id":"code","cell_type":"code","metadata":{"exact":123456789012345678901234567890,"negative_zero":-0.0,"scientific":1.2300e+02},
                 "source":"before","execution_count":null,
                 "outputs":[{"output_type":"stream","name":"custom-stream","text":["one","two"]}]}
                """);
        NotebookDocument document = NotebookDocument.parse(original);
        document.setSource("code", "after");
        assertTrue(document.toJson().contains("123456789012345678901234567890"));
        JsonObject savedMetadata = JsonParser.parseString(document.toJson()).getAsJsonObject()
                .getAsJsonArray("cells").get(0).getAsJsonObject().getAsJsonObject("metadata");
        assertEquals("-0.0", savedMetadata.get("negative_zero").getAsString());
        assertEquals("1.2300e+02", savedMetadata.get("scientific").getAsString());
        assertEquals("custom-stream", NotebookDocument.parse(document.toJson()).cell("code")
                .outputs().get(0).getAsJsonObject().get("name").getAsString());
    }

    public void testIncomingJsonIsOwnedAndCyclicValuesCannotChangeTheDocument() throws Exception {
        NotebookDocument document = NotebookDocument.create();
        JsonObject metadata = new JsonObject();
        var number = new java.util.concurrent.atomic.AtomicInteger(7);
        metadata.addProperty("counter", number);
        document.setMetadata(metadata);
        number.set(99);
        assertEquals(7, document.metadata().get("counter").getAsInt());
        String original = document.toJson();
        JsonObject overlong = new JsonObject();
        overlong.addProperty("number", new java.math.BigInteger("1".repeat(2048)));
        expectMutationError(() -> document.setMetadata(overlong), "Numeric literal");
        assertEquals(original, document.toJson());
        JsonObject cyclic = new JsonObject();
        cyclic.add("cycle", cyclic);
        expectMutationError(() -> document.setMetadata(cyclic), "nesting");
        assertEquals(original, document.toJson());
    }

    public void testSerializationRejectsEscapedTextOverBudgetWithoutTruncation() throws Exception {
        NotebookDocument document = NotebookDocument.create();
        var cell = document.insertCell(0, CellType.RAW, String.valueOf((char) 1).repeat(NotebookDocument.MAX_INPUT_CHARACTERS / 6));
        expectMutationError(document::toJson, "text limit");
        assertEquals(NotebookDocument.MAX_INPUT_CHARACTERS / 6, cell.source().length());
        document.setSource(cell.id(), "shortened");
        assertEquals("shortened", NotebookDocument.parse(document.toJson()).cell(cell.id()).source());
    }

    public void testDistinctLargeNumbersCannotBeMistakenForNoOpMetadataOrOutputUpdates() throws Exception {
        NotebookDocument document = NotebookDocument.create();
        document.setMetadata(JsonParser.parseString("{\"large\":1e500}").getAsJsonObject());
        document.setMetadata(JsonParser.parseString("{\"large\":2e500}").getAsJsonObject());
        assertEquals("2e500", document.metadata().get("large").getAsString());
        var cell = document.insertCell(0, CellType.CODE, "1");
        String output = "[{\"output_type\":\"future_output\",\"number\":%s}]";
        document.replaceOutputs(cell.id(), JsonParser.parseString(output.formatted("1e500")).getAsJsonArray());
        document.replaceOutputs(cell.id(), JsonParser.parseString(output.formatted("2e500")).getAsJsonArray());
        assertEquals("2e500", cell.outputs().get(0).getAsJsonObject().get("number").getAsString());
    }

    public void testUtf8FileRoundTripPreservesSurrogateEscapesAndSupplementaryCharacters() throws Exception {
        String value = "prefix" + (char) 0xd800 + "🧬" + (char) 0xdc00;
        NotebookDocument document = NotebookDocument.create();
        var cell = document.insertCell(0, CellType.RAW, value);
        JsonObject metadata = new JsonObject();
        metadata.addProperty(value, value);
        document.setMetadata(metadata);
        String json = document.toJson();
        assertTrue(json.contains("\\ud800"));
        assertTrue(json.contains("\\udc00"));
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        NotebookDocument saved = NotebookDocument.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(value, saved.cell(cell.id()).source());
        assertEquals(value, saved.metadata().get(value).getAsString());
    }

    public void testTransactionCopiesKeepVirtualIdsAndIsolateEveryExplicitMutation() throws Exception {
        String text = notebook(0, "{\"cell_type\":\"raw\",\"metadata\":{},\"source\":[\"a\",\"b\"]}");
        NotebookDocument original = NotebookDocument.parse(text);
        NotebookDocument copy = original.copy();
        String id = original.cellAt(0).id();
        assertEquals(id, copy.cellAt(0).id());
        assertEquals(text, copy.toJson());
        assertFalse(copy.isModified());
        copy.setSource(id, "changed");
        assertEquals(text, original.toJson());
        assertFalse(original.cellAt(0).json().has("id"));
        assertEquals(id, NotebookDocument.parse(copy.toJson()).cellAt(0).id());
        assertEquals(5, copy.formatMinor());
        NotebookDocument second = copy.copy();
        assertTrue(second.isModified());
        assertEquals(copy.toJson(), second.toJson());
        second.setSource(id, "another");
        assertEquals("changed", copy.cell(id).source());
        NotebookDocument rich = NotebookDocument.parse(RICH_NOTEBOOK);
        NotebookDocument fork = rich.copy();
        var output = JsonParser.parseString("{\"output_type\":\"future_output\",\"data\":{\"owned\":true}}").getAsJsonObject();
        fork.appendOutput("code-existing", output);
        assertEquals(3, rich.cell("code-existing").outputs().size());
        assertEquals(4, fork.cell("code-existing").outputs().size());
        rich.clearOutputs("code-existing");
        assertEquals(4, fork.cell("code-existing").outputs().size());
        fork.setCellType("markdown-existing", CellType.RAW);
        assertEquals(CellType.MARKDOWN, rich.cell("markdown-existing").type());
        fork.setMetadata(new JsonObject());
        assertTrue(rich.metadata().has("custom"));
        fork.removeCell("raw-existing");
        assertEquals(4, rich.cells().size());
        NotebookDocument.parse(fork.toJson());
    }

    private static String notebook(int minor, String cells) {
        return "{\"nbformat\":4,\"nbformat_minor\":" + minor + ",\"metadata\":{},\"cells\":[" + cells + "]}";
    }

    private static void assertInvalid(String original, String messagePart) throws Exception {
        try { NotebookDocument.parse(original); fail("Expected invalid notebook"); }
        catch (NotebookFormatException expected) {
            assertSame(original, expected.originalText());
            // The IDE's bundled Gson enforces its own nesting limit before our
            // defensive limit. Its human-readable wording/capitalization may
            // differ, but the rejection reason and exact retained text must not.
            assertTrue(expected.getMessage(), expected.getMessage().toLowerCase(java.util.Locale.ROOT)
                    .contains(messagePart.toLowerCase(java.util.Locale.ROOT)));
        }
    }

    private static void expectMutationError(Runnable mutation, String messagePart) {
        try { mutation.run(); fail("Expected rejected mutation"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(messagePart)); }
    }
}
