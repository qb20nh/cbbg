import copy
import hashlib
import json
from pathlib import Path
import tempfile
import struct
import subprocess
import sys
import unittest
import warnings
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fabric_package import FabricPackageError, verify_candidate_package, verify_fabric_metadata


class FabricPackageTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.jar = Path(self.directory.name) / 'candidate.jar'
        self.target = {'loader': 'fabric', 'minecraft': '26.3', 'java': 25,
                       'dependencies': {'loader': '0.19.5'}}
        self.metadata = {
            'schemaVersion': 1, 'id': 'cbbg', 'environment': 'client', 'version': '1.4.0',
            'depends': {'fabricloader': '>=0.19.5', 'minecraft': '26.3',
                        'java': '>=25', 'fabric-api': '*'},
            'entrypoints': {'client': ['example.Client']},
            'mixins': ['cbbg.mixins.json']}
        self.mixin = {'required': True, 'compatibilityLevel': 'JAVA_25',
                      'package': 'example.mixin', 'client': ['RenderMixin']}

    def write(self, omit=()):
        entries = {'fabric.mod.json': json.dumps(self.metadata),
                   'cbbg.mixins.json': json.dumps(self.mixin),
                   'example/Client.class': b'class',
                   'example/mixin/RenderMixin.class': b'class'}
        with zipfile.ZipFile(self.jar, 'w') as archive:
            for name, contents in entries.items():
                if name not in omit:
                    archive.writestr(name, contents)

    def verify(self):
        return verify_fabric_metadata(self.jar, self.target, '1.4.0')

    def test_complete_package(self):
        self.write()
        self.assertEqual(self.verify(), {'declared_classes': 2, 'mixin_configs': 1})

    def test_wrong_metadata(self):
        original = copy.deepcopy(self.metadata)
        for field, value in [('schemaVersion', True), ('id', 'other'),
                             ('environment', '*'), ('version', '1.3.0')]:
            with self.subTest(field=field):
                self.metadata = copy.deepcopy(original)
                self.metadata[field] = value
                self.write()
                with self.assertRaises(FabricPackageError):
                    self.verify()

    def test_wrong_dependencies(self):
        for dependency in self.metadata['depends']:
            with self.subTest(dependency=dependency):
                previous = self.metadata['depends'][dependency]
                self.metadata['depends'][dependency] = 'wrong'
                self.write()
                with self.assertRaisesRegex(FabricPackageError, 'Dependencies'):
                    self.verify()
                self.metadata['depends'][dependency] = previous

    def test_missing_resource_or_class(self):
        for name in ['fabric.mod.json', 'cbbg.mixins.json', 'example/Client.class',
                     'example/mixin/RenderMixin.class']:
            with self.subTest(name=name):
                self.write(omit=[name])
                with self.assertRaises(FabricPackageError):
                    self.verify()

    def test_invalid_mixin_settings(self):
        original = copy.deepcopy(self.mixin)
        for field, value in [('required', False), ('compatibilityLevel', 'JAVA_21'),
                             ('package', ''), ('client', []), ('client', 'RenderMixin'),
                             ('plugin', 'example.Missing'), ('refmap', 'missing.json'),
                             ('refmap', False)]:
            with self.subTest(field=field, value=value):
                self.mixin = copy.deepcopy(original)
                self.mixin[field] = value
                self.write()
                with self.assertRaises(FabricPackageError):
                    self.verify()

    def test_invalid_entrypoints(self):
        for value in [{}, {'client': []}, {'client': 'example.Client'},
                      {'client': [None]}, {'client': ['../Client']}]:
            with self.subTest(value=value):
                self.metadata['entrypoints'] = value
                self.write()
                with self.assertRaises(FabricPackageError):
                    self.verify()

    def test_missing_invalid_or_duplicate_mixin_list(self):
        for value in [None, [], ['../cbbg.mixins.json'],
                      ['cbbg.mixins.json', 'cbbg.mixins.json'], [False]]:
            with self.subTest(value=value):
                self.metadata['mixins'] = value
                self.write()
                with self.assertRaises(FabricPackageError):
                    self.verify()

    def test_quilt_requires_its_own_validation(self):
        self.write()
        self.target['loader'] = 'quilt'
        with self.assertRaisesRegex(FabricPackageError, 'Fabric target'):
            self.verify()

    def test_duplicate_archive_entry(self):
        self.write()
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(self.jar, 'a') as archive:
                archive.writestr('fabric.mod.json', json.dumps(self.metadata))
        with self.assertRaisesRegex(FabricPackageError, 'Duplicate'):
            self.verify()

    def test_invalid_metadata_json(self):
        for contents in ['{', '[]', 'null', b'\xff']:
            with self.subTest(contents=contents):
                self.write(omit=['fabric.mod.json'])
                with zipfile.ZipFile(self.jar, 'a') as archive:
                    archive.writestr('fabric.mod.json', contents)
                with self.assertRaises(FabricPackageError):
                    self.verify()

    def test_candidate_requires_matching_source_inventory(self):
        root = Path(self.directory.name)
        core_root = root / 'core/src/main/java'
        core_file = core_root / 'example/Core.java'
        core_file.parent.mkdir(parents=True)
        core_file.write_text('package example; class Core {}')
        self.write()
        with zipfile.ZipFile(self.jar) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()}
        for name in entries:
            if name.endswith('.class'):
                entries[name] = struct.pack('>IHH', 0xCAFEBABE, 0, 69)
        entries['example/Core.class'] = struct.pack('>IHH', 0xCAFEBABE, 0, 52)
        with zipfile.ZipFile(self.jar, 'w') as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
        sources = root / 'sources.jar'
        with zipfile.ZipFile(sources, 'w') as archive:
            archive.writestr('example/Core.java', core_file.read_bytes())
        inventory = root / 'source-inventory.json'
        inventory.write_text(json.dumps({'schema': 1, 'sources': [{
            'archive_path': 'example/Core.java',
            'source_path': core_file.relative_to(root).as_posix(),
            'sha256': hashlib.sha256(core_file.read_bytes()).hexdigest()}]}))

        def reference(path):
            return {'path': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}

        target = {'artifact': reference(self.jar), 'sources': reference(sources)}
        with self.assertRaisesRegex(FabricPackageError, 'missing its source inventory'):
            verify_candidate_package(root, target, self.target, '1.4.0', root)
        target['source_inventory'] = reference(inventory)
        result = verify_candidate_package(root, target, self.target, '1.4.0', root)
        self.assertEqual(result['sources']['java_sources'], 1)
        self.assertEqual(result['packaging']['core_classes'], 1)

        from parity_evidence import catalog_digest
        from targets import load_catalog
        catalog = load_catalog()
        catalog_file = root / 'catalog.json'
        catalog_file.write_text(json.dumps(catalog))
        self.metadata['version'] = '1.4.0+mc26.3-fabric'
        entries['fabric.mod.json'] = json.dumps(self.metadata).encode()
        with zipfile.ZipFile(self.jar, 'w') as archive:
            for name, data in entries.items():
                archive.writestr(name, data)
        target.update(id='26.3-fabric', artifact=reference(self.jar),
                      client_tests={'catalog': reference(catalog_file)})
        manifest = root / 'candidate.json'
        manifest.write_text(json.dumps({
            'schema': 2, 'release': 'v1.4.0', 'commit': 'a' * 40,
            'selected_targets': ['26.3-fabric'], 'catalog_sha256': catalog_digest(catalog),
            'targets': [target]}))
        command = [sys.executable, str(Path(__file__).resolve().parents[1] / 'fabric_package.py'),
                   '--candidate', str(manifest), '--target', '26.3-fabric', '--source-root', str(root)]
        completed = subprocess.run(command, text=True, capture_output=True, timeout=10)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        report = json.loads(completed.stdout)
        self.assertEqual(report['checks']['sources']['java_sources'], 1)
        self.assertFalse(report['releaseAcceptance'])
        inventory.write_text('{}')
        with self.assertRaises(ValueError):
            verify_candidate_package(root, target, self.target, self.metadata['version'], root)
        completed = subprocess.run(command, text=True, capture_output=True, timeout=10)
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn('Changed evidence file', completed.stderr)


if __name__ == '__main__':
    unittest.main()
