import json
from pathlib import Path
import tempfile
import unittest

from fabric_scenario_evidence import (STARTUP_DRIVER, graphics_identity, validate_scenarios,
                                      validate_shutdown, validate_startup)
from fabric_parity_runtime import prepare_startup_cache


class StartupEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.game = Path(self.temp.name)
        self.evidence = self.game / 'evidence'
        prepare_startup_cache(self.game, self.evidence, 'cold', None)
        self.result = dict(backend='opengl', size=16, depth=8, seed=74123, preLaunchWorkers=1,
                           preLaunchMillis=10, firstDrawMillis=30, titleMillis=40, cacheReadyMillis=50,
                           pixelsSha256='4f953f23c7a2a7de8960caa4272090458b2ec986ceeea65796467a0c9109a076')
        self.early = dict(preLaunchMillis=10, preLaunchWorkers=1)
        self.log = 'Starting Async STBN Math Generation (16x16x8)...\nSTBN Math Complete in 2 ms\n'

    def verify(self, mode='cold'):
        (self.evidence / 'startup.json').write_text(json.dumps(self.result))
        (self.evidence / 'startup-prelaunch.json').write_text(json.dumps(self.early))
        validate_startup([STARTUP_DRIVER], mode, self.evidence, self.game / '.cbbg', self.log, 'opengl')

    def test_complete_cold_startup(self):
        self.verify()

    def test_duplicate_or_missing_generation_rejected(self):
        for value in ('', self.log + self.log):
            with self.subTest(log=value), self.assertRaises(ValueError):
                self.log = value
                self.verify()

    def test_missing_workers_pixels_or_timing_rejected(self):
        original = self.result.copy()
        for field, value in [('preLaunchWorkers', 0), ('pixelsSha256', 'bad'),
                             ('preLaunchMillis', 31), ('firstDrawMillis', 0),
                             ('cacheReadyMillis', 39), ('backend', 'vulkan'), ('seed', 0)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.result = dict(original, **{field: value})
                self.verify()

    def test_missing_prelaunch_file_rejected(self):
        self.verify()
        (self.evidence / 'startup-prelaunch.json').unlink()
        with self.assertRaises(FileNotFoundError):
            validate_startup([STARTUP_DRIVER], 'cold', self.evidence, self.game / '.cbbg', self.log, 'opengl')

    def test_wrong_driver_or_missing_mode_rejected(self):
        for expected, mode in [([STARTUP_DRIVER], None), (['other'], 'cold'),
                               ([STARTUP_DRIVER, 'other'], 'cold')]:
            with self.subTest(expected=expected, mode=mode), self.assertRaises(ValueError):
                validate_startup(expected, mode, self.evidence, self.game / '.cbbg', self.log, 'opengl')

    def test_warm_and_damaged_inputs_preserve_source(self):
        from fabric_scenario_evidence import STARTUP_CACHE_FILES
        source = self.game / 'source'
        source.mkdir()
        for name in STARTUP_CACHE_FILES:
            (source / name).write_bytes(name.encode())
        for mode in ('warm', 'damaged'):
            directory = self.game / mode
            directory.mkdir()
            prepare_startup_cache(directory, directory / 'evidence', mode, source)
            inputs = json.loads((directory / 'evidence/startup-input.json').read_text())
            self.assertEqual(set(inputs['files']), set(STARTUP_CACHE_FILES))
            self.assertEqual((source / STARTUP_CACHE_FILES[-1]).read_bytes(), STARTUP_CACHE_FILES[-1].encode())
            self.assertEqual((directory / '.cbbg' / STARTUP_CACHE_FILES[-1]).read_bytes(),
                             bytes([1, 2, 3]) if mode == 'damaged' else STARTUP_CACHE_FILES[-1].encode())
            for name in ('startup.json', 'startup-prelaunch.json'):
                (directory / 'evidence' / name).write_text(json.dumps(
                    self.result if name == 'startup.json' else self.early))
            log = 'Valid STBN cache found for 16x16x8' if mode == 'warm' else self.log
            if mode == 'damaged':
                with self.assertRaisesRegex(ValueError, 'not repaired'):
                    validate_startup([STARTUP_DRIVER], mode, directory / 'evidence', directory / '.cbbg', log, 'opengl')
                (directory / '.cbbg' / STARTUP_CACHE_FILES[-1]).write_bytes(STARTUP_CACHE_FILES[-1].encode())
            validate_startup([STARTUP_DRIVER], mode, directory / 'evidence', directory / '.cbbg', log, 'opengl')
            if mode == 'warm':
                (directory / '.cbbg' / STARTUP_CACHE_FILES[-1]).touch()
                with self.assertRaisesRegex(ValueError, 'rewrote cache'):
                    validate_startup([STARTUP_DRIVER], mode, directory / 'evidence', directory / '.cbbg', log, 'opengl')


class ShutdownEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'shutdown.json'
        self.expected = ['com.qb20nh.cbbg.gametest.ReleaseShutdownGameTest']
        self.record = dict(kind='idle', workerTerminated=True, resourcesChecked=2,
                           resourcesClosed=True, generationStarted=False, generationCompleted=False)

    def verify(self):
        self.path.write_text(json.dumps(self.record))
        validate_shutdown(self.expected, self.path)

    def test_idle_and_generating_shutdown(self):
        self.verify()
        self.expected = ['com.qb20nh.cbbg.gametest.ReleaseGeneratingShutdownGameTest']
        self.record.update(kind='generating', resourcesChecked=0, generationStarted=True)
        self.verify()

    def test_missing_result_cannot_pass(self):
        with self.assertRaises(FileNotFoundError):
            validate_shutdown(self.expected, self.path)

    def test_missing_or_failed_checks_cannot_pass(self):
        valid = self.record.copy()
        for field in valid:
            with self.subTest(missing=field), self.assertRaises(ValueError):
                self.record = {key: value for key, value in valid.items() if key != field}
                self.verify()
        for field, value in [('failure', 'worker active'), ('workerTerminated', False),
                             ('workerTerminated', 1), ('resourcesClosed', False),
                             ('resourcesChecked', 0), ('resourcesChecked', True),
                             ('generationStarted', True), ('generationCompleted', True),
                             ('kind', 'generating')]:
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                self.record = dict(valid, **{field: value})
                self.verify()

    def test_shutdown_cannot_be_combined_with_other_tests(self):
        self.expected.append('example.Other')
        with self.assertRaises(ValueError):
            self.verify()

    def test_other_drivers_need_no_shutdown_record(self):
        validate_shutdown(['example.Other'], self.path)


class FabricScenarioEvidenceTests(unittest.TestCase):
    def test_graphics_identity_preserves_observed_driver_and_extensions(self):
        log = ('Readback backend=OpenGL GPU=Fixture GPU driver=3.3 Fixture Driver\n'
               'Using graphics device extensions: GL_B, GL_A\n')
        result = graphics_identity(log + log, 'opengl')
        self.assertEqual(result['gpu'], 'Fixture GPU')
        self.assertEqual(result['driver'], '3.3 Fixture Driver')
        self.assertEqual(result['reportedExtensions'], ['GL_A', 'GL_B'])
        self.assertIsNone(result['contextProfile'])

    def test_graphics_identity_rejects_missing_conflicting_or_wrong_backend(self):
        log = 'Readback backend=Vulkan GPU=Fixture driver=1.4\n'
        for text, backend in [('', 'vulkan'), (log, 'opengl'),
                              (log + log.replace('1.4', '1.3'), 'vulkan')]:
            with self.subTest(text=text, backend=backend):
                with self.assertRaises(ValueError):
                    graphics_identity(text, backend)

    def setUp(self):
        self.expected = ["example.First", "example.Second"]
        self.trace = "started\texample.First\npassed\texample.First\nstarted\texample.Second\npassed\texample.Second\n"
        self.log = "Readback backend=Vulkan GPU=fixture\n"

    def verify(self, **changes):
        args = dict(expected=self.expected, trace=self.trace, log=self.log,
                    exit_code=0, backend="vulkan")
        args.update(changes)
        return validate_scenarios(**args)

    def test_complete_entrypoints_are_not_release_acceptance(self):
        result = self.verify()
        self.assertEqual(result["completedEntrypoints"], 2)
        self.assertFalse(result["releaseAcceptance"])

    def test_zero_exit_cannot_hide_vulkan_validation_error(self):
        with self.assertRaises(ValueError):
            self.verify(log=self.log + "VUID-vkCmdBlitImage-dstOffset-00248")

    def test_zero_exit_cannot_hide_logged_minecraft_crash(self):
        for error in ("Minecraft has crashed!", "Client gametests failed with an exception"):
            with self.subTest(error=error), self.assertRaises(ValueError):
                self.verify(log=self.log + error)

    def test_zero_exit_cannot_hide_opengl_errors(self):
        for error in ("GL_INVALID_ENUM", "GL_INVALID_VALUE", "GL_INVALID_OPERATION",
                      "GL_INVALID_FRAMEBUFFER_OPERATION", "GL_OUT_OF_MEMORY"):
            with self.subTest(error=error), self.assertRaises(ValueError):
                self.verify(backend="opengl", log="Readback backend=OpenGL\n" + error)

    def test_missing_reordered_duplicate_or_diagnostic_trace_fails(self):
        for trace in ("", self.trace.rsplit("passed", 1)[0],
                      self.trace + "passed\texample.Second\n",
                      "\n".join(reversed(self.trace.splitlines())),
                      self.trace + "diagnostic-options-reset-skipped\texample.Second\n"):
            with self.subTest(trace=trace), self.assertRaises(ValueError):
                self.verify(trace=trace)

    def test_backend_fallback_or_missing_identity_fails(self):
        for log in ("", "Readback backend=OpenGL GPU=fixture", self.log + "Readback backend=OpenGL"):
            with self.subTest(log=log), self.assertRaises(ValueError):
                self.verify(log=log)

    def test_invalid_contract_and_nonzero_exit_fail(self):
        for changes in ({"expected": []}, {"expected": ["same", "same"]},
                        {"expected": [None]}, {"exit_code": 255}, {"exit_code": False},
                        {"backend": "unknown"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.verify(**changes)


if __name__ == "__main__":
    unittest.main()
