package org.openkaryon.karyoforge.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/** Application preferences that overlay editing behavior without changing project profiles. */
@Service(Service.Level.APP)
@State(name = "org.openkaryon.karyoforge.editor", storages = @Storage("karyoforge-editor.xml"))
public final class EditorPreferences implements PersistentStateComponent<EditorPreferences.Snapshot> {
    private volatile boolean spellingEnabled;

    public static EditorPreferences getInstance() {
        return ApplicationManager.getApplication().getService(EditorPreferences.class);
    }

    public boolean isSpellingEnabled() {
        return spellingEnabled;
    }

    public void setSpellingEnabled(boolean enabled) {
        spellingEnabled = enabled;
    }

    @Override
    public @NotNull Snapshot getState() {
        var snapshot = new Snapshot();
        snapshot.spellingEnabled = spellingEnabled;
        return snapshot;
    }

    @Override
    public void loadState(@NotNull Snapshot snapshot) {
        spellingEnabled = snapshot.spellingEnabled;
    }

    public static final class Snapshot {
        public boolean spellingEnabled;
    }
}
