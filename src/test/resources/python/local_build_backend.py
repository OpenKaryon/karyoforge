"""Dependency-free PEP 517/660 acceptance backend for an owned test project.

This fixture deliberately uses only the standard library. It exercises real pip
build isolation, editable wheels, source layout and an installed optional extra
without fetching a third-party build backend or changing the owner's Python.
"""

from pathlib import Path
import zipfile


NAME = "karyoforge_project_fixture"
VERSION = "1.0.0"
DIST_INFO = f"{NAME}-{VERSION}.dist-info"


def get_requires_for_build_wheel(config_settings=None):
    return []


def get_requires_for_build_editable(config_settings=None):
    return []


def _build(wheel_directory, editable):
    project = Path(__file__).resolve().parent.parent
    entries = {
        f"{DIST_INFO}/METADATA": (
            "Metadata-Version: 2.2\n"
            "Name: karyoforge-project-fixture\n"
            f"Version: {VERSION}\n"
            "Provides-Extra: validation\n"
            'Requires-Dist: pip; extra == "validation"\n'
        ),
        f"{DIST_INFO}/WHEEL": (
            "Wheel-Version: 1.0\n"
            "Generator: KaryoForge acceptance\n"
            "Root-Is-Purelib: true\n"
            "Tag: py3-none-any\n"
        ),
    }
    if editable:
        entries[f"{NAME}.pth"] = str(project / "src") + "\n"
    else:
        entries[f"{NAME}.py"] = (project / "src" / f"{NAME}.py").read_text(encoding="utf-8")
    record = f"{DIST_INFO}/RECORD"
    entries[record] = "".join(f"{name},,\n" for name in [*entries, record])
    filename = f"{NAME}-{VERSION}-py3-none-any.whl"
    with zipfile.ZipFile(Path(wheel_directory) / filename, "w") as archive:
        for name, value in entries.items():
            archive.writestr(name, value)
    return filename


def build_wheel(wheel_directory, config_settings=None, metadata_directory=None):
    return _build(wheel_directory, editable=False)


def build_editable(wheel_directory, config_settings=None, metadata_directory=None):
    return _build(wheel_directory, editable=True)
