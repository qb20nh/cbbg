from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from change_impact import changed_paths, ci_plan, select_checks
from targets import load_catalog


class ChangeImpactTests(unittest.TestCase):
    def setUp(self):
        self.catalog = {'targets': [
            {'id': 'fabric', 'renderer': 'modern', 'buildProfile': 'fabric',
             'sourceGroups': ['adapters/fabric']},
            {'id': 'quilt', 'renderer': 'modern', 'artifactOf': 'fabric'},
            {'id': 'forge', 'renderer': 'legacy', 'buildProfile': 'forge',
             'sourceGroups': ['adapters/forge']},
        ]}

    def select(self, *paths):
        return select_checks(self.catalog, paths)

    def test_adapter_changes_include_every_runtime_sharing_the_jar(self):
        result = self.select('adapters/fabric/src/Example.java')
        self.assertEqual(result['targets'], ['fabric', 'quilt'])
        self.assertIn('runtime', result['checks'])

    def test_renderer_and_build_profile_use_catalog_dependencies(self):
        for path in ('renderers/modern/src/Example.java', 'build-config/fabric/build.gradle'):
            with self.subTest(path=path):
                self.assertEqual(self.select(path)['targets'], ['fabric', 'quilt'])

    def test_undeclared_sources_prevent_excluding_a_target(self):
        del self.catalog['targets'][2]['sourceGroups']
        self.assertEqual(self.select('adapters/fabric/src/Example.java')['targets'],
                         ['fabric', 'forge', 'quilt'])

    def test_core_and_unknown_paths_expand_checks(self):
        for path in ('core/src/Example.java', 'new-renderer/Example.java', 'targets.json'):
            with self.subTest(path=path):
                result = self.select(path)
                self.assertEqual(result['targets'], ['fabric', 'forge', 'quilt'])
                self.assertIn('core-java-8-17-21-25', result['checks'])

    def test_publication_and_documentation_do_not_request_clients(self):
        result = self.select('build-config/publishing/build.gradle',
                             '.github/scripts/release.py', 'README.md')
        self.assertEqual(result['targets'], [])
        self.assertEqual(result['checks'], ['documentation', 'publication'])

    def test_mixed_changes_keep_runtime_checks(self):
        result = self.select('README.md', 'adapters/forge/src/Example.java')
        self.assertEqual(result['targets'], ['forge'])
        self.assertIn('runtime', result['checks'])

    def test_prefixes_require_directory_boundaries(self):
        result = self.select('adapters/fabric-other/src/Example.java')
        self.assertEqual(result['targets'], ['fabric', 'forge', 'quilt'])
        self.assertIn('unknown dependency', result['changes'][0]['reason'])

    def test_empty_changes_and_invalid_paths(self):
        self.assertEqual(self.select(), {'changes': [], 'targets': [], 'checks': []})
        for path in ('', '../file', '/file', 'dir\\file'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                self.select(path)

    def test_ci_skips_builds_for_publication_changes(self):
        catalog = load_catalog()
        plan = ci_plan(catalog, select_checks(catalog, ['.github/scripts/release.py']))
        self.assertEqual(plan, {'matrix': {'include': []}, 'build': False, 'core': False})

    def test_ci_runs_configured_targets_and_all_core_jvms_for_core_changes(self):
        catalog = load_catalog()
        plan = ci_plan(catalog, select_checks(catalog, ['core/src/main/java/Example.java']))
        self.assertTrue(plan['build'])
        self.assertTrue(plan['core'])
        self.assertEqual([row['id'] for row in plan['matrix']['include']], catalog['ciTargets'])

    def test_renames_include_both_dependency_locations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.run(['git', *args], cwd=root, check=True,
                                      capture_output=True, text=True).stdout.strip()
            git('init')
            (root / 'old.java').write_text('class Example {}')
            git('add', '.')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                '-c', 'commit.gpgsign=false', 'commit', '-m', 'Initial')
            base = git('rev-parse', 'HEAD')
            git('mv', 'old.java', 'new.java')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                '-c', 'commit.gpgsign=false', 'commit', '-m', 'Move')
            self.assertEqual(set(changed_paths(root, base, 'HEAD')), {'old.java', 'new.java'})


if __name__ == '__main__':
    unittest.main()
