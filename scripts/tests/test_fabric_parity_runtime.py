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
        self.launch_identity = None
        def get_minecraft_command(*args):
            self.launch_identity = args[0]
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

    def test_patch_target_uses_selected_runtime_and_dependencies(self):
        self.run.side_effect = subprocess.TimeoutExpired(['java', 'fixture.Main'], 240)
        with (patch.object(sys, 'argv', sys.argv + ['--target', '26.1.2-fabric']),
              self.assertRaises(subprocess.TimeoutExpired)):
            launcher.main()
        self.assertEqual(self.launch_identity, 'fabric-loader-0.19.5-26.1.2')
        self.assertEqual(self.receipt()['target'], '26.1.2-fabric')
        self.assertEqual(self.receipt()['renderer'], 'blaze-texture-format')
        selected = launcher.verify_dependencies.call_args.args[0]
        self.assertEqual(selected['dependencies']['fabricApi'], '0.155.3+26.1.2')
        self.assertEqual(self.launch_options['jvmArguments'][-2], '-Dcbbg.test.modmenu.version=18.0.2')

    def test_26_1_patch_rejects_vulkan_before_launch(self):
        arguments = sys.argv + ['--target', '26.1.2-fabric']
        arguments[arguments.index('opengl')] = 'vulkan'
        with patch.object(sys, 'argv', arguments), self.assertRaises(SystemExit):
            launcher.main()
        self.run.assert_not_called()
        self.assertFalse(self.game.exists())

    def test_minimum_search_changes_only_cbbg_and_test_driver_requirements(self):
        def client(command, **kwargs):
            overrides = json.loads((self.game / 'config/fabric_loader_dependencies.json').read_text())
            self.assertEqual(overrides, {'version': 1, 'overrides': {
                'cbbg': {'-depends': {'fabricloader': '*', 'fabric-api': '*'},
                         '+depends': {'fabricloader': '*', 'fabric-api': '*'}},
                'cbbg-renderer-test': {'-depends': {'fabricloader': '*'},
                                       '+depends': {'fabricloader': '*'}}}})
            raise subprocess.TimeoutExpired(command, 240)
        self.run.side_effect = client
        with (patch.object(sys, 'argv', sys.argv + ['--loader-version', '0.19.3',
                '--fabric-api-version', '0.153.0+26.3', '--test-dependency-minimums']),
              self.assertRaises(subprocess.TimeoutExpired)):
            launcher.main()
        self.assertEqual(self.receipt()['loaderProfile'], 'fabric-loader-0.19.3-26.3')
        self.assertTrue(self.receipt()['dependencyMinimumTest'])
        self.assertFalse(self.receipt()['releaseAcceptance'])
        selected = launcher.verify_dependencies.call_args.args[0]
        framework = self.gametest_check.call_args.args[0]
        self.assertEqual(selected['dependencies']['fabricApi'], '0.153.0+26.3')
        self.assertNotEqual(framework['dependencies']['fabricApi'], '0.153.0+26.3')
        self.assertEqual(self.receipt()['gametestApiVersion'], framework['dependencies']['fabricApi'])

    def test_gametest_framework_cannot_change_with_runtime_api(self):
        with (patch.object(sys, 'argv', sys.argv + ['--gametest-api-version', '0.144.1+26.1']),
              self.assertRaises(SystemExit)):
            launcher.main()
        self.run.assert_not_called()

    def test_initial_config_is_installed_before_launch_and_retained(self):
        settings = self.root / 'settings.json'
        settings.write_text('{"strength": 2}\n')
        def client(command, **kwargs):
            self.assertEqual((self.game / 'config/cbbg.json').read_bytes(), settings.read_bytes())
            (self.game / 'config/cbbg.json').write_text('{"strength": 1}')
            raise subprocess.TimeoutExpired(command, 240)
        self.run.side_effect = client
        with (patch.object(sys, 'argv', sys.argv + ['--cbbg-config', str(settings)]),
              self.assertRaises(subprocess.TimeoutExpired)):
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
            with (self.subTest(value=value), patch.object(sys, 'argv',
                    sys.argv + ['--cbbg-config', str(settings)]),
                  redirect_stderr(io.StringIO()), self.assertRaises(SystemExit)):
                launcher.main()
            self.run.assert_not_called()
            self.assertFalse(self.game.exists())

    def test_restart_cannot_replace_initial_config(self):
        settings = self.root / 'settings.json'
        settings.write_text('{"strength": 2}')
        with (patch.object(sys, 'argv', sys.argv + ['--cbbg-config', str(settings),
                                                 '--restart-phase', 'verify']),
              redirect_stderr(io.StringIO()), self.assertRaises(SystemExit)):
            launcher.main()
        self.run.assert_not_called()
        self.assertFalse(self.game.exists())

    def test_x11_launch_prefers_x11_in_minecraft(self):
        self.run.side_effect = subprocess.TimeoutExpired(['java'], 240)
        with self.assertRaises(subprocess.TimeoutExpired):
            launcher.main()
        self.assertIn('-DMC_DEBUG_ENABLED=true', self.launch_options['jvmArguments'])
        self.assertIn('-DMC_DEBUG_PREFER_WAYLAND=false', self.launch_options['jvmArguments'])

    def test_native_extraction_uses_fresh_game_directory(self):
        module = sys.modules['minecraft_launcher_lib.command']
        original = module.get_minecraft_command

        def command(*args):
            return original(*args) + [
                '-Dorg.lwjgl.system.SharedLibraryExtractPath=' + str(self.root / 'runtime-natives')]

        def client(arguments, **kwargs):
            extracts = [value for value in arguments
                        if value.startswith('-Dorg.lwjgl.system.SharedLibraryExtractPath=')]
            self.assertEqual(extracts, [
                '-Dorg.lwjgl.system.SharedLibraryExtractPath=' + str(self.game / 'natives')])
            native = Path(extracts[0].split('=', 1)[1])
            self.assertTrue(native.is_dir())
            (native / 'libglfw.so').write_bytes(b'generated native')
            raise subprocess.TimeoutExpired(arguments, 240)

        self.run.side_effect = client
        with (patch.object(module, 'get_minecraft_command', command),
              self.assertRaises(subprocess.TimeoutExpired)):
            launcher.main()
        self.assertFalse((self.root / 'runtime-natives').exists())
        checked_command = self.runtime_check.call_args.args[2]
        self.assertIn('-Dorg.lwjgl.system.SharedLibraryExtractPath=' +
                      str(self.root / 'runtime-natives'), checked_command)

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
        with (patch.object(sys, 'argv', sys.argv + ['--dsa-mode', 'emulated']),
              redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as failure):
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

    def prepare_restart(self):
        entry = 'com.qb20nh.cbbg.gametest.IrisRestartGameTest'
        backend = 'opengl'
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

    def test_restart_rejects_changed_settings_without_launch(self):
        self.prepare_restart()
        (self.game / 'config/cbbg.json').write_text('{"mode":"DISABLED"}')
        with (patch.object(sys, 'argv', sys.argv + ['--restart-phase', 'verify']),
              self.assertRaisesRegex(ValueError, 'persisted state changed')):
            launcher.main()
        self.assertEqual(self.run.call_count, 1)
        self.assertFalse((self.game / 'verify-probe.json').exists())

    def test_restart_rejects_changed_installed_jar(self):
        self.prepare_restart()
        (self.game / 'mods/candidate.jar').write_bytes(b'changed')
        with (patch.object(sys, 'argv', sys.argv + ['--restart-phase', 'verify']),
              self.assertRaisesRegex(ValueError, 'installed artifact changed')):
            launcher.main()
        self.assertEqual(self.run.call_count, 1)

    def test_restart_rejects_changed_prepare_evidence(self):
        self.prepare_restart()
        (self.game / 'evidence-prepare/scenarios.tsv').write_text('altered')
        with (patch.object(sys, 'argv', sys.argv + ['--restart-phase', 'verify']),
              self.assertRaisesRegex(ValueError, 'prepare evidence changed')):
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
