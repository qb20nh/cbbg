"""Exercise candidate workflow commands without contacting GitHub."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from workflow_support import ROOT, script

WORKFLOW = ROOT / '.github/workflows/release.yml'


class CandidateWorkflowTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.env = dict(os.environ, GITHUB_REF_TYPE='tag', GITHUB_REF_NAME='v1.4.0',
                        REQUESTED_TARGET='26.3-fabric', GITHUB_OUTPUT=str(self.root / 'output'),
                        GITHUB_RUN_ID='123456', PRODUCT='cbbg')
        locks = self.root / 'runtime-locks'
        locks.mkdir()
        profile = self.root / 'build-config/fabric-modern'
        profile.mkdir(parents=True)
        (profile / 'build.gradle').write_text('')
        (self.root / 'targets.json').write_text(json.dumps({'targets': [
            {'id': '26.3-fabric', 'java': 25, 'minecraft': '26.3', 'loader': 'fabric'},
            {'id': '26.2-fabric', 'java': 8, 'buildJava': 21, 'minecraft': '26.2', 'loader': 'fabric'},
            {'id': '26.3-quilt', 'java': 25, 'minecraft': '26.3', 'loader': 'quilt'},
            {'id': '26.1-fabric', 'java': 25, 'minecraft': '26.1', 'loader': 'fabric',
             'compatibleMinecraft': ['26.1.1', '26.1.2']},
            {'id': '26.1.1-fabric', 'java': 25, 'minecraft': '26.1.1', 'loader': 'fabric',
             'artifactOf': '26.1-fabric'},
            {'id': '26.1.2-fabric', 'java': 25, 'minecraft': '26.1.2', 'loader': 'fabric',
             'artifactOf': '26.1-fabric'},
        ]}))
        for suffix in ('scenarios', 'mods', 'linux-x86_64'):
            (locks / f'26.3-fabric-{suffix}.json').write_text('{}')
        launcher = self.root / 'gradlew'
        launcher.write_text('#!/usr/bin/env python3\nimport json,os,re,sys\nfrom pathlib import Path\n'
                            'args=sys.argv[1:]\n'
                            'with Path("gradle-arguments.jsonl").open("a") as out: out.write(json.dumps(args)+"\\n")\n'
                            'if args[-1] == "releaseIdentity":\n'
                            '  tag=next(a.split("=",1)[1] for a in args if a.startswith("-Prelease="))\n'
                            '  match=re.fullmatch(r"(lib/)?v([0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z][0-9A-Za-z.-]*\\.[1-9][0-9]*)?)(?:\\+mc[0-9][0-9A-Za-z.-]*-(?:fabric|quilt|forge|neoforge|legacy-fabric))?", tag)\n'
                            '  if not match: sys.exit(1)\n'
                            '  product="lib" if match[1] else "cbbg"; version=match[2]\n'
                            '  output=next(a.split("=",1)[1] for a in args if a.startswith("-Poutput="))\n'
                            '  Path(output).write_text(json.dumps({"product":product,"version":version,"title":("CBBG Lib " if product == "lib" else "cbbg ")+version,"prerelease":"-" in version}))\n'
                            'if args[-1] == "targetMatrix":\n'
                            '  target=next(a.split("=",1)[1] for a in args if a.startswith("-Ptarget="))\n'
                            '  targets=target.split(",")\n'
                            '  if len(set(targets)) != len(targets) or any(t not in ("26.3-fabric", "26.2-fabric", "26.3-quilt") for t in targets): sys.exit(1)\n'
                            '  output=next(a.split("=",1)[1] for a in args if a.startswith("-Poutput="))\n'
                            '  Path(output).write_text(json.dumps({"include":[{"id":t,"java":8 if t == "26.2-fabric" else 25,"buildProfile":"quilt" if t == "26.3-quilt" else "fabric-modern"} for t in targets]}))\n'
                            'if args[-1] == "releaseNotes" and os.environ.get("MISSING_RELEASE_NOTES") == "true": sys.exit(2)\n')
        launcher.chmod(0o755)

    def run_step(self, name, cwd=None):
        return subprocess.run(['bash', '-euo', 'pipefail', '-c', script(name, WORKFLOW)],
                              cwd=cwd or self.root, env=self.env, text=True, capture_output=True)

    def test_selects_catalog_java_and_build_profile(self):
        result = self.run_step('Validate release selection')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / 'output').read_text(),
                         'product=cbbg\ntargets=26.3-fabric\njava<<JAVA_VERSIONS\n25\nJAVA_VERSIONS\n')
        args = json.loads((self.root / 'gradle-arguments.jsonl').read_text().splitlines()[-1])
        self.assertIn('-PrequireImplemented=true', args)
        self.assertEqual(args[-1], 'targetMatrix')

    def test_rejects_branch_invalid_tag_and_unsupported_target(self):
        for key, value in [('GITHUB_REF_TYPE', 'branch'), ('GITHUB_REF_NAME', 'main'),
                           ('GITHUB_REF_NAME', 'v1.4.0+mc26.3'),
                           ('GITHUB_REF_NAME', 'v1.4.0+mc26.3_fabric'),
                           ('REQUESTED_TARGET', '26.3-quilt'), ('REQUESTED_TARGET', 'missing')]:
            with self.subTest(key=key, value=value):
                previous = self.env[key]
                self.env[key] = value
                result = self.run_step('Validate release selection')
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.root / 'output').exists())
                self.env[key] = previous

    def test_accepts_target_metadata_on_stable_and_prerelease_tags(self):
        for tag in ('v1.4.0+mc26.3-fabric', 'v1.4.0-rc.1+mc26.3-fabric'):
            self.env['GITHUB_REF_NAME'] = tag
            result = self.run_step('Validate release selection')
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_selection_errors_explain_the_required_tag_and_missing_files(self):
        self.env['GITHUB_REF_TYPE'] = 'branch'
        result = self.run_step('Validate release selection')
        self.assertIn('::error::', result.stdout)
        self.assertIn('Use workflow from', result.stdout)
        self.assertIn('v1.4.2+mc26.2-fabric', result.stdout)
        self.env['GITHUB_REF_TYPE'] = 'tag'
        profile = self.root / 'build-config/fabric-modern/build.gradle'
        profile.unlink()
        result = self.run_step('Validate release selection')
        self.assertIn('::error::', result.stdout)
        self.assertIn('build-config/fabric-modern/build.gradle', result.stdout)
        self.assertIn('26.3-fabric', result.stdout)
        profile.write_text('')
        (self.root / 'runtime-locks/26.3-fabric-mods.json').unlink()
        result = self.run_step('Validate release selection')
        self.assertIn('::error::', result.stdout)
        self.assertIn('runtime-locks/26.3-fabric-mods.json', result.stdout)
        self.assertIn('commit', result.stdout)

    def test_selection_annotation_escapes_untrusted_ref(self):
        self.env.update(GITHUB_REF_TYPE='branch', GITHUB_REF_NAME='bad%\n$(touch executed)')
        result = self.run_step('Validate release selection')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout.count('::error::'), 1)
        self.assertIn('bad%25%0A$(touch executed)', result.stdout)
        self.assertFalse((self.root / 'executed').exists())

    def test_build_and_bundle_use_recorded_outputs_without_python(self):
        self.env['TARGETS'] = '26.3-fabric'
        self.env['JAVA_HOME_25_X64'] = self.env.get('JAVA_HOME', '/tmp/java25')
        self.assertEqual(self.run_step('Validate release selection').returncode, 0)
        result = self.run_step('Build and bundle candidate')
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line) for line in
                 (self.root / 'gradle-arguments.jsonl').read_text().splitlines()[2:]]
        self.assertEqual([args[-1] for args in calls], ['candidateBuildOutputs', 'bundleCandidate'])
        self.assertIn('-Ptargets=26.3-fabric',
                      calls[1])
        self.assertIn('-Prelease=v1.4.0', calls[1])
        self.assertIn('-Poutput=build/release-candidate', calls[1])

    def test_library_tags_use_the_library_profile_and_contract(self):
        self.env.update(GITHUB_REF_NAME='lib/v1.0.0', PRODUCT='lib', TARGETS='26.3-fabric',
                        JAVA_HOME_25_X64=self.env.get('JAVA_HOME', '/tmp/java25'))
        (self.root / 'libraries/fabric').mkdir(parents=True)
        (self.root / 'libraries/fabric/build.gradle').write_text('')
        (self.root / 'runtime-locks/lib').mkdir()
        (self.root / 'runtime-locks/lib/26.3-fabric-scenarios.json').write_text('{}')
        (self.root / 'runtime-locks/26.3-fabric-scenarios.json').unlink()
        result = self.run_step('Validate release selection')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('product=lib', (self.root / 'output').read_text())
        result = self.run_step('Build and bundle candidate')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = [json.loads(line) for line in (self.root / 'gradle-arguments.jsonl').read_text().splitlines()]
        build = next(args for args in calls if args[-1] == 'candidateBuildOutputs')
        self.assertEqual(build[build.index('-p') + 1], 'libraries/fabric')
        self.assertIn('-Plibrary_version=1.0.0', build)
        self.assertIn('-Prelease=lib/v1.0.0', calls[-1])

    def test_selects_notes_for_the_release_tag_and_target(self):
        self.env['TARGETS'] = '26.3-fabric'
        result = self.run_step('Select release notes')
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'gradle-arguments.jsonl').read_text().splitlines()[-1])
        self.assertEqual(args[-1], 'releaseNotes')
        self.assertIn('-Prelease=v1.4.0', args)
        self.assertIn('-Ptargets=26.3-fabric', args)
        self.assertIn('-Poutput=build/release-notes.md', args)
        self.env['MISSING_RELEASE_NOTES'] = 'true'
        self.assertNotEqual(self.run_step('Select release notes').returncode, 0)

    def test_multiple_targets_validate_inputs_and_bundle_once(self):
        self.env['REQUESTED_TARGET'] = '26.3-fabric,26.2-fabric'
        self.env['TARGETS'] = self.env['REQUESTED_TARGET']
        self.env['JAVA_HOME_25_X64'] = self.env.get('JAVA_HOME', '/tmp/java25')
        for suffix in ('scenarios', 'mods', 'linux-x86_64'):
            (self.root / f'runtime-locks/26.2-fabric-{suffix}.json').write_text('{}')
        self.assertEqual(self.run_step('Validate release selection').returncode, 0)
        self.assertEqual((self.root / 'output').read_text(),
                         'product=cbbg\ntargets=26.3-fabric,26.2-fabric\njava<<JAVA_VERSIONS\n8\n21\n25\nJAVA_VERSIONS\n')
        self.assertEqual(self.run_step('Build and bundle candidate').returncode, 0)
        calls = [json.loads(line) for line in (self.root / 'gradle-arguments.jsonl').read_text().splitlines()]
        self.assertEqual([args[-1] for args in calls],
                         ['releaseIdentity', 'targetMatrix', 'candidateBuildOutputs', 'candidateBuildOutputs', 'bundleCandidate'])
        self.assertIn('-Ptargets=26.3-fabric,26.2-fabric', calls[-1])
        (self.root / 'runtime-locks/26.2-fabric-mods.json').unlink()
        self.assertNotEqual(self.run_step('Validate release selection').returncode, 0)

    def test_duplicate_targets_are_rejected(self):
        self.env['REQUESTED_TARGET'] = '26.3-fabric,26.3-fabric'
        self.assertNotEqual(self.run_step('Validate release selection').returncode, 0)

    def candidate_fixture(self, targets=('26.3-fabric',)):
        bundle = self.root / 'build/release-candidate'
        bundle.mkdir(parents=True)
        records = []
        for target in targets:
            prefix = f"cbbg-1.4.0+mc{target.removesuffix('-fabric')}-fabric"
            record = {'id': target}
            for kind, name in (('artifact', prefix + '.jar'),
                               ('sources', prefix + '-sources.jar'),
                               ('sbom', prefix + '-sbom.cdx.json'),
                               ('mapping', prefix + '-mapping.txt')):
                payload = f'{target} {kind}'.encode()
                (bundle / name).write_bytes(payload)
                record[kind] = {'path': name, 'sha256': hashlib.sha256(payload).hexdigest()}
            records.append(record)
        (bundle / 'candidate.json').write_text(json.dumps({'targets': records}))
        (bundle / 'test-driver.jar').write_text('private test driver')
        (bundle / 'SHA256SUMS').write_text('private checksums')
        (bundle / 'provenance.jsonl').write_text('{}\n')
        return bundle

    def public_fixture(self, bundle):
        public = self.root / 'build/release-assets'
        public.mkdir()
        record = json.loads((bundle / 'candidate.json').read_text())['targets'][0]
        names = {record[kind]['path'] for kind in ('artifact', 'sources')}
        names.add('provenance.jsonl')
        for name in names:
            shutil.copyfile(bundle / name, public / name)
        (public / 'SHA256SUMS').write_text(''.join(
            f'{hashlib.sha256((public / name).read_bytes()).hexdigest()}  {name}\n'
            for name in sorted(names)))
        return public

    def test_prepares_public_assets_with_gradle(self):
        self.candidate_fixture()
        result = self.run_step('Prepare public release assets')
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'gradle-arguments.jsonl').read_text().splitlines()[-1])
        self.assertEqual(args, ['--no-daemon',
                                '-Pcandidate=build/release-candidate/candidate.json',
                                '-Poutput=build/release-assets', 'preparePublicReleaseAssets'])

    def test_private_candidate_is_uploaded_before_public_release(self):
        workflow = WORKFLOW.read_text()
        self.assertLess(workflow.index('packageReleaseEvidence'),
                        workflow.index('- name: Store private candidate'))
        self.assertLess(workflow.index('- name: Store private candidate'),
                        workflow.index('- name: Create draft release'))
        self.assertIn('uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1\n'
                      '        with:\n          name: cbbg-candidate\n'
                      '          path: build/release-candidate/\n', workflow)

    def test_main_library_dependencies_are_verified_before_candidate_attestation(self):
        workflow = WORKFLOW.read_text()
        self.assertLess(workflow.index('- name: Verify library candidate dependencies'),
                        workflow.index('- name: Attest candidate files'))
        step = next(step for step in workflow.split('\n      - name: ')
                    if step.startswith('Verify library candidate dependencies'))
        self.assertIn("if: steps.selection.outputs.product == 'cbbg'", step)
        self.env['GH_REPO'] = 'example/cbbg'
        result = self.run_step('Verify library candidate dependencies')
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'gradle-arguments.jsonl').read_text().splitlines()[-1])
        self.assertEqual(args[-1], 'verifyLibraryDependencies')
        self.assertIn('-Pcandidate=build/release-candidate/candidate.json', args)
        self.assertIn('-Prepo=example/cbbg', args)

    def test_verify_provenance_and_package_evidence_with_gradle(self):
        bundle = self.candidate_fixture()
        provenance = self.root / 'attestation.jsonl'
        provenance.write_text('attested\n')
        self.env.update(PROVENANCE_BUNDLE=str(provenance), GH_REPO='example/cbbg')
        result = self.run_step('Verify candidate provenance')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((bundle / 'provenance.jsonl').read_text(), 'attested\n')
        calls = [json.loads(line) for line in
                 (self.root / 'gradle-arguments.jsonl').read_text().splitlines()]
        self.assertEqual([args[-1] for args in calls],
                         ['verifyProvenance', 'packageReleaseEvidence'])

    def test_creates_draft_with_existing_tag_and_public_files(self):
        command = self.root / 'gh'
        command.write_text('#!/usr/bin/env python3\nimport json, sys\n'
                           'from pathlib import Path\n'
                           'Path("arguments.json").write_text(json.dumps(sys.argv[1:]))\n')
        command.chmod(0o755)
        self.env['PATH'] = str(self.root) + os.pathsep + self.env['PATH']
        self.env['TARGETS'] = '26.3-fabric'
        public = self.public_fixture(self.candidate_fixture())
        (self.root / 'build/release-notes.md').write_text('Release notes\n')
        cases = [(tag, '26.3-fabric', ' for Minecraft 26.3 Fabric' if '+mc' in tag else '')
                 for tag in ('v1.4.0', 'v1.4.0-rc.1', 'v1.4.0+mc26.3-fabric',
                             'v1.4.0-rc.1+mc26.3-fabric')]
        cases.extend([
            ('v1.4.1+mc26.1-fabric', '26.1-fabric,26.1.1-fabric,26.1.2-fabric',
             ' for Minecraft 26.1.x Fabric'),
            ('v1.4.1+mc26.1-fabric', '26.1.2-fabric,26.1.1-fabric,26.1-fabric',
             ' for Minecraft 26.1.x Fabric'),
            ('v1.4.1+mc26.1-fabric', '26.1-fabric,26.1.1-fabric',
             ' for Minecraft 26.1, 26.1.1 Fabric'),
            ('v1.4.1+mc26.1-fabric', '26.1-fabric,26.1.2-fabric',
             ' for Minecraft 26.1, 26.1.2 Fabric'),
            ('v1.4.1+mc26.1-fabric', '26.1.2-fabric', ' for Minecraft 26.1.2 Fabric'),
            ('v1.4.0+mc26.3-fabric', '26.3-fabric,26.3-quilt',
             ' for Minecraft 26.3 Fabric, Quilt'),
        ])
        cases.append(('lib/v1.0.0', '26.3-fabric', ''))
        for tag, targets, scope in cases:
            with self.subTest(tag=tag, targets=targets):
                self.env['GITHUB_REF_NAME'] = tag
                self.env['TARGETS'] = targets
                lib = tag.startswith('lib/')
                version = tag.removeprefix('lib/')[1:].split('+')[0]
                (self.root / 'build/release-identity.json').write_text(json.dumps({
                    'version': version, 'title': ('CBBG Lib ' if lib else 'cbbg ') + version,
                    'prerelease': '-rc.' in tag}))
                result = self.run_step('Create draft release', self.root)
                self.assertEqual(result.returncode, 0, result.stderr)
                arguments = json.loads((self.root / 'arguments.json').read_text())
                self.assertEqual(arguments[:3], ['release', 'create', tag])
                self.assertIn('--draft', arguments)
                self.assertIn('--verify-tag', arguments)
                expected_title = ('CBBG Lib ' if lib else 'cbbg ') + version + scope
                self.assertEqual(arguments[arguments.index('--title') + 1], expected_title)
                self.assertEqual(arguments[arguments.index('--notes-file') + 1], 'build/release-notes.md')
                self.assertEqual('--prerelease' in arguments, '-rc.' in tag)
                uploaded = {argument for argument in arguments if argument.startswith('build/release-assets/')}
                self.assertEqual(uploaded, {path.relative_to(self.root).as_posix() for path in public.iterdir()})
                self.assertEqual(len(uploaded), 4)
                self.assertFalse(any(argument.startswith('build/release-candidate/') for argument in arguments))
                self.assertNotIn('candidate.json', ' '.join(arguments))
                self.assertNotIn('test-driver.jar', ' '.join(arguments))
                self.assertIn('<!-- cbbg-candidate-run: 123456 -->',
                              (self.root / 'build/release-notes.md').read_text())


if __name__ == '__main__':
    unittest.main()
