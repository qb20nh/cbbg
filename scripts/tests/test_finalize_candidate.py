import copy
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from finalize_candidate import candidate_files, finalize, main
from bundle_candidate import source_state
from parity_evidence import EvidenceError, digest


class FinalizeCandidateTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.bundle = self.root / 'candidate'
        self.bundle.mkdir()
        self.manifest = self.bundle / 'candidate.json'
        self.manifest.write_text(json.dumps({'release': 'v1.4.0', 'commit': 'a' * 40,
                                            'selected_targets': ['26.3-fabric']}))
        self.notes = self.root / 'notes.md'
        self.notes.write_text('Adds Minecraft 26.3 support.\n')
        self.specification = {'implemented': True}
        def reference(name):
            path = self.bundle / name
            path.write_text(name)
            return {'path': name, 'sha256': digest(path)}
        self.target = {key: reference(key + '.jar') for key in
                       ('artifact', 'sources', 'source_inventory')}
        self.target['client_tests'] = {key: reference(key + '.json') for key in
                                      ('catalog', 'contract', 'ordinary_metadata',
                                       'runtime_lock', 'dependency_lock')}
        self.target['client_tests']['drivers'] = {'ordinary': reference('driver.jar')}
        self.client = self.enterContext(patch('finalize_candidate.client_candidate',
                                              side_effect=lambda *a: (json.loads(self.manifest.read_text()),
                                                                      self.target, self.specification)))
        names = ['candidate.json'] + [v['path'] for k, v in self.target.items() if k != 'client_tests']
        names += [v['path'] for k, v in self.target['client_tests'].items() if k != 'drivers']
        names.append('driver.jar')
        self.sums = self.bundle / 'SHA256SUMS'
        self.sums.write_text(''.join(digest(self.bundle / name) + '  ' + name + '\n'
                                    for name in sorted(names)))
        (self.bundle / 'provenance.jsonl').write_text('attestation fixture')
        self.files = candidate_files(self.manifest)[1]
        self.source = self.enterContext(patch('finalize_candidate.source_state', return_value=('a' * 40, False)))
        self.verify = self.enterContext(patch('finalize_candidate.verify_candidate',
                                             return_value={'releaseAcceptance': False}))
        self.release = {'id': 7, 'tag_name': 'v1.4.0', 'draft': True, 'prerelease': False,
                        'name': 'cbbg 1.4.0', 'body': 'Candidate', 'immutable': False,
                        'html_url': 'https://github.com/example/mod/releases/tag/v1.4.0',
                        'assets': [{'id': i + 1, 'name': name, 'state': 'uploaded',
                                    'size': (self.bundle / name).stat().st_size,
                                    'digest': 'sha256:' + sha, 'updated_at': '2026-09-25T00:00:00Z'}
                                   for i, (name, sha) in enumerate(self.files.items())]}
        self.enabled = True
        self.tag_commit = 'a' * 40
        self.api = self.enterContext(patch('finalize_candidate.api', side_effect=self.read_remote))
        self.command = self.enterContext(patch('finalize_candidate.command', side_effect=self.run_command))
        self.uploads = []

    def read_remote(self, endpoint):
        if '/commits/' in endpoint:
            if self.tag_commit is None:
                raise EvidenceError('Release tag does not exist')
            return {'sha': self.tag_commit}
        if endpoint.endswith('/immutable-releases'):
            return {'enabled': self.enabled}
        return copy.deepcopy(self.release)

    def run_command(self, arguments):
        if arguments[:2] == ['release', 'download']:
            directory = Path(arguments[arguments.index('--dir') + 1])
            for name in self.files:
                shutil.copyfile(self.bundle / name, directory / name)
        elif arguments[:3] == ['api', '--method', 'PATCH']:
            payload = json.loads(Path(arguments[-1]).read_text())
            self.uploads.append(payload)
            self.release.update(payload, immutable=True)
        else:
            self.fail('Unexpected GitHub command: ' + repr(arguments))
        return '{}'

    def finalize(self, **kwargs):
        return finalize(self.manifest, {'26.3-fabric': self.root / 'results.json'}, self.root,
                        'example/mod', 'v1.4.0', self.notes, **kwargs)

    def test_default_checks_without_publication(self):
        report = self.finalize()
        self.assertFalse(report['published'])
        self.assertEqual(report['files'], self.files)
        self.assertEqual(self.uploads, [])
        self.verify.assert_called_once()
        self.assertTrue(self.release['draft'])

    def test_explicit_publication_sets_notes_and_checks_immutability(self):
        report = self.finalize(publish=True)
        self.assertTrue(report['published'])
        self.assertEqual(self.uploads, [{'draft': False, 'body': self.notes.read_text()}])
        self.assertEqual(report['url'], self.release['html_url'])

    def test_unimplemented_target_and_bad_checksums_stop_before_github(self):
        self.specification['implemented'] = False
        with self.assertRaisesRegex(EvidenceError, 'not marked implemented'):
            self.finalize()
        self.specification['implemented'] = True
        self.sums.write_text('incorrect')
        with self.assertRaisesRegex(EvidenceError, 'SHA256SUMS'):
            self.finalize()
        self.api.assert_not_called()

    def test_wrong_source_and_empty_notes_stop_before_github(self):
        self.source.return_value = ('b' * 40, False)
        with self.assertRaisesRegex(EvidenceError, 'clean source checkout'):
            self.finalize()
        self.source.return_value = ('a' * 40, False)
        self.notes.write_text(' ')
        with self.assertRaisesRegex(EvidenceError, 'notes are required'):
            self.finalize()
        self.api.assert_not_called()

    def test_failed_candidate_validation_prevents_publication(self):
        self.verify.side_effect = EvidenceError('Missing runtime results')
        with self.assertRaisesRegex(EvidenceError, 'Missing runtime results'):
            self.finalize(publish=True)
        self.api.assert_not_called()

    def test_immutable_releases_must_be_enabled(self):
        self.enabled = False
        with self.assertRaisesRegex(EvidenceError, 'Enable immutable releases'):
            self.finalize(publish=True)
        self.assertEqual(self.uploads, [])

    def test_wrong_tag_channel_or_published_release_is_rejected(self):
        for key, value in [('tag_name', 'v1.5.0'), ('prerelease', True), ('draft', False)]:
            with self.subTest(key=key):
                old = self.release[key]
                self.release[key] = value
                with self.assertRaisesRegex(EvidenceError, 'selected draft'):
                    self.finalize(publish=True)
                self.release[key] = old
        self.assertEqual(self.uploads, [])

    def test_missing_and_extra_assets_are_rejected(self):
        old = self.release['assets']
        for assets in (old[:-1], old + [dict(old[0], name='extra.jar')]):
            self.release['assets'] = assets
            with self.assertRaisesRegex(EvidenceError, 'asset names differ'):
                self.finalize(publish=True)
        self.assertEqual(self.uploads, [])

    def test_changed_download_is_rejected(self):
        def download(arguments):
            self.run_command(arguments)
            directory = Path(arguments[arguments.index('--dir') + 1])
            (directory / 'artifact.jar').write_text('changed')
        self.command.side_effect = download
        with self.assertRaisesRegex(EvidenceError, 'Downloaded draft files differ'):
            self.finalize(publish=True)
        self.assertEqual(self.uploads, [])

    def test_draft_change_during_download_is_rejected(self):
        def download(arguments):
            self.run_command(arguments)
            self.release['body'] = 'Changed elsewhere'
        self.command.side_effect = download
        with self.assertRaisesRegex(EvidenceError, 'Draft changed'):
            self.finalize(publish=True)
        self.assertEqual(self.uploads, [])

    def test_unconfirmed_publication_does_not_retry(self):
        def publish(arguments):
            self.run_command(arguments)
            self.release['immutable'] = False
        self.command.side_effect = publish
        with self.assertRaisesRegex(EvidenceError, 'manual inspection'):
            self.finalize(publish=True)
        self.assertEqual(len(self.uploads), 1)

    def test_moved_or_missing_release_tag_prevents_publication(self):
        for commit in ('b' * 40, None):
            with self.subTest(commit=commit):
                self.tag_commit = commit
                with self.assertRaises(EvidenceError):
                    self.finalize(publish=True)
        self.assertEqual(self.uploads, [])

    def test_tag_moved_during_download_prevents_publication(self):
        def download(arguments):
            self.run_command(arguments)
            self.tag_commit = 'b' * 40
        self.command.side_effect = download
        with self.assertRaisesRegex(EvidenceError, 'tag'):
            self.finalize(publish=True)
        self.assertEqual(self.uploads, [])

    def test_tag_change_at_publication_requires_manual_inspection(self):
        def publish(arguments):
            self.run_command(arguments)
            if arguments[:3] == ['api', '--method', 'PATCH']:
                self.tag_commit = 'b' * 40
        self.command.side_effect = publish
        with self.assertRaisesRegex(EvidenceError, 'manual inspection'):
            self.finalize(publish=True)
        self.assertEqual(len(self.uploads), 1)

    def test_cli_can_write_report_inside_clean_source_checkout(self):
        def git(*arguments):
            subprocess.run(['git', *arguments], cwd=self.root, check=True, capture_output=True)
        git('init')
        git('add', '.')
        git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
            '-c', 'commit.gpgsign=false', 'commit', '-m', 'Fixture')
        self.source.side_effect = lambda root: ('a' * 40, source_state(root)[1])
        output = self.root / 'report.json'
        arguments = ['finalize_candidate.py', '--candidate', str(self.manifest),
                     '--results', '26.3-fabric=results.json', '--source-root', str(self.root),
                     '--repo', 'example/mod', '--release', 'v1.4.0', '--notes', str(self.notes),
                     '--output', str(output)]
        with patch('sys.argv', arguments), redirect_stdout(io.StringIO()):
            main()
        self.assertFalse(json.loads(output.read_text())['published'])
        self.assertEqual(self.uploads, [])

    def test_unwritable_report_destination_prevents_publication(self):
        with self.assertRaises(OSError):
            self.finalize(publish=True, output_path=self.root / 'missing' / 'report.json')
        self.assertEqual(self.uploads, [])

    def test_uncertain_publication_retains_local_check_record(self):
        output = self.root / 'report.json'
        def publish(arguments):
            self.run_command(arguments)
            self.release['immutable'] = False
        self.command.side_effect = publish
        with self.assertRaisesRegex(EvidenceError, 'manual inspection'):
            self.finalize(publish=True, output_path=output)
        self.assertIsNone(json.loads(output.read_text())['published'])


if __name__ == '__main__':
    unittest.main()
