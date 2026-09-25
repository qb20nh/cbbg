"""Exercise candidate publishing commands without uploads."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_release import ROOT, script


class CandidatePublishWorkflowTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.env = dict(os.environ, DRY_RUN='true', TARGET_ID='26.3-fabric',
                        MODRINTH_TOKEN='', RELEASE_TAG='v1.4.0', GH_REPO='example/mod',
                        SERVICES='both', GITHUB_OUTPUT=str(self.root / 'output'))
        launcher = self.root / 'gradlew'
        launcher.write_text('#!/usr/bin/env python3\nimport json,sys\n'
                            'from pathlib import Path\n'
                            'Path("gradle-arguments.json").write_text(json.dumps(sys.argv[1:]))\n')
        launcher.chmod(0o755)

    def run_step(self, name):
        return subprocess.run(['bash', '-euo', 'pipefail', '-c', script(name)],
                              cwd=self.root, env=self.env, capture_output=True, text=True)

    def test_preflight_receives_explicit_release_source_and_service(self):
        helper = self.root / '.github/scripts/prepare_publication.py'
        helper.parent.mkdir(parents=True)
        helper.write_text('import json,sys\nfrom pathlib import Path\n'
                          'Path("preflight-arguments.json").write_text(json.dumps(sys.argv[1:]))\n')
        self.env['SERVICES'] = 'modrinth'
        result = self.run_step('Prepare candidate publication')
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'preflight-arguments.json').read_text())
        self.assertEqual(dict(zip(args[::2], args[1::2])), {
            '--release': 'v1.4.0', '--repo': 'example/mod', '--source-root': '.release-source',
            '--assets': 'release-assets', '--output': 'build/publication.json',
            '--github-output': str(self.root / 'output'), '--services': 'modrinth'})

    def test_modrinth_dry_run_uses_isolated_publisher_and_tagged_source(self):
        result = self.run_step('Validate or publish candidate to Modrinth')
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'gradle-arguments.json').read_text())
        self.assertEqual(args[-1], 'modrinth')
        self.assertEqual(args[args.index('-p') + 1], 'build-config/publishing')
        self.assertIn('-PsourceRoot=' + str(self.root / '.release-source'), args)
        self.assertIn('-Pcandidate=' + str(self.root / 'release-assets/candidate.json'), args)
        self.assertIn('--init-script', args)
        self.assertNotIn('build', args)

    def test_modrinth_upload_requires_token(self):
        self.env['DRY_RUN'] = 'false'
        result = self.run_step('Validate or publish candidate to Modrinth')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / 'gradle-arguments.json').exists())
        self.env['MODRINTH_TOKEN'] = 'fixture-token'
        result = self.run_step('Validate or publish candidate to Modrinth')
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'gradle-arguments.json').read_text())
        self.assertNotIn('--init-script', args)
        self.assertNotIn('fixture-token', args)

    def test_curseforge_record_identifies_submitted_artifact_and_metadata(self):
        metadata = {'release': 'v1.4.0', 'source_commit': 'a' * 40,
                    'records': [{'targets': ['26.3-fabric'],
                                 'artifact': {'path': 'mod.jar', 'sha256': 'b' * 64}}]}
        path = self.root / 'build/publication.json'
        path.parent.mkdir()
        path.write_text(json.dumps(metadata))
        self.env['CF_FILE_ID'] = '123'
        result = self.run_step('Save CurseForge upload record')
        self.assertEqual(result.returncode, 0, result.stderr)
        record = json.loads((self.root / 'build/curseforge-result.json').read_text())
        self.assertEqual(record['file_id'], '123')
        self.assertEqual(record['artifact'], metadata['records'][0]['artifact'])
        self.assertEqual(record['metadata_sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
        self.assertTrue(record['url'].endswith('/123'))

    def test_missing_curseforge_id_requires_manual_inspection(self):
        for identifier in ('', 'unknown', '0', '${bad}'):
            with self.subTest(identifier=identifier):
                self.env['CF_FILE_ID'] = identifier
                result = self.run_step('Save CurseForge upload record')
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.root / 'build/curseforge-result.json').exists())


if __name__ == '__main__':
    unittest.main()
