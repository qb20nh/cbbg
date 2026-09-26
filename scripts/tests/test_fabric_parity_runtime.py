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


class SourceStatusTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        (self.root / 'docs').mkdir()
        (self.root / 'docs/plan.md').write_text('Local plan')

    def test_local_docs_do_not_mark_source_dirty(self):
        self.assertFalse(launcher.source_dirty(self.root))

    def test_untracked_source_marks_source_dirty(self):
        (self.root / 'renderer.java').write_text('class Renderer {}')
        self.assertTrue(launcher.source_dirty(self.root))

    def test_staged_source_marks_source_dirty(self):
        (self.root / 'targets.json').write_text('{}')
        subprocess.run(['git', 'add', 'targets.json'], cwd=self.root, check=True)
        self.assertTrue(launcher.source_dirty(self.root))

    def test_docs_in_other_directories_are_included(self):
        (self.root / 'adapter/docs').mkdir(parents=True)
        (self.root / 'adapter/docs/input.md').write_text('Packaged input')
        self.assertTrue(launcher.source_dirty(self.root))


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
        self.launch_options = None
        def get_minecraft_command(*args):
            self.launch_options = args[2]
            return ['java', 'fixture.Main']
        command_module.get_minecraft_command = get_minecraft_command
        stack.enter_context(patch.dict(sys.modules, {
            'minecraft_launcher_lib': types.ModuleType('minecraft_launcher_lib'),
            'minecraft_launcher_lib.command': command_module}))
        stack.enter_context(patch.object(launcher, 'verify_dependencies'))
        self.gametest_check = stack.enter_context(patch.object(launcher, 'verify_gametest_api'))
        self.runtime_check = stack.enter_context(patch.object(launcher, 'verify_runtime'))
        stack.enter_context(patch.object(launcher, 'java_identity', return_value={'fixture': True}))
        stack.enter_context(patch.object(launcher.subprocess, 'check_output',
                                        side_effect=['a' * 40, b'']))
        self.run = stack.enter_context(patch.object(launcher.subprocess, 'run'))

    def receipt(self):
        return json.loads((self.game / 'probe.json').read_text())

    def test_initial_config_is_installed_before_launch_and_retained(self):
        settings = self.root / 'settings.json'
        settings.write_text('{"strength": 2}\n')
        def client(command, **kwargs):
            self.assertEqual((self.game / 'config/cbbg.json').read_bytes(), settings.read_bytes())
            (self.game / 'config/cbbg.json').write_text('{"strength": 1}')
            raise subprocess.TimeoutExpired(command, 240)
        self.run.side_effect = client
        with patch.object(sys, 'argv', sys.argv + ['--cbbg-config', str(settings)]):
            with self.assertRaises(subprocess.TimeoutExpired):
                launcher.main()
        receipt = self.receipt()
        retained = self.game / 'evidence/initial-cbbg.json'
        self.assertEqual(retained.read_bytes(), settings.read_bytes())
        self.assertEqual(receipt['initialConfigSha256'], launcher.digest(settings))
        self.assertEqual(receipt['evidence']['evidence/initial-cbbg.json'], launcher.digest(settings))

    def test_invalid_initial_config_prevents_launch(self):
        settings = self.root / 'settings.json'
        for value in ('{', '[]', 'null'):
            settings.write_text(value)
            with self.subTest(value=value), patch.object(sys, 'argv',
                    sys.argv + ['--cbbg-config', str(settings)]):
                with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    launcher.main()
            self.run.assert_not_called()
            self.assertFalse(self.game.exists())

    def test_restart_cannot_replace_initial_config(self):
        settings = self.root / 'settings.json'
        settings.write_text('{"strength": 2}')
        with patch.object(sys, 'argv', sys.argv + ['--cbbg-config', str(settings),
                                                 '--restart-phase', 'verify']):
            with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                launcher.main()
        self.run.assert_not_called()
        self.assertFalse(self.game.exists())

    def test_x11_launch_prefers_x11_in_minecraft(self):
        self.run.side_effect = subprocess.TimeoutExpired(['java'], 240)
        with self.assertRaises(subprocess.TimeoutExpired):
            launcher.main()
        self.assertIn('-DMC_DEBUG_ENABLED=true', self.launch_options['jvmArguments'])
        self.assertIn('-DMC_DEBUG_PREFER_WAYLAND=false', self.launch_options['jvmArguments'])

    def test_wayland_launch_prefers_wayland_in_minecraft(self):
        args = [arg for arg in sys.argv if arg not in ('--x-display', ':99')]
        args.extend(['--wayland-display', 'wayland-7'])
        with patch.object(sys, 'argv', args):
            self.run.side_effect = subprocess.TimeoutExpired(['java'], 240)
            with self.assertRaises(subprocess.TimeoutExpired):
                launcher.main()
        self.assertIn('-DMC_DEBUG_ENABLED=true', self.launch_options['jvmArguments'])
        self.assertIn('-DMC_DEBUG_PREFER_WAYLAND=true', self.launch_options['jvmArguments'])

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

    def test_completed_shutdown_entrypoint_requires_cleanup_result(self):
        entrypoint = 'com.qb20nh.cbbg.gametest.ReleaseShutdownGameTest'
        with zipfile.ZipFile(self.driver, 'w') as jar:
            jar.writestr('fabric.mod.json', json.dumps({
                'id': 'cbbg-renderer-test',
                'entrypoints': {'fabric-client-gametest': [entrypoint]}}))
        def client(command, **kwargs):
            evidence = self.game / 'evidence'
            evidence.mkdir()
            (evidence / 'scenarios.tsv').write_text(
                f'started\t{entrypoint}\npassed\t{entrypoint}\n')
            kwargs['stdout'].write('Readback backend=OpenGL GPU=Fixture driver=3.3\n')
            return subprocess.CompletedProcess(command, 0)
        self.run.side_effect = client
        with self.assertRaises(FileNotFoundError):
            launcher.main()
        self.assertIn('failure', self.receipt())
        self.assertNotIn('scenarios', self.receipt())

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
            (evidence / 'locales').mkdir()
            (evidence / 'world-opengl.png').write_bytes(b'world capture')
            (evidence / 'locales/ko_kr.png').write_bytes(b'locale capture')
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
                         {'launch.log', 'evidence/scenarios.tsv', 'evidence/graphics-context.json',
                          'evidence/world-opengl.png', 'evidence/locales/ko_kr.png'})
        (self.game / 'evidence/world-opengl.png').write_bytes(b'changed capture')
        self.assertNotEqual(receipt['evidence']['evidence/world-opengl.png'],
                            launcher.digest(self.game / 'evidence/world-opengl.png'))

    def test_lock_failure_prevents_directory_creation_and_launch(self):
        self.runtime_check.side_effect = ValueError('Runtime inputs differ')
        with self.assertRaisesRegex(ValueError, 'Runtime inputs differ'):
            launcher.main()
        self.run.assert_not_called()
        self.assertFalse(self.game.exists())

    def test_gametest_lock_failure_prevents_launch(self):
        self.gametest_check.side_effect = ValueError('Gametest API checksum mismatch')
        with self.assertRaisesRegex(ValueError, 'Gametest API checksum mismatch'):
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

    def prepare_restart(self, shader='iris', external=False):
        entry = ('com.qb20nh.cbbg.gametest.' + shader.capitalize()
                 + ('External' if external else '') + 'RestartGameTest')
        backend = 'vulkan' if shader == 'sulkan' else 'opengl'
        if shader == 'sulkan':
            args = list(sys.argv)
            args[args.index('--backend') + 1] = backend
            self.enterContext(patch.object(sys, 'argv', args))
        with zipfile.ZipFile(self.driver, 'w') as jar:
            jar.writestr('fabric.mod.json', json.dumps({
                'id': 'cbbg-renderer-test',
                'entrypoints': {'fabric-client-gametest': [entry]}}))

        def client(command, **kwargs):
            phase = sys.argv[-1]
            evidence = self.game / ('evidence-' + phase)
            evidence.mkdir()
            (evidence / 'scenarios.tsv').write_text(
                'started\t' + entry + '\npassed\t' + entry + '\n')
            (evidence / 'graphics-context.json').write_text(json.dumps({'backend': backend}))
            kwargs['stdout'].write('Readback backend=' + backend + ' GPU=Fixture driver=3.3\n')
            if phase == 'prepare':
                (self.game / 'config').mkdir()
                (self.game / 'config/cbbg.json').write_text('{"mode":"ENABLED"}')
                if shader == 'sulkan':
                    (self.game / 'config/sulkan-shaders.json').write_text(json.dumps({
                        'enabled': True, 'selectedPackId': 'cbbg-native-test' if external else '__builtin__'}))
                    if external:
                        pack = self.game / 'shaders/cbbg-native-test'
                        pack.mkdir(parents=True)
                        (pack / 'sulkan.json').write_text('{"version":1}')
                        (pack / 'color.fsh').write_text('fixture shader')
                else:
                    (self.game / 'config/iris.properties').write_text('enableShaders=true')
                    pack = self.game / 'shaderpacks/cbbg-parity/shaders'
                    pack.mkdir(parents=True)
                    (pack / 'final.fsh').write_text('fixture shader')
            return subprocess.CompletedProcess(command, 0)
        self.run.side_effect = client
        self.enterContext(patch.object(launcher.subprocess, 'check_output',
            side_effect=lambda *a, **kw: 'a' * 40 if kw.get('text') else b''))
        with patch.object(sys, 'argv', sys.argv + ['--restart-phase', 'prepare']), patch('builtins.print'):
            launcher.main()

    def test_restart_preserves_prepare_evidence_and_binds_state(self):
        self.prepare_restart()
        prepare = self.game / 'prepare-probe.json'
        before = prepare.read_bytes()
        with patch.object(sys, 'argv', sys.argv + ['--restart-phase', 'verify']), patch('builtins.print'):
            launcher.main()
        self.assertEqual(prepare.read_bytes(), before)
        verified = json.loads((self.game / 'verify-probe.json').read_text())
        self.assertEqual(verified['prepareReceiptSha256'], launcher.digest(prepare))
        self.assertEqual(verified['inputState'], json.loads(before)['persistedState'])
        self.assertEqual(self.run.call_count, 2)

    def test_sulkan_restart_records_settings_from_prepare(self):
        self.prepare_restart('sulkan')
        prepare = self.game / 'prepare-probe.json'
        before = prepare.read_bytes()
        with patch.object(sys, 'argv', sys.argv + ['--restart-phase', 'verify']), patch('builtins.print'):
            launcher.main()
        verified = json.loads((self.game / 'verify-probe.json').read_text())
        self.assertEqual(prepare.read_bytes(), before)
        self.assertEqual(verified['inputState'], json.loads(before)['persistedState'])
        self.assertEqual(set(verified['inputState']), {'config/cbbg.json', 'config/sulkan-shaders.json'})
        self.assertEqual(self.run.call_count, 2)

    def test_sulkan_restart_rejects_changed_shader_settings(self):
        self.prepare_restart('sulkan')
        (self.game / 'config/sulkan-shaders.json').write_text('{"enabled":false}')
        with patch.object(sys, 'argv', sys.argv + ['--restart-phase', 'verify']):
            with self.assertRaisesRegex(ValueError, 'persisted state changed'):
                launcher.main()
        self.assertEqual(self.run.call_count, 1)

    def test_sulkan_restart_rejects_opengl_before_launch(self):
        with zipfile.ZipFile(self.driver, 'w') as jar:
            jar.writestr('fabric.mod.json', json.dumps({
                'id': 'cbbg-renderer-test',
                'entrypoints': {'fabric-client-gametest': [
                    'com.qb20nh.cbbg.gametest.SulkanRestartGameTest']}}))
        with patch.object(sys, 'argv', sys.argv + ['--restart-phase', 'prepare']):
            with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                launcher.main()
        self.run.assert_not_called()
        self.assertFalse(self.game.exists())

    def test_external_sulkan_restart_records_pack_files(self):
        self.prepare_restart('sulkan', external=True)
        with patch.object(sys, 'argv', sys.argv + ['--restart-phase', 'verify']), patch('builtins.print'):
            launcher.main()
        verified = json.loads((self.game / 'verify-probe.json').read_text())
        self.assertEqual(set(verified['inputState']), {
            'config/cbbg.json', 'config/sulkan-shaders.json',
            'shaders/cbbg-native-test/sulkan.json', 'shaders/cbbg-native-test/color.fsh'})

    def test_external_sulkan_restart_rejects_changed_shader(self):
        self.prepare_restart('sulkan', external=True)
        (self.game / 'shaders/cbbg-native-test/color.fsh').write_text('different shader')
        with patch.object(sys, 'argv', sys.argv + ['--restart-phase', 'verify']):
            with self.assertRaisesRegex(ValueError, 'persisted state changed'):
                launcher.main()
        self.assertEqual(self.run.call_count, 1)

    def test_external_sulkan_restart_rejects_missing_shader(self):
        self.prepare_restart('sulkan', external=True)
        (self.game / 'shaders/cbbg-native-test/color.fsh').unlink()
        with patch.object(sys, 'argv', sys.argv + ['--restart-phase', 'verify']):
            with self.assertRaisesRegex(ValueError, 'restart pack is missing'):
                launcher.main()
        self.assertEqual(self.run.call_count, 1)

    def test_restart_rejects_changed_settings_without_launch(self):
        self.prepare_restart()
        (self.game / 'config/cbbg.json').write_text('{"mode":"DISABLED"}')
        with patch.object(sys, 'argv', sys.argv + ['--restart-phase', 'verify']):
            with self.assertRaisesRegex(ValueError, 'persisted state changed'):
                launcher.main()
        self.assertEqual(self.run.call_count, 1)
        self.assertFalse((self.game / 'verify-probe.json').exists())

    def test_restart_rejects_changed_installed_jar(self):
        self.prepare_restart()
        (self.game / 'mods/candidate.jar').write_bytes(b'changed')
        with patch.object(sys, 'argv', sys.argv + ['--restart-phase', 'verify']):
            with self.assertRaisesRegex(ValueError, 'installed artifact changed'):
                launcher.main()
        self.assertEqual(self.run.call_count, 1)

    def test_restart_rejects_changed_prepare_evidence(self):
        self.prepare_restart()
        (self.game / 'evidence-prepare/scenarios.tsv').write_text('altered')
        with patch.object(sys, 'argv', sys.argv + ['--restart-phase', 'verify']):
            with self.assertRaisesRegex(ValueError, 'prepare evidence changed'):
                launcher.main()
        self.assertEqual(self.run.call_count, 1)

    def test_restart_verify_cannot_overwrite_previous_attempt(self):
        self.prepare_restart()
        with patch.object(sys, 'argv', sys.argv + ['--restart-phase', 'verify']), patch('builtins.print'):
            launcher.main()
            before = (self.game / 'verify-probe.json').read_bytes()
            with self.assertRaises(FileExistsError):
                launcher.main()
        self.assertEqual(self.run.call_count, 2)
        self.assertEqual((self.game / 'verify-probe.json').read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
