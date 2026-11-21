import copy
import json
from pathlib import Path
import struct
import tempfile
import unittest

from fabric_classic_scenarios import (CLASSIC_ENTRYPOINT, CLASSIC_OPTIONS, classic_config, classic_jvm_arguments,
                                      classic_result, pixel_cache_files, validate_classic_choice)
from runtime_catalog import load_catalog, select_targets


class ClassicScenarioTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.evidence = Path(temp.name)
        self.metadata = {'id': 'cbbg_classic_smoke', 'entrypoints': {'client': [CLASSIC_ENTRYPOINT]},
                         'custom': {'cbbg:scenarios': ['world', 'satin', 'vulkan-lifecycle', 'vulkan-pixels']}}
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
                if profile in ('forcegl2', 'threatengl'):
                    with self.assertRaisesRegex(ValueError, 'profile or backend'):
                        validate_classic_choice(self.metadata, 'world', profile, 'opengl')
                    continue
                validate_classic_choice(self.metadata, 'world', profile, 'opengl')
                arguments = classic_jvm_arguments('candidate.jar', 'world', 'opengl', profile)
                self.assertIn('-Dcbbg.test.worldProfile=' + profile, arguments)
                self.assertNotIn('-Dcbbg.test.satin=true', arguments)
                self.assertNotIn('-Dcbbg.test.iris=true', arguments)
        for profile in ('iris', 'modmenu+sodium+satin'):
            self.assertIn('-Dcbbg.test.sodium=true',
                          classic_jvm_arguments('candidate.jar', 'world', 'opengl', profile))

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

    def test_world_rejects_changed_config_and_truncated_capture(self):
        data = self.capture.read_bytes()
        self.capture.write_bytes(data[:24])
        with self.assertRaisesRegex(ValueError, 'PNG structure'):
            classic_result('world', 'vulkanmod', 'vulkan', self.log, self.evidence, 0)
        self.capture.write_bytes(data)
        (self.evidence / 'initial-options.txt').write_text(CLASSIC_OPTIONS.replace('60', '30'))
        with self.assertRaisesRegex(ValueError, 'config or options differ'):
            classic_result('world', 'vulkanmod', 'vulkan', self.log, self.evidence, 0)

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
