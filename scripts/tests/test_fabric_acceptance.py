import copy
import unittest

from fabric_acceptance import ROOT, required_runs
from parity_evidence import read_json
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


if __name__ == '__main__':
    unittest.main()
