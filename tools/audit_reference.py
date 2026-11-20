#!/usr/bin/env python3
"""Inventory a separately supplied reference checkout without importing its code.

Uses only the Python standard library. Output contains relative source paths,
registration metadata and hashes, never Git history or user configuration.
This is a static audit; it neither launches an IDE nor certifies runtime behavior.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET


PUBLIC_NAMESPACE = "org.openkaryon.karyoforge"


def public_aliases(value, source_namespace: str):
    """Project source identifiers to public aliases; retain original-byte hashes."""
    if isinstance(value, str):
        return value.replace(source_namespace, PUBLIC_NAMESPACE).replace(
            source_namespace.replace(".", "/"), PUBLIC_NAMESPACE.replace(".", "/"))
    if isinstance(value, list):
        return [public_aliases(item, source_namespace) for item in value]
    if isinstance(value, dict):
        return {key: public_aliases(item, source_namespace) for key, item in value.items()}
    return value


COMMON_EDITORS = {
    "BiologicalFileEditorProvider", "GenomeFileEditorProvider",
    "VariantFilterEditorProvider", "NotebookFileEditorProvider",
}
MODULE_ACTIONS = {
    "OpenKaryon.Databases": "databases",
    "OpenKaryon.NewStudy": "bioinformatics",
    "OpenKaryon.ClaudeVoice": "console-agents",
    "OpenKaryon.OpenOfficialClaude": "console-agents",
    "OpenKaryon.Agents": "console-agents",
    "OpenKaryon.Agent.Claude": "console-agents",
    "OpenKaryon.Agent.Codex": "console-agents",
    "OpenKaryon.Agent.Pi": "console-agents",
}
CONTEXT_ACTIONS = {
    "OpenKaryon.ExplainSql", "OpenKaryon.RunSql",
    "OpenKaryon.WorkspaceLanguageRecommendations",
    "OpenKaryon.ConfigurePythonInterpreter", "OpenKaryon.OpenPipelineDag",
}
COMMON_ACTIONS = {
    "OpenKaryon.Tools", "OpenKaryon.WorkflowPalette",
    "OpenKaryon.SetUpProject", "OpenKaryon.NewNotebook",
}
LANGUAGE_KINDS = {
    "server", "fileNamePatternMapping", "languageMapping", "fileType",
    "lang.parserDefinition", "lang.syntaxHighlighterFactory",
    "completion.contributor", "gotoDeclarationHandler", "textmate.bundleProvider",
    "lang.psiStructureViewFactory", "lang.commenter", "lang.braceMatcher",
}
INFRA_KINDS = {
    "programRunner", "notificationGroup", "lang.inspectionSuppressor",
    "buildSettingsInitializer", "editorTabTitleProvider",
}
ATTRIBUTES = {
    "id", "name", "language", "extensions", "fileNames", "class",
    "implementation", "implementationClass", "instance", "factoryClass",
    "serverId", "patterns", "languageId", "topic", "interface", "dynamic",
    "displayName", "parentId", "order", "anchor", "secondary", "fieldName",
    "documentMatcher", "config-file", "optional",
}
OWNER_ATTRIBUTES = (
    "implementation", "implementationClass", "class", "instance", "factoryClass",
    "interface", "documentMatcher",
)


def policy(kind: str, attrs: dict[str, str], identity: str) -> tuple[str, str]:
    """Classify intended ownership, not a claim of an implemented resolver."""
    owner = next((attrs[key] for key in OWNER_ATTRIBUTES if key in attrs), "")
    leaf = owner.rsplit(".", 1)[-1]
    if kind == "depends":
        return "dependency", "Installed provider; selection cannot unload it."
    if kind in {"listener", "extensionPoint"}:
        return "infrastructure", "Preserve reversible defaults; audit early loading."
    if kind in {"action", "group"}:
        if identity in MODULE_ACTIONS:
            return "module:" + MODULE_ACTIONS[identity], "Explicit module/use; retain active controls."
        if identity in CONTEXT_ACTIONS:
            return "contextual", "Explicit file/language/workflow use; no persisted module change."
        if identity in COMMON_ACTIONS:
            return "common", "Shared entry; filter its children through the resolver."
        raise ValueError(f"Unclassified action: {identity}")
    if kind == "toolWindow":
        if "ComingSoon" in owner:
            return "remove", "Do not migrate placeholder registrations."
        modules = {"Database": "databases", "Agent": "console-agents", "Remote": "remote-hpc"}
        for prefix, module in modules.items():
            if leaf.startswith(prefix):
                return "module:" + module, "Explicit module/use or active-operation management."
        if leaf == "PipelineDagToolWindowFactory":
            return "contextual", "Explicit DOT action; no unconditional stripe."
    if kind in {"applicationConfigurable", "projectConfigurable"}:
        if leaf in {"OpenKaryonConfigurable", "ProjectImportConfigurable"}:
            return "common", "Shared defaults/local project settings; remove generic Python prose."
        if leaf == "DatabaseProfilesConfigurable":
            return "module:databases", "Application-scoped provider; preserve profiles."
        if leaf == "RemoteHostsConfigurable":
            return "module:remote-hpc", "Application-scoped provider; preserve hosts."
        if leaf in {"LanguagePacksConfigurable", "NextflowLanguageConfigurable"}:
            return "language-focus", "Selected language/workflow; application settings stay stable."
    if kind == "fileEditorProvider":
        if leaf in COMMON_EDITORS:
            return "common-file", "Keep registered in every role; load on matching file use."
        if leaf.startswith("DatabaseEditorProviders$"):
            return "contextual:databases", "Explicit console/table or retained active work."
    if kind in {"fileTypeOverrider", "fileIconProvider"}:
        if ".database." in owner:
            return "contextual:databases", "Explicit database virtual files."
        if ".languages." in owner:
            return "language-focus", "Passive file ownership; no runtime launch."
        return "common-file", "Passive file handling; never role-gate common viewers."
    if kind == "editorNotificationProvider":
        return "contextual", "Python files only; merge with explicit setup."
    if kind == "postStartupActivity":
        return "infrastructure", "Defaults or bounded suggestions; no module auto-selection."
    if kind == "configurationType":
        return "contextual", "Preserve saved run data; offer creation for selected workflow."
    if kind in LANGUAGE_KINDS:
        return "language-focus", "Preserve passive editing; separate optional provider linkage."
    if kind in INFRA_KINDS:
        return "infrastructure", "Preserve existing behavior and scoped ownership."
    raise ValueError(f"Unclassified registration: {kind} {identity}")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig")


def fingerprint(root: Path, path: Path) -> dict:
    data = path.read_bytes()
    return {"path": path.relative_to(root).as_posix(),
            "sha256": hashlib.sha256(data).hexdigest()}


def inventory(root: Path) -> dict:
    meta = root / "src/main/resources/META-INF"
    main = meta / "plugin.xml"
    if not main.is_file():
        raise ValueError("--source must be the reference IDE root containing plugin.xml")
    source_namespace = ET.fromstring(read(main)).findtext("id", "").strip()
    if not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*", source_namespace):
        raise ValueError("Reference plugin ID must declare its source namespace")
    registrations, dependencies, descriptors = [], [], []
    visited: set[Path] = set()

    def visit(path: Path) -> None:
        path = path.resolve()
        if path in visited:
            return
        if path.parent != meta.resolve():
            raise ValueError("Optional descriptor escapes META-INF")
        visited.add(path)
        descriptor = ET.fromstring(read(path))
        descriptors.append(fingerprint(root, path))
        for node in descriptor.findall("depends"):
            entry = {"id": (node.text or "").strip(),
                     "optional": node.get("optional") == "true",
                     "descriptor": node.get("config-file", "")}
            dependencies.append(entry)
            if entry["descriptor"]:
                visit(meta / entry["descriptor"])
        groups = [("extensions", "com.intellij"), ("applicationListeners", "listener"),
                  ("extensionPoints", "extensionPoint")]
        for group_name, default_ns in groups:
            for group in descriptor.findall(group_name):
                for node in group:
                    append(node, path, group.get("defaultExtensionNs", default_ns), "")
        for group in descriptor.findall("actions"):
            def walk(parent: ET.Element, parent_id: str) -> None:
                for node in parent:
                    if node.tag in {"action", "group"}:
                        append(node, path, "actions", parent_id)
                        walk(node, node.get("id", parent_id))
            walk(group, "")

    def append(node: ET.Element, path: Path, namespace: str, parent: str) -> None:
        attrs = {key: value for key, value in sorted(node.attrib.items()) if key in ATTRIBUTES}
        identity = node.get("id") or next((node.get(key) for key in OWNER_ATTRIBUTES if node.get(key)), "")
        identity = identity or node.get("name") or node.get("language") or node.tag
        planned_policy, rule = policy(node.tag, attrs, identity)
        entry = {"descriptor": path.name, "namespace": namespace,
                 "kind": node.tag, "identity": identity, "owner": next(
                     (attrs[key] for key in OWNER_ATTRIBUTES if key in attrs), "platform descriptor"),
                 "policy": planned_policy, "rule": rule, "attributes": attrs}
        if parent:
            entry["parentGroup"] = parent
        placements = [dict(sorted(child.attrib.items())) for child in node.findall("add-to-group")]
        shortcuts = [dict(sorted(child.attrib.items())) for child in node.findall("keyboard-shortcut")]
        if placements:
            entry["placements"] = placements
        if shortcuts:
            entry["shortcuts"] = shortcuts
        registrations.append(entry)

    visit(main)
    java_root = root / "src/main/java"
    coupling, persistence, runtime_sites, dynamic_sites = [], [], [], []
    relevant: set[Path] = set(visited)
    relevant.update(root / name for name in (
        "gradle.properties", "build.gradle.kts", "settings.gradle.kts",
        "gradle/wrapper/gradle-wrapper.properties") if (root / name).is_file())
    import_pattern = re.compile(r"^import (com\.jetbrains\.python\.[\w.]+|com\.redhat\.devtools\.lsp4ij\.[\w.]+|org\.jetbrains\.r\.[\w.]+);")
    runtime_pattern = re.compile(r"\b(ProcessBuilder|GeneralCommandLine|HttpClient|WebSocket|Socket|OSProcessStreamConnectionProvider)\b")
    dynamic_pattern = re.compile(r"\b(registerToolWindow|unregisterToolWindow|registerAction|unregisterAction)\s*\(")
    for path in sorted(java_root.rglob("*.java")):
        relative = path.relative_to(root).as_posix()
        source = read(path)
        for number, line in enumerate(source.splitlines(), 1):
            match = import_pattern.match(line)
            if match:
                coupling.append({"path": relative, "line": number, "symbol": match[1]})
                relevant.add(path)
            if not line.lstrip().startswith("import "):
                for symbol in sorted(set(runtime_pattern.findall(line))):
                    runtime_sites.append({"path": relative, "line": number, "symbol": symbol})
                    relevant.add(path)
            if dynamic_pattern.search(line):
                dynamic_sites.append({"path": relative, "line": number})
                relevant.add(path)
        if "@State(" in source:
            state_match = re.search(r'@State\(name\s*=\s*"([^"]+)"', source)
            storages = re.findall(r'@Storage\((?:value\s*=\s*)?("[^"]+"|StoragePathMacros\.WORKSPACE_FILE)', source)
            persistence.append({"path": relative, "component": state_match[1] if state_match else "REVIEW",
                                "scope": "project" if "Service.Level.PROJECT" in source else "application",
                                "storage": [value.strip('"') for value in storages]})
            relevant.add(path)
    properties = dict(re.findall(r"^([A-Za-z][A-Za-z0-9]*)=(.*)$", read(root / "gradle.properties"), re.M))
    pins = {key: value for key, value in properties.items() if key.endswith("Version") or key in {"pluginSinceBuild", "pluginUntilBuild"}}
    for entry in registrations:
        owner = entry["owner"].split("$", 1)[0]
        candidate = java_root / (owner.replace(".", "/") + ".java")
        if candidate.is_file():
            relevant.add(candidate)
    result = {"schemaVersion": 2, "evidence": "static-reference-audit; no runtime acceptance",
            "identifierProjection": {"publicNamespace": PUBLIC_NAMESPACE,
                                     "paths": "namespace aliases, not public implementation paths",
                                     "hashes": "unmodified reference file bytes",
                                     "sourceNamespace": "derived locally from the supplied plugin descriptor"},
            "platformPins": pins, "descriptors": sorted(descriptors, key=lambda item: item["path"]),
            "dependencies": sorted(dependencies, key=lambda item: item["id"]),
            "registrations": registrations, "directProviderImports": coupling,
            "persistentComponents": persistence, "runtimeConstructionSites": runtime_sites,
            "dynamicRegistrationSites": dynamic_sites,
            "sourceFingerprints": [fingerprint(root, path) for path in sorted(relevant)]}
    return public_aliases(result, source_namespace)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check", action="store_true", help="Compare with existing output without rewriting it")
    args = parser.parse_args()
    try:
        result = inventory(args.source.resolve())
        serialized = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
        if args.check:
            if read(args.output) != serialized:
                print("Reference inventory differs; review and regenerate deliberately.", file=sys.stderr)
                return 1
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(serialized, encoding="utf-8", newline="\n")
        print(f"Audited {len(result['registrations'])} registrations, "
              f"{len(result['persistentComponents'])} persistent components; "
              f"{len(result['dynamicRegistrationSites'])} dynamic registration candidates.")
        return 0
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"Audit failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
