#!/usr/bin/env python3
"""Prepare verified Windows notebook wheels and an explicitly owned offline test runtime."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request

HERE = Path(__file__).resolve().parent
DEFAULT_OUTPUT = HERE.parent / 'build/notebook-test-fixtures'
OWNER = 'org.openkaryon.karyoforge.notebook.fixtures'
RUNTIME_OWNER = 'org.openkaryon.karyoforge.notebook.runtime'
MAX_WHEEL_BYTES = 64 * 1024 * 1024


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def owned_directory(path: Path, owner: str, identity: str) -> Path:
    path = path.absolute()
    if path.is_symlink() or path.resolve() != path:
        raise ValueError('Owned fixture/runtime path must not traverse a symlink')
    path.mkdir(parents=True, exist_ok=True)
    marker = path / '.karyoforge-notebook-owner.json'
    expected = dict(owner=owner, schemaVersion=1, lockSha256=identity)
    if marker.is_symlink():
        raise ValueError('Fixture/runtime ownership marker must be a regular file')
    if marker.exists():
        if json.loads(marker.read_text(encoding='utf-8')) != expected:
            raise ValueError('Fixture/runtime ownership or wheel-lock identity does not match; choose a new output directory')
    elif any(path.iterdir()):
        raise ValueError('Refusing an unowned nonempty fixture/runtime directory')
    else:
        with marker.open('x', encoding='utf-8') as stream:
            stream.write(json.dumps(expected, indent=2) + '\n')
    return path


def plain_tree(path: Path) -> None:
    """Reject links and Windows junctions before writing or executing inside owned trees."""
    path = path.absolute()
    if path.is_symlink() or path.resolve() != path:
        raise ValueError('Owned fixture/runtime tree must not traverse links or junctions')
    for folder, directories, files in os.walk(path, followlinks=False):
        for name in directories + files:
            entry = Path(folder) / name
            if entry.is_symlink() or entry.resolve() != entry.absolute():
                raise ValueError('Owned fixture/runtime tree contains a link or junction')


def read_lock(path: Path) -> dict:
    lock = json.loads(path.read_text(encoding='utf-8'))
    if lock.get('schemaVersion') != 1 or lock.get('python') != '3.14' or lock.get('platform') != 'win_amd64':
        raise ValueError('Unsupported notebook test wheel lock')
    packages = lock.get('packages', [])
    if not 2 <= len(packages) <= 128:
        raise ValueError('Invalid notebook wheel inventory')
    names = set()
    files = set()
    versions = {}
    for package in packages:
        name, version, filename = package['name'], package['version'], package['filename']
        normalized = re.sub(r'[-_.]+', '-', name).lower()
        url = urllib.parse.urlsplit(package['url'])
        if (not re.fullmatch(r'[A-Za-z0-9_.-]+', name) or not re.fullmatch(r'[A-Za-z0-9_.+!-]+', version)
                or not re.fullmatch(r'[A-Za-z0-9_.-]+\.whl', filename) or normalized in names or filename in files
                or not re.fullmatch(r'[a-f0-9]{64}', package['sha256'])
                or url.scheme != 'https' or url.netloc != 'files.pythonhosted.org'
                or not url.path.startswith('/packages/') or url.path.rsplit('/', 1)[-1] != filename or url.query or url.fragment):
            raise ValueError('Invalid or duplicate notebook wheel identity')
        names.add(normalized)
        files.add(filename)
        versions[normalized] = version
    roots = lock.get('roots', {})
    if set(roots) != {'jupyter_client', 'ipykernel'} or any(versions.get(re.sub(r'[-_.]+', '-', name)) != version for name, version in roots.items()):
        raise ValueError('Notebook roots do not match the locked wheel inventory')
    return lock


def requirements(lock: dict) -> str:
    return ''.join(f"{item['name']}=={item['version']} --hash=sha256:{item['sha256']}\n" for item in lock['packages'])


def prepare(output: Path, lock_path: Path, offline: bool = False) -> Path:
    lock = read_lock(lock_path)
    output = owned_directory(output, OWNER, digest(lock_path))
    plain_tree(output)
    wheels = output / 'wheelhouse'
    if wheels.is_symlink():
        raise ValueError('Wheelhouse must not be a symlink')
    wheels.mkdir(exist_ok=True)
    for item in lock['packages']:
        target = wheels / item['filename']
        if target.is_symlink():
            raise ValueError('Wheel file must not be a symlink')
        if target.is_file() and digest(target) == item['sha256']:
            continue
        if offline:
            raise ValueError('Missing or mismatched offline notebook wheel: ' + item['filename'])
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=wheels, prefix='.download-', delete=False) as stream:
                temporary = Path(stream.name)
                with urllib.request.urlopen(item['url'], timeout=30) as response:
                    total = 0
                    while chunk := response.read(1024 * 1024):
                        total += len(chunk)
                        if total > MAX_WHEEL_BYTES:
                            raise ValueError('Notebook wheel exceeds its download bound')
                        stream.write(chunk)
            if digest(temporary) != item['sha256']:
                raise ValueError('Notebook wheel SHA-256 mismatch: ' + item['filename'])
            temporary.replace(target)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    (output / 'notebook-test-wheels.json').write_bytes(lock_path.read_bytes())
    (output / 'requirements.txt').write_text(requirements(lock), encoding='utf-8')
    shutil.copyfile(Path(__file__), output / 'prepare_runtime.py')
    expected = {'notebook-test-wheels.json', 'requirements.txt', 'prepare_runtime.py',
                'manifest.json', '.karyoforge-notebook-owner.json'} | {'wheelhouse/' + item['filename'] for item in lock['packages']}
    for path in output.rglob('*'):
        if path.is_symlink() or path.is_file() and path.relative_to(output).as_posix() not in expected:
            raise ValueError('Unexpected notebook fixture entry: ' + path.relative_to(output).as_posix())
    files = {path.relative_to(output).as_posix(): digest(path)
             for path in sorted(output.rglob('*')) if path.is_file() and path.name not in {'manifest.json', '.karyoforge-notebook-owner.json'}}
    manifest = dict(owner=OWNER, schemaVersion=1, lockSha256=digest(lock_path), files=files)
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    verify(output)
    print(f'Prepared {len(lock["packages"])} verified offline notebook wheels: {output}', flush=True)
    return output


def verify(fixtures: Path) -> dict:
    plain_tree(fixtures)
    if fixtures.is_symlink() or (fixtures / 'wheelhouse').is_symlink() or (fixtures / 'notebook-test-wheels.json').is_symlink():
        raise ValueError('Fixture root must not be a symlink')
    lock_path = fixtures / 'notebook-test-wheels.json'
    lock = read_lock(lock_path)
    manifest = json.loads((fixtures / 'manifest.json').read_text(encoding='utf-8'))
    if manifest.get('owner') != OWNER or manifest.get('schemaVersion') != 1 or manifest.get('lockSha256') != digest(lock_path):
        raise ValueError('Invalid notebook fixture manifest identity')
    expected = {'notebook-test-wheels.json', 'requirements.txt', 'prepare_runtime.py'} | {'wheelhouse/' + item['filename'] for item in lock['packages']}
    if set(manifest.get('files', {})) != expected:
        raise ValueError('Notebook fixture manifest inventory does not match its lock')
    for relative, expected_hash in manifest['files'].items():
        path = fixtures / relative
        if path.is_symlink() or not path.resolve().is_relative_to(fixtures.resolve()) or not path.is_file() or digest(path) != expected_hash:
            raise ValueError('Notebook fixture integrity failure: ' + relative)
    if (fixtures / 'requirements.txt').read_text(encoding='utf-8') != requirements(lock):
        raise ValueError('Notebook requirements do not match the wheel lock')
    for item in lock['packages']:
        if digest(fixtures / 'wheelhouse' / item['filename']) != item['sha256']:
            raise ValueError('Notebook wheel does not match its lock')
    return lock


def child_environment() -> dict:
    environment = {name: os.environ[name] for name in ('SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'PATH', 'PATHEXT') if name in os.environ}
    environment.update(PIP_CONFIG_FILE=os.devnull, PYTHONNOUSERSITE='1', PYTHONIOENCODING='utf-8')
    return environment


def create_runtime(base: Path, runtime: Path, fixtures: Path) -> Path:
    lock = verify(fixtures)
    if not base.is_absolute() or not base.is_file():
        raise ValueError('Choose an existing absolute base Python executable')
    probe = subprocess.run([str(base), '-I', '-c',
        "import json,platform,struct,sys;print(json.dumps([platform.python_implementation(),list(sys.version_info[:2]),sys.platform,struct.calcsize('P')*8]))"],
        env=child_environment(), check=True, capture_output=True, text=True, encoding='utf-8', timeout=20)
    if json.loads(probe.stdout) != ['CPython', [3, 14], 'win32', 64]:
        raise ValueError('The locked notebook test runtime requires Windows x64 CPython 3.14')
    runtime = owned_directory(runtime, RUNTIME_OWNER, digest(fixtures / 'notebook-test-wheels.json'))
    environment = runtime / 'venv'
    plain_tree(runtime)
    if environment.is_symlink():
        raise ValueError('Owned environment must not be a symlink')
    python = environment / 'Scripts/python.exe'
    if not python.exists():
        subprocess.run([str(base), '-I', '-m', 'venv', str(environment)], env=child_environment(), check=True, timeout=90)
    subprocess.run([str(python), '-I', '-m', 'pip', '--isolated', '--disable-pip-version-check', 'install',
                    '--no-input', '--no-index', '--only-binary=:all:', '--require-hashes', '--no-deps',
                    '--find-links', str(fixtures / 'wheelhouse'), '-r', str(fixtures / 'requirements.txt')],
                   env=child_environment(), check=True, timeout=120)
    subprocess.run([str(python), '-I', '-m', 'pip', '--isolated', '--disable-pip-version-check', 'check'],
                   env=child_environment(), check=True, timeout=30)
    report = subprocess.run([str(python), '-I', '-c',
        "import importlib.metadata,json,sys;print(json.dumps({'python':sys.executable,'version':sys.version.split()[0],'jupyter_client':importlib.metadata.version('jupyter_client'),'ipykernel':importlib.metadata.version('ipykernel')}))"],
        env=child_environment(), check=True, capture_output=True, text=True, encoding='utf-8', timeout=20)
    versions = json.loads(report.stdout)
    if any(versions[name] != version for name, version in lock['roots'].items()):
        raise ValueError('Prepared runtime does not match its locked notebook roots')
    (runtime / 'runtime.json').write_text(json.dumps(versions, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(versions), flush=True)
    return python


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--fixtures', type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument('--lock', type=Path, default=HERE / 'notebook-test-wheels.json')
    parser.add_argument('--python-executable', type=Path)
    parser.add_argument('--runtime', type=Path)
    args = parser.parse_args()
    if args.prepare:
        prepare(args.fixtures, args.lock, args.offline)
    if args.runtime is not None:
        if args.python_executable is None:
            parser.error('--runtime needs --python-executable')
        create_runtime(args.python_executable, args.runtime, args.fixtures)
    elif not args.prepare:
        verify(args.fixtures)


if __name__ == '__main__':
    main()
