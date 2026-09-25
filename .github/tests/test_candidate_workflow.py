"""Exercise candidate workflow commands without contacting GitHub."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_release import ROOT, script

WORKFLOW = ROOT / '.github/workflows/release.yml'


class CandidateWorkflowTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.env = dict(os.environ, GITHUB_REF_TYPE='tag', GITHUB_REF_NAME='v1.4.0',
                        REQUESTED_TARGET='26.3-fabric', GITHUB_OUTPUT=str(self.root / 'output'))

    def run_step(self, name, cwd=ROOT):
        return subprocess.run(['bash', '-euo', 'pipefail', '-c', script(name, WORKFLOW)],
                              cwd=cwd, env=self.env, text=True, capture_output=True)

    def test_selects_catalog_java_and_build_profile(self):
        result = self.run_step('Validate release selection')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / 'output').read_text(),
                         'target=26.3-fabric\njava=25\nprofile=fabric-modern\n')

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
