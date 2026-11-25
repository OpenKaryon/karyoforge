package org.openkaryon.karyoforge.uitest

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.ActionManager
import com.intellij.driver.sdk.getToolWindow
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.editor
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.JComboBoxUiComponent
import com.intellij.driver.sdk.ui.components.elements.JTextFieldUI
import com.intellij.driver.sdk.ui.components.elements.accessibleTree
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.checkBox
import com.intellij.driver.sdk.ui.components.elements.comboBox
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.present
import com.intellij.driver.sdk.ui.shouldBe
import com.intellij.driver.sdk.ui.ui
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

class SqliteDatabasesUiTest {
    @Test
    fun opensOwnedDatabaseAndPagesResultsWithoutChangingBytes() {
        val driverJar = VmIde.fixtures.resolve("sqlite/driver.jar")
        require(Files.isRegularFile(driverJar)) { "Supply SQLITE_DRIVER when building the VM suite" }
        val project = VmIde.project("sqlite database")
        val database = seed(project)

        val original = Files.readAllBytes(database)
        VmIde.context("sqlite-databases", project).runIdeWithDriver(runTimeout = 16.minutes).useDriverAndCloseIde {
            openDatabases()
            ideFrame {
                val add = button { byVisibleText("Add SQLite Source") }
                add.shouldBe("The Databases tool window is open", present, 1.minutes)
                add.click()
            }
            ui.dialog({ byTitle("Add SQLite Data Source") }) {
                textField { byAccessibleName("Data source name") }.text = "Owned SQLite"
                textField { byAccessibleName("SQLite database file") }.text = database.toString()
                textField { byAccessibleName("SQLite JDBC JAR") }.text = driverJar.toString()
                pressButton("Save")
            }
            ideFrame {
                x { byVisibleText("Data source saved.") }.shouldBe("Saving is passive", present, 1.minutes)
                button { byVisibleText("Query Limits") }.click()
            }
            ui.dialog({ byTitle("Database Query Limits") }) {
                val timeout = xx(JTextFieldUI::class.java) { byClass("JBTextField") }
                    .list().single { it.text == "30" }
                timeout.text = "240"
                pressButton("Save")
            }
            ideFrame {
                button { byVisibleText("Test Connection") }.click()
                x { byVisibleText("Connection test passed. The temporary session was closed.") }
                    .shouldBe("Testing releases the temporary connection", present, 1.minutes)
                button { byVisibleText("Connect") }.click()
                x { contains(byVisibleText("Connected in read-only mode.")) }
                    .shouldBe("The driver opens the owned source", present, 1.minutes)
                accessibleTree { byAccessibleName("Database schema objects") }.clickPath("main", "Tables", "samples (table)")
                button { byVisibleText("Show Rows") }.click()
                x { contains(byVisibleText("Page 1 · 200 rows")) }
                    .shouldBe("The first page contains 200 rows", present, 1.minutes)
                val results = x { byAccessibleName("Database query results") }
                val table = driver.cast(results.component, QueryGrid::class)
                assertEquals("sample-1", table.getValueAt(0, 1))
                button { byVisibleText("Next Result Page") }.click()
                x { contains(byVisibleText("Page 2 · 5 rows")) }
                    .shouldBe("The next page contains five rows", present, 1.minutes)
                assertEquals("sample-201", table.getValueAt(0, 1))

                button { byVisibleText("Open SQL Console") }.click()
                val nativeSql = editor()
                nativeSql.text = "SELECT 17 AS first;\nSELECT 42 AS second;"
                nativeSql.click()
                nativeSql.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_END) }
                nativeSql.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_ENTER) }
                x { contains(byVisibleText("Page 1 · 1 rows")) }
                    .shouldBe("The native SQL action runs the statement at the caret", present, 1.minutes)
                assertEquals("42", table.getValueAt(0, 0))
                button { byVisibleText("Add SQLite Source") }.click()
            }
            ui.dialog({ byTitle("Add SQLite Data Source") }) {
                textField { byAccessibleName("Data source name") }.text = "Parallel SQLite"
                textField { byAccessibleName("SQLite database file") }.text = database.toString()
                textField { byAccessibleName("SQLite JDBC JAR") }.text = driverJar.toString()
                pressButton("Save")
            }
            ideFrame {
                button { byVisibleText("Connect") }.click()
                x { contains(byVisibleText("Connected in read-only mode.")) }
                    .shouldBe("The second connection opens without closing the first", present, 1.minutes)
                val picker = comboBox { byAccessibleName("Database data sources") }
                selectSource(picker, "Owned SQLite")
                val sql = textField { byAccessibleName("SQL statement") }
                sql.shouldBe("The first source retains its previous statement", { text.contains("samples") }, 1.minutes)
                sql.text = "WITH RECURSIVE x(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM x WHERE i<1000000000) SELECT sum(i) FROM x"
                button { byVisibleText("Run SQL") }.click()
                button { byVisibleText("Stop") }.shouldBe("A running query can be stopped", { isEnabled() }, 1.minutes)
                selectSource(picker, "Parallel SQLite")
                val parallelSql = textField { byAccessibleName("SQL statement") }
                parallelSql.shouldBe("The second source has an independent statement field", { text.isEmpty() }, 1.minutes)
                parallelSql.text = "SELECT 99 AS independent_session"
                button { byVisibleText("Run SQL") }.click()
                x { contains(byVisibleText("Page 1 · 1 rows")) }
                    .shouldBe("The second source finishes while the first is busy", present, 1.minutes)
                val parallelTable = driver.cast(x { byAccessibleName("Database query results") }.component, QueryGrid::class)
                assertEquals("99", parallelTable.getValueAt(0, 0))
                selectSource(picker, "Owned SQLite")
                val stop = button { byVisibleText("Stop") }
                stop.shouldBe("The first request is still running after the second completes", { isEnabled() }, 1.minutes)
                stop.click()
                x { contains(byVisibleText("Database request stopped.")) }
                    .shouldBe("The owned request stops", present, 1.minutes)
                button { byVisibleText("Disconnect") }.click()
                x { byVisibleText("Disconnected.") }.shouldBe("The data source releases its connection", present, 1.minutes)
                selectSource(picker, "Parallel SQLite")
                assertEquals("99", parallelTable.getValueAt(0, 0))
                button { byVisibleText("Disconnect") }.click()
                x { byVisibleText("Disconnected.") }.shouldBe("The second source releases its own connection", present, 1.minutes)
            }
        }
        assertArrayEquals(original, Files.readAllBytes(database))
        VmIde.assertNoConsentCrash()
    }

    @Test
    fun stagesAKeyedEditAndCommitsItExplicitly() {
        val driverJar = VmIde.fixtures.resolve("sqlite/driver.jar")
        require(Files.isRegularFile(driverJar)) { "Supply SQLITE_DRIVER when building the VM suite" }
        val project = VmIde.project("sqlite editing")
        val database = seed(project)
        VmIde.context("sqlite-editing", project).runIdeWithDriver(runTimeout = 16.minutes).useDriverAndCloseIde {
            openDatabases()
            ideFrame { button { byVisibleText("Add SQLite Source") }.click() }
            ui.dialog({ byTitle("Add SQLite Data Source") }) {
                textField { byAccessibleName("Data source name") }.text = "Writable SQLite"
                textField { byAccessibleName("SQLite database file") }.text = database.toString()
                textField { byAccessibleName("SQLite JDBC JAR") }.text = driverJar.toString()
                checkBox { contains(byVisibleText("Allow writes")) }.check()
                pressButton("Save")
            }
            ideFrame {
                button { byVisibleText("Connect") }.click()
                x { contains(byVisibleText("Connected in read-write mode.")) }
                    .shouldBe("The writable profile connects", present, 1.minutes)
                accessibleTree { byAccessibleName("Database schema objects") }.doubleClickPath("main", "Tables", "samples (table)")
                val editorRows = x { byAccessibleName("Table editor rows") }
                editorRows.shouldBe("The table opens in a native editor", present, 1.minutes)
                val editInDatabases = button { byVisibleText("Edit in Databases") }
                editInDatabases.shouldBe("The table editor uses the session's reviewed editing path", { isEnabled() }, 1.minutes)
                editInDatabases.click()
                x { contains(byVisibleText("samples · 200 rows")) }
                    .shouldBe("The first table page opens", present, 1.minutes)
                val where = textField { byAccessibleName("Table WHERE expression") }
                val order = textField { byAccessibleName("Table ORDER BY expression") }
                where.text = "id >= 3"
                order.text = "id DESC"
                button { byVisibleText("Reload Table") }.click()
                x { contains(byVisibleText("Filtered rows are read-only.")) }
                    .shouldBe("Filters open a read-only table view", present, 1.minutes)
                val grid = x { byAccessibleName("Database table data") }
                val table = driver.cast(grid.component, QueryGrid::class)
                assertEquals("205", table.getValueAt(0, 1))
                button { byVisibleText("Next Table Page") }.click()
                x { contains(byVisibleText("samples · 3 rows · Page 2")) }
                    .shouldBe("Filtered rows have their own next page", present, 1.minutes)
                assertEquals("5", table.getValueAt(0, 1))
                where.text = ""
                order.text = ""
                button { byVisibleText("Reload Table") }.click()
                x { contains(byVisibleText("samples · 200 rows")) }
                    .shouldBe("Clearing filters restores an editable snapshot", present, 1.minutes)
                table.setRowSelectionInterval(0, 0)
                button { byVisibleText("Edit Row") }.click()
            }
            ui.dialog({ byTitle("Edit Table Row") }) {
                textField { byAccessibleName("Value for note") }.text = "owned updated row"
                pressButton("Stage")
            }
            ideFrame {
                x { byVisibleText("1 local row drafts.") }.shouldBe("The edit is a local draft", present, 1.minutes)
                button { byVisibleText("Review & Apply") }.click()
            }
            ui.dialog({ byTitle("Review Table Changes") }) {
                val review = x { byAccessibleName("Table change preview") }.nativeDocumentText()
                check(review.contains("Key id = 1") && review.contains("sample-1") && review.contains("owned updated row"))
                pressButton("Apply Pending Changes")
            }
            ideFrame {
                x { contains(byVisibleText("1 rows staged in the transaction.")) }
                    .shouldBe("Applying does not commit", present, 1.minutes)
                button { byVisibleText("Commit") }.click()
                x { byVisibleText("Transaction committed.") }.shouldBe("Commit completes", present, 1.minutes)
                button { byVisibleText("Disconnect") }.click()
            }
        }
        val marker = project.resolve("verify-sqlite.py")
        Files.writeString(marker, """
            import sqlite3, sys
            with sqlite3.connect(sys.argv[1]) as db:
                assert db.execute("SELECT note FROM samples WHERE id=1").fetchone() == ("owned updated row",)
        """.trimIndent())
        runPython(marker, database)
        VmIde.assertNoConsentCrash()
    }

    private fun Driver.selectSource(picker: JComboBoxUiComponent, name: String) {
        val index = picker.listValues().indexOf(name)
        check(index >= 0) { "The source picker does not contain $name" }
        val selection = cast(picker.component, NativeDatabaseSourcePicker::class)
        withContext(OnDispatcher.EDT) { selection.setSelectedIndex(index) }
        picker.shouldBe("The source picker selects $name", { picker.getSelectedItem() == name }, 1.minutes)
    }

    private fun Driver.openDatabases() {
        waitForPythonIndexing(10.minutes)
        val action = "ActivateKaryoForgeDatabasesToolWindow"
        val actions = service<ActionManager>()
        awaitCondition("The Databases tool window action is registered", 3.minutes) { actions.getAction(action) != null }
        invokeAction(action)
        ideFrame {
            button { byVisibleText("Add SQLite Source") }.shouldBe("The Databases panel is visible", present, 1.minutes)
        }
        val window = requireNotNull(getToolWindow("KaryoForge Databases"))
        val dock = cast(window, ResizableDatabaseToolWindow::class)
        withContext(OnDispatcher.EDT) { dock.stretchHeight(240) }
    }

    private fun seed(project: java.nio.file.Path): java.nio.file.Path {
        val database = project.resolve("test rows #1.sqlite")
        val seedFile = project.resolve("seed-sqlite.py")
        Files.writeString(seedFile, """
            import sqlite3, sys
            with sqlite3.connect(sys.argv[1]) as db:
                db.execute("CREATE TABLE samples (id INTEGER PRIMARY KEY, note TEXT)")
                db.executemany("INSERT INTO samples VALUES (?, ?)",
                               ((i, f"sample-{i}") for i in range(1, 206)))
        """.trimIndent())
        runPython(seedFile, database)
        return database
    }

    private fun runPython(script: java.nio.file.Path, database: java.nio.file.Path) {
        val process = ProcessBuilder(VmIde.python.toString(), script.toString(), database.toString())
            .redirectErrorStream(true).start()
        try {
            check(process.waitFor(30, TimeUnit.SECONDS)) { "The owned SQLite fixture process timed out" }
            check(process.exitValue() == 0) { process.inputStream.bufferedReader().readText() }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

@Remote("javax.swing.JComboBox")
private interface NativeDatabaseSourcePicker {
    fun setSelectedIndex(index: Int)
}

@Remote("com.intellij.openapi.wm.ex.ToolWindowEx")
private interface ResizableDatabaseToolWindow {
    fun stretchHeight(delta: Int)
}

@Remote("javax.swing.JTable")
interface QueryGrid {
    fun setRowSelectionInterval(start: Int, end: Int)
    fun getValueAt(row: Int, column: Int): String
}
