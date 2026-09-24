from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from subprocess import CompletedProcess

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_targets import commands, main
from targets import load_catalog


class BuildDispatchTest(unittest.TestCase):
    def setUp(self):
        self.catalog = load_catalog()
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'gradlew').touch()
        # Simulate independently installed build profiles; do not depend on
        # unfinished targets being present in the current branch.
        for profile in ('forge-classic', 'neoforge-modern', 'forge-modern',
                        'legacy-fabric', 'forge-legacy', 'fabric-modern'):
            directory = self.root / 'builds' / profile
            directory.mkdir(parents=True)
            (directory / 'build.gradle').touch()
        for profile in ('forge-classic', 'forge-modern'):
            (self.root / 'builds' / profile / 'gradlew').touch()

    def plan(self, task, selection, **kwargs):
        return commands(self.catalog, task, selection, root=self.root, **kwargs)

    def test_mixed_loaders_keep_wrappers_and_catalog_order(self):
        planned = self.plan('check', '26.2-neoforge,1.20.1-forge')
        self.assertEqual(2, len(planned))
        self.assertEqual(str(self.root / 'builds/forge-classic/gradlew'), planned[0][0])
        self.assertEqual(str(self.root / 'gradlew'), planned[1][0])
        self.assertIn('-Ptarget=26.2-neoforge', planned[1])

    def test_upstream_client_is_26_2_and_does_not_reenter_dispatch(self):
        command, = self.plan('check', '26.2-fabric')
        self.assertEqual(str(self.root), command[2])
        self.assertFalse(any(arg.startswith('-Ptarget') for arg in command))
        self.assertIn(str(self.root / '.gradle/dispatch/26.2-fabric'), command)
        target = next(t for t in self.catalog['targets'] if t['id'] == '1.21.11-fabric')
        target['buildProfile'] = 'fabric-upstream'
        with self.assertRaisesRegex(ValueError, 'belongs to 26.2'):
            self.plan('check', target['id'])

    def test_pending_targets_cannot_distribute(self):
        for target in ('26.2-fabric', '26.3-forge', '1.21.11-fabric'):
            with self.subTest(target=target), self.assertRaisesRegex(ValueError, 'not implemented'):
                self.plan('build', target)

    def test_source_generation_preserves_isolation(self):
        planned = self.plan('genSources', '26.2-fabric,26.3-forge', offline=True)
        self.assertEqual(2, len(planned))
        self.assertFalse(any(arg.startswith('-Ptarget') for arg in planned[0]))
        self.assertEqual(str(self.root / 'builds/forge-modern/gradlew'), planned[1][0])
        self.assertIn('-Ptarget=26.3-forge', planned[1])
        self.assertTrue(all('--offline' in command for command in planned))

    def test_client_selection_and_forwarded_properties(self):
        with self.assertRaisesRegex(ValueError, 'exactly one'):
            self.plan('runClient', '26.2-fabric,26.2-neoforge')
        command, = self.plan('runClient', '26.2-neoforge',
                            properties=['backend=vulkan', 'compat=none'])
        self.assertIn('-Pbackend=vulkan', command)
        with self.assertRaisesRegex(ValueError, 'Unsupported forwarded property'):
            self.plan('check', '26.2-neoforge', properties=['target=other'])

    def test_invalid_or_missing_profiles_fail_closed(self):
        for value in (None, '../outside', 'not-installed'):
            target = next(t for t in self.catalog['targets'] if t['id'] == '26.3-forge')
            target['buildProfile'] = value
            with self.subTest(profile=value), self.assertRaises(ValueError):
                self.plan('check', target['id'])
        for selection in ('', 'missing', '26.2-fabric,26.2-fabric'):
            with self.assertRaises(ValueError):
                self.plan('check', selection)
        with self.assertRaisesRegex(ValueError, 'Unsupported dispatch task'):
            self.plan('publish', '26.2-fabric')

    def test_child_failure_stops_remaining_commands(self):
        planned = self.plan('check', '26.2-fabric,26.3-forge')
        with patch.object(sys, 'argv', ['build_targets.py', 'check', '--targets',
                                       '26.2-fabric,26.3-forge']), \
                patch('build_targets.commands', return_value=planned), \
                patch('build_targets.subprocess.run', return_value=CompletedProcess([], 7)) as run:
            self.assertEqual(7, main())
            self.assertEqual(1, run.call_count)

    def test_entire_selection_checked_before_execution(self):
        next(t for t in self.catalog['targets'] if t['id'] == '26.3-forge')['buildProfile'] = None
        with patch.object(sys, 'argv', ['build_targets.py', 'check', '--targets',
                                       '26.2-fabric,26.3-forge']), \
                patch('build_targets.load_catalog', return_value=self.catalog), \
                patch('build_targets.subprocess.run') as run, \
                patch('sys.stderr'), self.assertRaises(SystemExit) as error:
            main()
        self.assertEqual(2, error.exception.code)
        run.assert_not_called()
