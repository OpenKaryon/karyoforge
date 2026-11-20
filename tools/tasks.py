#!/usr/bin/env python3
"""Portable Make targets with explicit prerequisites and controlled dispatch."""

from __future__ import annotations

import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
NAMESPACE = "org.openkaryon.karyoforge"
WRAPPER_SHA256 = "76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3"
IGNORED = {".git", ".idea", ".gradle", ".intellijPlatform", ".venv",
           "__pycache__", "build", "out", "work", "outputs"}
HELP = """KaryoForge local commands:
  make lint                     Python syntax, documentation links and text checks
  make test                     Audit/development-tool unit tests
  make audit                    Inventory integrity and public namespace policy
  make check                    Lint, tool tests and public audit
  make audit-reference REFERENCE_DIR="/path/to/reference-ide"
                                Regenerate the namespace-projected reference audit
  make audit-reference-check REFERENCE_DIR="/path/to/reference-ide"
                                Check reference drift without rewriting the report
  make build                    Gradle buildPlugin and verifyPluginStructure
  make verify                   Strict Gradle plugin compatibility verification
  make test-ide                 Gradle IDE integration tests
  make test-python PYTHON_EXECUTABLE="/path/to/python"
                                Native script, selected unittest/pytest and debugger checks
  make run                      Launch a separate development IDE profile
  make package-windows          Windows installer script
  make package-linux            Linux package script

Set PYTHON to the desired Python executable. IDE targets require their checked-in
build definition, wrapper and packaging scripts; missing prerequisites fail.
No target commits or pushes changes. Gradle may resolve declared dependencies.
"""


def source_files(root: Path):
    for folder, directories, filenames in os.walk(root, followlinks=False):
        directories[:] = sorted(name for name in directories if name not in IGNORED)
        for name in sorted(filenames):
            path = Path(folder) / name
            if path.is_symlink():
                raise ValueError(f"Source symlink requires review: {path.relative_to(root)}")
            yield path


def lint(root: Path) -> None:
    checked = 0
    for path in source_files(root):
        if path.suffix not in {".py", ".md", ".json", ".java", ".kt", ".kts", ".xml", ".properties"} and path.name not in {
                "Makefile", "NOTICE", ".gitattributes", ".gitignore"}:
            continue
        text = path.read_text(encoding="utf-8-sig")
        label = path.relative_to(root)
        if any(line.endswith((" ", "\t")) for line in text.splitlines()):
            raise ValueError(f"Trailing whitespace: {label}")
        if not text.endswith("\n"):
            raise ValueError(f"Missing final newline: {label}")
        if path.suffix == ".py":
            ast.parse(text, filename=str(label))
        if path.suffix == ".json":
            json.loads(text)
        if path.suffix == ".xml":
            ET.fromstring(text)
        if path.suffix == ".md":
            if re.search(r"\b(?:[PB]\d+(?:\.\d+)?|C[1-4](?:-C?[1-4])?)\b", text):
                raise ValueError(f"Delivery-stage code in documentation: {label}")
            if "PLAN" in path.stem.upper() or "ROADMAP" in path.stem.upper():
                raise ValueError(f"Delivery plans belong outside product documentation: {label}")
            for destination in re.findall(r"\[[^\]]+\]\(([^)]+)\)", text):
                if destination.startswith(("https://", "http://", "#", "mailto:")):
                    continue
                target = (path.parent / destination.split("#", 1)[0]).resolve()
                if not target.is_relative_to(root.resolve()) or not target.is_file():
                    raise ValueError(f"Invalid local documentation link: {label}: {destination}")
        checked += 1
    print(f"Lint passed for {checked} source/documentation files.")


def audit(root: Path) -> None:
    path = root / "docs/audit/reference-inventory.json"
    report = json.loads(path.read_text(encoding="utf-8"))
    if report.get("schemaVersion") != 2:
        raise ValueError("Unsupported audit inventory schema")
    projection = report.get("identifierProjection", {})
    if projection.get("publicNamespace") != NAMESPACE:
        raise ValueError("Inventory must disclose its public namespace projection")
    identities = set()
    for row in report["registrations"]:
        identity = tuple(row[key] for key in ("descriptor", "namespace", "kind", "identity")) + (
            json.dumps(row.get("attributes", {}), sort_keys=True),)
        if identity in identities or not all(row.get(key) for key in ("owner", "policy", "rule")):
            raise ValueError("Duplicate or unclassified inventory registration")
        identities.add(identity)
    for row in report["sourceFingerprints"]:
        relative = row["path"]
        if (relative.startswith(("/", "\\")) or ".." in Path(relative).parts
                or ":" in relative or not re.fullmatch(r"[0-9a-f]{64}", row["sha256"])):
            raise ValueError("Invalid reference fingerprint")
    descriptor = root / "src/main/resources/META-INF/plugin.xml"
    if descriptor.is_file():
        plugin = ET.fromstring(descriptor.read_text(encoding="utf-8"))
        if plugin.findtext("id") != NAMESPACE or plugin.findtext("vendor") != "OpenKaryon":
            raise ValueError("Public plugin identity or vendor does not match project conventions")
        verify_wrapper(root)
    for path in source_files(root):
        if path.suffix not in {".py", ".md", ".json", ".java", ".kt", ".xml", ".kts"}:
            continue
        text = path.read_text(encoding="utf-8-sig")
        if re.search(r"\bru\.[\w.]*openkaryon\b|ru/[\w/]*openkaryon/", text):
            raise ValueError(f"Unprojected project namespace: {path.relative_to(root)}")
        if path.suffix in {".java", ".kt"}:
            package = re.search(r"^package\s+([\w.]+)", text, re.M)
            if package and not (package[1] == NAMESPACE or package[1].startswith(NAMESPACE + ".")):
                raise ValueError(f"Unexpected project package: {path.relative_to(root)}")
    print(f"Public audit passed: {len(identities)} classified registrations; namespace {NAMESPACE}.")


def verify_wrapper(root: Path) -> None:
    wrapper = root / "gradle/wrapper/gradle-wrapper.jar"
    if not wrapper.is_file() or hashlib.sha256(wrapper.read_bytes()).hexdigest() != WRAPPER_SHA256:
        raise ValueError("Gradle wrapper JAR does not match the verified official Gradle 9.1.0 artifact")


def gradle_command(root: Path, tasks: list[str]) -> list[str]:
    wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    required = [root / "build.gradle.kts", root / "settings.gradle.kts", wrapper,
                root / "gradle/wrapper/gradle-wrapper.jar",
                root / "gradle/wrapper/gradle-wrapper.properties"]
    missing = [path.relative_to(root).as_posix() for path in required if not path.is_file()]
    if missing:
        raise ValueError("IDE build is unavailable; missing: " + ", ".join(missing))
    verify_wrapper(root)
    return ([str(wrapper)] if os.name == "nt" else ["sh", str(wrapper)]) + [
        "--no-daemon", "--console=plain", *tasks]


def execute(task: str, root: Path, reference: str = "", output: str = "", python_executable: str = "") -> int:
    if task == "help":
        print(HELP)
        return 0
    if task == "lint":
        lint(root)
        return 0
    if task == "audit":
        audit(root)
        return 0
    if task == "test":
        command = [sys.executable, "-m", "unittest", "discover", "-s", "tools/tests", "-v"]
    elif task in {"audit-reference", "audit-reference-check"}:
        if not reference.strip():
            raise ValueError("REFERENCE_DIR is required; supply an authorized reference IDE checkout")
        command = [sys.executable, "tools/audit_reference.py", "--source", reference,
                   "--output", output or "docs/audit/reference-inventory.json"]
        if task.endswith("-check"):
            command.append("--check")
    elif task == "test-python":
        if not python_executable.strip():
            raise ValueError("PYTHON_EXECUTABLE is required; select an owned Python runtime with pytest installed")
        selected = Path(python_executable).expanduser()
        if not selected.is_absolute():
            selected = root / selected
        selected = selected.resolve()
        if not selected.is_file():
            raise ValueError("PYTHON_EXECUTABLE must name an existing interpreter file")
        command = gradle_command(root, ["test", "-PpythonRuntimeTests=true", f"-PpythonExecutable={selected}"])
    elif task in {"build", "verify", "test-ide", "run"}:
        tasks = {"build": ["buildPlugin", "verifyPluginStructure"],
                 "verify": ["verifyPlugin"], "test-ide": ["test"], "run": ["runIde"]}[task]
        command = gradle_command(root, tasks)
    elif task in {"package-windows", "package-linux"}:
        name = "build-installer.ps1" if task == "package-windows" else "build-linux.ps1"
        script = root / "installer" / name
        if not script.is_file():
            raise ValueError(f"Packaging is unavailable; missing installer/{name}")
        command = ["pwsh", "-NoProfile", "-File", str(script)]
    else:
        raise ValueError(f"Unknown task: {task}")
    return subprocess.run(command, cwd=root, check=False).returncode


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("task")
    parser.add_argument("--reference", default="")
    parser.add_argument("--output", default="")
    parser.add_argument("--python-executable", default="")
    args = parser.parse_args()
    try:
        return execute(args.task, ROOT, args.reference, args.output, args.python_executable)
    except (OSError, ValueError, SyntaxError, KeyError, ET.ParseError) as error:
        print(f"Task failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
