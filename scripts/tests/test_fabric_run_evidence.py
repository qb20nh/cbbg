import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fabric_run_evidence import verify_run
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
        return verify_run(self.receipt, self.target, source_commit='a' * 40,
                          candidate_sha256=self.report['artifacts']['candidate.jar'],
                          driver_sha256=self.report['artifacts']['driver.jar'],
                          catalog_path=self.catalog, runtime_lock_path=self.runtime,
                          dependency_lock_path=self.lock)

    def test_complete_run(self):
        result = self.verify()
        self.assertEqual(self.expected, result['scenarios'])
        self.assertFalse(result['releaseAcceptance'])

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


if __name__ == '__main__':
    unittest.main()
