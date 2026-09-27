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
                         '-PgithubOutput=' + str(self.root / 'output'), '-Pservices=modrinth',
                         '-PdryRun=true'):
            self.assertIn(argument, args)

    def test_preflight_passes_actual_publication_mode_and_dry_run_env(self):
        self.env['DRY_RUN'] = 'false'
        result = self.run_step('Prepare publication')
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'gradle-arguments.json').read_text())
        self.assertIn('-PdryRun=false', args)
        workflow = (ROOT / '.github/workflows/publish.yml').read_text()
        prepare = next(step for step in workflow.split('\n      - ') if 'id: publication' in step)
        self.assertIn('DRY_RUN: ${{ inputs.dry_run }}', prepare)
        for step in workflow.split('\n      - '):
            if 'uses: itsmeow/curseforge-upload' in step:
                self.assertIn('!inputs.dry_run', step)
        publisher = (ROOT / 'build-config/publishing/build.gradle').read_text()
        self.assertIn('Publication.requireUploadAllowed(CandidateFiles.read(metadataFile))', publisher)

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
        self.assertIn('-Ptarget=26.3-fabric', args)

    def test_matrix_waits_for_all_preflight_checks_and_selects_owner(self):
        workflow = (ROOT / '.github/workflows/publish.yml').read_text()
        preflight, publish = workflow.split('\n  publish:', 1)
        self.assertIn('name: Check all Modrinth retry plans', preflight)
        self.assertNotIn('uses: itsmeow/curseforge-upload', preflight)
        self.assertIn('needs: preflight', publish)
        self.assertIn('matrix: ${{ fromJSON(needs.preflight.outputs.matrix) }}', publish)
        self.assertIn('fail-fast: false', publish)
        self.assertIn('max-parallel: 1', publish)
        self.assertIn('name: Recheck publication', publish)
        self.assertIn('name: publication-${{ inputs.tag }}-${{ inputs.services }}-${{ matrix.target }}', publish)
        self.assertEqual(workflow.count('ref: ${{ github.workflow_sha }}'), 2)
        self.assertNotIn('.release-source/gradlew', workflow)
        result = self.run_step('Select checked publication record')
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'gradle-arguments.json').read_text())
        self.assertEqual(args[-1], 'selectPublication')
        self.assertIn('-Ptarget=26.3-fabric', args)
        self.assertIn('-PpublicationMetadata=build/publication.json', args)
        self.assertIn('-PsourceRoot=.release-source', args)

    def test_modrinth_preflight_checks_every_record_before_uploads(self):
        (self.root / 'build').mkdir()
        (self.root / 'build/publication.json').write_text(json.dumps({
            'records': [{'targets': ['26.3-fabric']}, {'targets': ['26.2-fabric']}]}))
        launcher = self.root / 'gradlew'
        launcher.write_text('#!/usr/bin/env python3\nimport json,sys\n'
                            'with open("plan-arguments.jsonl", "a") as f: f.write(json.dumps(sys.argv[1:])+"\\n")\n')
        launcher.chmod(0o755)
        result = self.run_step('Check all Modrinth retry plans')
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line) for line in (self.root / 'plan-arguments.jsonl').read_text().splitlines()]
        self.assertEqual(len(calls), 2)
        for call, target in zip(calls, ['26.3-fabric', '26.2-fabric']):
            self.assertIn('-Ptarget=' + target, call)
            self.assertEqual(call[-1], 'planModrinthUpload')

    def test_curseforge_sources_are_linked_to_main_file(self):
        workflow = (ROOT / '.github/workflows/publish.yml').read_text()
        child = next(step for step in workflow.split('\n      - ') if 'id: curseforge_sources_upload' in step)
        self.assertIn('parent_file_id: ${{ steps.curseforge_upload.outputs.id }}', child)
        self.assertIn('file_path: ${{ steps.publication.outputs.sources }}', child)
        self.assertNotIn('game_versions:', child)
        self.assertIn("if: ${{ !inputs.dry_run && inputs.services != 'modrinth' }}", child)
        self.assertLess(workflow.index('name: Save CurseForge upload record'),
                        workflow.index('name: Upload sources and mappings to CurseForge'))

        self.env['CF_FILE_ID'] = '123'
        self.env['CF_SOURCES_FILE_ID'] = '456'
        result = self.run_step('Save CurseForge sources upload record')
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'gradle-arguments.json').read_text())
        self.assertIn('-PfileId=123', args)
        self.assertIn('-PsourcesFileId=456', args)
        self.assertIn('-Poutput=build/curseforge-sources-result.json', args)

    def test_curseforge_evidence_is_a_separate_child_file(self):
        workflow = (ROOT / '.github/workflows/publish.yml').read_text()
        child = next(step for step in workflow.split('\n      - ') if 'id: curseforge_evidence_upload' in step)
        self.assertIn('parent_file_id: ${{ steps.curseforge_upload.outputs.id }}', child)
        self.assertIn('file_path: ${{ steps.publication.outputs.evidence }}', child)
        self.assertNotIn('game_versions:', child)
        self.assertIn("steps.publication.outputs.evidence != ''", child)
        self.env.update(CF_FILE_ID='123', CF_SOURCES_FILE_ID='456', CF_EVIDENCE_FILE_ID='789')
        result = self.run_step('Save CurseForge evidence upload record')
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'gradle-arguments.json').read_text())
        self.assertIn('-PevidenceFileId=789', args)
        self.assertIn('-Poutput=build/curseforge-evidence-result.json', args)

    def test_curseforge_retry_requires_new_dispatch(self):
        workflow = (ROOT / '.github/workflows/publish.yml').read_text()
        self.assertIn("github.run_attempt != '1'", workflow)
        self.assertIn('Check the CurseForge project files', workflow)


if __name__ == '__main__':
    unittest.main()
