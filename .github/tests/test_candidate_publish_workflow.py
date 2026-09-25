"""Exercise candidate publishing commands without uploads."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from workflow_support import ROOT, script


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
        self.env['SERVICES'] = 'modrinth'
        result = self.run_step('Prepare publication')
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'gradle-arguments.json').read_text())
        self.assertEqual(args[-1], 'preparePublication')
        for argument in ('-Prelease=v1.4.0', '-Prepo=example/mod',
                         '-PsourceRoot=.release-source', '-Passets=release-assets',
                         '-Poutput=build/publication.json',
                         '-PgithubOutput=' + str(self.root / 'output'), '-Pservices=modrinth'):
            self.assertIn(argument, args)

    def test_modrinth_dry_run_uses_isolated_publisher_and_tagged_source(self):
        result = self.run_step('Validate or publish to Modrinth')
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'gradle-arguments.json').read_text())
        self.assertEqual(args[-1], 'planModrinthUpload')
        self.assertEqual(args[args.index('-p') + 1], 'build-config/publishing')
        self.assertIn('-PsourceRoot=' + str(self.root / '.release-source'), args)
        self.assertIn('-Pcandidate=' + str(self.root / 'release-assets/candidate.json'), args)
        self.assertIn('-PpublicationMetadata=' + str(self.root / 'build/publication.json'), args)
        self.assertNotIn('--init-script', args)
        self.assertNotIn('build', args)

        self.env['RELEASE_TAG'] = 'v1.3.0+mc26.2'
        result = self.run_step('Validate or publish to Modrinth')
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'gradle-arguments.json').read_text())
        self.assertIn('-PlegacyAssets=' + str(self.root / 'release-assets'), args)
        self.assertFalse(any(arg.startswith('-Pcandidate=') for arg in args))

    def test_modrinth_upload_requires_token(self):
        self.env['DRY_RUN'] = 'false'
        result = self.run_step('Validate or publish to Modrinth')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / 'gradle-arguments.json').exists())
        self.env['MODRINTH_TOKEN'] = 'fixture-token'
        result = self.run_step('Validate or publish to Modrinth')
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'gradle-arguments.json').read_text())
        self.assertEqual(args[-1], 'modrinth')
        self.assertNotIn('--init-script', args)
        self.assertNotIn('fixture-token', args)

    def test_curseforge_record_identifies_submitted_artifact_and_metadata(self):
        self.env['CF_FILE_ID'] = '123'
        result = self.run_step('Save CurseForge upload record')
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'gradle-arguments.json').read_text())
        self.assertEqual(args[-1], 'recordCurseForgeUpload')
        self.assertIn('-PfileId=123', args)
        self.assertIn('-PpublicationMetadata=build/publication.json', args)
        self.assertIn('-Poutput=build/curseforge-result.json', args)

    def test_curseforge_retry_requires_new_dispatch(self):
        workflow = (ROOT / '.github/workflows/publish.yml').read_text()
        self.assertIn("github.run_attempt != '1'", workflow)
        self.assertIn('Check the CurseForge project files', workflow)


if __name__ == '__main__':
    unittest.main()
