package org.openkaryon.karyoforge.settings;

import com.intellij.spellchecker.inspections.SpellCheckingInspection;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.xmlb.XmlSerializer;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;

public final class EditingPreferencesIntegrationTest extends BasePlatformTestCase {
    private EditorPreferences.Snapshot previous;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        previous = EditorPreferences.getInstance().getState();
        EditorPreferences.getInstance().loadState(new EditorPreferences.Snapshot());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            EditorPreferences.getInstance().loadState(previous);
        } finally {
            super.tearDown();
        }
    }

    public void testRegisteredSuppressionIsReversibleThroughTheNativeInspection() {
        myFixture.configureByText("notes.txt", "openkaryonmisspelledword");
        var element = myFixture.getFile().findElementAt(0);
        assertNotNull(element);
        var inspection = new SpellCheckingInspection();
        assertTrue(inspection.isSuppressedFor(element));
        EditorPreferences.getInstance().setSpellingEnabled(true);
        assertFalse(inspection.isSuppressedFor(element));
        EditorPreferences.getInstance().setSpellingEnabled(false);
        assertTrue(inspection.isSuppressedFor(element));
    }

    public void testOtherInspectionIdsAndSuppressionFixesRemainUntouched() {
        myFixture.configureByText("notes.txt", "text");
        var element = myFixture.getFile().findElementAt(0);
        assertNotNull(element);
        var suppressor = new SpellingInspectionSuppressor();
        for (boolean enabled : new boolean[]{false, true}) {
            EditorPreferences.getInstance().setSpellingEnabled(enabled);
            assertFalse(suppressor.isSuppressedFor(element, "UnusedDeclaration"));
            assertFalse(suppressor.isSuppressedFor(element, "UnresolvedReference"));
        }
        assertEmpty(suppressor.getSuppressActions(element, "SpellCheckingInspection"));
    }

    public void testStateRoundTripAndSnapshotsDoNotShareMutableState() {
        var preferences = EditorPreferences.getInstance();
        var snapshot = preferences.getState();
        snapshot.spellingEnabled = true;
        assertFalse(preferences.isSpellingEnabled());
        preferences.loadState(snapshot);
        snapshot.spellingEnabled = false;
        assertTrue(preferences.isSpellingEnabled());
        var serialized = XmlSerializer.serialize(preferences.getState());
        preferences.setSpellingEnabled(false);
        preferences.loadState(XmlSerializer.deserialize(serialized, EditorPreferences.Snapshot.class));
        assertTrue(preferences.isSpellingEnabled());
    }

    public void testSettingsApplyResetAndDisposalDoNotApplyDraftChanges() {
        var settings = new EditingSettingsConfigurable();
        try {
            JComponent panel = settings.createComponent();
            var checkbox = findCheckbox(panel);
            assertNotNull(checkbox);
            assertFalse(checkbox.isSelected());
            checkbox.setSelected(true);
            assertTrue(settings.isModified());
            assertFalse(EditorPreferences.getInstance().isSpellingEnabled());
            settings.reset();
            assertFalse(checkbox.isSelected());
            checkbox.setSelected(true);
            settings.apply();
            assertTrue(EditorPreferences.getInstance().isSpellingEnabled());
            assertFalse(settings.isModified());
            checkbox.setSelected(false);
        } finally {
            settings.disposeUIResources();
        }
        assertTrue(EditorPreferences.getInstance().isSpellingEnabled());
        assertFalse(settings.isModified());
    }

    private static JBCheckBox findCheckbox(Component component) {
        if (component instanceof JBCheckBox checkbox) {
            return checkbox;
        }
        if (component instanceof Container container) {
            for (var child : container.getComponents()) {
                var found = findCheckbox(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
