#!/usr/bin/env python3
"""Assemble and verify a standalone Windows IDE using pinned local build inputs."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.request
from zipfile import ZipFile, ZIP_DEFLATED


ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "build/windows"
MARKER = "karyoforge-distribution.json"
PUBLIC_ID = "org.openkaryon.karyoforge"


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        digest = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
        return digest.hexdigest()


def write_json(path: Path, data) -> None:
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def artifact(spec: dict, cache: Path, candidates=()) -> Path:
    for path in (cache / spec["name"], *map(Path, candidates)):
        if path.is_file() and sha256(path) == spec["sha256"]:
            print(f"Verified cached input: {spec['name']}", flush=True)
            return path
    cache.mkdir(parents=True, exist_ok=True)
    destination = cache / spec["name"]
    partial = destination.with_suffix(destination.suffix + ".part")
    print(f"Downloading pinned input: {spec['name']}", flush=True)
    request = urllib.request.Request(spec["url"], headers={"User-Agent": "KaryoForge-build/0.1"})
    with urllib.request.urlopen(request, timeout=90) as response, partial.open("wb") as output:
        shutil.copyfileobj(response, output, 1024 * 1024)
    if sha256(partial) != spec["sha256"]:
        raise ValueError(f"Checksum mismatch: {spec['name']}; downloaded file was not executed")
    partial.replace(destination)
    return destination


def archive_path(name: str) -> PurePosixPath:
    path = PurePosixPath(name)
    if (not name or "\\" in name or path.is_absolute() or ".." in path.parts
            or any(":" in part or part.endswith((" ", ".")) for part in path.parts)):
        raise ValueError(f"Unsafe archive path: {name}")
    return path


def extract(archive: Path, destination: Path, include=lambda path: True) -> None:
    destination = destination.resolve()
    with ZipFile(archive) as source:
        entries = []
        for entry in source.infolist():
            relative = archive_path(entry.filename)
            if stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError(f"Archive symlink requires review: {entry.filename}")
            if not include(relative):
                continue
            target = destination.joinpath(*relative.parts)
            if not target.resolve().is_relative_to(destination):
                raise ValueError("Archive entry escapes its destination")
            entries.append((entry, target))
        for entry, target in entries:
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(entry) as stream, target.open("wb") as output:
                    shutil.copyfileobj(stream, output, 1024 * 1024)


def remove_owned_stage(path: Path, work: Path = WORK) -> None:
    target, boundary = path.resolve(), work.resolve()
    if (path.is_symlink() or target.parent != boundary or target.name != "app"
            or not (target / MARKER).is_file()):
        raise ValueError("Refusing to remove a directory outside the owned application stage")
    marker = json.loads((target / MARKER).read_text(encoding="utf-8"))
    if marker.get("pluginId") != PUBLIC_ID:
        raise ValueError("Application stage has no public KaryoForge ownership marker")
    print(f"Replacing owned build stage: {target}", flush=True)
    shutil.rmtree(target)


def run(command: list[str], **kwargs) -> None:
    subprocess.run(list(map(str, command)), check=True, **kwargs)


def validate_inputs(inputs: dict, lock: dict) -> None:
    if inputs.get("schemaVersion") != 1 or lock.get("schemaVersion") != 1:
        raise ValueError("Unsupported Windows build input schema")
    for name in ("platformVersion", "platformBuild", "pythonPluginVersion"):
        if inputs[name] != lock[name]:
            raise ValueError(f"Windows artifact pins disagree with Gradle: {name}")
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-SNAPSHOT)?", inputs["pluginVersion"]):
        raise ValueError("Unsupported distribution version")


def stage(inputs: dict, lock: dict) -> Path:
    validate_inputs(inputs, lock)
    WORK.mkdir(parents=True, exist_ok=True)
    cache = WORK / "downloads"
    pins = lock["artifacts"]
    platform = artifact(pins["platform"], cache, inputs.get("platformArchives", ()))
    python = artifact(pins["python"], cache, inputs.get("pythonArchives", ()))
    rcedit = artifact(pins["rcedit"], cache)
    tools_archive = artifact(pins["nsis"], cache)
    tools = WORK / "tools"
    extract(tools_archive, tools)
    app = Path(tempfile.mkdtemp(prefix="staging-", dir=WORK))
    plugins = set(lock["bundledPlugins"])
    print("Extracting the standalone IDE and selected bundled plugins", flush=True)
    extract(platform, app, lambda path: path.parts[0] != "plugins"
            or len(path.parts) == 1 or path.parts[1] in plugins)
    product = json.loads((app / "product-info.json").read_text(encoding="utf-8"))
    if product["productCode"] != "IC" or product["buildNumber"] != lock["platformBuild"]:
        raise ValueError("Unexpected SDK identity")
    extract(python, app / "plugins", lambda path: path.parts[:3] != ("python-ce", "helpers", "testResources"))
    extract(Path(inputs["pluginArchive"]), app / "plugins",
            lambda path: path.parts[0] == "karyoforge")
    for name in (*plugins, "python-ce", "karyoforge"):
        if not (app / "plugins" / name / "lib").is_dir():
            raise ValueError(f"Missing staged plugin: {name}")
    shutil.copy2(inputs["brandingArchive"], app / "lib/karyoforge-branding.jar")
    for name in ("karyoforge.ico", "karyoforge.svg"):
        shutil.copy2(ROOT / "installer/branding" / name, app / "bin" / name)
    launcher = app / "bin/karyoforge.exe"
    (app / "bin/idea64.exe").rename(launcher)
    (app / "bin/idea64.exe.vmoptions").rename(app / "bin/karyoforge.exe.vmoptions")
    # The stock batch launcher references the old executable; publish one supported entry point.
    (app / "bin/idea.bat").unlink(missing_ok=True)
    version = inputs["pluginVersion"].split("-", 1)[0]
    run([rcedit, launcher, "--set-icon", app / "bin/karyoforge.ico",
         "--set-version-string", "ProductName", "KaryoForge",
         "--set-version-string", "FileDescription", "KaryoForge",
         "--set-version-string", "CompanyName", "OpenKaryon",
         "--set-product-version", version, "--set-file-version", version])
    selector = "KaryoForge" + ".".join(version.split(".")[:2])
    launch = next(item for item in product["launch"] if item["os"] == "Windows" and item["arch"] == "amd64")
    launch["launcherPath"] = "bin/karyoforge.exe"
    launch["vmOptionsFilePath"] = "bin/karyoforge.exe.vmoptions"
    launch["bootClassPathJarNames"].insert(0, "karyoforge-branding.jar")
    launch["additionalJvmArguments"] = [
        "-Didea.vendor.name=OpenKaryon" if value.startswith("-Didea.vendor.name=") else
        "-Didea.paths.selector=" + selector if value.startswith("-Didea.paths.selector=") else value
        for value in launch["additionalJvmArguments"]]
    product.update(name="KaryoForge", productVendor="OpenKaryon", envVarBaseName="KARYOFORGE",
                   dataDirectoryName=selector, svgIconPath="bin/karyoforge.svg")
    write_json(app / "product-info.json", product)
    with (app / "bin/karyoforge.exe.vmoptions").open("a", encoding="utf-8", newline="\n") as output:
        output.write("\n-Dintellij.platform.load.app.info.from.resources=true\n")
    profile = "OpenKaryon/KaryoForge/" + ".".join(version.split(".")[:2])
    with (app / "bin/idea.properties").open("a", encoding="utf-8", newline="\n") as output:
        output.write("\n# KaryoForge public distribution; separate from development and private profiles.\n"
                     "idea.config.path=${user.home}/AppData/Roaming/" + profile + "\n"
                     "idea.system.path=${user.home}/AppData/Local/" + profile + "\n"
                     "idea.plugins.path=${idea.config.path}/plugins\n"
                     "idea.log.path=${idea.system.path}/log\nide.no.platform.update=true\n"
                     "idea.disable.collect.statistics=true\nidea.suppress.statistics.report=true\n"
                     "idea.headless.enable.statistics=false\n")
    notices = app / "openkaryon-license"
    notices.mkdir()
    for name in ("LICENSE", "NOTICE"):
        shutil.copy2(ROOT / name, notices / name)
    shutil.copy2(tools / "nsis-3.13/COPYING", notices / "NSIS-COPYING.txt")
    marker = {"schemaVersion": 1, "pluginId": PUBLIC_ID, "version": inputs["pluginVersion"],
              "platformBuild": "IC-" + lock["platformBuild"],
              "bundledPlugins": sorted(plugins | {"python-ce", "karyoforge"}),
              "sourceArtifacts": {name: {"url": value["url"], "sha256": value["sha256"]}
                                  for name, value in pins.items()},
              "pluginSha256": sha256(Path(inputs["pluginArchive"])),
              "brandingSha256": sha256(Path(inputs["brandingArchive"]))}
    write_json(app / MARKER, marker)
    write_json(notices / "COMPONENTS.json", marker)
    destination = WORK / "app"
    if destination.exists():
        if destination.resolve() != WORK.resolve() / "app" or destination.is_symlink():
            raise ValueError("Application output path escapes the build workspace")
        if destination.is_dir() and not any(destination.iterdir()):
            # Gradle creates declared output directories before executing an Exec task.
            destination.rmdir()
        else:
            remove_owned_stage(destination)
    app.rename(destination)
    validate_stage(destination, lock)
    print(f"Standalone IDE ready: {destination / 'bin/karyoforge.exe'}", flush=True)
    return destination


def validate_stage(app: Path, lock: dict) -> None:
    marker = json.loads((app / MARKER).read_text(encoding="utf-8"))
    product = json.loads((app / "product-info.json").read_text(encoding="utf-8"))
    if marker["pluginId"] != PUBLIC_ID or product["name"] != "KaryoForge":
        raise ValueError("Staged product identity mismatch")
    if product["buildNumber"] != lock["platformBuild"]:
        raise ValueError("Staged platform build mismatch")
    for name in ("bin/karyoforge.exe", "bin/karyoforge.exe.vmoptions", "jbr/bin/java.exe",
                 "lib/karyoforge-branding.jar", "LICENSE.txt", "NOTICE.txt",
                 "openkaryon-license/LICENSE", "openkaryon-license/NOTICE"):
        if not (app / name).is_file():
            raise ValueError(f"Missing distribution file: {name}")
    actual = {path.name for path in (app / "plugins").iterdir() if path.is_dir()}
    if actual != set(lock["bundledPlugins"]) | {"python-ce", "karyoforge"}:
        raise ValueError("Unexpected bundled plugin directory")
    launch = product["launch"][0]
    if launch["bootClassPathJarNames"][0] != "karyoforge-branding.jar":
        raise ValueError("Branding does not precede SDK resources")


def nsis_quote(value: str) -> str:
    return value.replace("$", "$$").replace('"', '$\\"').replace("\n", "$\\n")


def compile_installer(app: Path, inputs: dict) -> Path:
    compiler = WORK / "tools/nsis-3.13/makensis.exe"
    version = subprocess.check_output([str(compiler), "/VERSION"], text=True).strip()
    if version != "v3.13":
        raise ValueError("Unexpected installer compiler version")
    # Explicit file deletions preserve user-created files and projects during uninstall.
    files = sorted(path for path in app.rglob("*") if path.is_file())
    folders = sorted((path for path in app.rglob("*") if path.is_dir()), key=lambda path: len(path.parts), reverse=True)
    uninstall = WORK / "uninstall-files.nsh"
    uninstall.write_text("\n".join(
        ['Delete "$INSTDIR\\' + nsis_quote(str(path.relative_to(app))) + '"' for path in files]
        + ['RMDir "$INSTDIR\\' + nsis_quote(str(path.relative_to(app))) + '"' for path in folders]
    ) + "\n", encoding="utf-8")
    output = ROOT / "build/distributions" / f"KaryoForge-{inputs['pluginVersion']}-windows-x64-Setup.exe"
    building = output.with_name(output.stem + ".building.exe")
    run([compiler, "/NOCONFIG", "/V2", f"/DAPP_VERSION={inputs['pluginVersion']}",
         f"/DNUMERIC_VERSION={inputs['pluginVersion'].split('-', 1)[0]}.0", f"/DSTAGE={app}",
         f"/DOUTPUT={building}", f"/DUNINSTALL_FILES={uninstall}", ROOT / "installer/windows.nsi"], cwd=ROOT)
    building.replace(output)
    checksum(output)
    print(f"Installer ready: {output} ({output.stat().st_size:,} bytes)", flush=True)
    return output


def checksum(path: Path) -> None:
    path.with_name(path.name + ".sha256").write_text(sha256(path) + "  " + path.name + "\n", encoding="ascii")


def portable(app: Path, inputs: dict) -> Path:
    output = ROOT / "build/distributions" / f"KaryoForge-{inputs['pluginVersion']}-windows-x64.zip"
    with ZipFile(output, "w", ZIP_DEFLATED, compresslevel=4) as archive:
        for path in sorted(app.rglob("*")):
            if path.is_file():
                archive.write(path, "KaryoForge/" + path.relative_to(app).as_posix())
    checksum(output)
    print(f"Portable ZIP ready: {output}", flush=True)
    return output


def smoke(app: Path) -> None:
    product = json.loads((app / "product-info.json").read_text(encoding="utf-8"))
    launch = product["launch"][0]
    profile = Path(tempfile.mkdtemp(prefix="smoke-", dir=WORK))
    arguments = [value.replace("%IDE_HOME%", str(app)) for value in launch["additionalJvmArguments"]]
    arguments.extend(["-Xmx2g", "-Djava.awt.headless=true", "-Didea.home.path=" + str(app),
                      "-Dintellij.platform.load.app.info.from.resources=true",
                      "-Dide.browser.jcef.headless.enabled=false", "-Dide.no.platform.update=true",
                      "-Didea.config.path=" + str(profile / "config"),
                      "-Didea.system.path=" + str(profile / "system"),
                      "-Didea.plugins.path=" + str(profile / "plugins"),
                      "-Didea.log.path=" + str(profile / "log")])
    classpath = os.pathsep.join(str(app / "lib" / name) for name in launch["bootClassPathJarNames"])
    environment = {name: value for name, value in os.environ.items()
                   if name.upper() in {"SYSTEMROOT", "WINDIR", "TEMP", "TMP", "PATH", "PATHEXT", "USERPROFILE", "APPDATA", "LOCALAPPDATA"}}
    with (profile / "startup.log").open("w", encoding="utf-8") as log:
        run([app / "jbr/bin/java.exe", *arguments, "-classpath", classpath, "com.intellij.idea.Main",
             "traverseUI", profile / "search-options", "true"], stdout=log, stderr=subprocess.STDOUT,
            env=environment, cwd=app, timeout=180)
    options = {}
    for name in (PUBLIC_ID, "PythonCore"):
        file = profile / "search-options" / f"p-{name}-searchableOptions.json"
        if not file.is_file():
            raise ValueError(f"Missing settings index for {name}; see {profile}")
        options[name] = {json.loads(line)["id"] for line in file.read_text(encoding="utf-8").splitlines() if line.strip()}
    if (PUBLIC_ID + ".settings" not in options[PUBLIC_ID]
            or "com.jetbrains.python.configuration.PyIntegratedToolsModulesConfigurable" not in options["PythonCore"]):
        raise ValueError(f"Standalone startup did not expose both KaryoForge and Python settings; see {profile}")
    print(f"Standalone headless startup passed with bundled Java, PythonCore and KaryoForge; logs: {profile}", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("stage", "installer", "zip", "verify"))
    parser.add_argument("--inputs", type=Path, required=True)
    args = parser.parse_args()
    if os.name != "nt":
        raise ValueError("Windows distribution targets require Windows x64")
    inputs = json.loads(args.inputs.read_text(encoding="utf-8"))
    lock = json.loads((ROOT / "installer/windows-artifacts.json").read_text(encoding="utf-8"))
    validate_inputs(inputs, lock)
    if args.mode == "stage":
        stage(inputs, lock)
    else:
        app = WORK / "app"
        validate_stage(app, lock)
        if args.mode == "installer":
            compile_installer(app, inputs)
        elif args.mode == "zip":
            portable(app, inputs)
        else:
            smoke(app)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f"Windows distribution failed: {error}", file=sys.stderr)
        sys.exit(2)
