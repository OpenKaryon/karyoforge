"""Exercise owned-process cleanup with real disposable Windows sleepers, never an IDE."""

import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
POWERSHELL = shutil.which("powershell.exe")


@unittest.skipUnless(os.name == "nt" and POWERSHELL, "Windows process identity acceptance")
class OwnedGuiProcessCleanupTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="karyoforge-owned-process-")
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.run_id = "controller-cleanup-test"
        self.write_json(".karyoforge-ui-run.json", {"runId": self.run_id})

    def write_json(self, name, value):
        (self.root / name).write_text(json.dumps(value), encoding="utf-8")

    def powershell(self, script):
        prefix = "$ErrorActionPreference='Stop'; [Console]::OutputEncoding = New-Object Text.UTF8Encoding($false); "
        encoded = base64.b64encode((prefix + script).encode("utf-16-le")).decode("ascii")
        result = subprocess.run([POWERSHELL, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded],
                                capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=20,
                                creationflags=subprocess.CREATE_NO_WINDOW)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.strip()

    def sleeper(self):
        process = subprocess.Popen([POWERSHELL, "-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Seconds 90"],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
        self.addCleanup(self.stop_sleeper, process)
        return process

    @staticmethod
    def stop_sleeper(process):
        if process.poll() is None:
            process.terminate()
        process.wait(timeout=10)

    def identity(self, process):
        result = self.powershell(
            f"$owned=Get-Process -Id {process.pid}; "
            "@{pid=$owned.Id; startedTicks=$owned.StartTime.ToUniversalTime().Ticks; executable=$owned.Path} | ConvertTo-Json -Compress")
        record = json.loads(result)
        record["runId"] = self.run_id
        return record

    def cleanup_run(self):
        script = str(ROOT / "uitest/stop-owned-run.ps1").replace("'", "''")
        run_root = str(self.root).replace("'", "''")
        self.powershell(f"& '{script}' -RunRoot '{run_root}'")

    def test_reused_or_unrelated_process_identities_are_ignored(self):
        owned = self.sleeper()
        record = self.identity(owned)
        changed = dict(record, startedTicks=record["startedTicks"] - 1)
        self.write_json("runner-process.json", changed)
        self.cleanup_run()
        self.assertIsNone(owned.poll(), "A reused PID with different creation time must survive")
        changed = dict(record, executable=str(self.root / "unrelated.exe"))
        self.write_json("runner-process.json", changed)
        self.cleanup_run()
        self.assertIsNone(owned.poll(), "An executable mismatch must survive")
        self.write_json("runner-process.json", record)
        self.cleanup_run()
        owned.wait(timeout=10)
        self.assertIsNotNone(owned.returncode)

    def test_recorded_descendant_is_stopped_after_the_runner_has_exited(self):
        runner, descendant, second_descendant, unrelated = self.sleeper(), self.sleeper(), self.sleeper(), self.sleeper()
        self.write_json("runner-process.json", self.identity(runner))
        self.write_json("owned-descendants.json", [self.identity(descendant), self.identity(second_descendant)])
        self.stop_sleeper(runner)
        self.cleanup_run()
        descendant.wait(timeout=10)
        second_descendant.wait(timeout=10)
        self.assertIsNone(unrelated.poll(), "Unrecorded Windows processes must survive cleanup")

    def test_a_process_owned_by_another_run_is_never_stopped(self):
        owned = self.sleeper()
        self.write_json("runner-process.json", self.identity(owned))
        self.write_json(".karyoforge-ui-run.json", {"runId": "another-run"})
        script = str(ROOT / "uitest/stop-owned-run.ps1").replace("'", "''")
        run_root = str(self.root).replace("'", "''")
        result = self.powershell(
            f"try {{ & '{script}' -RunRoot '{run_root}'; throw 'Unexpected cleanup success' }} "
            "catch { if ($_.Exception.Message -ne 'Owned runner identity differs from this run') { throw }; 'rejected' }")
        self.assertEqual("rejected", result)
        self.assertIsNone(owned.poll(), "Cross-run ownership mismatch must leave the process alive")

    def test_an_unrecorded_child_is_not_stopped_through_a_parent_pid_tree(self):
        child_file = self.root / "unrecorded-child.json"
        quoted_file = str(child_file).replace("'", "''")
        quoted_executable = POWERSHELL.replace("'", "''")
        script = (
            f"$child=Start-Process -FilePath '{quoted_executable}' "
            "-ArgumentList '-NoProfile -NonInteractive -Command Start-Sleep -Seconds 90' -WindowStyle Hidden -PassThru; "
            "@{pid=$child.Id; executable=$child.Path; startedTicks=$child.StartTime.ToUniversalTime().Ticks} "
            f"| ConvertTo-Json -Compress | Set-Content -Encoding UTF8 -LiteralPath '{quoted_file}'; Start-Sleep -Seconds 90")
        encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
        parent = subprocess.Popen([POWERSHELL, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                  creationflags=subprocess.CREATE_NO_WINDOW)
        self.addCleanup(self.stop_sleeper, parent)
        deadline = time.monotonic() + 15
        record = None
        while time.monotonic() < deadline:
            try:
                record = json.loads(child_file.read_text(encoding="utf-8-sig"))
                break
            except (FileNotFoundError, PermissionError, json.JSONDecodeError):
                time.sleep(0.05)
        self.assertIsNotNone(record, "The owned fixture parent publishes its child's identity")
        candidate = (f"$child=Get-Process -Id {record['pid']} -ErrorAction SilentlyContinue; "
                     f"if ($child -and $child.StartTime.ToUniversalTime().Ticks -eq {record['startedTicks']})")
        self.addCleanup(self.powershell, candidate + " { Stop-Process -Id $child.Id -Force }")
        self.write_json("runner-process.json", self.identity(parent))
        self.cleanup_run()
        parent.wait(timeout=10)
        self.assertEqual("running", self.powershell(candidate + " { 'running' }"),
                         "Cleanup must use recorded identities, not infer ownership from a parent PID")


if __name__ == "__main__":
    unittest.main()
