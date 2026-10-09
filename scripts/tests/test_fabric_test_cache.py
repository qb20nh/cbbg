import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from fabric_test_cache import check_driver_dependencies, execution_inputs, reuse_arguments
from fabric_acceptance import verify_results
from parity_evidence import EvidenceError, digest


class TestCacheTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.target = {'id': '26.2-fabric', 'java': 25}
        self.inputs = {'source': 'a' * 40, 'manifest': 'old', 'targets': ['26.2-fabric'],
            'root': '/repo', 'graphicsEnvironment': {}, 'host': {'graphics': 'gpu'},
            'executionEnvironment': {},
            'python': 'python', 'executables': {'java25': 'java'},
            'scripts': {'files': {}}, 'runtimes': {'26.2-fabric': '/runtime'},
            'sharedRuntime': None, 'gametestApis': {}, 'seedCaches': {},
            'executionSources': {'runner': 'checksum'}, 'initialConfig': {'mode': 'ENABLED'}}

    def write(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data))
        return {'path': name, 'sha256': digest(path)}

    def driver(self, name, helper, other):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, 'w') as jar:
            jar.writestr('Test.class', b'test')
            jar.writestr('Helper.class', helper)
            jar.writestr('Other.class', other)
        return path

    def test_bookkeeping_and_other_targets_do_not_invalidate(self):
        changed = copy.deepcopy(self.inputs)
        changed.update(source='b' * 40, manifest='new', targets=['26.3-fabric', '26.2-fabric'])
        changed['executables']['java21'] = 'changed unrelated JVM'
        changed['runtimes']['26.3-fabric'] = 'different runtime'
        self.assertEqual(execution_inputs(self.inputs, self.target), execution_inputs(changed, self.target))
        changed['executables']['java25'] = 'new Java'
        self.assertNotEqual(execution_inputs(self.inputs, self.target), execution_inputs(changed, self.target))
        missing = dict(self.inputs)
        missing.pop('executionEnvironment')
        with self.assertRaisesRegex(EvidenceError, 'not recorded'):
            execution_inputs(missing, self.target)
        changed = copy.deepcopy(self.inputs)
        changed['executionEnvironment']['VK_DRIVER_FILES'] = 'custom driver hash'
        with self.assertRaisesRegex(EvidenceError, 'Custom execution environment'):
            execution_inputs(changed, self.target)

    def test_only_required_class_contents_affect_reuse(self):
        old = self.driver('old.jar', b'helper', b'other')
        current = self.driver('current.jar', b'helper', b'changed other')
        dependency = {'schema': 1, 'complete': True, 'driver_sha256': digest(current),
            'files': {'Test.class': hashlib.sha256(b'test').hexdigest(),
                      'Helper.class': hashlib.sha256(b'helper').hexdigest()}}
        check_driver_dependencies(old, dependency)
        check_driver_dependencies(current, dependency)
        changed = self.driver('changed.jar', b'changed helper', b'other')
        with self.assertRaisesRegex(EvidenceError, 'Helper.class'):
            check_driver_dependencies(changed, dependency)
        with self.assertRaisesRegex(EvidenceError, 'incomplete'):
            check_driver_dependencies(old, dict(dependency, complete=False))
        with self.assertRaisesRegex(EvidenceError, 'entry set'):
            check_driver_dependencies(old, dict(dependency, full_archive=True))
        with zipfile.ZipFile(old, 'a') as jar:
            jar.writestr('removed-resource.json', b'old configuration')
        with self.assertRaisesRegex(EvidenceError, 'resource set'):
            check_driver_dependencies(old, dependency)

    def test_reuse_retains_tested_source_and_rejects_changed_execution_inputs(self):
        driver = self.driver('game/mods/driver.jar', b'helper', b'other')
        config = self.write('config.json', self.inputs['initialConfig'])
        receipt_ref = self.write('game/probe.json', {'artifacts': {'driver.jar': digest(driver)},
            'initialConfigSha256': config['sha256']})
        receipt = self.root / receipt_ref['path']
        prior = self.write('prior.json', self.inputs)
        current = dict(self.inputs, source='b' * 40, manifest='new')
        self.write('current.json', current)
        row = {'suite': 'notifications', 'reuse': {'inputs': prior, 'config': config,
            'execution_sources': self.inputs['executionSources']}}
        arguments = dict(current_driver=driver, dependencies={'notifications': {'driver_sha256': digest(driver)}},
            current_inputs=self.root / 'current.json', base=self.root)
        self.assertEqual({'source_commit': 'a' * 40, 'driver_sha256': digest(driver)},
                         reuse_arguments(row, receipt, self.target, **arguments))
        for reference in (prior, config):
            path = self.root / reference['path']
            original = path.read_bytes()
            path.write_bytes(original + b'\n')
            with self.assertRaises(EvidenceError):
                reuse_arguments(row, receipt, self.target, **arguments)
            path.write_bytes(original)
        self.write('game/probe.json', {'artifacts': {'driver.jar': digest(driver)}, 'restartPhase': 'verify'})
        self.assertEqual('a' * 40, reuse_arguments(row, receipt, self.target, **arguments)['source_commit'])
        current['host'] = {'graphics': 'other gpu'}
        self.write('current.json', current)
        with self.assertRaisesRegex(EvidenceError, 'execution inputs'):
            reuse_arguments(row, receipt, self.target, **arguments)

    def test_resources_are_dependencies_and_missing_classes_are_rejected(self):
        driver = self.driver('driver.jar', b'helper', b'other')
        with zipfile.ZipFile(driver, 'a') as jar:
            jar.writestr('fixture.json', b'config')
        expected = {'schema': 1, 'complete': True, 'files': {
            'fixture.json': hashlib.sha256(b'config').hexdigest()}}
        check_driver_dependencies(driver, expected)
        expected['files']['fixture.json'] = hashlib.sha256(b'changed').hexdigest()
        with self.assertRaisesRegex(EvidenceError, 'fixture.json'):
            check_driver_dependencies(driver, expected)
        expected['files'] = {'Missing.class': 'a' * 64}
        with self.assertRaisesRegex(EvidenceError, 'Missing.class'):
            check_driver_dependencies(driver, expected)

    def test_library_runs_can_be_reused_without_cbbg_configuration(self):
        self.inputs['initialConfig'] = None
        driver = self.driver('game/mods/driver.jar', b'helper', b'other')
        receipt = self.write('game/probe.json', {'artifacts': {'driver.jar': digest(driver)}})
        prior = self.write('prior.json', self.inputs)
        self.write('current.json', dict(self.inputs, source='b' * 40))
        row = {'suite': 'ordinary', 'reuse': {'inputs': prior,
            'execution_sources': self.inputs['executionSources']}}
        arguments = dict(current_driver=driver, dependencies={'ordinary': {'driver_sha256': digest(driver)}},
            current_inputs=self.root / 'current.json', base=self.root)
        self.assertEqual('a' * 40,
                         reuse_arguments(row, self.root / receipt['path'], self.target, **arguments)['source_commit'])

    def test_final_validation_keeps_historical_run_source(self):
        driver = self.driver('game/mods/driver.jar', b'helper', b'other')
        config = self.write('config.json', self.inputs['initialConfig'])
        receipt = self.write('game/probe.json', {'artifacts': {'driver.jar': digest(driver)},
            'initialConfigSha256': config['sha256']})
        old_inputs = self.write('old-inputs.json', self.inputs)
        current = dict(self.inputs, source='b' * 40)
        self.write('inputs.json', current)
        self.write('metadata.json', {'id': 'cbbg-renderer-test',
            'entrypoints': {'fabric-client-gametest': ['Test']}})
        self.write('contract.json', {'schemaVersion': 1, 'target': '26.2-fabric', 'additionalRuns': []})
        self.write('results.json', [{'suite': 'ordinary', 'profile': 'none', 'backend': 'opengl',
            'receipt': receipt, 'reuse': {'inputs': old_inputs, 'config': config,
            'execution_sources': self.inputs['executionSources']}}])
        target = dict(self.target, compatibilityProfiles={'none': ['opengl']}, backends=['opengl'])
        def verify(receipt, target, **inputs):
            self.assertEqual('a' * 40, inputs['source_commit'])
            return {'source_commit': inputs['source_commit'], 'profile': 'none', 'backend': 'opengl',
                    'scenarios': ['Test'], 'startupMode': None}
        with patch('fabric_acceptance.verify_run', side_effect=verify):
            result = verify_results(self.root / 'results.json', target, self.root / 'contract.json',
                {'ordinary': digest(driver)}, metadata_path=self.root / 'metadata.json',
                driver_paths={'ordinary': driver}, test_dependencies={'ordinary': {'driver_sha256': digest(driver)}},
                acceptance_inputs=self.root / 'inputs.json', source_commit='b' * 40, candidate_sha256='c' * 64)
        self.assertEqual('b' * 40, result['source_commit'])
        self.assertEqual('a' * 40, result['runs'][0]['source_commit'])
