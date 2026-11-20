"""Transport boundaries and result integrity; never opens an IDE on the controller."""

import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC = importlib.util.spec_from_file_location("ui_vm", Path(__file__).resolve().parents[1] / "ui_vm.py")
ui_vm = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ui_vm)


class GuiControllerTest(unittest.TestCase):
    def test_remote_values_cannot_inject_powershell_commands(self):
        self.assertEqual("'a''; Write-Output injection; '''", ui_vm.ps_string("a'; Write-Output injection; '"))
        for value in ("line\nbreak", "nul\0value", "carriage\rreturn"):
            with self.assertRaises(ValueError):
                ui_vm.ps_string(value)

    def test_an_explicit_vm_and_existing_key_are_required(self):
        for host in ("", "localhost;shutdown", "host@another", "vm\ncommand"):
            with self.assertRaises(ValueError):
                ui_vm.VmConnection(host, "test", Path("missing-key"))
        with self.assertRaises(ValueError):
            ui_vm.VmConnection("vm.example", "user", Path("missing-key"))

    def test_archive_escape_is_rejected_before_any_results_are_written(self):
        with tempfile.TemporaryDirectory(prefix="forge-gui-results-") as folder:
            root = Path(folder)
            archive = root / "bad.zip"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("junit/TEST-safe.xml", "safe")
                output.writestr("../escape.txt", "unsafe")
            with self.assertRaises(ValueError):
                ui_vm.extract_results(archive, root / "results")
            self.assertFalse((root / "results").exists())
            for name in ("/absolute", "C:/absolute", "dir/file:stream", "..\\escape", "dir/.. /escape", "NUL.txt", "dir./file"):
                with self.assertRaises(ValueError):
                    ui_vm.validate_member(name)

    def test_empty_failed_and_skipped_gui_runs_never_count_as_success(self):
        with tempfile.TemporaryDirectory(prefix="forge-gui-junit-") as folder:
            root = Path(folder)
            junit = root / "junit"
            junit.mkdir()
            with self.assertRaises(ValueError):
                ui_vm.assert_junit_passed(root)
            report = junit / "TEST-suite.xml"
            for body in ("", "<testcase><skipped/></testcase>", "<testcase><failure/></testcase>", "<testcase><error/></testcase>"):
                report.write_text("<testsuite>" + body + "</testsuite>")
                with self.assertRaises(ValueError):
                    ui_vm.assert_junit_passed(root)
            report.write_text('<testsuite><testcase name="real-gui-case"/></testsuite>')
            self.assertEqual(1, ui_vm.assert_junit_passed(root))

    def test_nested_junit_containers_are_counted_once(self):
        with tempfile.TemporaryDirectory(prefix="forge-gui-nested-") as folder:
            root = Path(folder)
            (root / "junit").mkdir()
            (root / "junit/TEST-nested.xml").write_text('<testsuites tests="1"><testsuite tests="1"><testcase/></testsuite></testsuites>')
            self.assertEqual(1, ui_vm.assert_junit_passed(root))


if __name__ == "__main__":
    unittest.main()
