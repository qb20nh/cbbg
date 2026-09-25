import hashlib
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fabric_run_evidence import verify_run, verify_restart
from fabric_parity_runtime import restart_state
from fabric_scenario_evidence import graphics_identity, validate_scenarios
from parity_evidence import digest


class FabricRunEvidenceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.game = self.root / 'game'
        (self.game / 'mods').mkdir(parents=True)
        (self.game / 'evidence').mkdir()
        self.target = {'id': '26.3-fabric', 'minecraft': '26.3', 'loader': 'fabric',
                       'renderer': 'renderpearl', 'dependencies': {'loader': '0.19.5', 'fabricApi': 'test'},
                       'compatibilityProfiles': {'none': ['opengl']}}
        self.expected = ['Example.first', 'Example.second']
        for name in ('candidate.jar', 'fabricApi.jar', 'fabric-gametest-api.jar'):
            (self.game / 'mods' / name).write_bytes(name.encode())
        with zipfile.ZipFile(self.game / 'mods/driver.jar', 'w') as jar:
            jar.writestr('fabric.mod.json', json.dumps({'id': 'cbbg-renderer-test',
                'entrypoints': {'fabric-client-gametest': self.expected}}))
        self.catalog = self.root / 'catalog.json'
        self.catalog.write_text(json.dumps({'targets': [self.target]}))
        self.runtime = self.root / 'runtime.json'
        self.runtime.write_text('{}')
        self.lock = self.root / 'dependencies.json'
        self.lock.write_text(json.dumps({'schemaVersion': 1, 'target': self.target['id'],
            'dependencies': {'fabricApi': {'pin': 'test', 'sha256': digest(self.game / 'mods/fabricApi.jar')}},
            'gametestApi': {'fabricApiPin': 'test', 'sha256': digest(self.game / 'mods/fabric-gametest-api.jar')}}))
        self.log = 'Readback backend=OPENGL GPU=Test GPU driver=Test driver\n'
        self.trace = ''.join(state + '\t' + name + '\n' for name in self.expected
                             for state in ('started', 'passed'))
        (self.game / 'launch.log').write_text(self.log)
        (self.game / 'evidence/scenarios.tsv').write_text(self.trace)
        context = {'backend': 'opengl', 'profile': 'core'}
        (self.game / 'evidence/graphics-context.json').write_text(json.dumps(context))
        graphics = graphics_identity(self.log, 'opengl')
        graphics.update(context=context, contextProfile='core')
        self.report = {'exitCode': 0, 'sourceDirty': False, 'sourceHead': 'a' * 40,
            'target': self.target['id'], 'renderer': 'renderpearl', 'profile': 'none',
            'backendRequested': 'opengl', 'loaderProfile': 'fabric-loader-0.19.5-26.3',
            'catalogSha256': digest(self.catalog), 'runtimeLockSha256': digest(self.runtime),
            'dependencyLockSha256': digest(self.lock),
            'artifacts': {p.name: digest(p) for p in (self.game / 'mods').iterdir()},
            'scenarioSha256': hashlib.sha256(json.dumps(self.expected, separators=(',', ':')).encode()).hexdigest(),
            'scenarios': validate_scenarios(self.expected, self.trace, self.log, 0, 'opengl'),
            'graphics': graphics, 'evidence': {name: digest(self.game / name) for name in
                ('launch.log', 'evidence/scenarios.tsv', 'evidence/graphics-context.json')}}
        self.receipt = self.game / 'probe.json'
        self.save()

    def save(self):
        self.receipt.write_text(json.dumps(self.report))

    def verify(self):
        verifier = verify_restart if getattr(self, 'restart', False) else verify_run
        return verifier(self.receipt, self.target, source_commit='a' * 40,
                          candidate_sha256=self.report['artifacts']['candidate.jar'],
                          driver_sha256=self.report['artifacts']['driver.jar'],
                          catalog_path=self.catalog, runtime_lock_path=self.runtime,
                          dependency_lock_path=self.lock)

    def test_complete_run(self):
        result = self.verify()
        self.assertEqual(self.expected, result['scenarios'])
        self.assertFalse(result['releaseAcceptance'])

    def test_fabric_run_cannot_validate_quilt(self):
        self.target['loader'] = 'quilt'
        with self.assertRaisesRegex(ValueError, 'requires a Fabric target'):
            self.verify()

    def test_changed_file_rejected(self):
        (self.game / 'launch.log').write_text('changed')
        with self.assertRaisesRegex(ValueError, 'Changed evidence file'):
            self.verify()

    def test_reordered_trace_rejected_even_with_updated_hash(self):
        path = self.game / 'evidence/scenarios.tsv'
        path.write_text(''.join(state + '\t' + name + '\n' for name in reversed(self.expected)
                                for state in ('started', 'passed')))
        self.report['evidence']['evidence/scenarios.tsv'] = digest(path)
        self.save()
        with self.assertRaisesRegex(ValueError, 'reordered'):
            self.verify()

    def test_crash_rejected_even_with_updated_hash(self):
        path = self.game / 'launch.log'
        path.write_text(self.log + 'Game crashed!\n')
        self.report['evidence']['launch.log'] = digest(path)
        self.save()
        with self.assertRaisesRegex(ValueError, 'runtime failure'):
            self.verify()

    def test_wrong_source_rejected(self):
        self.report['sourceHead'] = 'b' * 40
        self.save()
        with self.assertRaisesRegex(ValueError, 'source differs'):
            self.verify()

    def test_extra_mod_rejected(self):
        (self.game / 'mods/extra.jar').write_bytes(b'extra')
        with self.assertRaisesRegex(ValueError, 'inventory differs'):
            self.verify()

    def test_omitted_saved_file_rejected(self):
        (self.game / 'evidence/screenshot.png').write_bytes(b'image')
        with self.assertRaisesRegex(ValueError, 'inventory differs'):
            self.verify()

    def test_changed_summary_rejected(self):
        self.report['graphics']['gpu'] = 'Another GPU'
        self.save()
        with self.assertRaisesRegex(ValueError, 'Graphics summary differs'):
            self.verify()

    def test_restart_requires_separate_validation(self):
        self.report['restartPhase'] = 'verify'
        self.save()
        with self.assertRaisesRegex(ValueError, 'dedicated validator'):
            self.verify()

    def prepare_restart_pair(self, shader='iris'):
        self.restart = True
        backend = 'opengl' if shader == 'iris' else 'vulkan'
        self.target['compatibilityProfiles'] = {shader: [backend]}
        self.target['dependencies'].update({shader: 'test', 'sodium': 'test'})
        lock = json.loads(self.lock.read_text())
        for mod in (shader, 'sodium'):
            path = self.game / 'mods' / (mod + '.jar')
            path.write_bytes(mod.encode())
            checksum = digest(path)
            self.report['artifacts'][path.name] = checksum
            lock['dependencies'][mod] = {'pin': 'test', 'sha256': checksum}
        self.lock.write_text(json.dumps(lock))
        self.catalog.write_text(json.dumps({'targets': [self.target]}))
        self.report.update(catalogSha256=digest(self.catalog), dependencyLockSha256=digest(self.lock),
                           profile=shader, backendRequested=backend)
        self.log = 'Readback backend=' + backend + ' GPU=Test GPU driver=Test driver\n'
        context = {'backend': backend, 'profile': 'core' if backend == 'opengl' else None}
        self.report['graphics'] = graphics_identity(self.log, backend)
        self.report['graphics'].update(context=context, contextProfile=context['profile'])
        self.expected = ['com.qb20nh.cbbg.gametest.' + shader.capitalize() + 'RestartGameTest']
        with zipfile.ZipFile(self.game / 'mods/driver.jar', 'w') as jar:
            jar.writestr('fabric.mod.json', json.dumps({'id': 'cbbg-renderer-test',
                'entrypoints': {'fabric-client-gametest': self.expected}}))
        self.report['artifacts']['driver.jar'] = digest(self.game / 'mods/driver.jar')
        self.report['scenarioSha256'] = hashlib.sha256(
            json.dumps(self.expected, separators=(',', ':')).encode()).hexdigest()
        trace = ''.join(state + '\t' + self.expected[0] + '\n' for state in ('started', 'passed'))
        self.report['scenarios'] = validate_scenarios(self.expected, trace, self.log, 0, backend)
        self.report['java'] = {'versionOutput': 'test Java'}
        self.report['host'] = {'system': 'test OS'}
        (self.game / 'config').mkdir()
        (self.game / 'config/cbbg.json').write_text('{"mode":"ENABLED"}')
        (self.game / 'config/iris.properties').write_text('enableShaders=true')
        (self.game / 'config/sulkan-shaders.json').write_text('{"enabled":true,"selectedPackId":"__builtin__"}')
        pack = self.game / 'shaderpacks/cbbg-parity/shaders'
        pack.mkdir(parents=True)
        (pack / 'final.fsh').write_text('test shader')
        before = restart_state(self.game, shader)
        for phase in ('prepare', 'verify'):
            report = copy.deepcopy(self.report)
            report['restartPhase'] = phase
            directory = self.game / ('evidence-' + phase)
            directory.mkdir()
            (directory / 'scenarios.tsv').write_text(trace)
            (directory / 'graphics-context.json').write_text(json.dumps(report['graphics']['context']))
            log = self.game / (phase + '-launch.log')
            log.write_text(self.log)
            report['evidence'] = {path.relative_to(self.game).as_posix(): digest(path)
                                  for path in [log, *directory.iterdir()]}
            if phase == 'verify':
                report['inputState'] = before
                report['prepareReceiptSha256'] = digest(self.game / 'prepare-probe.json')
                (self.game / 'config/iris.properties').write_text('enableShaders=false')
                (self.game / 'config/sulkan-shaders.json').write_text('{"enabled":false,"selectedPackId":"__builtin__"}')
            report['persistedState'] = restart_state(self.game, shader)
            path = self.game / (phase + '-probe.json')
            path.write_text(json.dumps(report))
        self.receipt = path
        self.report = report

    def test_restart_pair_checks_both_runs(self):
        self.prepare_restart_pair()
        result = self.verify()
        self.assertTrue(result['restart'])
        self.assertEqual(result['evidence_files'], 6)
        self.assertFalse(result['releaseAcceptance'])

    def test_sulkan_restart_pair_checks_both_runs(self):
        self.prepare_restart_pair('sulkan')
        result = self.verify()
        self.assertTrue(result['restart'])
        self.assertEqual(result['backend'], 'vulkan')

    def test_restart_rejects_changed_prepare_receipt(self):
        self.prepare_restart_pair()
        path = self.game / 'prepare-probe.json'
        path.write_text(path.read_text() + '\n')
        with self.assertRaisesRegex(ValueError, 'Prepare receipt changed'):
            self.verify()

    def test_restart_rejects_changed_prepare_log(self):
        self.prepare_restart_pair()
        (self.game / 'prepare-launch.log').write_text('changed')
        with self.assertRaisesRegex(ValueError, 'Changed evidence file'):
            self.verify()

    def test_restart_rejects_wrong_input_state(self):
        self.prepare_restart_pair()
        self.report['inputState']['config/cbbg.json'] = '0' * 64
        self.save()
        with self.assertRaisesRegex(ValueError, 'input state differs'):
            self.verify()

    def test_restart_rejects_changed_final_settings(self):
        self.prepare_restart_pair()
        (self.game / 'config/iris.properties').write_text('enableShaders=true')
        with self.assertRaisesRegex(ValueError, 'Changed evidence file'):
            self.verify()

    def test_restart_rejects_runtime_change(self):
        self.prepare_restart_pair()
        self.report['java']['versionOutput'] = 'different Java'
        self.save()
        with self.assertRaisesRegex(ValueError, 'identity differs: java'):
            self.verify()

    def test_restart_rejects_extra_pack_file(self):
        self.prepare_restart_pair()
        (self.game / 'shaderpacks/cbbg-parity/shaders/extra.fsh').write_text('extra shader')
        with self.assertRaisesRegex(ValueError, 'state inventory differs'):
            self.verify()


if __name__ == '__main__':
    unittest.main()
