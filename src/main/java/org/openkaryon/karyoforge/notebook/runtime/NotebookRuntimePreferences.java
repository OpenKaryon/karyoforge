package org.openkaryon.karyoforge.notebook.runtime;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.TreeMap;

/** Per-file local choices belong to the workspace, never portable notebook metadata. */
@Service(Service.Level.PROJECT)
@State(name = "org.openkaryon.karyoforge.notebook.runtime", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class NotebookRuntimePreferences implements PersistentStateComponent<NotebookRuntimePreferences.Snapshot> {
    public static final String NONE = "<none>";
    private final Map<String, String> paths = new TreeMap<>();

    /** Null follows the owning module; NONE explicitly disables execution. */
    public synchronized String selection(String url) { return paths.get(url); }

    public synchronized void select(String url, String path) {
        if (path == null) paths.remove(url);
        else paths.put(url, path);
    }

    @Override public synchronized @NotNull Snapshot getState() {
        var result = new Snapshot();
        result.paths.putAll(paths);
        return result;
    }

    @Override public synchronized void loadState(@NotNull Snapshot state) {
        paths.clear();
        if (state.paths != null) state.paths.forEach((url, path) -> {
            if (url != null && path != null) paths.put(url, path);
        });
    }

    public static final class Snapshot {
        public Map<String, String> paths = new TreeMap<>();
    }
}
