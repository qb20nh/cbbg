"""Run CI selection against local Git histories."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from test_release import ROOT, script


class CiSelectionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for name in ('scripts/change_impact.py', 'scripts/targets.py', 'targets.json'):
            destination = self.root / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, destination)
        self.git('init')
        self.commit()
        self.base = self.git('rev-parse', 'HEAD')

    def git(self, *args):
        return subprocess.run(['git', *args], cwd=self.root, check=True,
                              capture_output=True, text=True).stdout.strip()

    def commit(self):
        self.git('add', '.')
        self.git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                 '-c', 'commit.gpgsign=false', 'commit', '-m', 'Fixture')

    def run_selection(self, path, base=None):
        changed = self.root / path
        changed.parent.mkdir(parents=True, exist_ok=True)
        changed.write_text('change')
        self.commit()
        output = self.root / 'output'
        result = subprocess.run(
            ['bash', '-euo', 'pipefail', '-c', script('Select catalog targets',
                                                   ROOT / '.github/workflows/gradle.yml')],
            cwd=self.root, text=True, capture_output=True,
            env=dict(os.environ, BASE_COMMIT=self.base if base is None else base,
                     GITHUB_OUTPUT=str(output)))
        values = dict(line.split('=', 1) for line in output.read_text().splitlines()) if output.exists() else {}
        return result, values

    def test_publication_changes_skip_java_jobs(self):
        result, values = self.run_selection('.github/scripts/upload.py')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values['build'], 'false')
        self.assertEqual(values['core'], 'false')
        self.assertEqual(json.loads(values['matrix']), {'include': []})
        report = json.loads((self.root / 'build/change-impact.json').read_text())
        self.assertEqual(report['checks'], ['publication'])
        self.assertEqual(report['base'], self.base)
        self.assertEqual(report['head'], self.git('rev-parse', 'HEAD'))

    def test_core_changes_run_configured_builds_and_java_matrix(self):
        result, values = self.run_selection('core/src/main/java/Example.java')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values['build'], 'true')
        self.assertEqual(values['core'], 'true')
        self.assertEqual([row['id'] for row in json.loads(values['matrix'])['include']],
                         ['26.3-fabric'])

    def test_initial_push_runs_full_development_checks(self):
        result, values = self.run_selection('README.md', base='0' * 40)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(values['build'], 'true')
        self.assertEqual(values['core'], 'true')

    def test_missing_baseline_fails_selection(self):
        result, values = self.run_selection('README.md', base='missing-commit')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(values, {})

    def test_explicit_head_uses_that_revisions_ci_catalog(self):
        path = self.root / 'targets.json'
        catalog = json.loads(path.read_text())
        catalog['ciTargets'] = ['26.2-fabric']
        path.write_text(json.dumps(catalog))
        self.commit()
        result = subprocess.run(
            ['python3', 'scripts/change_impact.py', '--base', '0' * 40,
             '--head', self.base], cwd=self.root, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(report['head'], self.base)
        self.assertEqual([row['id'] for row in report['ci']['matrix']['include']],
                         ['26.3-fabric'])


if __name__ == '__main__':
    unittest.main()
