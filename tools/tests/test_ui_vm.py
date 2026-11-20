"""Transport boundaries and result integrity; never opens an IDE on the controller."""

import importlib.util
import base64
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("ui_vm", Path(__file__).resolve().parents[1] / "ui_vm.py")
ui_vm = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ui_vm)


class GuiControllerTest(unittest.TestCase):
    @unittest.skipUnless(os.name == "nt", "Requires the actual Windows PowerShell parser")
    def test_gui_scripts_parse_with_windows_powershell_without_executing_them(self):
        directory = Path(__file__).resolve().parents[2] / "uitest"
        script = """
        $ErrorActionPreference='Stop'
        foreach ($file in Get-ChildItem -LiteralPath DIRECTORY -Filter '*.ps1') {
            $tokens=$null; $errors=$null
            [Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tokens,[ref]$errors) | Out-Null
            if ($errors.Count) { $errors | ForEach-Object { Write-Output ($file.Name + ': ' + $_.Message) }; exit 1 }
        }
        """.replace("DIRECTORY", ui_vm.ps_string(str(directory)))
        encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
        result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded],
                                capture_output=True, text=True, errors="replace", timeout=30)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_targeted_reruns_are_confined_to_public_classes_and_no_argument_methods(self):
        prefix = "org.openkaryon.karyoforge.uitest."
        method = prefix + "PythonEnvironmentUiTest#editorCompletionAndTypingUseTheConfiguredPythonProvider"
        self.assertEqual([method, prefix + "DistributionUiTest"],
                         ui_vm.parse_selectors(f" {method}, {prefix}DistributionUiTest,{method}, "))
        for invalid in ("other.package.Test", prefix + "Test#", prefix + "Test#method()",
                        prefix + "Test#method;cmd", prefix + "Test#one#two", prefix + "Test\n#method",
                        prefix + "Test --scan-classpath", prefix + "1Test", prefix.upper() + "Test"):
            with self.subTest(selector=invalid), self.assertRaises(ValueError):
                ui_vm.parse_selectors(invalid)

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

    def test_runtime_cache_identity_survives_gradle_refreshing_file_timestamps(self):
        with tempfile.TemporaryDirectory(prefix="forge-stable-runtime-") as folder:
            root = Path(folder)
            runtime = root / "runtime"
            runtime.mkdir()
            library = runtime / "fixture.jar"
            library.write_bytes(b"owned runtime fixture")
            first = ui_vm.archive_tree(runtime, root / "first.zip")
            os.utime(library, (1700000000, 1700000000))
            second = ui_vm.archive_tree(runtime, root / "second.zip")
            self.assertEqual(ui_vm.digest(first), ui_vm.digest(second))
            library.write_bytes(b"changed runtime fixture")
            third = ui_vm.archive_tree(runtime, root / "third.zip")
            self.assertNotEqual(ui_vm.digest(first), ui_vm.digest(third))

    def test_vmware_diagnostics_redact_credentials_and_subprocess_arguments(self):
        with tempfile.TemporaryDirectory(prefix="forge-vmware-test-") as folder:
            root = Path(folder)
            vmx, executable = root / "test.vmx", root / "vmrun.exe"
            vmx.touch()
            executable.touch()
            with patch.dict(os.environ, {"KARYOFORGE_UI_GUEST_PASSWORD": "guest-secret",
                                         "KARYOFORGE_UI_VM_PASSWORD": "vm-secret"}):
                vm = ui_vm.VmwareConnection(vmx, "guest", executable)
            failure = subprocess.CompletedProcess([], 1, stdout="guest-secret vm-secret", stderr="")
            with patch.object(ui_vm.subprocess, "run", return_value=failure):
                with self.assertRaises(RuntimeError) as caught:
                    vm.copy("host.zip", "C:/owned/guest.zip")
            self.assertNotIn("guest-secret", str(caught.exception))
            self.assertNotIn("vm-secret", str(caught.exception))
            with patch.object(ui_vm.subprocess, "run", side_effect=subprocess.TimeoutExpired(vm.prefix, 1)):
                with self.assertRaises(RuntimeError) as caught:
                    vm.powershell("Get-Date", timeout=1)
            self.assertNotIn("secret", str(caught.exception))

    def test_vmware_requires_external_credentials_and_existing_inputs(self):
        with tempfile.TemporaryDirectory(prefix="forge-vmware-inputs-") as folder:
            root = Path(folder)
            vmx, executable = root / "test.vmx", root / "vmrun.exe"
            with self.assertRaises(ValueError):
                ui_vm.VmwareConnection(vmx, "guest", executable)
            vmx.touch()
            executable.touch()
            with patch.dict(os.environ, {"KARYOFORGE_UI_GUEST_PASSWORD": ""}):
                with self.assertRaisesRegex(ValueError, "process environment"):
                    ui_vm.VmwareConnection(vmx, "guest", executable)

    def test_vmware_file_operations_use_native_guest_paths(self):
        with tempfile.TemporaryDirectory(prefix="forge-vmware-paths-") as folder:
            root = Path(folder)
            vmx, executable = root / "test.vmx", root / "vmrun.exe"
            vmx.touch()
            executable.touch()
            with patch.dict(os.environ, {"KARYOFORGE_UI_GUEST_PASSWORD": "test-only"}):
                vm = ui_vm.VmwareConnection(vmx, "guest", executable)
            with patch.object(vm, "_call") as call:
                vm.copy("D:/controller/input.zip", "C:/owned/guest.zip")
                call.assert_called_once_with("copyFileFromHostToGuest", "D:/controller/input.zip", "C:\\owned\\guest.zip", timeout=1800)
                call.reset_mock()
                vm.copy("C:/owned/results.zip", "D:/controller/results.zip", download=True)
                call.assert_called_once_with("copyFileFromGuestToHost", "C:\\owned\\results.zip", "D:/controller/results.zip", timeout=1800)


if __name__ == "__main__":
    unittest.main()
