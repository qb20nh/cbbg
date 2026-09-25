import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from candidate_manifest import client_candidate
from fabric_package import verify_candidate
from parity_evidence import EvidenceError, catalog_digest, digest
from targets import load_catalog


class CandidateManifestTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.catalog = load_catalog()
        (self.root / 'catalog.json').write_text(json.dumps(self.catalog))
        for name in ('artifact.jar', 'sources.jar', 'inventory.json'):
            (self.root / name).write_bytes(name.encode())
        self.target = {'id': '26.3-fabric', 'artifact': self.reference('artifact.jar'),
                       'sources': self.reference('sources.jar'),
                       'source_inventory': self.reference('inventory.json'),
                       'client_tests': {'catalog': self.reference('catalog.json')}}
        self.manifest = {'schema': 2, 'release': 'v1.4.0-rc.1', 'commit': 'a' * 40,
                         'selected_targets': ['26.3-fabric'],
                         'catalog_sha256': catalog_digest(self.catalog),
                         'targets': [self.target]}
        self.path = self.root / 'candidate.json'

    def reference(self, name):
        return {'path': name, 'sha256': digest(self.root / name)}

    def write(self):
        self.path.write_text(json.dumps(self.manifest))

    def read(self):
        self.write()
        return client_candidate(self.path, '26.3-fabric')

    def test_valid_target(self):
        manifest, target, specification = self.read()
        self.assertEqual(manifest['commit'], 'a' * 40)
        self.assertEqual(target['artifact'], self.reference('artifact.jar'))
        self.assertEqual(specification['minecraft'], '26.3')

    def test_saved_runtime_candidates_without_inventory_remain_readable(self):
        del self.target['source_inventory']
        self.assertEqual(self.read()[1]['id'], '26.3-fabric')

    def test_changed_inventory_is_rejected(self):
        (self.root / 'inventory.json').write_text('changed')
        with self.assertRaisesRegex(EvidenceError, 'Changed evidence file'):
            self.read()

    def test_wrong_schema_and_selection(self):
        self.manifest['schema'] = True
        with self.assertRaisesRegex(EvidenceError, 'schema 2'):
            self.read()
        self.manifest['schema'] = 2
        self.manifest['selected_targets'] = ['26.2-fabric']
        with self.assertRaisesRegex(EvidenceError, 'catalog or target selection'):
            self.read()

    def test_shared_runtime_requires_matching_artifact_and_sources(self):
        self.manifest['selected_targets'].append('26.3-quilt')
        quilt = copy.deepcopy(self.target)
        quilt['id'] = '26.3-quilt'
        self.manifest['targets'].append(quilt)
        self.read()
        for kind in ('artifact', 'sources'):
            with self.subTest(kind=kind):
                original = quilt[kind]['sha256']
                quilt[kind]['sha256'] = '0' * 64
                with self.assertRaisesRegex(EvidenceError, 'Shared ' + kind):
                    self.read()
                quilt[kind]['sha256'] = original

    def test_package_validation_uses_explicit_candidate_version(self):
        self.write()
        with patch('fabric_package.verify_candidate_package', return_value={'checked': True}) as check:
            result = verify_candidate(self.path, '26.3-fabric', self.root)
        self.assertEqual(check.call_args.args[3], '1.4.0-rc.1+mc26.3-fabric')
        self.assertEqual(result['source_inventory_sha256'], self.target['source_inventory']['sha256'])
        self.assertEqual(result['manifest_sha256'], digest(self.path))
        self.assertFalse(result['releaseAcceptance'])

    def test_catalog_failure_prevents_package_validation(self):
        self.manifest['catalog_sha256'] = '0' * 64
        self.write()
        with patch('fabric_package.verify_candidate_package') as check:
            with self.assertRaisesRegex(EvidenceError, 'catalog or target selection'):
                verify_candidate(self.path, '26.3-fabric', self.root)
        check.assert_not_called()


if __name__ == '__main__':
    unittest.main()
