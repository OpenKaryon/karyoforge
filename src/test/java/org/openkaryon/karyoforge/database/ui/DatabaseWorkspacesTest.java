package org.openkaryon.karyoforge.database.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.openkaryon.karyoforge.database.core.ResultLimits;
import org.openkaryon.karyoforge.database.core.SqliteSource;

import javax.accessibility.Accessible;
import javax.swing.JComboBox;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;

public final class DatabaseWorkspacesTest extends BasePlatformTestCase {
    public void testSwitchingSourcesKeepsIndependentEditorsAndRemembersTheSelection() {
        var sources = ApplicationManager.getApplication().getService(DatabaseSources.class);
        var baseline = sources.getState();
        var projectHome = Path.of(getProject().getBasePath());
        try {
            sources.loadState(new DatabaseSources.Snapshot());
            sources.save(null, new SqliteSource("First source", projectHome.resolve("first.sqlite"), projectHome.resolve("driver.jar")));
            sources.save(null, new SqliteSource("Second source", projectHome.resolve("second.sqlite"), projectHome.resolve("driver.jar")));
            var panel = new DatabasesPanel(getProject());
            try {
                var picker = find(panel, JComboBox.class, "Database data sources");
                var first = find(panel, JTextArea.class, "SQL statement");
                first.setText("SELECT 17");
                picker.setSelectedIndex(1);
                var second = find(panel, JTextArea.class, "SQL statement");
                assertNotSame(first, second);
                second.setText("SELECT 42");
                picker.setSelectedIndex(0);
                assertEquals("SELECT 17", find(panel, JTextArea.class, "SQL statement").getText());
                picker.setSelectedIndex(1);
                assertEquals("SELECT 42", find(panel, JTextArea.class, "SQL statement").getText());
            } finally {
                panel.dispose();
            }

            var reopened = new DatabasesPanel(getProject());
            try {
                assertEquals("Second source", find(reopened, JComboBox.class, "Database data sources").getSelectedItem().toString());
            } finally {
                reopened.dispose();
            }
        } finally {
            sources.loadState(baseline);
        }
    }

    public void testReloadsSavedLimitsAndDefaultsForEarlierProfiles() {
        var sources = new DatabaseSources();
        var home = Path.of(getProject().getBasePath());
        var source = new SqliteSource("Owned source", home.resolve("fixture.sqlite"), home.resolve("driver.jar"));
        String current = sources.save(null, source);
        String previous = sources.save(null, source);
        var limits = new ResultLimits(75, 350, 2048, 32, 32, 40);
        sources.saveLimits(current, limits);
        sources.save(current, new SqliteSource("Renamed", source.database(), source.driver()));
        var reopened = new DatabaseSources();
        reopened.loadState(sources.getState());
        assertEquals(limits, reopened.profiles().stream().filter(profile -> profile.id().equals(current)).findFirst().orElseThrow().limits());
        assertEquals(ResultLimits.DEFAULT,
                reopened.profiles().stream().filter(profile -> profile.id().equals(previous)).findFirst().orElseThrow().limits());
    }

    private static <T extends Component & Accessible> T find(Component component, Class<T> type, String name) {
        if (type.isInstance(component) && name.equals(type.cast(component).getAccessibleContext().getAccessibleName())) {
            return type.cast(component);
        }
        if (component instanceof Container container) {
            for (var child : container.getComponents()) {
                T result = find(child, type, name);
                if (result != null) return result;
            }
        }
        return null;
    }
}
