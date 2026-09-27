"""Exercise candidate workflow commands without contacting GitHub."""

import json
import os
from pathlib import Path
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
                        REQUESTED_TARGET='26.3-fabric', GITHUB_OUTPUT=str(self.root / 'output'))
        locks = self.root / 'runtime-locks'
        locks.mkdir()
        for suffix in ('scenarios', 'mods', 'linux-x86_64'):
            (locks / f'26.3-fabric-{suffix}.json').write_text('{}')
        launcher = self.root / 'gradlew'
        launcher.write_text('#!/usr/bin/env python3\nimport json,sys\nfrom pathlib import Path\n'
                            'args=sys.argv[1:]\n'
                            'with Path("gradle-arguments.jsonl").open("a") as out: out.write(json.dumps(args)+"\\n")\n'
                            'if args[-1] == "targetMatrix":\n'
                            '  target=next(a.split("=",1)[1] for a in args if a.startswith("-Ptarget="))\n'
                            '  if target not in ("26.3-fabric", "26.3-quilt"): sys.exit(1)\n'
                            '  profile="fabric-modern" if target == "26.3-fabric" else "quilt"\n'
                            '  output=next(a.split("=",1)[1] for a in args if a.startswith("-Poutput="))\n'
                            '  Path(output).write_text(json.dumps({"include":[{"id":target,"java":25,"buildProfile":profile}]}))\n')
        launcher.chmod(0o755)

    def run_step(self, name, cwd=None):
        return subprocess.run(['bash', '-euo', 'pipefail', '-c', script(name, WORKFLOW)],
                              cwd=cwd or self.root, env=self.env, text=True, capture_output=True)

    def test_selects_catalog_java_and_build_profile(self):
        result = self.run_step('Validate release selection')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / 'output').read_text(),
                         'target=26.3-fabric\njava=25\nprofile=fabric-modern\n')
        args = json.loads((self.root / 'gradle-arguments.jsonl').read_text().splitlines()[-1])
        self.assertIn('-PrequireImplemented=true', args)
        self.assertEqual(args[-1], 'targetMatrix')

    def test_rejects_branch_invalid_tag_and_unsupported_target(self):
        for key, value in [('GITHUB_REF_TYPE', 'branch'), ('GITHUB_REF_NAME', 'main'),
                           ('GITHUB_REF_NAME', 'v1.4.0+mc26.3'),
                           ('REQUESTED_TARGET', '26.3-quilt'), ('REQUESTED_TARGET', 'missing')]:
            with self.subTest(key=key, value=value):
                previous = self.env[key]
                self.env[key] = value
                result = self.run_step('Validate release selection')
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.root / 'output').exists())
                self.env[key] = previous

    def test_build_and_bundle_use_recorded_outputs_without_python(self):
        self.env['TARGET'] = '26.3-fabric'
        self.env['BUILD_PROFILE'] = 'fabric-modern'
        result = self.run_step('Build and bundle candidate')
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line) for line in
                 (self.root / 'gradle-arguments.jsonl').read_text().splitlines()]
        self.assertEqual([args[-1] for args in calls], ['candidateBuildOutputs', 'bundleCandidate'])
        self.assertIn('-PbuildOutputs=build/targets/26.3-fabric/candidate-build-outputs.json',
                      calls[1])
        self.assertIn('-Prelease=v1.4.0', calls[1])
        self.assertIn('-Poutput=build/release-candidate', calls[1])

    def test_creates_draft_with_existing_tag_and_candidate_files(self):
        command = self.root / 'gh'
        command.write_text('#!/usr/bin/env python3\nimport json, sys\n'
                           'from pathlib import Path\n'
                           'Path("arguments.json").write_text(json.dumps(sys.argv[1:]))\n')
        command.chmod(0o755)
        self.env['PATH'] = str(self.root) + os.pathsep + self.env['PATH']
        bundle = self.root / 'build/release-candidate'
        bundle.mkdir(parents=True)
        for name in ('candidate.json', 'mod.jar', 'sources.jar', 'provenance.jsonl'):
            (bundle / name).write_text('fixture')
        for tag in ('v1.4.0', 'v1.4.0-rc.1'):
            with self.subTest(tag=tag):
                self.env['GITHUB_REF_NAME'] = tag
                result = self.run_step('Create draft release', self.root)
                self.assertEqual(result.returncode, 0, result.stderr)
                arguments = json.loads((self.root / 'arguments.json').read_text())
                self.assertEqual(arguments[:3], ['release', 'create', tag])
                self.assertIn('--draft', arguments)
                self.assertIn('--verify-tag', arguments)
                self.assertEqual('--prerelease' in arguments, '-rc.' in tag)
                for path in bundle.iterdir():
                    self.assertIn(path.relative_to(self.root).as_posix(), arguments)


if __name__ == '__main__':
    unittest.main()
