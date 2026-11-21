import copy
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest

from fabric_classic_scenarios import (CLASSIC_CACHE_SCENARIOS, CLASSIC_ENTRYPOINT, CLASSIC_IRIS_OPTIONS,
                                      CLASSIC_MODE_RESTART, CLASSIC_OPTIONS,
                                      REDUCED_GL_SCENARIO, SETTINGS_MANIFEST, SETTINGS_NOISE_FRAMES,
                                      VMA_PROBE_AGENT, VULKAN_FORMAT_CASES, VULKAN_NATIVE_CASES,
                                      VULKAN_PIPELINE_CASES, VULKAN_SAMPLER_CASES, VULKAN_VMA_CASES,
                                      classic_config, classic_jvm_arguments, classic_result,
                                      pixel_cache_files, prepare_classic_cache, retain_classic_cache,
                                      validate_classic_choice, vulkan_probe_agent)
from runtime_catalog import load_catalog, select_targets


class ClassicScenarioTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.evidence = Path(temp.name)
        self.metadata = {'id': 'cbbg_classic_smoke', 'entrypoints': {'client': [CLASSIC_ENTRYPOINT]},
                         'custom': {'cbbg:scenarios': ['world', 'satin', REDUCED_GL_SCENARIO,
                                                       'renderscale', 'iris', 'vulkan-lifecycle',
                                                       CLASSIC_MODE_RESTART, *CLASSIC_CACHE_SCENARIOS,
                                                       'vulkan-pixels', 'vulkan-settings',
                                                       *VULKAN_FORMAT_CASES, *VULKAN_VMA_CASES,
                                                       *VULKAN_NATIVE_CASES, *VULKAN_SAMPLER_CASES,
                                                       *VULKAN_PIPELINE_CASES]}}
        self.log = ('CBBG_CLASSIC_VULKAN_DEVICE mod=0.5.2 device=Test GPU api=1.3.0 glfw=no-api reference=false\n'
                    'CBBG_CLASSIC_VULKAN_SCENE format=97 disabled=false\n'
                    'CBBG_CLASSIC_VULKAN_WORLD presentations=12\n')
        (self.evidence / 'initial-cbbg.json').write_text(json.dumps(classic_config('vulkan')))
        (self.evidence / 'initial-options.txt').write_text(CLASSIC_OPTIONS)
        self.capture = self.evidence / 'vulkan-world.png'
        self.write_capture(self.capture, 800, 600)

    @staticmethod
    def write_capture(path, width, height):
        template = pixel_cache_files()['stbn_16x16x8_0.png']
        path.write_bytes(template[:16] + struct.pack('>II', width, height) + template[24:])

    def test_classic_driver_rejects_undeclared_or_wrong_backend(self):
        validate_classic_choice(self.metadata, 'world', 'vulkanmod', 'vulkan')
        with self.assertRaisesRegex(ValueError, 'not declared'):
            validate_classic_choice(self.metadata, 'odd', 'vulkanmod', 'vulkan')
        with self.assertRaisesRegex(ValueError, 'profile or backend'):
            validate_classic_choice(self.metadata, 'world', 'none', 'vulkan')
        wrong = copy.deepcopy(self.metadata)
        wrong['entrypoints']['client'] = ['fabric.client.gametest']
        with self.assertRaisesRegex(ValueError, 'classic test driver metadata'):
            validate_classic_choice(wrong, 'world', 'vulkanmod', 'vulkan')

    def test_world_requires_actual_vulkanmod_device_and_scene(self):
        result, graphics = classic_result('world', 'vulkanmod', 'vulkan', self.log, self.evidence, 0)
        self.assertEqual('vulkanmod', graphics['provider'])
        self.assertEqual('none', graphics['glfwClientApi'])
        self.assertFalse(result['releaseAcceptance'])
        for log in [self.log.replace('glfw=no-api', 'glfw=opengl'),
                    self.log + 'CBBG_CLASSIC_OPENGL Test GPU / 3.2 sodium=false\n',
                    self.log.replace('format=97', 'format=37')]:
            with self.assertRaises(ValueError):
                classic_result('world', 'vulkanmod', 'vulkan', log, self.evidence, 0)

    def test_opengl_world_uses_catalog_profiles_without_enabling_feature_drivers(self):
        target, = select_targets(load_catalog(), '1.20.1-fabric')
        for profile, backends in target['compatibilityProfiles'].items():
            if backends != ['opengl']:
                continue
            with self.subTest(profile=profile):
                validate_classic_choice(self.metadata, 'world', profile, 'opengl')
                arguments = classic_jvm_arguments('candidate.jar', 'world', 'opengl', profile)
                self.assertIn('-Dcbbg.test.compatProfile=' + profile, arguments)
                self.assertNotIn('-Dcbbg.test.satin=true', arguments)
                self.assertNotIn('-Dcbbg.test.iris=true', arguments)
        for profile in ('iris', 'modmenu+sodium+satin'):
            self.assertIn('-Dcbbg.test.sodium=true',
                          classic_jvm_arguments('candidate.jar', 'world', 'opengl', profile))

    def test_context_changers_require_the_observed_context(self):
        validate_classic_choice(self.metadata, REDUCED_GL_SCENARIO, 'forcegl2', 'opengl')
        with self.assertRaisesRegex(ValueError, 'profile or backend'):
            validate_classic_choice(self.metadata, REDUCED_GL_SCENARIO, 'threatengl', 'opengl')
        (self.evidence / 'initial-cbbg.json').write_text(json.dumps(classic_config('opengl')))
        for capture in ('enabled.png', 'disabled.png'):
            self.write_capture(self.evidence / capture, 800, 600)
        force = ('Overriding GLFW_CONTEXT_VERSION_MAJOR: 3 -> 2\n'
                 'Overriding GLFW_CONTEXT_VERSION_MINOR: 2 -> 0\n')
        threaten = ('Threatened Minecraft to use GLFW_CONTEXT_VERSION_MAJOR 4\n'
                    'Threatened Minecraft to use GLFW_CONTEXT_VERSION_MINOR 6\n')
        smoke = 'CBBG_CLASSIC_SMOKE_PASS presentations=12\n'
        for profile, mod, version, glfw, hints in (
                ('forcegl2', 'forcegl20', '4.6', '204802', force),
                ('threatengl', 'threatengl', '4.6', '204801', threaten),
                ('forcegl2', 'forcegl20', '3.0', '0', force)):
            scenario = REDUCED_GL_SCENARIO if version == '3.0' else 'world'
            gpu = 'llvmpipe' if scenario == REDUCED_GL_SCENARIO else 'Test GPU'
            gl32 = str(scenario != REDUCED_GL_SCENARIO).lower()
            log = (hints + f'CBBG_CLASSIC_OPENGL {gpu} / {version} sodium=false\n'
                   f'CBBG_CLASSIC_CONTEXT mod={mod} reference=false actual={version} profile={glfw} '
                   f'gl30=true gl32={gl32} glsl=4.60\n' + smoke)
            with self.subTest(scenario=scenario, profile=profile):
                self.assertIn('-Dcbbg.test.contextMod=' + mod,
                              classic_jvm_arguments('candidate.jar', scenario, 'opengl', profile))
                _, graphics = classic_result(scenario, profile, 'opengl', log, self.evidence, 0)
                self.assertEqual(graphics['context']['version'], version)
                for bad in (log.replace(hints, ''), log.replace('gl30=true', 'gl30=false'),
                            log.replace('profile=' + glfw, 'profile=42'),
                            log.replace('reference=false', 'reference=true')):
                    with self.assertRaises(ValueError):
                        classic_result(scenario, profile, 'opengl', bad, self.evidence, 0)
                if version == '4.6' and profile == 'forcegl2':
                    with self.assertRaisesRegex(ValueError, 'actual Mesa software OpenGL 3.0'):
                        classic_result(REDUCED_GL_SCENARIO, profile, 'opengl', log, self.evidence, 0)

    def test_satin_requires_resize_reload_and_postprocessing_evidence(self):
        validate_classic_choice(self.metadata, 'satin', 'satin', 'opengl')
        with self.assertRaisesRegex(ValueError, 'profile or backend'):
            validate_classic_choice(self.metadata, 'satin', 'none', 'opengl')
        self.assertIn('-Dcbbg.test.satin=true',
                      classic_jvm_arguments('candidate.jar', 'satin', 'opengl', 'satin'))
        (self.evidence / 'initial-cbbg.json').write_text(json.dumps(classic_config('opengl')))
        log = ('CBBG_CLASSIC_OPENGL Test GPU / 4.6 sodium=false\n'
               'CBBG_CLASSIC_SATIN_STAGE initial 800x600 cases=6\n'
               'CBBG_CLASSIC_SATIN_STAGE reloaded 800x600 cases=12\n'
               'CBBG_CLASSIC_SATIN_STAGE smaller 736x552 cases=18\n'
               'CBBG_CLASSIC_SATIN_STAGE restored 800x600 cases=24\n'
               'CBBG_CLASSIC_SATIN_PASS cases=24 readableDepth=3\n')
        classic_result('satin', 'satin', 'opengl', log, self.evidence, 0)
        for invalid in (log.replace('736x552', '800x600'), log.replace('readableDepth=3', 'readableDepth=1')):
            with self.assertRaises(ValueError):
                classic_result('satin', 'satin', 'opengl', invalid, self.evidence, 0)

    def test_renderscale_checks_all_formats_scales_filters_and_captures(self):
        profile = 'modmenu+sodium+satin+immediatelyfast+chatpatches+iris+renderscale'
        validate_classic_choice(self.metadata, 'renderscale', profile, 'opengl')
        with self.assertRaisesRegex(ValueError, 'profile or backend'):
            validate_classic_choice(self.metadata, 'renderscale', 'sodium', 'opengl')
        arguments = classic_jvm_arguments('candidate.jar', 'renderscale', 'opengl', profile)
        self.assertIn('-Dcbbg.test.renderScale=true', arguments)
        self.assertIn('-Dcbbg.test.compatProfile=' + profile, arguments)
        self.assertNotIn('-Dcbbg.test.iris=true', arguments)
        (self.evidence / 'initial-cbbg.json').write_text(json.dumps(classic_config('opengl')))
        iris = self.evidence / 'initial-iris.properties'
        iris.write_text(CLASSIC_IRIS_OPTIONS)
        log = 'CBBG_CLASSIC_OPENGL Test GPU / 3.2 sodium=true\n'
        for scale, size, default_filter, block in (
                ('0.5', '400x300', 'nearest', 2), ('1.0', '800x600', 'nearest', 1),
                ('2.0', '1600x1200', 'linear', 1)):
            for mode, fmt, filtering, grid in (
                    ('enabled', 34842, default_filter, block), ('enabled', 34842, 'linear', block),
                    ('enabled', 34836, default_filter, block), ('disabled', 32856, default_filter, 0)):
                log += (f'CBBG_CLASSIC_RENDERSCALE_CASE scale={scale} mode={mode} format={fmt} '
                        f'filter={filtering} world={size} block={grid}\n')
        log += 'CBBG_CLASSIC_RENDERSCALE_PASS cases=12\n'
        for index in (0, 1, 2, 4, 5, 6, 8, 9, 10):
            self.write_capture(self.evidence / f'grid-{index}.png', 800, 600)
        classic_result('renderscale', profile, 'opengl', log, self.evidence, 0)
        for bad in (log.replace('block=2', 'block=1', 1), log.replace('cases=12', 'cases=11'),
                    log.replace('world=400x300', 'world=800x600', 1)):
            with self.assertRaisesRegex(ValueError, 'RenderScale framebuffer and grid'):
                classic_result('renderscale', profile, 'opengl', bad, self.evidence, 0)
        iris.write_text('enableShaders=true\n')
        with self.assertRaisesRegex(ValueError, 'Iris initial config'):
            classic_result('renderscale', profile, 'opengl', log, self.evidence, 0)
        iris.write_text(CLASSIC_IRIS_OPTIONS)
        (self.evidence / 'grid-0.png').unlink()
        with self.assertRaises(FileNotFoundError):
            classic_result('renderscale', profile, 'opengl', log, self.evidence, 0)

    def test_world_rejects_changed_config_and_truncated_capture(self):
        data = self.capture.read_bytes()
        self.capture.write_bytes(data[:24])
        with self.assertRaisesRegex(ValueError, 'PNG structure'):
            classic_result('world', 'vulkanmod', 'vulkan', self.log, self.evidence, 0)
        self.capture.write_bytes(data)
        (self.evidence / 'initial-options.txt').write_text(CLASSIC_OPTIONS.replace('60', '30'))
        with self.assertRaisesRegex(ValueError, 'config or options differ'):
            classic_result('world', 'vulkanmod', 'vulkan', self.log, self.evidence, 0)

    def test_iris_requires_activation_reload_recovery_and_unchanged_saved_mode(self):
        validate_classic_choice(self.metadata, 'iris', 'iris', 'opengl')
        with self.assertRaisesRegex(ValueError, 'profile or backend'):
            validate_classic_choice(self.metadata, 'iris', 'sodium', 'opengl')
        arguments = classic_jvm_arguments('candidate.jar', 'iris', 'opengl', 'iris')
        self.assertIn('-Dcbbg.test.iris=true', arguments)
        self.assertIn('-Dcbbg.test.irisCycle=true', arguments)
        config = json.dumps(classic_config('opengl', 'iris'))
        self.assertEqual('DEMO', json.loads(config)['mode'])
        (self.evidence / 'initial-cbbg.json').write_text(config)
        (self.evidence / 'final-cbbg.json').write_text(config)
        (self.evidence / 'initial-iris.properties').write_text(CLASSIC_IRIS_OPTIONS)
        for name in ('inactive', 'active', 'reloaded', 'restored'):
            self.write_capture(self.evidence / f'iris-{name}.png', 800, 600)
        log = ('CBBG_CLASSIC_OPENGL Test GPU / 3.2 sodium=true\n'
               'CBBG_CLASSIC_IRIS_SUPPRESSED iris-active.png format=RGBA8 draws=0\n'
               'CBBG_CLASSIC_IRIS_SUPPRESSED iris-reloaded.png format=RGBA8 draws=0\n'
               'CBBG_CLASSIC_IRIS_RELOAD_PASS\n'
               'CBBG_CLASSIC_IRIS_CYCLE_PASS presentations=10\n')
        classic_result('iris', 'iris', 'opengl', log, self.evidence, 0)
        for bad in (log.replace('draws=0', 'draws=1', 1), log.replace('iris-reloaded', 'iris-active'),
                    log.replace('presentations=10', 'presentations=9')):
            with self.assertRaises(ValueError):
                classic_result('iris', 'iris', 'opengl', bad, self.evidence, 0)
        (self.evidence / 'final-cbbg.json').write_text(config.replace('DEMO', 'DISABLED'))
        with self.assertRaisesRegex(ValueError, 'saved CBBG configuration'):
            classic_result('iris', 'iris', 'opengl', log, self.evidence, 0)

    def test_iris_restart_requires_the_phase_specific_shader_state(self):
        config = json.dumps(classic_config('opengl', 'iris'))
        for name in ('initial-cbbg.json', 'final-cbbg.json'):
            (self.evidence / name).write_text(config)
        for name in ('control', 'inactive', 'active', 'reloaded', 'restart-active', 'restored'):
            self.write_capture(self.evidence / f'iris-{name}.png', 800, 600)
        off = CLASSIC_IRIS_OPTIONS
        on = 'shaderPack=cbbg-parity\nenableShaders=true\n'
        initial = self.evidence / 'initial-iris.properties'
        final = self.evidence / 'final-iris.properties'
        gpu = 'CBBG_CLASSIC_OPENGL Test GPU / 3.2 sodium=true\n'
        initial.write_text(off)
        final.write_text(off)
        classic_result('iris', 'iris', 'opengl',
                       gpu + 'CBBG_CLASSIC_IRIS_CONTROL_PASS presentations=10\n',
                       self.evidence, 0, 'control')
        final.write_text(on)
        classic_result('iris', 'iris', 'opengl',
                       gpu + 'CBBG_CLASSIC_IRIS_SUPPRESSED iris-active.png format=RGBA8 draws=0\n'
                       'CBBG_CLASSIC_IRIS_SUPPRESSED iris-reloaded.png format=RGBA8 draws=0\n'
                       'CBBG_CLASSIC_IRIS_RELOAD_PASS\n', self.evidence, 0, 'prepare')
        verify_log = (gpu + 'CBBG_CLASSIC_IRIS_SUPPRESSED iris-restart-active.png format=RGBA8 draws=0\n'
                      'CBBG_CLASSIC_IRIS_RESTART_PASS presentations=10\n')
        final.write_text(off)
        with self.assertRaisesRegex(ValueError, 'persisted shader state'):
            classic_result('iris', 'iris', 'opengl', verify_log, self.evidence, 0, 'verify')
        initial.write_text(on)
        classic_result('iris', 'iris', 'opengl', verify_log, self.evidence, 0, 'verify')
        final.write_text(on)
        with self.assertRaisesRegex(ValueError, 'persisted shader state'):
            classic_result('iris', 'iris', 'opengl', verify_log, self.evidence, 0, 'verify')
        self.assertIn('-Dcbbg.test.restart=true',
                      classic_jvm_arguments('candidate.jar', 'iris', 'opengl', 'iris', 'verify'))
        self.assertNotIn('-Dcbbg.test.irisCycle=true',
                         classic_jvm_arguments('candidate.jar', 'iris', 'opengl', 'iris', 'prepare'))

    def test_vulkan_settings_requires_real_outputs_and_one_regeneration(self):
        scenario = 'vulkan-settings'
        validate_classic_choice(self.metadata, scenario, 'vulkanmod', 'vulkan')
        self.assertIn('-Dcbbg.test.vulkanSettings=true',
                      classic_jvm_arguments('candidate.jar', scenario, 'vulkan', 'vulkanmod'))
        initial = classic_config('vulkan', scenario)
        self.assertEqual(64, initial['stbnSize'])
        self.assertTrue(initial['notifyChat'] and initial['notifyToast'])
        (self.evidence / 'initial-cbbg.json').write_text(json.dumps(initial))
        for name in ('vulkan-settings', 'vulkan-confirmation', 'vulkan-generation-complete'):
            self.write_capture(self.evidence / (name + '.png'), 800, 600)
        cache = self.evidence / 'settings-cache'
        cache.mkdir()
        rows = ['# seed 0', '# algorithm 3']
        for name in SETTINGS_NOISE_FRAMES:
            frame = cache / name
            self.write_capture(frame, 64, 64)
            rows.append(f'{hashlib.sha256(frame.read_bytes()).hexdigest()}  {name}')
        manifest = '\n'.join(rows) + '\n'
        for name in ('warm.sha256', SETTINGS_MANIFEST):
            (cache / name).write_text(manifest)
        generation = 'Starting Async STBN Math Generation (64x64x8)\nSTBN Math Complete in 80 ms\n'
        log = (self.log + generation + 'CBBG_CLASSIC_VULKAN_CONFIRMATION cancelled=unchanged\n'
               + generation + 'CBBG_CLASSIC_VULKAN_TOAST rendered=generating\n'
               'CBBG_CLASSIC_VULKAN_TOAST rendered=complete\n'
               'CBBG_CLASSIC_VULKAN_SETTINGS confirmations=2 chat=2 toasts=2\n')
        classic_result(scenario, 'vulkanmod', 'vulkan', log, self.evidence, 0)
        with self.assertRaisesRegex(ValueError, 'confirmed regeneration'):
            classic_result(scenario, 'vulkanmod', 'vulkan', log + generation, self.evidence, 0)
        with self.assertRaisesRegex(ValueError, 'confirmation or notifications'):
            classic_result(scenario, 'vulkanmod', 'vulkan', log.replace('rendered=complete', 'rendered=generating'),
                           self.evidence, 0)
        (cache / SETTINGS_NOISE_FRAMES[0]).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'noise frame differs'):
            classic_result(scenario, 'vulkanmod', 'vulkan', log, self.evidence, 0)

    def test_vulkan_fallback_requires_capability_mask_real_format_and_saved_preference(self):
        self.write_capture(self.evidence / 'vulkan-format-settings.png', 800, 600)
        for scenario, (mask, observed) in VULKAN_FORMAT_CASES.items():
            with self.subTest(scenario=scenario):
                validate_classic_choice(self.metadata, scenario, 'vulkanmod', 'vulkan')
                config = classic_config('vulkan', scenario)
                self.assertEqual('RGBA32F', config['pixelFormat'])
                for name in ('initial-cbbg.json', 'final-cbbg.json'):
                    (self.evidence / name).write_text(json.dumps(config))
                flags = classic_jvm_arguments('candidate.jar', scenario, 'vulkan', 'vulkanmod')
                self.assertIn('-Dcbbg.test.vulkanFormatMask=' + mask, flags)
                self.assertIn('-Dcbbg.test.vulkanSceneFormat=' + str(observed), flags)
                log = (self.log.replace('format=97', f'format={observed}')
                       + f'CBBG_CLASSIC_VULKAN_SCENE format={observed} disabled=false\n'
                       'CBBG_FORMAT_AGENT querySites=1\n'
                       'CBBG_CLASSIC_VULKAN_MASK format=109 feature=blend\n')
                if mask == 'both':
                    log += ('CBBG_CLASSIC_VULKAN_MASK format=97 feature=blend\n'
                            'CBBG_CLASSIC_VULKAN_FORMAT_LABEL x=200 y=243 width=223 height=9 text=Localized warning\n')
                log += f'CBBG_CLASSIC_VULKAN_FORMAT_WARNING format={observed} draws={4 if mask == "both" else 0}\n'
                classic_result(scenario, 'vulkanmod', 'vulkan', log, self.evidence, 0)
                for bad in (log.replace('querySites=1', 'querySites=2'),
                            log.replace('format=109 feature=blend', 'format=37 feature=blend'),
                            log.replace('draws=4' if mask == 'both' else 'draws=0',
                                        'draws=0' if mask == 'both' else 'draws=1')):
                    with self.assertRaises(ValueError):
                        classic_result(scenario, 'vulkanmod', 'vulkan', bad, self.evidence, 0)
                (self.evidence / 'final-cbbg.json').write_text(json.dumps({**config, 'pixelFormat': 'RGBA8'}))
                with self.assertRaisesRegex(ValueError, 'saved precision preference'):
                    classic_result(scenario, 'vulkanmod', 'vulkan', log, self.evidence, 0)

    def test_vma_depth_failure_requires_cleanup_before_retry(self):
        scenario = 'vulkan-vma-depth'
        validate_classic_choice(self.metadata, scenario, 'vulkanmod', 'vulkan')
        self.assertEqual(VMA_PROBE_AGENT, vulkan_probe_agent(scenario))
        settings = json.dumps(classic_config('vulkan', scenario))
        for file in ('initial-cbbg.json', 'final-cbbg.json'):
            (self.evidence / file).write_text(settings)
        reject = 'CBBG_CLASSIC_VULKAN_REJECT depth format=129\n'
        cleanup = 'CBBG_CLASSIC_VULKAN_PARTIAL_FREE count=1\n'
        failure = ('VulkanMod scene allocation failed for RGBA32F at 800x600: '
                   'Failed to create image: VK_ERROR_OUT_OF_DEVICE_MEMORY\n')
        log = self.log + 'CBBG_ALLOC_AGENT allocationSites=1 freeSites=1\n'
        classic_result(scenario, 'vulkanmod', 'vulkan', log + reject + cleanup + failure, self.evidence, 0)
        for invalid in (reject + failure, reject + failure + cleanup, reject + cleanup * 2 + failure):
            with self.assertRaisesRegex(ValueError, 'failure and cleanup'):
                classic_result(scenario, 'vulkanmod', 'vulkan', log + invalid, self.evidence, 0)

    def test_vma_total_failure_requires_original_swapchain(self):
        scenario = 'vulkan-vma-all'
        config = json.dumps(classic_config('vulkan', scenario))
        for name in ('initial-cbbg.json', 'final-cbbg.json'):
            (self.evidence / name).write_text(config)
        log = (self.log.replace('format=97 disabled=false', 'format=44 disabled=true')
               + 'CBBG_ALLOC_AGENT allocationSites=1 freeSites=1\n')
        for code, name in ((109, 'RGBA32F'), (97, 'RGBA16F'), (37, 'RGBA8')):
            log += (f'CBBG_CLASSIC_VULKAN_REJECT color format={code}\n'
                    f'VulkanMod scene allocation failed for {name} at 800x600: '
                    'Failed to create image: VK_ERROR_OUT_OF_DEVICE_MEMORY\n')
        classic_result(scenario, 'vulkanmod', 'vulkan', log, self.evidence, 0)
        with self.assertRaisesRegex(ValueError, 'fallback framebuffer'):
            classic_result(scenario, 'vulkanmod', 'vulkan', log.replace('disabled=true', 'disabled=false'),
                           self.evidence, 0)
        self.assertIn('-Dcbbg.test.vulkanOriginalTarget=true',
                      classic_jvm_arguments('candidate.jar', scenario, 'vulkan', 'vulkanmod'))

    def test_vma_resize_requires_fallback_then_recovery_on_reload(self):
        scenario = 'vulkan-vma-resize'
        config = classic_config('vulkan', scenario)
        (self.evidence / 'initial-cbbg.json').write_text(json.dumps(config))
        (self.evidence / 'final-cbbg.json').write_text(json.dumps({**config, 'stbnSize': 32}))
        self.write_capture(self.capture, 736, 552)
        self.write_capture(self.evidence / 'vulkan-resized.png', 736, 552)
        for file in ('vulkan-demo-before.png', 'vulkan-disabled.png', 'vulkan-rgba32f.png'):
            self.write_capture(self.evidence / file, 800, 600)
        log = (self.log + 'CBBG_ALLOC_AGENT allocationSites=1 freeSites=1\n'
               'CBBG_CLASSIC_VULKAN_REJECT depth format=129\n'
               'CBBG_CLASSIC_VULKAN_PARTIAL_FREE count=1\n'
               'VulkanMod scene allocation failed for RGBA16F at 736x552: '
               'Failed to create image: VK_ERROR_OUT_OF_DEVICE_MEMORY\n'
               'CBBG_CLASSIC_VULKAN_NOISE sampled=32 pending-edit=16\n'
               'CBBG_CLASSIC_VULKAN_LIFECYCLE width=736 height=552\n'
               'CBBG_CLASSIC_VULKAN_SCENE format=44 disabled=true\n'
               'CBBG_CLASSIC_VULKAN_SCENE format=109 disabled=false\n'
               'CBBG_CLASSIC_VULKAN_SCENE format=97 disabled=false\n'
               'CBBG_CLASSIC_VULKAN_SCENE format=37 disabled=false\n')
        restored = 'CBBG_CLASSIC_VULKAN_SCENE format=97 disabled=false\n'
        classic_result(scenario, 'vulkanmod', 'vulkan', log + restored, self.evidence, 0)
        with self.assertRaisesRegex(ValueError, 'Vulkan lifecycle evidence'):
            classic_result(scenario, 'vulkanmod', 'vulkan', log + restored.replace('97', '37'),
                           self.evidence, 0)

    def test_native_allocation_retry_requires_each_created_handle_released(self):
        scenario = 'vulkan-native-framebuffer'
        validate_classic_choice(self.metadata, scenario, 'vulkanmod', 'vulkan')
        self.assertEqual(VMA_PROBE_AGENT, vulkan_probe_agent(scenario))
        self.assertIn('-Dcbbg.test.vulkanNativeFailure=Framebuffer',
                      classic_jvm_arguments('candidate.jar', scenario, 'vulkan', 'vulkanmod'))
        config = json.dumps(classic_config('vulkan', scenario))
        for name in ('initial-cbbg.json', 'final-cbbg.json'):
            (self.evidence / name).write_text(config)
        hooks = ('CBBG_ALLOC_AGENT allocationSites=1 freeSites=1\n'
                 'CBBG_NATIVE_AGENT class=net/vulkanmod/vulkan/framebuffer/Framebuffer calls=3\n'
                 'CBBG_NATIVE_AGENT class=net/vulkanmod/vulkan/framebuffer/RenderPass calls=2\n'
                 'CBBG_NATIVE_AGENT class=net/vulkanmod/vulkan/texture/VulkanImage calls=3\n'
                 'CBBG_NATIVE_AGENT class=com/qb20nh/cbbg/mapped$scene calls=2\n')
        handles = ('Image', 'ImageView', 'Image', 'ImageView', 'RenderPass', 'RenderPass', 'Framebuffer')
        created = ''.join(f'CBBG_CLASSIC_VULKAN_NATIVE_CREATED resource={name}\n' for name in handles)
        released = ''.join(f'CBBG_CLASSIC_VULKAN_NATIVE_RELEASED resource={name}\n' for name in handles)
        reject = 'CBBG_CLASSIC_VULKAN_NATIVE_FAIL resource=Framebuffer\n'
        retry = ('VulkanMod scene allocation failed for RGBA32F at 800x600: '
                 'Failed to create framebuffer: VK_ERROR_OUT_OF_DEVICE_MEMORY\n')
        prefix = hooks + created + reject
        classic_result(scenario, 'vulkanmod', 'vulkan', prefix + released + retry + self.log, self.evidence, 0)
        for bad in (released.replace('resource=ImageView\n', 'resource=Image\n', 1) + retry,
                    retry + released, released + released + retry):
            with self.assertRaisesRegex(ValueError, 'release every handle before retry'):
                classic_result(scenario, 'vulkanmod', 'vulkan', prefix + bad + self.log, self.evidence, 0)
        with self.assertRaisesRegex(ValueError, 'expected framebuffer'):
            classic_result(scenario, 'vulkanmod', 'vulkan',
                           self.log + prefix + released + retry, self.evidence, 0)

    def test_sampler_recovery_requires_correct_resources_and_reloaded_format(self):
        self.write_capture(self.evidence / 'vulkan-sampler-fallback.png', 800, 600)
        hooks = ('CBBG_SAMPLER_AGENT class=net/vulkanmod/vulkan/texture/VulkanImage hooks=1\n'
                 'CBBG_SAMPLER_AGENT class=net/vulkanmod/vulkan/texture/SamplerManager hooks=2\n')
        for scenario, mode in VULKAN_SAMPLER_CASES.items():
            with self.subTest(mode=mode):
                validate_classic_choice(self.metadata, scenario, 'vulkanmod', 'vulkan')
                self.assertIn('-Dcbbg.test.vulkanSamplerFault=' + mode,
                              classic_jvm_arguments('candidate.jar', scenario, 'vulkan', 'vulkanmod'))
                config = json.dumps(classic_config('vulkan', scenario))
                for name in ('initial-cbbg.json', 'final-cbbg.json'):
                    (self.evidence / name).write_text(config)
                scene = mode == 'scene'
                fallback_format = 37 if scene else 97
                freed = 1 if scene else 2
                marker = f'CBBG_CLASSIC_VULKAN_SAMPLER_RECOVERED scene={str(scene).lower()} failures=1 freedImages={freed}\n'
                log = (hooks + 'CBBG_CLASSIC_VULKAN_SAMPLER_REJECTED count=1\n'
                       'Failed to create texture sampler: VK_ERROR_OUT_OF_DEVICE_MEMORY\n'
                       f'CBBG_CLASSIC_VULKAN_SCENE format={fallback_format} disabled=false\n' + self.log + marker)
                classic_result(scenario, 'vulkanmod', 'vulkan', log, self.evidence, 0)
                for bad in (log.replace(marker, marker.replace(f'freedImages={freed}', 'freedImages=0')),
                            log.replace('hooks=2', 'hooks=1'),
                            log.replace(self.log, self.log.replace('format=97', 'format=37'))):
                    with self.assertRaises(ValueError):
                        classic_result(scenario, 'vulkanmod', 'vulkan', bad, self.evidence, 0)

    def test_mode_restart_requires_disabled_world_and_reuses_cache(self):
        scenario = CLASSIC_MODE_RESTART
        validate_classic_choice(self.metadata, scenario, 'none', 'opengl')
        self.assertNotIn('-Dcbbg.test.restart=true',
                         classic_jvm_arguments('candidate.jar', scenario, 'opengl', 'none', 'prepare'))
        self.assertIn('-Dcbbg.test.restart=true',
                      classic_jvm_arguments('candidate.jar', scenario, 'opengl', 'none', 'verify'))
        for name in ('restart-disabled.png', 'restart-enabled.png'):
            self.write_capture(self.evidence / name, 800, 600)
        log = ('CBBG_CLASSIC_OPENGL Test GPU / 3.2 sodium=false\n'
               'STBN Frames loaded from cache.\n'
               'CBBG_CLASSIC_RESTORED_DISABLED format=RGBA8 presentations=0\n'
               'CBBG_CLASSIC_RESTART_PASS presentations=12\n')
        config = classic_config('opengl', scenario)
        (self.evidence / 'initial-cbbg.json').write_text(json.dumps(dict(config, mode='DISABLED')))
        final = self.evidence / 'final-cbbg.json'
        final.write_text(json.dumps(config))
        classic_result(scenario, 'none', 'opengl', log, self.evidence, 0, 'verify')
        for bad in (log.replace('RGBA8', 'RGBA16F'), log.replace('presentations=12', 'presentations=9'),
                    log.replace('STBN Frames loaded from cache.', 'Starting Async STBN Math Generation')):
            with self.assertRaises(ValueError):
                classic_result(scenario, 'none', 'opengl', bad, self.evidence, 0, 'verify')
        final.write_text(json.dumps(dict(config, strength=4)))
        with self.assertRaisesRegex(ValueError, 'other saved settings'):
            classic_result(scenario, 'none', 'opengl', log, self.evidence, 0, 'verify')

    def test_cache_startup_checks_frozen_inputs_and_cache_behavior(self):
        log = ('CBBG_CLASSIC_OPENGL Test GPU / 3.2 sodium=false\n'
               'CBBG_CLASSIC_SMOKE_PASS presentations=12\n')
        generated = ('Starting Async STBN Math Generation (16x16x8)\nSTBN Math Complete in 1 ms\n'
                     'STBN Images generated from math fields.\n')
        loaded = 'STBN Frames loaded from cache.\n'
        for scenario in CLASSIC_CACHE_SCENARIOS:
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as temp:
                game = Path(temp)
                evidence = game / 'evidence'
                evidence.mkdir()
                (evidence / 'initial-cbbg.json').write_text(json.dumps(classic_config('opengl', scenario)))
                (evidence / 'initial-options.txt').write_text(CLASSIC_OPTIONS)
                for name in ('enabled.png', 'disabled.png'):
                    self.write_capture(evidence / name, 800, 600)
                validate_classic_choice(self.metadata, scenario, 'none', 'opengl')
                validate_classic_choice(self.metadata, scenario, 'sodium', 'opengl')
                with self.assertRaises(ValueError):
                    validate_classic_choice(self.metadata, scenario, 'vulkanmod', 'vulkan')
                prepare_classic_cache(game, evidence, scenario)
                cache = game / '.cbbg'
                warm = scenario == 'cache-warm'
                if not warm:
                    cache.mkdir(exist_ok=True)
                    for name, content in pixel_cache_files(offset=2).items():
                        (cache / name).write_bytes(content)
                retain_classic_cache(game, evidence, scenario)
                classic_result(scenario, 'none', 'opengl', (loaded if warm else generated) + log, evidence, 0)
                with self.assertRaisesRegex(ValueError, 'did not load or regenerate'):
                    classic_result(scenario, 'none', 'opengl', (generated if warm else loaded) + log, evidence, 0)
                (evidence / 'cache-output' / 'stbn_16x16x8_3.png').write_bytes(b'corrupt')
                with self.assertRaisesRegex(ValueError, 'expected content'):
                    classic_result(scenario, 'none', 'opengl', (loaded if warm else generated) + log, evidence, 0)

    def test_pipeline_recovery_requires_released_resources_and_ordered_reload(self):
        self.write_capture(self.evidence / 'vulkan-pipeline-fallback.png', 800, 600)
        (self.evidence / 'final-cbbg.json').write_bytes((self.evidence / 'initial-cbbg.json').read_bytes())
        hooks = ''.join('CBBG_PIPELINE_AGENT class=net/vulkanmod/vulkan/shader/' + name + '\n'
                        for name in ('SPIRVUtils', 'GraphicsPipeline',
                                     'GraphicsPipeline$VertexInputDescription', 'Pipeline', 'Pipeline$DescriptorSets'))
        cases = {
            'shader': ('CBBG test shader failure', 'ShaderResult=4'),
            'native': ('Failed to create graphics pipeline cbbg_vulkanmod_dither: VK_ERROR_OUT_OF_DEVICE_MEMORY', ''),
            'constructor': ('Failed to allocate descriptor sets. Result:-2',
                            'DescriptorSetLayout=2, PipelineLayout=2, ShaderModule=4, DescriptorPool=4, '
                            'DynamicOffsets=4, DescriptorHandles=4, VertexBindings=2, VertexAttributes=2, ShaderResult=4'),
        }
        for fault, (error, released) in cases.items():
            with self.subTest(fault=fault):
                scenario = 'vulkan-pipeline-' + fault
                validate_classic_choice(self.metadata, scenario, 'vulkanmod', 'vulkan')
                arguments = classic_jvm_arguments('candidate.jar', scenario, 'vulkan', 'vulkanmod')
                self.assertIn('-Dcbbg.test.vulkanPipelineFault=' + fault, arguments)
                self.assertEqual('-Dcbbg.test.vulkanTrackConstruction=true' in arguments, bool(released))
                cleanup = f'CBBG_CLASSIC_VULKAN_CONSTRUCTION_RELEASED {{{released}}}\n' if released else ''
                log = (hooks + (error + '\n') * 2 + cleanup
                       + 'CBBG_CLASSIC_VULKAN_SCENE format=44 disabled=true\n' + cleanup
                       + 'Reloading ResourceManager: vanilla\n' + self.log
                       + f'CBBG_CLASSIC_VULKAN_PIPELINE_RECOVERED fault={fault} attempts=2 '
                         f'cleaned={0 if released else 2}\n')
                classic_result(scenario, 'vulkanmod', 'vulkan', log, self.evidence, 0)
                for bad in (log.replace('Reloading ResourceManager:', 'Reload skipped:'),
                            log.replace(error + '\n', '', 1),
                            log.replace('ShaderResult=4', 'ShaderResult=2', 1) if released
                            else log.replace('cleaned=2', 'cleaned=1')):
                    with self.assertRaises(ValueError):
                        classic_result(scenario, 'vulkanmod', 'vulkan', bad, self.evidence, 0)

    def test_pixel_oracle_requires_all_three_comparisons_and_frozen_tile(self):
        tile = self.evidence / 'pixel-input'
        tile.mkdir()
        for name, contents in pixel_cache_files().items():
            (tile / name).write_bytes(contents)
        self.write_capture(self.capture, 803, 601)
        log = (self.log + 'CBBG_CLASSIC_VULKAN_SCENE format=97 disabled=false\n'
               'CBBG_CLASSIC_VULKAN_SCENE format=44 disabled=true\n')
        for mode in ('demo', 'enabled', 'disabled'):
            self.write_capture(self.evidence / f'vulkan-pixels-{mode}.png', 803, 601)
            log += (f'CBBG_CLASSIC_VULKAN_PIXELS mode={mode} channels=1447809 mismatches=0 '
                    f'topOriginMismatches=1 alphaMismatches=0 changedChannels={int(mode != "disabled")}\n')
        classic_result('vulkan-pixels', 'vulkanmod', 'vulkan', log, self.evidence, 0)
        with self.assertRaisesRegex(ValueError, 'pixel comparisons'):
            classic_result('vulkan-pixels', 'vulkanmod', 'vulkan', log.replace('mode=demo', 'mode=wrong'),
                           self.evidence, 0)
        (tile / 'stbn_16x16x8_0.png').write_bytes(b'different')
        with self.assertRaisesRegex(ValueError, 'fixture input differs'):
            classic_result('vulkan-pixels', 'vulkanmod', 'vulkan', log, self.evidence, 0)
