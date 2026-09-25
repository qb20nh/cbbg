import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bundle_candidate import bundle_candidate
from parity_evidence import EvidenceError, digest


class BundleCandidateTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.destination = self.root / 'bundle'
        self.target = {'id': '26.3-fabric', 'loader': 'fabric',
                       'buildProfile': 'fabric-modern', 'minecraft': '26.3'}
        self.state = self.enterContext(patch('bundle_candidate.source_state',
                                            return_value=('a' * 40, False)))
        self.enterContext(patch('bundle_candidate.load_catalog', return_value={}))
        self.enterContext(patch('bundle_candidate.select_targets', return_value=[self.target]))
        self.enterContext(patch('bundle_candidate.required_runs',
                               return_value=[{'suite': 'ordinary'}]))
        self.prepare = self.enterContext(patch('bundle_candidate.prepare', return_value={'schema': 2}))
        self.verify = self.enterContext(patch('bundle_candidate.verify_candidate'))
        for name in ('targets.json', 'metadata.json', 'runtime.json', 'dependencies.json'):
            (self.root / name).write_text('{}')
        (self.root / 'contract.json').write_text(json.dumps({'ordinaryMetadata': 'metadata.json'}))
        self.outputs = {'schema': 1, 'target': '26.3-fabric', 'source_commit': 'a' * 40,
                        'source_dirty': False, 'version': '1.4.0+mc26.3-fabric'}
        for kind in ('artifact', 'sources', 'source_inventory', 'driver'):
            path = self.root / (kind + '.bin')
            path.write_bytes(kind.encode())
            self.outputs[kind] = {'path': path.name, 'filename': path.name, 'sha256': digest(path)}
        self.outputs['drivers'] = {'ordinary': self.outputs.pop('driver')}

    def bundle(self):
        outputs = self.root / 'outputs.json'
        outputs.write_text(json.dumps(self.outputs))
        return bundle_candidate(self.root, outputs, self.destination, 'v1.4.0',
                                self.root / 'contract.json', self.root / 'runtime.json',
                                self.root / 'dependencies.json')

    def test_copies_inputs_and_records_checksums(self):
        manifest = self.bundle()
        self.assertEqual(manifest, self.destination / 'candidate.json')
        self.assertEqual((self.destination / 'artifact.bin').read_bytes(), b'artifact')
        for line in (self.destination / 'SHA256SUMS').read_text().splitlines():
            checksum, name = line.split('  ')
            self.assertEqual(checksum, digest(self.destination / name))
        self.verify.assert_called_once_with(manifest, '26.3-fabric', self.root)

    def test_existing_destination_is_preserved(self):
        self.destination.mkdir()
        marker = self.destination / 'keep'
        marker.write_text('keep')
        with self.assertRaisesRegex(EvidenceError, 'already exists'):
            self.bundle()
        self.assertEqual(marker.read_text(), 'keep')

    def test_reserved_and_escaping_filenames_are_rejected(self):
        for name in ('candidate.json', 'SHA256SUMS', '../outside', 'dir/file', 'dir\\file'):
            with self.subTest(name=name):
                self.outputs['artifact']['filename'] = name
                with self.assertRaisesRegex(EvidenceError, 'filename'):
                    self.bundle()
                self.assertFalse(self.destination.exists())

    def test_missing_driver_is_rejected(self):
        self.outputs['drivers'] = {}
        with self.assertRaisesRegex(EvidenceError, 'required test drivers'):
            self.bundle()

    def test_changed_input_is_rejected(self):
        (self.root / 'artifact.bin').write_bytes(b'changed')
        with self.assertRaises(ValueError):
            self.bundle()
        self.assertFalse(self.destination.exists())

    def test_wrong_build_identity_is_rejected(self):
        for field, value in (('source_commit', 'b' * 40), ('source_dirty', True),
                             ('version', '1.3.0+mc26.3-fabric')):
            with self.subTest(field=field):
                original = self.outputs[field]
                self.outputs[field] = value
                with self.assertRaises(EvidenceError):
                    self.bundle()
                self.outputs[field] = original
        self.assertFalse(self.destination.exists())

    def test_validation_failure_removes_new_bundle(self):
        self.verify.side_effect = EvidenceError('invalid package')
        with self.assertRaisesRegex(EvidenceError, 'invalid package'):
            self.bundle()
        self.assertFalse(self.destination.exists())

    def test_source_change_during_copy_removes_bundle(self):
        self.state.side_effect = [('a' * 40, False), ('b' * 40, False)]
        with self.assertRaisesRegex(EvidenceError, 'Source changed'):
            self.bundle()
        self.assertFalse(self.destination.exists())


if __name__ == '__main__':
    unittest.main()
