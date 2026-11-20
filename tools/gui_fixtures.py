#!/usr/bin/env python3
"""Prepare small public Python GUI fixtures and pinned offline wheels under build/."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/ui-test-fixtures"


def wheel(folder: Path, version: str) -> None:
    name = "karyoforge_gui_fixture"
    info = f"{name}-{version}.dist-info"
    entries = {
        name + ".py": f"VALUE = {1 if version == '1.0.0' else 2}\n",
        info + "/METADATA": f"Metadata-Version: 2.1\nName: karyoforge-gui-fixture\nVersion: {version}\n",
        info + "/WHEEL": "Wheel-Version: 1.0\nGenerator: KaryoForge GUI fixture\nRoot-Is-Purelib: true\nTag: py3-none-any\n",
    }
    record = info + "/RECORD"
    entries[record] = "".join(path + ",,\n" for path in [*entries, record])
    with zipfile.ZipFile(folder / f"{name}-{version}-py3-none-any.whl", "w") as archive:
        for path, content in entries.items():
            entry = zipfile.ZipInfo(path, date_time=(2026, 1, 1, 0, 0, 0))
            archive.writestr(entry, content)


def prepare() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    wheels = OUTPUT / "wheelhouse"
    wheels.mkdir(exist_ok=True)
    environment = {name: value for name, value in os.environ.items()
                   if not name.upper().startswith(("PIP_", "PYTHON"))}
    environment["PIP_CONFIG_FILE"] = os.devnull
    subprocess.run([sys.executable, "-I", "-m", "pip", "--isolated", "--disable-pip-version-check",
                    "--no-input", "download", "--only-binary=:all:", "--index-url", "https://pypi.org/simple",
                    "--timeout", "20", "--retries", "2", "--dest", str(wheels),
                    "-r", str(ROOT / "tools/python-test-requirements.txt")], env=environment, check=True)
    wheel(wheels, "1.0.0")
    wheel(wheels, "2.0.0")
    (OUTPUT / "requirements.txt").write_text("--no-index\n--find-links=wheelhouse\npytest==9.0.2\nkaryoforge-gui-fixture==1.0.0\n", encoding="utf-8")
    (OUTPUT / "upgrade.txt").write_text("--no-index\n--find-links=wheelhouse\nkaryoforge-gui-fixture==2.0.0\n", encoding="utf-8")
    (OUTPUT / "bad-requirements.txt").write_text("--no-index\n./missing-owned-wheel.whl\n", encoding="utf-8")
    backend = OUTPUT / "build_backend"
    backend.mkdir(exist_ok=True)
    shutil.copyfile(ROOT / "src/test/resources/python/local_build_backend.py", backend / "local_backend.py")
    (OUTPUT / "pyproject.toml").write_text('''[build-system]
requires = []
build-backend = "local_backend"
backend-path = ["build_backend"]
[project]
name = "karyoforge-project-fixture"
version = "1.0.0"
[project.optional-dependencies]
validation = ["pip"]
''', encoding="utf-8")
    source = OUTPUT / "src"
    source.mkdir(exist_ok=True)
    (source / "karyoforge_project_fixture.py").write_text("VALUE = 'original source'\n", encoding="utf-8")
    manifest = {}
    for file in sorted(OUTPUT.rglob("*")):
        if file.is_file() and file.name != "manifest.json":
            manifest[file.relative_to(OUTPUT).as_posix()] = hashlib.sha256(file.read_bytes()).hexdigest()
    (OUTPUT / "manifest.json").write_text(json.dumps({"owner": "org.openkaryon.karyoforge.uitest", "files": manifest}, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared offline Python GUI fixtures: {OUTPUT}")


if __name__ == "__main__":
    prepare()
