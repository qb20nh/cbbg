from contextlib import ExitStack
from contextlib import redirect_stderr
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
import zipfile

import fabric_parity_runtime as launcher


class LauncherFailureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.game = self.root / 'new-game'
        self.jar = self.root / 'fixture.jar'
        self.jar.write_bytes(b'fixture')
        self.driver = self.root / 'driver.jar'
        with zipfile.ZipFile(self.driver, 'w') as jar:
            jar.writestr('fabric.mod.json', json.dumps({
                'id': 'cbbg-renderer-test',
                'entrypoints': {'fabric-client-gametest': ['example.Scenario']}}))
        self.lock = self.root / 'lock.json'
        self.lock.write_text('{}')
        args = ['launcher', '--backend', 'opengl', '--x-display', ':99']
        paths = {'runtime': self.root, 'java': self.jar, 'game-dir': self.game,
                 'candidate': self.jar, 'driver': self.driver, 'gametest-api': self.jar,
                 'runtime-lock': self.lock, 'dependency-lock': self.lock,
                 'xdg-runtime-dir': self.root}
        for name, path in paths.items():
            args.extend(['--' + name, str(path)])
        args.extend(['--dependency', 'fabricApi=' + str(self.jar)])
        stack = self.enterContext(ExitStack())
        stack.enter_context(patch.object(sys, 'argv', args))
        stack.enter_context(patch.object(launcher, 'version', return_value='8.0'))
        # Isolate client execution, not receipt writing or scenario validation.
        command_module = types.ModuleType('minecraft_launcher_lib.command')
        command_module.get_minecraft_command = lambda *args: ['java', 'fixture.Main']
        stack.enter_context(patch.dict(sys.modules, {
            'minecraft_launcher_lib': types.ModuleType('minecraft_launcher_lib'),
            'minecraft_launcher_lib.command': command_module}))
        stack.enter_context(patch.object(launcher, 'verify_dependencies'))
        self.runtime_check = stack.enter_context(patch.object(launcher, 'verify_runtime'))
        stack.enter_context(patch.object(launcher, 'java_identity', return_value={'fixture': True}))
        stack.enter_context(patch.object(launcher.subprocess, 'check_output',
                                        side_effect=['a' * 40, b'']))
        self.run = stack.enter_context(patch.object(launcher.subprocess, 'run'))

    def receipt(self):
        return json.loads((self.game / 'probe.json').read_text())

    def test_timeout_is_recorded_without_success(self):
        self.run.side_effect = subprocess.TimeoutExpired(['java'], 240)
        with self.assertRaises(subprocess.TimeoutExpired):
            launcher.main()
        receipt = self.receipt()
        self.assertEqual(receipt['failure']['type'], 'TimeoutExpired')
        self.assertFalse(receipt['releaseAcceptance'])
        self.assertNotIn('scenarios', receipt)
        self.assertEqual(len(receipt['artifacts']), 4)

    def test_nonzero_exit_rejects_even_complete_trace(self):
        def failed_client(command, **kwargs):
            evidence = self.game / 'evidence'
            evidence.mkdir()
            (evidence / 'scenarios.tsv').write_text(
                'started\texample.Scenario\npassed\texample.Scenario\n')
            kwargs['stdout'].write('Readback backend=opengl\n')
            return subprocess.CompletedProcess(command, 1)
        self.run.side_effect = failed_client
        with self.assertRaisesRegex(ValueError, 'did not exit successfully'):
            launcher.main()
        receipt = self.receipt()
        self.assertEqual(receipt['exitCode'], 1)
        self.assertNotIn('scenarios', receipt)

    def test_existing_directory_is_untouched(self):
        self.game.mkdir()
        sentinel = self.game / 'personal-data'
        sentinel.write_bytes(b'preserve')
        with self.assertRaises(FileExistsError):
            launcher.main()
        self.run.assert_not_called()
        self.assertEqual(list(self.game.iterdir()), [sentinel])
        self.assertEqual(sentinel.read_bytes(), b'preserve')

    def test_success_receipt_binds_scenarios_logs_catalog_and_graphics(self):
        def successful_client(command, **kwargs):
            evidence = self.game / 'evidence'
            evidence.mkdir()
            (evidence / 'scenarios.tsv').write_text(
                'started\texample.Scenario\npassed\texample.Scenario\n')
            kwargs['stdout'].write('Readback backend=OpenGL GPU=Fixture driver=3.3\n')
            (evidence / 'graphics-context.json').write_text(
                json.dumps({'backend': 'opengl', 'profile': 'core', 'major': 3, 'minor': 3}))
            return subprocess.CompletedProcess(command, 0)
        self.run.side_effect = successful_client
        with patch('builtins.print'):
            launcher.main()
        receipt = self.receipt()
        self.assertNotIn('failure', receipt)
        self.assertEqual(receipt['catalogSha256'], launcher.digest(launcher.ROOT / 'targets.json'))
        self.assertEqual(receipt['graphics']['gpu'], 'Fixture')
        self.assertEqual(receipt['scenarios']['completedEntrypoints'], 1)
        self.assertEqual(receipt['java'], {'fixture': True})
        for name, digest in receipt['evidence'].items():
            self.assertEqual(digest, launcher.digest(self.game / name))
        self.assertEqual(receipt['graphics']['contextProfile'], 'core')
        self.assertEqual(set(receipt['evidence']),
                         {'launch.log', 'evidence/scenarios.tsv', 'evidence/graphics-context.json'})

    def test_lock_failure_prevents_directory_creation_and_launch(self):
        self.runtime_check.side_effect = ValueError('Runtime inputs differ')
        with self.assertRaisesRegex(ValueError, 'Runtime inputs differ'):
            launcher.main()
        self.run.assert_not_called()
        self.assertFalse(self.game.exists())

    def test_dsa_selector_rejects_an_ordinary_driver(self):
        with patch.object(sys, 'argv', sys.argv + ['--dsa-mode', 'emulated']):
            with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as failure:
                launcher.main()
        self.assertEqual(failure.exception.code, 2)
        self.run.assert_not_called()
        self.assertFalse(self.game.exists())

    def test_context_backend_mismatch_cannot_record_success(self):
        def wrong_context(command, **kwargs):
            evidence = self.game / 'evidence'
            evidence.mkdir()
            (evidence / 'scenarios.tsv').write_text(
                'started\texample.Scenario\npassed\texample.Scenario\n')
            (evidence / 'graphics-context.json').write_text('{"backend":"vulkan"}')
            kwargs['stdout'].write('Readback backend=OpenGL GPU=Fixture driver=3.3\n')
            return subprocess.CompletedProcess(command, 0)
        self.run.side_effect = wrong_context
        with self.assertRaisesRegex(ValueError, 'context backend mismatch'):
            launcher.main()
        self.assertNotIn('scenarios', self.receipt())


if __name__ == '__main__':
    unittest.main()
