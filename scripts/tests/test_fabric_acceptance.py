import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fabric_acceptance import ROOT, required_runs, verify_results, verify_candidate_results
from parity_evidence import catalog_digest, digest, read_json
from runtime_catalog import load_catalog, select_targets


class FabricAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.target = select_targets(load_catalog(), '26.3-fabric')[0]
        self.contract = read_json(ROOT / 'runtime-locks/26.3-fabric-scenarios.json')

    def test_ordinary_runs_cover_every_catalog_configuration(self):
        runs = required_runs(self.target, self.contract)
        actual = {(run['profile'], run['backend']) for run in runs if run['suite'] == 'ordinary'}
        expected = {(profile, backend) for profile, backends in self.target['compatibilityProfiles'].items()
                    for backend in backends}
        self.assertEqual(actual, expected)

    def test_restart_runs_follow_shader_profiles(self):
        runs = required_runs(self.target, self.contract)
        for suite, mod, backend in [('iris-restart', 'iris', 'opengl'),
                                    ('sulkan-restart', 'sulkan', 'vulkan'),
                                    ('sulkan-external-restart', 'sulkan', 'vulkan')]:
            selected = [run for run in runs if run['suite'] == suite]
            self.assertEqual({run['profile'] for run in selected}, {
                profile for profile in self.target['compatibilityProfiles'] if mod in profile.split('+')})
            self.assertTrue(all(run['restart'] and run['backend'] == backend for run in selected))

    def test_added_shader_profile_requires_restart_runs(self):
        self.target['compatibilityProfiles']['sulkan+modmenu'] = ['vulkan']
        selected = [run['suite'] for run in required_runs(self.target, self.contract)
                    if run['profile'] == 'sulkan+modmenu']
        self.assertEqual(set(selected), {'ordinary', 'sulkan-restart', 'sulkan-external-restart'})

    def test_duplicate_suite_rejected(self):
        self.contract['additionalRuns'].append(copy.deepcopy(self.contract['additionalRuns'][0]))
        with self.assertRaisesRegex(ValueError, 'duplicate suite'):
            required_runs(self.target, self.contract)

    def test_unknown_profile_rejected(self):
        self.contract['additionalRuns'][0]['profiles'] = ['missing']
        with self.assertRaisesRegex(ValueError, 'Invalid suite selection'):
            required_runs(self.target, self.contract)

    def test_empty_shader_selection_rejected(self):
        self.contract['additionalRuns'][2]['requiresMod'] = 'missing'
        with self.assertRaisesRegex(ValueError, 'Invalid suite selection'):
            required_runs(self.target, self.contract)


class FabricResultMatrixTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.index = self.root / 'results.json'
        self.contract = ROOT / 'runtime-locks/26.3-fabric-scenarios.json'
        self.target = select_targets(load_catalog(), '26.3-fabric')[0]
        self.runs = required_runs(self.target, read_json(self.contract))
        self.drivers = {run['suite']: 'd' * 64 for run in self.runs}
        self.rows = []
        self.results = {}
        for number, run in enumerate(self.runs):
            path = self.root / (str(number) + '.json')
            path.write_text('{}')
            self.rows.append({key: run[key] for key in ('suite', 'profile', 'backend')})
            self.rows[-1]['receipt'] = {'path': path.name, 'sha256': digest(path)}
            self.results[path] = {'target': self.target['id'], 'profile': run['profile'],
                                  'backend': run['backend'], 'scenarios': run['entrypoints'],
                                  'releaseAcceptance': False}
        # Run-file checks have their own tests; exercise matrix selection and dispatch here.
        self.ordinary = self.enterContext(patch('fabric_acceptance.verify_run', side_effect=self.check))
        self.restart = self.enterContext(patch('fabric_acceptance.verify_restart', side_effect=self.check))

    def check(self, receipt, target, **inputs):
        self.assertEqual(inputs['source_commit'], 'a' * 40)
        self.assertEqual(inputs['candidate_sha256'], 'c' * 64)
        self.assertEqual(inputs['driver_sha256'], 'd' * 64)
        return self.results[receipt]

    def verify(self):
        self.index.write_text(json.dumps(self.rows))
        return verify_results(self.index, self.target, self.contract, self.drivers,
                              source_commit='a' * 40, candidate_sha256='c' * 64,
                              catalog_path=ROOT / 'targets.json',
                              runtime_lock_path=ROOT / 'runtime-locks/26.3-fabric-linux-x86_64.json',
                              dependency_lock_path=ROOT / 'runtime-locks/26.3-fabric-mods.json')

    def test_complete_matrix_dispatches_to_both_validators(self):
        result = self.verify()
        self.assertEqual(len(result['runs']), len(self.runs))
        self.assertEqual(self.restart.call_count, sum(run['restart'] for run in self.runs))
        self.assertEqual(self.ordinary.call_count, sum(not run['restart'] for run in self.runs))
        self.assertEqual(result['contract_sha256'], digest(self.contract))
        self.assertFalse(result['releaseAcceptance'])

    def test_missing_run_rejected_before_validation(self):
        self.rows.pop()
        with self.assertRaisesRegex(ValueError, 'coverage differs'):
            self.verify()
        self.ordinary.assert_not_called()
        self.restart.assert_not_called()

    def test_duplicate_run_rejected(self):
        self.rows.append(copy.deepcopy(self.rows[0]))
        with self.assertRaisesRegex(ValueError, 'Duplicate run result'):
            self.verify()

    def test_reused_receipt_rejected(self):
        self.rows[1]['receipt'] = self.rows[0]['receipt']
        with self.assertRaisesRegex(ValueError, 'Receipt reused'):
            self.verify()

    def test_wrong_backend_rejected(self):
        self.results[self.root / '0.json']['backend'] = 'unexpected'
        with self.assertRaisesRegex(ValueError, 'differs from required configuration'):
            self.verify()

    def test_incomplete_scenario_list_rejected(self):
        self.results[self.root / '0.json']['scenarios'] = ['Example.incomplete']
        with self.assertRaisesRegex(ValueError, 'differs from required configuration'):
            self.verify()

    def test_missing_driver_hash_rejected(self):
        self.drivers.pop('ordinary')
        with self.assertRaisesRegex(ValueError, 'hashes must cover'):
            self.verify()

    def test_changed_receipt_rejected(self):
        (self.root / '0.json').write_text('{"changed":true}')
        with self.assertRaisesRegex(ValueError, 'Changed evidence file'):
            self.verify()

    def test_failed_restart_rejected(self):
        self.restart.side_effect = ValueError('Restart run failed')
        with self.assertRaisesRegex(ValueError, 'Restart run failed'):
            self.verify()


class FabricCandidateTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.catalog = load_catalog()
        self.target = '26.3-fabric'
        (self.root / 'catalog.json').write_text(json.dumps(self.catalog))
        (self.root / 'source-inventory.json').write_text('{"schema": 1, "sources": []}')
        for name in ('artifact.jar', 'sources.jar', 'driver.jar'):
            (self.root / name).write_bytes(name.encode())
        for source, name in [
                ('runtime-locks/26.3-fabric-scenarios.json', 'contract.json'),
                ('runtime-locks/26.3-fabric-linux-x86_64.json', 'runtime.json'),
                ('runtime-locks/26.3-fabric-mods.json', 'mods.json'),
                ('renderers/renderpearl/src/gametest/resources/fabric.mod.json', 'metadata.json')]:
            (self.root / name).write_bytes((ROOT / source).read_bytes())
        runs = required_runs(select_targets(self.catalog, self.target)[0], read_json(self.root / 'contract.json'))
        inventory = [{'id': self.target, 'artifact': 'artifact.jar', 'sources': 'sources.jar',
                      'source_inventory': 'source-inventory.json',
                      'client_tests': {'catalog': 'catalog.json', 'contract': 'contract.json',
                                       'ordinary_metadata': 'metadata.json', 'runtime_lock': 'runtime.json',
                                       'dependency_lock': 'mods.json',
                                       'drivers': {run['suite']: 'driver.jar' for run in runs}}}]
        def reference(name):
            return {'path': name, 'sha256': digest(self.root / name)}

        record = inventory[0]
        for key in ('artifact', 'sources', 'source_inventory'):
            record[key] = reference(record[key])
        client = record['client_tests']
        for key in ('catalog', 'contract', 'ordinary_metadata', 'runtime_lock', 'dependency_lock'):
            client[key] = reference(client[key])
        client['drivers'] = {suite: reference(name) for suite, name in client['drivers'].items()}
        self.manifest = {'schema': 2, 'release': 'v2.0.0-rc.1', 'commit': 'a' * 40,
                         'catalog_sha256': catalog_digest(self.catalog),
                         'selected_targets': [self.target], 'targets': inventory}
        self.path = self.root / 'candidate.json'
        self.path.write_text(json.dumps(self.manifest))
        self.results = self.enterContext(patch('fabric_acceptance.verify_results',
                                              return_value={'releaseAcceptance': False}))

    def verify(self):
        return verify_candidate_results(self.path, self.target, self.root / 'results.json')

    def test_candidate_supplies_expected_artifacts_and_source(self):
        result = self.verify()
        args, kwargs = self.results.call_args
        self.assertEqual(kwargs['source_commit'], 'a' * 40)
        self.assertEqual(kwargs['candidate_sha256'], digest(self.root / 'artifact.jar'))
        self.assertEqual(kwargs['metadata_path'], self.root / 'metadata.json')
        self.assertEqual(set(args[3].values()), {digest(self.root / 'driver.jar')})
        self.assertEqual(result['manifest_sha256'], digest(self.path))
        self.assertEqual(result['release'], 'v2.0.0-rc.1')
        self.assertFalse(result['releaseAcceptance'])

    def test_changed_candidate_artifact_rejected(self):
        (self.root / 'artifact.jar').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'Changed evidence file'):
            self.verify()
        self.results.assert_not_called()

    def test_changed_test_driver_rejected(self):
        (self.root / 'driver.jar').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'Changed evidence file'):
            self.verify()

    def test_changed_contract_rejected(self):
        (self.root / 'contract.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'Changed evidence file'):
            self.verify()

    def test_wrong_catalog_digest_rejected(self):
        self.manifest['catalog_sha256'] = '0' * 64
        self.path.write_text(json.dumps(self.manifest))
        with self.assertRaisesRegex(ValueError, 'catalog or target selection differs'):
            self.verify()

    def test_missing_target_rejected(self):
        with self.assertRaisesRegex(ValueError, 'absent from candidate'):
            verify_candidate_results(self.path, '26.2-fabric', self.root / 'results.json')


if __name__ == '__main__':
    unittest.main()
