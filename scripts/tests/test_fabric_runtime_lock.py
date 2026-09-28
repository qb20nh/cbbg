import hashlib
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
            'versions/fabric/fabric.json': json.dumps({
                'id': 'fabric', 'inheritsFrom': 'base',
                'time': '2026-09-26T15:06:17+0000',
                'releaseTime': '2026-09-26T15:06:17+0000'}),
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

    def test_loader_profile_timestamps_do_not_change_runtime_identity(self):
        lock = capture_runtime(self.root, 'fabric', self.command)
        path = self.root / 'versions/fabric/fabric.json'
        profile = json.loads(path.read_text())
        profile['time'] = '2026-09-28T15:06:18+0000'
        profile['releaseTime'] = profile['time']
        path.write_text(json.dumps(profile))
        verify_runtime(self.root, 'fabric', self.command, lock)
        profile['libraries'] = [{'name': 'different:library:1.0'}]
        path.write_text(json.dumps(profile))
        with self.assertRaises(ValueError):
            verify_runtime(self.root, 'fabric', self.command, lock)

    def test_existing_schema_one_lock_uses_original_profile_hash(self):
        lock = capture_runtime(self.root, 'fabric', self.command)
        lock.pop('profileTimestampFieldsExcluded')
        path = self.root / 'versions/fabric/fabric.json'
        lock['files']['versions/fabric/fabric.json'] = hashlib.sha256(path.read_bytes()).hexdigest()
        verify_runtime(self.root, 'fabric', self.command, lock)

        profile = json.loads(path.read_text())
        profile['time'] = '2026-09-28T15:06:18+0000'
        path.write_text(json.dumps(profile))
        with self.assertRaises(ValueError):
            verify_runtime(self.root, 'fabric', self.command, lock)

    def test_unknown_profile_hash_policy_fails(self):
        lock = capture_runtime(self.root, 'fabric', self.command)
        lock['profileTimestampFieldsExcluded'] = ['releaseTime']
        with self.assertRaises(ValueError):
            verify_runtime(self.root, 'fabric', self.command, lock)

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
