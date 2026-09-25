import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from parity_evidence import EvidenceError, digest
from verify_candidate import result_selection, verify_candidate


class VerifyCandidateTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.manifest = self.root / 'candidate.json'
        self.manifest.write_text(json.dumps({'selected_targets': ['26.3-fabric'],
                                            'commit': 'a' * 40, 'release': 'v1.4.0'}))
        self.package = self.enterContext(patch('verify_candidate.verify_package',
                                               side_effect=lambda path, target, root: self.report(target)))
        self.runtime = self.enterContext(patch('verify_candidate.verify_candidate_results',
                                               side_effect=lambda path, target, index: self.report(target)))
        self.provenance = self.enterContext(patch('verify_candidate.verify_provenance',
                                                  side_effect=lambda *args: self.report()))
        self.results = {'26.3-fabric': self.root / 'results.json'}

    def report(self, target=None):
        return {'manifest_sha256': digest(self.manifest), 'source_commit': 'a' * 40,
                'release': 'v1.4.0', 'target': target, 'repository': 'qb20nh/cbbg'}

    def verify(self):
        return verify_candidate(self.manifest, self.results, self.root / 'bundle.jsonl',
                                self.root, 'qb20nh/cbbg')

    def test_complete_checks_do_not_approve_publication(self):
        report = self.verify()
        self.assertEqual(set(report['targets']), {'26.3-fabric'})
        self.assertFalse(report['releaseAcceptance'])
        self.package.assert_called_once()
        self.runtime.assert_called_once()
        self.provenance.assert_called_once()

    def test_missing_or_extra_runtime_results_are_rejected(self):
        for value in ({}, {'26.3-fabric': 'a', '26.3-quilt': 'b'}):
            with self.subTest(value=value):
                self.results = value
                with self.assertRaisesRegex(EvidenceError, 'exactly the selected targets'):
                    self.verify()
        self.package.assert_not_called()
        self.provenance.assert_not_called()

    def test_shared_jar_runtimes_need_separate_results(self):
        manifest = json.loads(self.manifest.read_text())
        manifest['selected_targets'].append('26.3-quilt')
        self.manifest.write_text(json.dumps(manifest))
        self.results['26.3-quilt'] = self.results['26.3-fabric']
        with self.assertRaisesRegex(EvidenceError, 'own result index'):
            self.verify()
        self.package.assert_not_called()

    def test_failure_in_any_check_prevents_success(self):
        for check in (self.package, self.runtime, self.provenance):
            with self.subTest(check=check):
                previous = check.side_effect
                check.side_effect = EvidenceError('check failed')
                with self.assertRaisesRegex(EvidenceError, 'check failed'):
                    self.verify()
                check.side_effect = previous

    def test_different_source_manifest_or_target_is_rejected(self):
        for field in ('source_commit', 'manifest_sha256', 'release', 'target'):
            with self.subTest(field=field):
                self.runtime.side_effect = lambda *args: {**self.report('26.3-fabric'), field: 'different'}
                with self.assertRaisesRegex(EvidenceError, 'differs'):
                    self.verify()
        self.provenance.assert_not_called()

    def test_changed_manifest_is_rejected(self):
        def change(*args):
            report = self.report()
            self.manifest.write_text('{}')
            return report
        self.provenance.side_effect = change
        with self.assertRaisesRegex(EvidenceError, 'changed during validation'):
            self.verify()

    def test_invalid_or_duplicate_result_options_are_rejected(self):
        for values in (['target'], ['=index'], ['target='], ['target=a', 'target=b']):
            with self.subTest(values=values), self.assertRaises(EvidenceError):
                result_selection(values)
        self.assertEqual(result_selection(['26.3-fabric=results.json']),
                         {'26.3-fabric': Path('results.json')})


if __name__ == '__main__':
    unittest.main()
