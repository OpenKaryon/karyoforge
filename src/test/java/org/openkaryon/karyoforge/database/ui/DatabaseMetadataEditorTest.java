package org.openkaryon.karyoforge.database.ui;

import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.openkaryon.karyoforge.database.core.JdbcSession.SchemaObject;
import org.openkaryon.karyoforge.database.core.TableDefinition;
import org.openkaryon.karyoforge.database.sql.DatabaseSqlDeclaration;

import java.util.List;

public final class DatabaseMetadataEditorTest extends BasePlatformTestCase {
    public void testEditorUsesOnlyLoadedMetadataAndNavigatesToAReadOnlySnapshot() {
        var bridge = getProject().getService(DatabaseEditorBridge.class);
        var samples = new SchemaObject("samples", "table");
        bridge.publishCatalog("owned", List.of(samples));
        bridge.selectCatalog("owned");
        try {
            myFixture.configureByText("catalog.sql", "SELECT * FROM sa<caret>");
            var choices = myFixture.completeBasic();
            if (choices == null) assertEquals("SELECT * FROM main.samples", myFixture.getEditor().getDocument().getText());
            else assertContainsElements(myFixture.getLookupElementStrings(), "main.samples");

            var columns = List.of(new TableDefinition.Column("note", "TEXT", false, 0, 0));
            bridge.publishDefinition("owned", new TableDefinition(samples, columns, "CREATE TABLE samples (note TEXT)", false));
            myFixture.configureByText("columns.sql", "SELECT s.no<caret> FROM main.samples s");
            choices = myFixture.completeBasic();
            if (choices == null) assertEquals("SELECT s.note FROM main.samples s", myFixture.getEditor().getDocument().getText());
            else assertContainsElements(myFixture.getLookupElementStrings(), "note");

            var file = myFixture.configureByText("declaration.sql", "SELECT s.note FROM main.samples AS s");
            int offset = file.getText().indexOf("note") + 1;
            var target = new DatabaseSqlDeclaration().getGotoDeclarationTargets(file.findElementAt(offset), offset, myFixture.getEditor());
            assertNotNull(target);
            assertEquals(1, target.length);
            var metadata = (PsiFile) target[0];
            assertEquals("Loaded Database Metadata.sql", metadata.getName());
            assertFalse(metadata.getVirtualFile().isWritable());
            assertTrue(metadata.getText().contains("CREATE TABLE samples (note TEXT)"));

            var unsupported = myFixture.configureByText("nested.sql", "SELECT s.note FROM (SELECT note FROM samples) s");
            offset = unsupported.getText().indexOf("note") + 1;
            assertNull(new DatabaseSqlDeclaration().getGotoDeclarationTargets(unsupported.findElementAt(offset), offset, myFixture.getEditor()));
            bridge.forgetCatalog("owned");
            assertTrue(bridge.catalogFor(file.getVirtualFile()).objects().isEmpty());
        } finally {
            bridge.forgetCatalog("owned");
            bridge.selectCatalog(null);
        }
    }
}
