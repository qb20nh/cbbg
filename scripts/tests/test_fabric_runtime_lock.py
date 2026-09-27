import json
import os
from pathlib import Path
import tempfile
import unittest

from fabric_runtime_lock import capture_runtime, verify_runtime


class RuntimeLockTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name, content in {
            'versions/fabric/fabric.json': json.dumps({'id': 'fabric', 'inheritsFrom': 'base'}),
            'versions/base/base.json': json.dumps({'id': 'base'}),
            'libraries/loader.jar': 'loader',
            'versions/base/base.jar': 'minecraft',
            'versions/base/natives/libtest.so': 'native',
        }.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        self.command = ['java', '-Djava.library.path=' + str(self.root / 'versions/base/natives'),
                        '-cp', os.pathsep.join(str(self.root / name) for name in
                        ('libraries/loader.jar', 'versions/base/base.jar')), 'Main',
                        '--accessToken', 'must-not-be-recorded']

    def test_round_trip_excludes_credentials_and_keeps_classpath_order(self):
        lock = capture_runtime(self.root, 'fabric', self.command)
        verify_runtime(self.root, 'fabric', self.command, lock)
        self.assertNotIn('must-not-be-recorded', json.dumps(lock))
        self.assertEqual(lock['classpath'], ['libraries/loader.jar', 'versions/base/base.jar'])

    def test_changed_library_metadata_native_and_added_native_fail(self):
        for name in ('libraries/loader.jar', 'versions/base/base.json',
                     'versions/base/natives/libtest.so', 'versions/base/natives/extra.so'):
            with self.subTest(name=name):
                lock = capture_runtime(self.root, 'fabric', self.command)
                path = self.root / name
                previous = path.read_bytes() if path.exists() else None
                path.write_text('{}' if name.endswith('.json') else 'changed')
                with self.assertRaises(ValueError):
                    verify_runtime(self.root, 'fabric', self.command, lock)
                if previous is None:
                    path.unlink()
                else:
                    path.write_bytes(previous)

    def test_reordered_classpath_fails(self):
        lock = capture_runtime(self.root, 'fabric', self.command)
        command = list(self.command)
        command[3] = os.pathsep.join(reversed(command[3].split(os.pathsep)))
        with self.assertRaises(ValueError):
            verify_runtime(self.root, 'fabric', command, lock)

    def test_profile_cycle_fails(self):
        (self.root / 'versions/base/base.json').write_text(
            json.dumps({'id': 'base', 'inheritsFrom': 'fabric'}))
        with self.assertRaises(ValueError):
            capture_runtime(self.root, 'fabric', self.command)

    def test_missing_and_external_classpath_fail(self):
        for path in (self.root / 'missing.jar', self.root.parent / 'external.jar'):
            command = list(self.command)
            command[3] = str(path)
            with self.assertRaises((ValueError, FileNotFoundError)):
                capture_runtime(self.root, 'fabric', command)


if __name__ == '__main__':
    unittest.main()
