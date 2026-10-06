import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from fabric_dependency_lock import locked_dependencies, verify_dependencies, verify_gametest_api


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

    def test_sulkan_requires_sodium(self):
        self.target['dependencies']['sulkan'] = 'pin'
        self.target['compatibilityProfiles']['sulkan'] = ['vulkan']
        self.target['compatibilityDependencyOverrides'] = {'sulkan': {'sodium': 'old-pin'}}
        self.lock['dependencies']['sulkan'] = dict(self.lock['dependencies']['sodium'])
        self.lock['dependencies']['sodium']['pin'] = 'old-pin'
        paths = dict.fromkeys(('fabricApi', 'sulkan', 'sodium'), self.jar)
        verify_dependencies(self.target, 'sulkan', paths, self.lock)
        del paths['sodium']
        with self.assertRaisesRegex(ValueError, 'Missing or extra'):
            verify_dependencies(self.target, 'sulkan', paths, self.lock)

    def test_profile_pin_does_not_apply_to_iris_or_plain_sodium(self):
        self.target['compatibilityDependencyOverrides'] = {'sulkan': {'sodium': 'old-pin'}}
        iris_paths = dict.fromkeys(('fabricApi', 'iris', 'sodium', 'renderScale', 'clothConfig'), self.jar)
        self.target['compatibilityProfiles']['sodium'] = ['opengl']
        sodium_paths = dict.fromkeys(('fabricApi', 'sodium'), self.jar)
        verify_dependencies(self.target, 'iris+renderscale', iris_paths, self.lock)
        verify_dependencies(self.target, 'sodium', sodium_paths, self.lock)
        self.lock['dependencies']['sodium']['pin'] = 'old-pin'
        for profile, paths in [('iris+renderscale', iris_paths), ('sodium', sodium_paths)]:
            with self.assertRaisesRegex(ValueError, 'catalog pin mismatch: sodium'):
                verify_dependencies(self.target, profile, paths, self.lock)
        self.target['compatibilityProfiles']['sulkan'] = ['vulkan']
        self.target['dependencies']['sulkan'] = 'pin'
        self.lock['dependencies']['sulkan'] = dict(self.lock['dependencies']['iris'])
        sulkan_paths = dict.fromkeys(('fabricApi', 'sulkan', 'sodium'), self.jar)
        verify_dependencies(self.target, 'sulkan', sulkan_paths, self.lock)
        self.lock['dependencies']['sodium']['pin'] = 'pin'
        with self.assertRaisesRegex(ValueError, 'catalog pin mismatch: sodium'):
            verify_dependencies(self.target, 'sulkan', sulkan_paths, self.lock)

    def test_profile_selects_alternate_version_and_its_checksum(self):
        alternate = Path(self.temp.name) / 'alternate.jar'
        alternate.write_bytes(b'alternate')
        self.target['compatibilityProfiles']['sodium'] = ['opengl']
        self.target['compatibilityDependencyOverrides'] = {'sodium': {'sodium': 'older'}}
        self.lock['dependencies']['sodium']['alternatives'] = {
            'older': {'sha256': hashlib.sha256(b'alternate').hexdigest()}}
        paths = {'fabricApi': self.jar, 'sodium': alternate}
        verify_dependencies(self.target, 'sodium', paths, self.lock)
        self.assertEqual(locked_dependencies(self.target, 'sodium', self.lock)['sodium']['pin'],
                         'older')
        paths['sodium'] = self.jar
        with self.assertRaisesRegex(ValueError, 'checksum mismatch: sodium'):
            verify_dependencies(self.target, 'sodium', paths, self.lock)
        verify_dependencies(self.target, 'iris+renderscale', self.paths, self.lock)

    def test_committed_release_locks_cover_every_declared_profile(self):
        root = Path(__file__).resolve().parents[2]
        catalog = json.loads((root / 'targets.json').read_text())
        for target in catalog['targets']:
            path = root / 'runtime-locks' / (target['id'] + '-mods.json')
            if not target.get('implemented') or target['loader'] != 'fabric':
                continue
            lock = json.loads(path.read_text())
            for profile in target['compatibilityProfiles']:
                with self.subTest(target=target['id'], profile=profile):
                    entries = locked_dependencies(target, profile, lock)
                    for entry in entries.values():
                        self.assertRegex(entry['sha256'], r'^[0-9a-f]{64}$')

    def test_chatpatches_requires_yacl(self):
        self.target['dependencies'].update(chatPatches='pin', yacl='pin')
        self.target['compatibilityProfiles']['chatpatches'] = ['opengl']
        for name in ('chatPatches', 'yacl'):
            self.lock['dependencies'][name] = dict(self.lock['dependencies']['fabricApi'])
        paths = dict.fromkeys(('fabricApi', 'chatPatches', 'yacl'), self.jar)
        verify_dependencies(self.target, 'chatpatches', paths, self.lock)
        del paths['yacl']
        with self.assertRaisesRegex(ValueError, 'Missing or extra'):
            verify_dependencies(self.target, 'chatpatches', paths, self.lock)

    def test_changed_bytes_fail(self):
        self.jar.write_bytes(b'changed')
        with self.assertRaises(ValueError):
            verify_dependencies(self.target, 'iris+renderscale', self.paths, self.lock)

    def test_gametest_api_requires_matching_pin_and_bytes(self):
        self.lock['gametestApi'] = {'fabricApiPin': 'pin',
                                  'sha256': hashlib.sha256(b'original').hexdigest()}
        verify_gametest_api(self.target, self.jar, self.lock)
        self.jar.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            verify_gametest_api(self.target, self.jar, self.lock)
        self.lock['gametestApi']['fabricApiPin'] = 'different'
        with self.assertRaisesRegex(ValueError, 'lock/catalog mismatch'):
            verify_gametest_api(self.target, self.jar, self.lock)

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
