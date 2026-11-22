"""Offline fixture integrity and ownership checks, without network or package installation."""

import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

TOOL = Path(__file__).resolve().parents[1] / 'notebook_fixtures.py'
SPEC = importlib.util.spec_from_file_location('notebook_fixtures', TOOL)
FIXTURES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURES)


class NotebookFixturesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='forge notebook fixtures ')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.payload = b'owned wheel fixture'
        sha = hashlib.sha256(self.payload).hexdigest()
        self.lock = dict(schemaVersion=1, python='3.14', platform='win_amd64',
                         roots={'jupyter_client': '8.10.0', 'ipykernel': '7.4.0'}, packages=[])
        for name, version in self.lock['roots'].items():
            filename = f'{name}-{version}-py3-none-any.whl'
            self.lock['packages'].append(dict(name=name, version=version, filename=filename,
                url='https://files.pythonhosted.org/packages/owned/' + filename, sha256=sha))
        self.lock_file = self.root / 'lock.json'
        self.write_lock()

    def write_lock(self):
        self.lock_file.write_text(json.dumps(self.lock) + '\n', encoding='utf-8')

    def prepared(self):
        output = self.root / 'fixtures'
        FIXTURES.owned_directory(output, FIXTURES.OWNER, FIXTURES.digest(self.lock_file))
        (output / 'wheelhouse').mkdir()
        for item in self.lock['packages']:
            (output / 'wheelhouse' / item['filename']).write_bytes(self.payload)
        FIXTURES.prepare(output, self.lock_file, offline=True)
        return output

    def test_cached_wheels_are_verified_and_tampering_fails_even_with_a_changed_manifest(self):
        output = self.prepared()
        self.assertEqual(self.lock, FIXTURES.verify(output))
        target = output / 'wheelhouse' / self.lock['packages'][0]['filename']
        target.write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError, 'integrity'):
            FIXTURES.verify(output)
        manifest_file = output / 'manifest.json'
        manifest = json.loads(manifest_file.read_text())
        manifest['files'][target.relative_to(output).as_posix()] = FIXTURES.digest(target)
        manifest_file.write_text(json.dumps(manifest), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'does not match its lock'):
            FIXTURES.verify(output)

    def test_missing_offline_wheel_never_uses_network(self):
        with patch.object(FIXTURES.urllib.request, 'urlopen') as network:
            with self.assertRaisesRegex(ValueError, 'Missing or mismatched'):
                FIXTURES.prepare(self.root / 'empty', self.lock_file, offline=True)
            network.assert_not_called()

    def test_wrong_download_hash_is_rejected_without_publishing_a_wheel(self):
        output = self.root / 'download'
        with patch.object(FIXTURES.urllib.request, 'urlopen', return_value=io.BytesIO(b'wrong')):
            with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
                FIXTURES.prepare(output, self.lock_file)
        self.assertEqual([], list((output / 'wheelhouse').iterdir()))

    def test_path_escape_external_origin_and_duplicate_package_identities_are_rejected(self):
        for field, bad in [('filename', '../escape.whl'), ('url', 'https://example.test/foreign.whl')]:
            original = self.lock['packages'][0][field]
            self.lock['packages'][0][field] = bad
            self.write_lock()
            with self.assertRaisesRegex(ValueError, 'wheel identity'):
                FIXTURES.read_lock(self.lock_file)
            self.lock['packages'][0][field] = original
        self.lock['packages'].append(dict(self.lock['packages'][0]))
        self.write_lock()
        with self.assertRaisesRegex(ValueError, 'wheel identity'):
            FIXTURES.read_lock(self.lock_file)

    def test_lf_and_crlf_lock_files_are_copied_byte_for_byte(self):
        for index, newline in enumerate(("\n", "\r\n")):
            with self.subTest(newline=repr(newline)):
                self.lock_file.write_bytes((json.dumps(self.lock) + newline).encode('utf-8'))
                output = self.root / ('line-endings-' + str(index))
                FIXTURES.owned_directory(output, FIXTURES.OWNER, FIXTURES.digest(self.lock_file))
                (output / 'wheelhouse').mkdir()
                for item in self.lock['packages']:
                    (output / 'wheelhouse' / item['filename']).write_bytes(self.payload)
                with patch.object(FIXTURES.urllib.request, 'urlopen') as network:
                    FIXTURES.prepare(output, self.lock_file, offline=True)
                    network.assert_not_called()
                self.assertEqual(self.lock_file.read_bytes(), (output / 'notebook-test-wheels.json').read_bytes())
                self.assertEqual(self.lock, FIXTURES.verify(output))

    def test_unowned_or_different_lock_output_is_never_reused_or_deleted(self):
        output = self.root / 'unowned'
        output.mkdir()
        sentinel = output / 'keep.txt'
        sentinel.write_text('preserved', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'unowned nonempty'):
            FIXTURES.owned_directory(output, FIXTURES.OWNER, 'a' * 64)
        self.assertEqual('preserved', sentinel.read_text())
        owned = FIXTURES.owned_directory(self.root / 'owned', FIXTURES.RUNTIME_OWNER, 'a' * 64)
        with self.assertRaisesRegex(ValueError, 'identity does not match'):
            FIXTURES.owned_directory(owned, FIXTURES.RUNTIME_OWNER, 'b' * 64)

    def test_nested_link_or_windows_junction_cannot_redirect_fixture_writes(self):
        output = FIXTURES.owned_directory(self.root / 'linked-fixtures', FIXTURES.OWNER, FIXTURES.digest(self.lock_file))
        outside = self.root / 'outside'
        outside.mkdir()
        sentinel = outside / 'preserved.txt'
        sentinel.write_text('not a fixture', encoding='utf-8')
        junction = output / 'wheelhouse'
        if os.name == 'nt':
            import _winapi
            _winapi.CreateJunction(str(outside), str(junction))
        else:
            junction.symlink_to(outside, target_is_directory=True)
        try:
            with patch.object(FIXTURES.urllib.request, 'urlopen') as network:
                with self.assertRaisesRegex(ValueError, 'link|junction'):
                    FIXTURES.prepare(output, self.lock_file)
                network.assert_not_called()
            self.assertEqual('not a fixture', sentinel.read_text())
            self.assertEqual(['preserved.txt'], [path.name for path in outside.iterdir()])
        finally:
            if os.name == 'nt':
                junction.rmdir()
            else:
                junction.unlink()

    def test_wrong_base_runtime_fails_before_creating_an_environment(self):
        output = self.prepared()
        python = self.root / 'python.exe'
        python.write_bytes(b'not executed')
        runtime = self.root / 'runtime'
        with patch.object(FIXTURES.subprocess, 'run') as run:
            run.return_value.stdout = json.dumps(['CPython', [3, 13], 'win32', 64])
            with self.assertRaisesRegex(ValueError, 'CPython 3.14'):
                FIXTURES.create_runtime(python, runtime, output)
            self.assertEqual(1, run.call_count)
            self.assertFalse(runtime.exists())


if __name__ == '__main__':
    unittest.main()
