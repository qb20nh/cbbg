import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from test_release import ROOT, release

sys.path.insert(0, str(ROOT / 'scripts'))


class CandidateMetadataTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'gradle.properties').write_text(
            'modrinth_project_id=UBlXUQbC\ncurseforge_project_id=1408371\n')
        self.path = self.root / 'candidate.json'
        self.manifest = {'release': 'v1.4.0', 'commit': 'a' * 40,
                         'selected_targets': ['26.3-fabric']}
        self.target = {'artifact': {'path': 'mod.jar', 'sha256': 'b' * 64},
                       'sources': {'path': 'sources.jar', 'sha256': 'c' * 64}}
        self.specification = {'id': '26.3-fabric', 'loader': 'fabric', 'minecraft': '26.3', 'java': 25}
        self.enterContext(patch('candidate_manifest.client_candidate',
                               side_effect=lambda *args: (self.manifest, self.target, self.specification)))
        self.package = self.enterContext(patch('fabric_package.verify_candidate'))

    def metadata(self):
        self.path.write_text(json.dumps(self.manifest))
        return release.candidate_metadata(self.path, self.root, 'Release notes')

    def test_labels_and_files_follow_selected_candidate(self):
        result = self.metadata()
        self.package.assert_called_once_with(self.path, '26.3-fabric', self.root)
        self.assertEqual(len(result['records']), 1)
        record = result['records'][0]
        self.assertEqual(record['targets'], ['26.3-fabric'])
        self.assertEqual(record['artifact'], self.target['artifact'])
        self.assertEqual(record['sources'], self.target['sources'])
        self.assertEqual(record['modrinth']['loaders'], ['fabric'])
        self.assertEqual(record['modrinth']['game_versions'], ['26.3'])
        self.assertEqual(record['modrinth']['required_projects'], ['fabric-api'])
        self.assertEqual(record['modrinth']['project_id'], 'UBlXUQbC')
        self.assertEqual(record['curseforge']['project_id'], '1408371')
        self.assertEqual(record['curseforge']['version_labels'],
                         ['26.3', 'Java 25', 'Fabric', 'Environment:Client'])

    def test_numbered_prerelease_uses_beta_channel(self):
        self.manifest['release'] = 'v1.4.0-rc.1'
        record = self.metadata()['records'][0]
        self.assertEqual(record['modrinth']['version_number'], '1.4.0-rc.1+mc26.3-fabric')
        self.assertEqual(record['modrinth']['version_type'], 'beta')
        self.assertEqual(record['curseforge']['release_type'], 'beta')

    def test_invalid_package_prevents_metadata(self):
        self.package.side_effect = ValueError('Invalid package')
        with self.assertRaisesRegex(ValueError, 'Invalid package'):
            self.metadata()

    def test_empty_selection_and_unknown_loader_are_rejected(self):
        self.manifest['selected_targets'] = []
        with self.assertRaisesRegex(ValueError, 'explicit target selection'):
            self.metadata()
        self.manifest['selected_targets'] = ['26.3-quilt']
        self.specification['loader'] = 'quilt'
        with self.assertRaisesRegex(ValueError, 'not configured'):
            self.metadata()

    def test_duplicate_project_property_is_rejected(self):
        with (self.root / 'gradle.properties').open('a') as stream:
            stream.write('modrinth_project_id=another\n')
        with self.assertRaisesRegex(ValueError, 'Invalid publishing project'):
            self.metadata()

    def test_destination_resolution_requires_every_label(self):
        metadata = self.metadata()
        original = copy.deepcopy(metadata)
        versions = [{'id': 1, 'name': '26.3', 'gameVersionTypeID': 10},
                    {'id': 2, 'name': 'Java 25', 'gameVersionTypeID': 11},
                    {'id': 3, 'name': 'Fabric', 'gameVersionTypeID': 12},
                    {'id': 4, 'name': 'Client', 'gameVersionTypeID': 13}]
        types = [{'id': 10, 'name': 'Minecraft 26.3'}, {'id': 13, 'name': 'Environment'}]
        with patch.dict('os.environ', {'CF_API_TOKEN': 'fixture'}):
            for complete in (True, False):
                with self.subTest(complete=complete), patch.object(release, 'get_json', side_effect=[
                        [{'version': '26.3'}], [{'name': 'fabric'}],
                        versions if complete else versions[:-1], types]):
                    if complete:
                        result = release.resolve_candidate_destinations(metadata)
                        self.assertEqual(result['records'][0]['curseforge']['game_versions'],
                                         ['1', '2', '3', '4'])
                    else:
                        with self.assertRaises(SystemExit):
                            release.resolve_candidate_destinations(metadata)
        self.assertEqual(metadata, original)

    def test_unavailable_modrinth_loader_is_rejected(self):
        metadata = self.metadata()
        with patch.dict('os.environ', {'CF_API_TOKEN': 'fixture'}), patch.object(
                release, 'get_json', side_effect=[[{'version': '26.3'}], [{'name': 'forge'}], [], []]):
            with self.assertRaisesRegex(ValueError, 'selected loaders'):
                release.resolve_candidate_destinations(metadata)


if __name__ == '__main__':
    unittest.main()
