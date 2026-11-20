"""Distribution boundaries: pinned artifacts, extraction and owned-stage cleanup."""

import importlib.util
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile, ZipInfo


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("windows_distribution", ROOT / "tools/windows_distribution.py")
WINDOWS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(WINDOWS)


class WindowsDistributionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="forge distribution ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_archive_traversal_is_rejected_before_extracting_any_file(self):
        source = self.root / "input.zip"
        with ZipFile(source, "w") as archive:
            archive.writestr("ordinary.txt", "allowed")
            archive.writestr("../outside.txt", "must not escape")
        destination = self.root / "output"
        with self.assertRaisesRegex(ValueError, "Unsafe archive path"):
            WINDOWS.extract(source, destination)
        self.assertFalse(destination.exists())
        self.assertFalse((self.root / "outside.txt").exists())

    def test_windows_absolute_ads_and_backslash_paths_are_rejected(self):
        for name in ("C:/outside", "/outside", "safe:stream", "safe\\..\\outside", "file. "):
            with self.subTest(name=name), self.assertRaises(ValueError):
                WINDOWS.archive_path(name)

    def test_archive_symlink_is_rejected(self):
        source = self.root / "links.zip"
        with ZipFile(source, "w") as archive:
            entry = ZipInfo("link")
            entry.create_system = 3
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(entry, "../../outside")
        with self.assertRaisesRegex(ValueError, "symlink"):
            WINDOWS.extract(source, self.root / "output")

    def test_extract_filters_test_resources_but_keeps_runtime_helpers(self):
        source = self.root / "python.zip"
        with ZipFile(source, "w") as archive:
            archive.writestr("python-ce/helpers/pydev/pydevd.py", "runtime")
            archive.writestr("python-ce/helpers/testResources/nested/test.py", "fixture")
        destination = self.root / "plugins"
        WINDOWS.extract(source, destination, lambda path: path.parts[:3] != ("python-ce", "helpers", "testResources"))
        self.assertTrue((destination / "python-ce/helpers/pydev/pydevd.py").is_file())
        self.assertFalse((destination / "python-ce/helpers/testResources").exists())

    def test_recursive_cleanup_requires_exact_owned_stage_and_identity(self):
        work = self.root / "work"
        stage = work / "app"
        stage.mkdir(parents=True)
        (stage / "project.py").write_text("keep")
        with patch.object(WINDOWS.shutil, "rmtree") as remove:
            with self.assertRaisesRegex(ValueError, "owned application stage"):
                WINDOWS.remove_owned_stage(stage, work)
            (stage / WINDOWS.MARKER).write_text(json.dumps({"pluginId": "another.product"}))
            with self.assertRaisesRegex(ValueError, "ownership marker"):
                WINDOWS.remove_owned_stage(stage, work)
            (stage / WINDOWS.MARKER).write_text(json.dumps({"pluginId": WINDOWS.PUBLIC_ID}))
            with self.assertRaises(ValueError):
                WINDOWS.remove_owned_stage(stage, self.root / "other")
            remove.assert_not_called()
            WINDOWS.remove_owned_stage(stage, work)
            remove.assert_called_once_with(stage.resolve())

    def test_matching_gradle_cache_artifact_needs_no_download(self):
        archive = self.root / "gradle cached.zip"
        archive.write_bytes(b"verified archive")
        spec = {"name": "sdk.zip", "sha256": WINDOWS.sha256(archive), "url": "https://example.invalid/sdk"}
        with patch.object(WINDOWS.urllib.request, "urlopen") as request:
            self.assertEqual(archive, WINDOWS.artifact(spec, self.root / "downloads", [archive]))
            request.assert_not_called()

    def test_gradle_and_packaging_version_disagreement_is_a_failure(self):
        lock = json.loads((ROOT / "installer/windows-artifacts.json").read_text())
        inputs = {key: lock[key] for key in ("schemaVersion", "platformVersion", "platformBuild", "pythonPluginVersion")}
        inputs["pluginVersion"] = "0.1.0-SNAPSHOT"
        WINDOWS.validate_inputs(inputs, lock)
        inputs["platformBuild"] = "252.0.1"
        with self.assertRaisesRegex(ValueError, "disagree"):
            WINDOWS.validate_inputs(inputs, lock)


if __name__ == "__main__":
    unittest.main()
