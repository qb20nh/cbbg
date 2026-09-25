import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fabric_acceptance import ROOT, required_runs, verify_results
from parity_evidence import digest, read_json
from targets import load_catalog, select_targets


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


if __name__ == '__main__':
    unittest.main()
