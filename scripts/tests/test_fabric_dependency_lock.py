import copy
import hashlib
from pathlib import Path
import tempfile
import unittest

from fabric_dependency_lock import verify_dependencies


class DependencyLockTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.jar = Path(self.temp.name) / 'mod.jar'
        self.jar.write_bytes(b'original')
        names = ['fabricApi', 'iris', 'sodium', 'renderScale', 'clothConfig']
        self.target = {'id': 'fixture', 'dependencies': dict.fromkeys(names, 'pin'),
                       'compatibilityProfiles': {'none': ['opengl'], 'iris+renderscale': ['opengl']}}
        self.lock = {'schemaVersion': 1, 'target': 'fixture', 'dependencies': {
            name: {'pin': 'pin', 'sha256': hashlib.sha256(b'original').hexdigest()}
            for name in names}}
        self.paths = dict.fromkeys(names, self.jar)

    def test_transitive_dependencies_required(self):
        verify_dependencies(self.target, 'iris+renderscale', self.paths, self.lock)
        for name in ('sodium', 'clothConfig'):
            paths = dict(self.paths)
            del paths[name]
            with self.assertRaises(ValueError):
                verify_dependencies(self.target, 'iris+renderscale', paths, self.lock)

    def test_none_accepts_only_api(self):
        verify_dependencies(self.target, 'none', {'fabricApi': self.jar}, self.lock)
        with self.assertRaises(ValueError):
            verify_dependencies(self.target, 'none', self.paths, self.lock)

    def test_changed_bytes_fail(self):
        self.jar.write_bytes(b'changed')
        with self.assertRaises(ValueError):
            verify_dependencies(self.target, 'iris+renderscale', self.paths, self.lock)

    def test_wrong_target_pin_profile_and_missing_entry_fail(self):
        for mutation in ('target', 'pin', 'entry'):
            lock = copy.deepcopy(self.lock)
            if mutation == 'target':
                lock['target'] = 'wrong'
            elif mutation == 'pin':
                lock['dependencies']['iris']['pin'] = 'different'
            else:
                del lock['dependencies']['iris']
            with self.assertRaises(ValueError):
                verify_dependencies(self.target, 'iris+renderscale', self.paths, lock)
        with self.assertRaises(ValueError):
            verify_dependencies(self.target, 'unknown', self.paths, self.lock)


if __name__ == '__main__':
    unittest.main()
