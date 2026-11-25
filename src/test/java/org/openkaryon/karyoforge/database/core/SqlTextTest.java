package org.openkaryon.karyoforge.database.core;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public final class SqlTextTest {
    @Test public void currentStatementAndSelectionUseSourceOffsetsAndSkipQuotedDelimiters() {
        var text = "SELECT 'one;''two'; -- ignored ;\nSELECT [semi;colon];\nSELECT 'unfinished";
        int second = text.indexOf("SELECT [");
        int third = text.lastIndexOf("SELECT");
        assertEquals("SELECT 'one;''two'", SqlText.activeStatement(text, 0, 0));
        assertEquals("SELECT [semi;colon]", SqlText.activeStatement(text, second, second));
        assertEquals("SELECT [semi;colon]", SqlText.activeStatement(text, second, third));
        assertThrows(IllegalArgumentException.class, () -> SqlText.activeStatement(text, third, third));
        assertThrows(IllegalArgumentException.class, () -> SqlText.activeStatement(text, second + 8, third));
        assertThrows(IllegalArgumentException.class, () -> SqlText.activeStatement(text, 0, third));
    }

    @Test public void compilationPreservesQuotedTextAndBindsRepeatedNamesInOrder() {
        var command = SqlText.compile("""
                -- :ignored
                SELECT :amount, ':quoted?', :note, :amount /* ? :ignored */
                """);
        assertEquals("SELECT", command.keyword());
        assertEquals(List.of("amount", "note"), command.parameters());
        assertEquals(List.of("amount", "note", "amount"), command.bindingOrder());
        assertTrue(command.sql().startsWith("SELECT ?, ':quoted?', ?, ? /* ? :ignored */"));
        assertEquals(List.of("1", "2"), SqlText.compile("SELECT ?, '?' AS literal, ?").parameters());
        for (String input : List.of("SELECT :named, ?", "SELECT $unsupported", "SELECT @unsupported",
                "SELECT ?42", "SELECT 1; SELECT 2", "SELECT 'unterminated")) {
            assertThrows(input, IllegalArgumentException.class, () -> SqlText.compile(input));
        }
    }
}
