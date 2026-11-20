"""Synthetic coverage for audit completeness, drift and disclosure boundaries."""

import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


TOOL = Path(__file__).resolve().parents[1] / "audit_reference.py"
SPEC = importlib.util.spec_from_file_location("audit_reference", TOOL)
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


class ReferenceAuditTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "reference"
        self.meta = self.root / "src/main/resources/META-INF"
        self.java = self.root / "src/main/java/example"
        self.meta.mkdir(parents=True)
        self.java.mkdir(parents=True)
        (self.root / "gradle.properties").write_text(
            "platformVersion=2025.2.6.3\npluginSinceBuild=252.28539.97\n", encoding="utf-8")
        self.descriptor = self.meta / "plugin.xml"
        self.descriptor.write_text("""<idea-plugin><id>example</id>
          <depends>PythonCore</depends>
          <depends optional="true" config-file="optional.xml">example.optional</depends>
          <extensions defaultExtensionNs="com.intellij">
            <fileEditorProvider implementation="example.NotebookFileEditorProvider"/>
            <toolWindow id="Empty" factoryClass="example.ComingSoonToolWindowFactory"/>
          </extensions>
          <actions><group id="OpenKaryon.Tools"><action id="OpenKaryon.RunSql"
            class="example.RunSqlAction"><keyboard-shortcut keymap="$default"
            first-keystroke="ctrl ENTER"/></action></group></actions>
        </idea-plugin>""", encoding="utf-8")
        (self.meta / "optional.xml").write_text("""<idea-plugin>
          <extensions defaultExtensionNs="com.intellij">
            <fileType name="YAML" extensions="cwl"/>
          </extensions></idea-plugin>""", encoding="utf-8")
        (self.java / "NotebookFileEditorProvider.java").write_text("""package example;
import com.jetbrains.python.sdk.PythonSdkUtil;
@Service(Service.Level.PROJECT)
@State(name = "ExampleState", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
class NotebookFileEditorProvider {
    void run() { new ProcessBuilder("explicit-fixture"); }
}
""", encoding="utf-8")

    def test_complete_nested_descriptors_placements_and_persistence(self):
        report = AUDIT.inventory(self.root.resolve())
        self.assertEqual(5, len(report["registrations"]))
        self.assertEqual(2, len(report["descriptors"]))
        entries = {entry["identity"]: entry for entry in report["registrations"]}
        self.assertEqual("common-file", entries["org.openkaryon.karyoforge.NotebookFileEditorProvider"]["policy"])
        self.assertEqual("remove", entries["Empty"]["policy"])
        self.assertEqual("OpenKaryon.Tools", entries["OpenKaryon.RunSql"]["parentGroup"])
        self.assertEqual("ctrl ENTER", entries["OpenKaryon.RunSql"]["shortcuts"][0]["first-keystroke"])
        self.assertEqual("project", report["persistentComponents"][0]["scope"])
        self.assertEqual(["StoragePathMacros.WORKSPACE_FILE"], report["persistentComponents"][0]["storage"])
        self.assertEqual(1, len(report["directProviderImports"]))
        self.assertEqual(1, len(report["runtimeConstructionSites"]))

    def test_unclassified_surface_fails_instead_of_silently_passing(self):
        source = self.descriptor.read_text(encoding="utf-8")
        self.descriptor.write_text(source.replace("OpenKaryon.RunSql", "New.UnreviewedAction"), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Unclassified action"):
            AUDIT.inventory(self.root.resolve())

    def test_missing_optional_descriptor_is_a_failure(self):
        (self.meta / "optional.xml").unlink()
        with self.assertRaises(OSError):
            AUDIT.inventory(self.root.resolve())

    def test_descriptor_cannot_escape_declared_source_boundary(self):
        source = self.descriptor.read_text(encoding="utf-8")
        self.descriptor.write_text(source.replace('config-file="optional.xml"',
                                                 'config-file="../outside.xml"'), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "escapes META-INF"):
            AUDIT.inventory(self.root.resolve())

    def test_output_excludes_root_git_and_personal_configuration(self):
        (self.root / ".git").mkdir()
        (self.root / ".git/config").write_text("SECRET_PRIVATE_REMOTE", encoding="utf-8")
        (self.root / "personal.env").write_text("SECRET_TOKEN_VALUE", encoding="utf-8")
        report = json.dumps(AUDIT.inventory(self.root.resolve()))
        self.assertNotIn("SECRET_", report)
        self.assertNotIn(str(self.root), report)
        self.assertNotIn(".git/config", report)

    def test_cli_is_deterministic_and_check_detects_reference_drift(self):
        output = Path(self.temporary.name) / "output.json"
        command = [sys.executable, str(TOOL), "--source", str(self.root), "--output", str(output)]
        generated = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(0, generated.returncode, generated.stderr)
        original = output.read_bytes()
        checked = subprocess.run(command + ["--check"], capture_output=True, text=True)
        self.assertEqual(0, checked.returncode, checked.stderr)
        (self.java / "NotebookFileEditorProvider.java").write_text("class Changed {}\n", encoding="utf-8")
        drift = subprocess.run(command + ["--check"], capture_output=True, text=True)
        self.assertEqual(1, drift.returncode)
        self.assertEqual(original, output.read_bytes())

    def test_public_aliases_preserve_original_byte_fingerprints(self):
        import hashlib
        source = self.descriptor.read_text(encoding="utf-8").replace(
            "<id>example</id>", "<id>reference.vendor</id>").replace(
            "example.NotebookFileEditorProvider", "reference.vendor.NotebookFileEditorProvider")
        self.descriptor.write_text(source, encoding="utf-8")
        moved = self.root / "src/main/java/reference/vendor"
        moved.mkdir(parents=True)
        original = self.java / "NotebookFileEditorProvider.java"
        original.rename(moved / original.name)
        report = AUDIT.inventory(self.root.resolve())
        projected = json.dumps(report)
        self.assertNotIn("reference.vendor", projected)
        self.assertNotIn("reference/vendor", projected)
        self.assertIn("org.openkaryon.karyoforge.NotebookFileEditorProvider", projected)
        row = next(item for item in report["sourceFingerprints"] if item["path"].endswith(original.name))
        self.assertEqual("src/main/java/org/openkaryon/karyoforge/NotebookFileEditorProvider.java", row["path"])
        self.assertEqual(hashlib.sha256((moved / original.name).read_bytes()).hexdigest(), row["sha256"])


if __name__ == "__main__":
    unittest.main()
