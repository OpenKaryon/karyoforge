package org.openkaryon.karyoforge.languages.python;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.toml.lang.psi.TomlArray;
import org.toml.lang.psi.TomlFile;
import org.toml.lang.psi.TomlInlineTable;
import org.toml.lang.psi.TomlKey;
import org.toml.lang.psi.TomlKeyValue;
import org.toml.lang.psi.TomlLiteral;
import org.toml.lang.psi.TomlTable;
import org.toml.lang.psi.TomlValue;
import org.toml.lang.psi.ext.TomlLiteralKind;
import org.toml.lang.psi.ext.TomlLiteralKt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads standard packaging metadata with the pinned TOML parser, without running project code. */
public record StandardPythonProject(Path directory, String name, List<String> extras) {
    static StandardPythonProject read(Project project, Path file) {
        if (!file.isAbsolute() || !Files.isRegularFile(file) || !"pyproject.toml".equals(file.getFileName().toString())) {
            throw new IllegalArgumentException("Choose an existing pyproject.toml file.");
        }
        var virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
        if (virtual == null) throw new IllegalArgumentException("The project metadata file is unavailable.");
        return ReadAction.compute(() -> {
            var psi = PsiManager.getInstance(project).findFile(virtual);
            if (!(psi instanceof TomlFile toml) || PsiTreeUtil.findChildOfType(psi, PsiErrorElement.class) != null) {
                throw new IllegalArgumentException("Fix TOML syntax errors before installing this project.");
            }
            return inspect(file.getParent(), toml);
        });
    }

    static StandardPythonProject inspect(Path directory, TomlFile file) {
        if (PsiTreeUtil.findChildOfType(file, PsiErrorElement.class) != null) {
            throw new IllegalArgumentException("Fix TOML syntax errors before installing this project.");
        }
        for (var literal : PsiTreeUtil.findChildrenOfType(file, TomlLiteral.class)) {
            if (TomlLiteralKt.getKind(literal) instanceof TomlLiteralKind.String text
                    && (text.getOffsets().getCloseDelim() == null || text.getOffsets().getCloseDelim().isEmpty())) {
                throw new IllegalArgumentException("Fix TOML string syntax before installing this project.");
            }
        }
        Map<List<String>, TomlValue> values = new LinkedHashMap<>();
        for (var entry : PsiTreeUtil.getChildrenOfTypeAsList(file, TomlKeyValue.class)) add(values, List.of(), entry);
        for (var table : PsiTreeUtil.getChildrenOfTypeAsList(file, TomlTable.class)) {
            var prefix = segments(table.getHeader().getKey());
            for (var entry : table.getEntries()) add(values, prefix, entry);
        }
        String name = string(values.get(List.of("project", "name")));
        String backend = string(values.get(List.of("build-system", "build-backend")));
        boolean standardBuild = backend != null && !backend.isBlank()
                && values.get(List.of("build-system", "requires")) instanceof TomlArray;
        if ((name == null || name.isBlank()) && !standardBuild) {
            throw new IllegalArgumentException("This file contains no standard Python project or build-system metadata. Tool-only TOML is not installable project metadata.");
        }
        var extras = new ArrayList<String>();
        for (var entry : values.entrySet()) {
            var key = entry.getKey();
            if (key.size() == 3 && key.get(0).equals("project") && key.get(1).equals("optional-dependencies")) {
                if (!(entry.getValue() instanceof TomlArray) || !key.get(2).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                    throw new IllegalArgumentException("Optional dependencies must use valid extra names and arrays.");
                }
                extras.add(key.get(2));
            }
        }
        return new StandardPythonProject(directory, name == null ? directory.getFileName().toString() : name,
                extras.stream().sorted().toList());
    }

    List<String> arguments(boolean editable, List<String> selectedExtras, boolean buildIsolation) {
        if (!extras.containsAll(selectedExtras)) throw new IllegalArgumentException("An extra is no longer declared by this project.");
        var arguments = new ArrayList<>(List.of("install", "--no-user"));
        if (editable) arguments.add("--editable");
        if (!buildIsolation) arguments.add("--no-build-isolation");
        String target = directory.toUri().toASCIIString();
        if (!selectedExtras.isEmpty()) {
            String suffix = "[" + String.join(",", selectedExtras) + "]";
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                throw new IllegalArgumentException("Installing extras requires a valid standard project name.");
            }
            target = editable ? target + suffix : name + suffix + " @ " + target;
        }
        arguments.add(target);
        return List.copyOf(arguments);
    }

    private static void add(Map<List<String>, TomlValue> values, List<String> prefix, TomlKeyValue entry) {
        var key = new ArrayList<>(prefix);
        key.addAll(segments(entry.getKey()));
        if (entry.getValue() instanceof TomlInlineTable inline) {
            for (var child : inline.getEntries()) add(values, key, child);
        } else if (values.putIfAbsent(List.copyOf(key), entry.getValue()) != null) {
            throw new IllegalArgumentException("Duplicate TOML keys must be fixed before installation.");
        }
    }

    private static List<String> segments(TomlKey key) {
        return key.getSegments().stream().map(segment -> segment.getName()).toList();
    }

    private static String string(TomlValue value) {
        return value instanceof TomlLiteral literal && TomlLiteralKt.getKind(literal) instanceof TomlLiteralKind.String text
                ? text.getValue() : null;
    }
}
