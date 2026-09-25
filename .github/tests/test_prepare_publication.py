import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from test_release import ROOT

sys.path.insert(0, str(ROOT / '.github' / 'scripts'))
import prepare_publication as preflight


class PreparePublicationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / 'source'
        self.source.mkdir()
        self.assets = self.root / 'assets'
        self.output = self.root / 'publication.json'
        self.github_output = self.root / 'github_output'
        self.github_output.touch()
        self.commit = 'a' * 40
        self.tag = 'v1.4.0-rc.1'
        self.manifest = {'release': self.tag, 'commit': self.commit,
                         'selected_targets': [preflight.TARGET]}
        self.files = {name: preflight.digest(self.github_output) for name in
                      ('candidate.json', 'mod.jar', 'provenance.jsonl', 'SHA256SUMS')}
        self.record = {'targets': [preflight.TARGET],
                       'artifact': {'path': 'mod.jar', 'sha256': self.files['mod.jar']},
                       'modrinth': {'changelog': 'First line\nSecond line'},
                       'curseforge': {'project_id': '1408371',
                                      'version_labels': ['26.3', 'Java 25', 'Fabric', 'Environment:Client'],
                                      'display_name': 'cbbg 1.4.0-rc.1+mc26.3-fabric',
                                      'release_type': 'beta',
                                      'relations': 'fabric-api:requiredDependency',
                                      'changelog': 'First line\nSecond line'}}
        self.published = {'id': 12, 'tag_name': self.tag, 'draft': False,
                          'prerelease': True, 'immutable': True,
                          'body': 'First line\nSecond line',
                          'assets': [{'name': name, 'state': 'uploaded', 'digest': None}
                                     for name in self.files]}
        self.api_values = [
            {'sha': self.commit}, self.published,
            {'sha': self.commit}, self.published]
        self.api = self.enterContext(patch.object(preflight, 'api', side_effect=self.api_values))
        self.enterContext(patch.object(preflight, 'source_state', return_value=(self.commit, False)))
        self.enterContext(patch.object(preflight.subprocess, 'check_output', return_value=self.commit))
        self.candidate_files = self.enterContext(patch.object(
            preflight, 'candidate_files', return_value=(self.manifest, self.files)))
        self.enterContext(patch.object(preflight, 'client_candidate', return_value=(
            self.manifest, {'artifact': self.record['artifact']}, {'implemented': True, 'java': 25})))
        self.provenance = self.enterContext(patch.object(preflight, 'verify_provenance'))
        self.metadata = self.enterContext(patch.object(
            preflight.release, 'candidate_metadata', return_value={'records': [self.record]}))
        self.resolve = self.enterContext(patch.object(
            preflight.release, 'resolve_candidate_destinations',
            side_effect=lambda metadata, services: metadata))
        self.command = self.enterContext(patch.object(preflight, 'command', side_effect=self.download))

    def download(self, arguments):
        for name in self.files:
            (self.assets / name).touch()
        return ''

    def run_preflight(self, services='modrinth'):
        return preflight.prepare(self.tag, 'owner/repo', self.source, self.assets,
                                 self.output, self.github_output, services)

    def test_valid_published_release_writes_metadata_and_multiline_output(self):
        result = self.run_preflight()
        self.assertEqual(json.loads(self.output.read_text()), result)
        self.assertEqual(self.resolve.call_args.args[1], 'modrinth')
        self.provenance.assert_called_once()
        text = self.github_output.read_text()
        self.assertIn('target=26.3-fabric\njava=25\nartifact=' + str(self.assets / 'mod.jar'), text)
        self.assertIn('cf_game_versions=26.3,Java 25,Fabric,Environment:Client\n', text)
        self.assertIn('cf_changelog<<EOF_', text)
        self.assertIn('First line\nSecond line\nEOF_', text)

    def test_wrong_live_tag_is_rejected_before_download(self):
        self.api.side_effect = [{'sha': 'b' * 40}]
        with self.assertRaisesRegex(ValueError, 'Live release tag'):
            self.run_preflight()
        self.command.assert_not_called()

    def test_draft_mutable_channel_and_missing_notes_are_rejected(self):
        for change in ({'draft': True}, {'immutable': False}, {'prerelease': False},
                       {'body': '  '}):
            with self.subTest(change=change):
                self.api.side_effect = [self.api_values[0],
                                        {**self.published, **change}]
                with self.assertRaises(ValueError):
                    self.run_preflight()
                self.assertFalse(self.assets.exists())

    def test_wrong_hash_target_and_provenance_are_rejected(self):
        self.files['mod.jar'] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'Downloaded release assets'):
            self.run_preflight()
        self.assertFalse(self.output.exists())

    def test_wrong_target_is_rejected(self):
        self.manifest['selected_targets'] = ['26.3-neoforge']
        with self.assertRaisesRegex(ValueError, 'exactly the selected Fabric target'):
            self.run_preflight()

    def test_bad_provenance_is_rejected(self):
        self.provenance.side_effect = ValueError('bad provenance')
        with self.assertRaisesRegex(ValueError, 'bad provenance'):
            self.run_preflight()
        self.assertFalse(self.output.exists())

    def test_changed_release_during_preflight_is_rejected(self):
        self.api.side_effect = self.api_values[:-1] + [{**self.published, 'body': 'new notes'}]
        with self.assertRaisesRegex(ValueError, 'changed during preflight'):
            self.run_preflight()
        self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
