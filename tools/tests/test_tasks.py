"""Checks for real failure behavior and safe task argument forwarding."""

import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


TOOL = Path(__file__).resolve().parents[1] / "tasks.py"
SPEC = importlib.util.spec_from_file_location("tasks", TOOL)
TASKS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TASKS)


class TasksTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="forge tools ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def report(self):
        row = {"descriptor": "plugin.xml", "namespace": "com.intellij",
               "kind": "fileNamePatternMapping", "identity": "Matcher",
               "owner": "fixture.Matcher", "policy": "language-focus",
               "rule": "Explicit language use", "attributes": {"patterns": "*.c"}}
        return {"schemaVersion": 2, "identifierProjection": {"publicNamespace": TASKS.NAMESPACE},
                "registrations": [row], "sourceFingerprints": [
                    {"path": "src/main/java/org/openkaryon/karyoforge/Fixture.java", "sha256": "a" * 64}]}

    def write_report(self, report):
        folder = self.root / "docs/audit"
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "reference-inventory.json").write_text(json.dumps(report) + "\n", encoding="utf-8")

    def test_lint_rejects_broken_links_and_delivery_codes(self):
        readme = self.root / "README.md"
        readme.write_text("[missing](missing.md)\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "documentation link"):
            TASKS.lint(self.root)
        readme.write_text("# P0 audit\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Delivery-stage code"):
            TASKS.lint(self.root)

    def test_lint_checks_syntax_and_ignores_generated_work_folders(self):
        work = self.root / "work"
        work.mkdir()
        (work / "ignored.py").write_text("invalid python\n", encoding="utf-8")
        TASKS.lint(self.root)
        (self.root / "broken.py").write_text("invalid python\n", encoding="utf-8")
        with self.assertRaises(SyntaxError):
            TASKS.lint(self.root)

    def test_inventory_allows_distinct_mappings_but_rejects_duplicates(self):
        report = self.report()
        second = dict(report["registrations"][0], attributes={"patterns": "*.cpp"})
        report["registrations"].append(second)
        self.write_report(report)
        TASKS.audit(self.root)
        report["registrations"].append(second.copy())
        self.write_report(report)
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            TASKS.audit(self.root)

    def test_inventory_requires_disclosed_projection_and_relative_fingerprints(self):
        report = self.report()
        report["identifierProjection"]["publicNamespace"] = "example.wrong"
        self.write_report(report)
        with self.assertRaisesRegex(ValueError, "projection"):
            TASKS.audit(self.root)
        report["identifierProjection"]["publicNamespace"] = TASKS.NAMESPACE
        report["sourceFingerprints"][0]["path"] = "../outside.java"
        self.write_report(report)
        with self.assertRaisesRegex(ValueError, "fingerprint"):
            TASKS.audit(self.root)

    def test_missing_ide_inputs_fail_before_spawning_a_command(self):
        with patch.object(TASKS.subprocess, "run") as run:
            for task in ("build", "verify", "test-ide", "package-windows", "package-linux"):
                with self.subTest(task=task), self.assertRaisesRegex(ValueError, "unavailable"):
                    TASKS.execute(task, self.root)
            run.assert_not_called()

    def test_gradle_task_dispatch_uses_the_wrapper_and_requested_tasks(self):
        wrapper = "gradlew.bat" if os.name == "nt" else "gradlew"
        files = [wrapper, "build.gradle.kts", "settings.gradle.kts",
                 "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"]
        for relative in files:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture\n", encoding="utf-8")
        with patch.object(TASKS.subprocess, "run") as run:
            run.return_value.returncode = 7
            self.assertEqual(7, TASKS.execute("build", self.root))
            command = run.call_args.args[0]
            self.assertIn(str(self.root / wrapper), command)
            self.assertEqual(["buildPlugin", "verifyPluginStructure"], command[-2:])
            self.assertEqual(self.root, run.call_args.kwargs["cwd"])
            self.assertNotIn("shell", run.call_args.kwargs)

    def test_reference_is_required_and_forwarded_without_shell_interpolation(self):
        with self.assertRaisesRegex(ValueError, "REFERENCE_DIR"):
            TASKS.execute("audit-reference", self.root)
        reference = str(self.root / "reference with spaces & literal")
        with patch.object(TASKS.subprocess, "run") as run:
            run.return_value.returncode = 0
            self.assertEqual(0, TASKS.execute("audit-reference-check", self.root, reference))
            command = run.call_args.args[0]
            self.assertEqual(reference, command[command.index("--source") + 1])
            self.assertEqual("--check", command[-1])
            self.assertNotIn("shell", run.call_args.kwargs)


if __name__ == "__main__":
    unittest.main()
