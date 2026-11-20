#!/usr/bin/env python3
"""Build and run GUI acceptance on an explicitly selected, separate Windows VM."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PUBLIC_PACKAGE = "org.openkaryon.karyoforge.uitest."


def ps_string(value: str) -> str:
    if any(character in value for character in "\0\r\n"):
        raise ValueError("A remote argument must be a single line without NUL characters")
    return "'" + value.replace("'", "''") + "'"


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        checksum = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(block)
        return checksum.hexdigest()


def validate_member(name: str) -> str:
    normalized = name.replace("\\", "/")
    path = PurePosixPath(normalized)
    reserved = {"CON", "PRN", "AUX", "NUL", *(f"COM{index}" for index in range(1, 10)), *(f"LPT{index}" for index in range(1, 10))}
    if not path.parts or path.is_absolute() or any(
            part in {"..", ""} or ":" in part or part.endswith((".", " "))
            or part.split(".", 1)[0].upper() in reserved for part in path.parts):
        raise ValueError("An archive entry escapes the owned results directory: " + name)
    return normalized


def extract_results(archive: Path, directory: Path) -> None:
    with zipfile.ZipFile(archive) as source:
        entries = source.infolist()
        for entry in entries:
            validate_member(entry.filename)
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Result archive symlinks are not accepted")
        source.extractall(directory)


def assert_junit_passed(directory: Path) -> int:
    tests = failures = errors = skipped = 0
    reports = list((directory / "junit").glob("TEST-*.xml"))
    if not reports:
        raise ValueError("The VM produced no JUnit XML; GUI acceptance did not pass")
    for report in reports:
        root = ET.parse(report).getroot()
        # Count test cases rather than nested suite totals, which can double-count JUnit containers.
        for case in root.iter("testcase"):
            tests += 1
            failures += case.find("failure") is not None
            errors += case.find("error") is not None
            skipped += case.find("skipped") is not None
    if tests == 0 or failures or errors or skipped:
        raise ValueError(f"GUI results: {tests} tests, {failures} failures, {errors} errors, {skipped} skipped")
    return tests


def archive_tree(directory: Path, output: Path) -> Path:
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
        for file in sorted(directory.rglob("*")):
            if file.is_symlink():
                raise ValueError("GUI input symlinks require review: " + str(file))
            if file.is_file():
                archive.write(file, validate_member(file.relative_to(directory).as_posix()))
    return output


class VmConnection:
    def __init__(self, host: str, user: str, key: Path, port: int = 22, ssh: str = "ssh", scp: str = "scp"):
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]*", host or ""):
            raise ValueError("UI_HOST must explicitly name a VM hostname or IPv4 address")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+", user or ""):
            raise ValueError("UI_USER must name the VM account without shell syntax")
        if not key.is_file() or not 1 <= port <= 65535:
            raise ValueError("UI_KEY must be an existing SSH private-key file and the port must be valid")
        self.target = f"{user}@{host}"
        self.ssh, self.scp, self.port = ssh, scp, port
        self.options = ["-i", str(key.resolve()), "-o", "IdentitiesOnly=yes", "-o", "BatchMode=yes",
                        "-o", "ConnectTimeout=10", "-o", "StrictHostKeyChecking=accept-new"]

    def powershell(self, script: str, timeout: int = 45) -> str:
        prefix = "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; "
        encoded = base64.b64encode((prefix + script).encode("utf-16-le")).decode("ascii")
        command = [self.ssh, *self.options, "-p", str(self.port), self.target,
                   "powershell.exe -NoProfile -NonInteractive -EncodedCommand " + encoded]
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
        if result.returncode:
            raise RuntimeError(f"VM command failed ({result.returncode}): " + (result.stderr or result.stdout)[-4000:])
        return result.stdout.strip()

    def copy(self, source: str, destination: str, *, download: bool = False) -> None:
        remote = (source if download else destination).replace("\\", "/")
        if not re.fullmatch(r"[A-Za-z]:/[A-Za-z0-9_./-]+", remote):
            raise ValueError("The owned remote transfer path must contain only safe path characters")
        arguments = [f"{self.target}:{remote}", destination] if download else [source, f"{self.target}:{remote}"]
        subprocess.run([self.scp, *self.options, "-P", str(self.port), *arguments], check=True, timeout=1800)


def preflight(vm: VmConnection, controller: str) -> dict:
    script = """
    $system = Get-CimInstance Win32_ComputerSystem
    $os = Get-CimInstance Win32_OperatingSystem
    if ($env:COMPUTERNAME -eq CONTROLLER) { throw 'Refusing to run GUI tests on the development computer' }
    if ($system.Model -notmatch 'Virtual|VMware|KVM|QEMU|HVM') { throw 'The target must be a separate Windows VM' }
    if ([version]$os.Version -lt [version]'10.0') { throw 'The target requires Windows 10 or newer' }
    [pscustomobject]@{computer=$env:COMPUTERNAME; model=$system.Model; version=$os.Version; user=$env:USERNAME} | ConvertTo-Json -Compress
    """.replace("CONTROLLER", ps_string(controller))
    return json.loads(vm.powershell(script))


def sync_archive(vm: VmConnection, archive: Path, remote_root: str, kind: str) -> str:
    checksum = digest(archive)
    target = remote_root + "/cache/" + kind + "-" + checksum
    marker = target + "/.karyoforge-ui-cache.json"
    existing = vm.powershell(f"if (Test-Path -LiteralPath {ps_string(marker)}) {{ (Get-Content -Raw -LiteralPath {ps_string(marker)} | ConvertFrom-Json).sha256 }}")
    if existing == checksum:
        print(f"Reusing verified VM {kind} cache", flush=True)
        return target
    upload = remote_root + "/incoming/" + kind + "-" + checksum + ".zip"
    vm.powershell(f"New-Item -ItemType Directory -Force -Path {ps_string(remote_root + '/incoming')} | Out-Null")
    vm.copy(str(archive), upload)
    unpack = (ROOT / "uitest/unpack-cache.ps1").read_text(encoding="utf-8")
    vm.powershell("& {" + unpack + "} -Archive " + ps_string(upload) + " -Target " + ps_string(target)
                  + " -Expected " + ps_string(checksum) + " -Root " + ps_string(remote_root), timeout=1200)
    return target


def run(args: argparse.Namespace) -> int:
    if os.name != "nt":
        raise ValueError("The distribution GUI controller currently requires a Windows host")
    vm = VmConnection(args.host, args.user, Path(args.key).expanduser(), args.port, args.ssh, args.scp)
    controller = os.environ.get("COMPUTERNAME", "")
    if not controller:
        raise ValueError("The controller computer identity is unavailable")
    info = preflight(vm, controller)
    print(f"Verified separate Windows VM: {info['computer']} ({info['model']})", flush=True)
    if not re.fullmatch(r"[A-Za-z]:/[A-Za-z0-9_/-]+", args.vm_root):
        raise ValueError("UI_VM_ROOT must be an absolute dedicated path without spaces or shell syntax")
    if args.vm_root.count("/") < 1 or not args.vm_root.split("/")[-1]:
        raise ValueError("Refusing a volume root as the VM workspace")
    if not args.skip_build:
        import tasks
        subprocess.run(tasks.gradle_command(ROOT, ["uiTestBundle", "zipWindows", f"-PpythonCommand={sys.executable}"]), cwd=ROOT, check=True)
    version = next(line.split("=", 1)[1] for line in (ROOT / "gradle.properties").read_text().splitlines() if line.startswith("pluginVersion="))
    ide_archive = ROOT / "build/distributions" / f"KaryoForge-{version}-windows-x64.zip"
    bundle = ROOT / "build/ui-test-bundle"
    if not ide_archive.is_file() or not (bundle / "classes").is_dir():
        raise ValueError("Missing built GUI bundle or portable public distribution")
    run_id = uuid.uuid4().hex[:12]
    transfer = ROOT / "build/ui-test-transfer" / run_id
    runtime = archive_tree(bundle / "runtime", transfer / "runtime.zip")
    # Keep changing sources/fixtures separate from the large runtime cache.
    payload = transfer / "payload"
    payload.mkdir(parents=True, exist_ok=True)
    import shutil
    shutil.copytree(bundle / "classes", payload / "classes", dirs_exist_ok=True)
    if (bundle / "fixtures").is_dir():
        shutil.copytree(bundle / "fixtures", payload / "fixtures", dirs_exist_ok=True)
    for script in bundle.glob("*.ps1"):
        shutil.copyfile(script, payload / script.name)
    payload_archive = archive_tree(payload, transfer / "payload.zip")
    home = sync_archive(vm, ide_archive, args.vm_root, "ide") + "/KaryoForge"
    libraries = sync_archive(vm, runtime, args.vm_root, "runtime")
    source = sync_archive(vm, payload_archive, args.vm_root, "payload")
    remote_run = args.vm_root + "/runs/" + run_id
    local = ROOT / "build/ui-test-results" / run_id
    local.mkdir(parents=True)
    selectors = [name.strip() for name in args.select.split(",") if name.strip()]
    if any(not re.fullmatch(re.escape(PUBLIC_PACKAGE) + r"[A-Za-z0-9_]+", name) for name in selectors):
        raise ValueError("GUI selectors must name public GUI test classes")
    request = {"runId": run_id, "controller": controller, "ide": home, "runtime": libraries,
               "payload": source, "python": args.python, "select": selectors, "timeoutMinutes": args.timeout}
    request_json = json.dumps(request, ensure_ascii=True)
    (local / "request.json").write_text(request_json + "\n", encoding="utf-8")
    task = "OpenKaryon.KaryoForge.Ui." + run_id
    start = f"""
    $root = {ps_string(remote_run)}
    if (Test-Path -LiteralPath $root) {{ throw 'Run directory already exists' }}
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    Set-Content -Encoding UTF8 -LiteralPath (Join-Path $root '.karyoforge-ui-run.json') -Value {ps_string(request_json)}
    $command = '& ' + {ps_string(ps_string(source + '/run-ui-tests.ps1'))} + ' -RunRoot ' + {ps_string(ps_string(remote_run))}
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -EncodedCommand ' + $encoded)
    $principal = New-ScheduledTaskPrincipal -UserId ($env:COMPUTERNAME + '\\' + $env:USERNAME) -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Minutes {args.timeout + 5}) -AllowStartIfOnBatteries
    Register-ScheduledTask -TaskName {ps_string(task)} -Action $action -Principal $principal -Settings $settings -ErrorAction Stop | Out-Null
    Start-ScheduledTask -TaskName {ps_string(task)}
    """
    vm.powershell(start)
    deadline = time.monotonic() + args.timeout * 60
    code = None
    try:
        while time.monotonic() < deadline:
            status = vm.powershell(f"if (Test-Path -LiteralPath {ps_string(remote_run + '/results/exit-code.txt')}) {{ Get-Content -LiteralPath {ps_string(remote_run + '/results/exit-code.txt')} }}")
            if status:
                code = int(status)
                break
            time.sleep(10)
        # Always retrieve diagnostic artifacts, including a timed-out or failed run.
        collector = (ROOT / "uitest/collect-results.ps1").read_text(encoding="utf-8")
        vm.powershell("& {" + collector + "} -RunRoot " + ps_string(remote_run), timeout=180)
        result_zip = local / "results.zip"
        vm.copy(remote_run + "/results.zip", str(result_zip), download=True)
        extract_results(result_zip, local)
    finally:
        # No global process matching, IDE-name matching or recursive workspace deletion.
        cleanup = (ROOT / "uitest/stop-owned-run.ps1").read_text(encoding="utf-8")
        vm.powershell("& {" + cleanup + "} -RunRoot " + ps_string(remote_run))
        vm.powershell(f"Unregister-ScheduledTask -TaskName {ps_string(task)} -Confirm:$false -ErrorAction SilentlyContinue")
    print(f"GUI results: {local}", flush=True)
    if code != 0:
        raise ValueError(f"GUI runner did not finish successfully (exit {code}); see {local}")
    print(f"Passed {assert_junit_passed(local)} GUI tests on the separate Windows VM", flush=True)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default=os.getenv("KARYOFORGE_UI_HOST", ""))
    parser.add_argument("--user", default=os.getenv("KARYOFORGE_UI_USER", ""))
    parser.add_argument("--key", default=os.getenv("KARYOFORGE_UI_KEY", ""))
    parser.add_argument("--port", type=int, default=22)
    parser.add_argument("--vm-root", default="C:/KaryoForgeUi")
    parser.add_argument("--python", default=os.getenv("KARYOFORGE_UI_PYTHON", ""))
    parser.add_argument("--select", default="")
    parser.add_argument("--timeout", type=int, default=90)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--ssh", default="ssh")
    parser.add_argument("--scp", default="scp")
    args = parser.parse_args()
    try:
        if not 1 <= args.timeout <= 240:
            raise ValueError("The GUI timeout must be between 1 and 240 minutes")
        return run(args)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, ET.ParseError) as error:
        print("GUI acceptance failed: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
