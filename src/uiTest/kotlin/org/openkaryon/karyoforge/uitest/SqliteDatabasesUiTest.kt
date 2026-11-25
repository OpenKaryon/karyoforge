package org.openkaryon.karyoforge.uitest

import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.present
import com.intellij.driver.sdk.ui.pasteText
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
        val database = project.resolve("test rows #1.sqlite")
        val seedSql = """
            import sqlite3, sys
            with sqlite3.connect(sys.argv[1]) as db:
                db.execute("CREATE TABLE samples (id INTEGER PRIMARY KEY, note TEXT)")
                db.executemany("INSERT INTO samples VALUES (?, ?)",
                               ((i, f"sample-{i}") for i in range(1, 206)))
        """.trimIndent()
        val seed = ProcessBuilder(VmIde.python.toString(), "-c", seedSql, database.toString())
            .redirectErrorStream(true).start()
        try {
            check(seed.waitFor(30, TimeUnit.SECONDS)) { "Creating the owned fixture timed out" }
            check(seed.exitValue() == 0) { seed.inputStream.bufferedReader().readText() }
        } finally {
            if (seed.isAlive) seed.destroyForcibly()
        }

        val original = Files.readAllBytes(database)
        VmIde.context("sqlite-databases", project).runIdeWithDriver(runTimeout = 16.minutes).useDriverAndCloseIde {
            waitForPythonIndexing(10.minutes)
            invokeAction("ActivateKaryoForgeDatabasesToolWindow")
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
                button { byVisibleText("Connect") }.click()
                x { contains(byVisibleText("Connected in read-only mode.")) }
                    .shouldBe("The driver opens the owned source", present, 1.minutes)
                x { byVisibleText("samples (table)") }.click()
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

                val sql = x { byAccessibleName("SQL statement") }
                sql.click()
                sql.keyboard { hotKey(KeyEvent.VK_CONTROL, KeyEvent.VK_A) }
                ui.pasteText("WITH RECURSIVE x(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM x WHERE i<1000000000) SELECT sum(i) FROM x")
                button { byVisibleText("Run SQL") }.click()
                button { byVisibleText("Stop") }.shouldBe("A running query can be stopped", { isEnabled() }, 1.minutes)
                button { byVisibleText("Stop") }.click()
                x { contains(byVisibleText("Database request stopped.")) }
                    .shouldBe("The owned request stops", present, 1.minutes)
                button { byVisibleText("Disconnect") }.click()
                x { byVisibleText("Disconnected.") }.shouldBe("The data source releases its connection", present, 1.minutes)
            }
        }
        assertArrayEquals(original, Files.readAllBytes(database))
        VmIde.assertNoConsentCrash()
    }
}

@Remote("javax.swing.JTable")
interface QueryGrid {
    fun getValueAt(row: Int, column: Int): String
}
