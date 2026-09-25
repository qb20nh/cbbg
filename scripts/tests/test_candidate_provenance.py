import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from candidate_provenance import verify_provenance
from parity_evidence import EvidenceError, catalog_digest, digest
from targets import load_catalog


class CandidateProvenanceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        catalog = load_catalog()
        (self.root / 'catalog.json').write_text(json.dumps(catalog))
        for name in ('artifact.jar', 'sources.jar', 'bundle.jsonl'):
            (self.root / name).write_bytes(name.encode())

        def reference(name):
            return {'path': name, 'sha256': digest(self.root / name)}

        self.manifest = self.root / 'candidate.json'
        self.manifest.write_text(json.dumps({
            'schema': 2, 'release': 'v1.4.0', 'commit': 'a' * 40,
            'selected_targets': ['26.3-fabric'], 'catalog_sha256': catalog_digest(catalog),
            'targets': [{'id': '26.3-fabric', 'artifact': reference('artifact.jar'),
                         'sources': reference('sources.jar'),
                         'client_tests': {'catalog': reference('catalog.json')}}]}))
        self.bundle = self.root / 'bundle.jsonl'
        self.runner = Mock(side_effect=self.success)

    def success(self, command, **kwargs):
        statement = {'predicateType': 'https://slsa.dev/provenance/v1',
                     'subject': [{'digest': {'sha256': digest(Path(command[3]))}}]}
        return subprocess.CompletedProcess(command, 0,
                                           json.dumps([{'verificationResult': {'statement': statement}}]), '')

    def verify(self):
        return verify_provenance(self.manifest, self.bundle, 'qb20nh/cbbg', run=self.runner)

    def test_verifies_manifest_binary_and_sources_with_expected_identity(self):
        result = self.verify()
        self.assertEqual(len(result['subjects']), 3)
        self.assertEqual(self.runner.call_count, 3)
        for call in self.runner.call_args_list:
            command = call.args[0]
            for flag, value in [('--repo', 'qb20nh/cbbg'),
                                ('--signer-workflow', 'qb20nh/cbbg/.github/workflows/release.yml'),
                                ('--source-digest', 'a' * 40), ('--signer-digest', 'a' * 40),
                                ('--source-ref', 'refs/tags/v1.4.0'),
                                ('--predicate-type', 'https://slsa.dev/provenance/v1')]:
                self.assertEqual(command[command.index(flag) + 1], value)
            self.assertIn('--deny-self-hosted-runners', command)
            self.assertEqual(command[command.index('--bundle') + 1], str(self.bundle))
        self.assertFalse(result['releaseAcceptance'])

    def test_failed_verification_stops_remaining_subjects(self):
        self.runner.side_effect = None
        self.runner.return_value = subprocess.CompletedProcess([], 1, '', 'signature rejected')
        with self.assertRaisesRegex(EvidenceError, 'signature rejected'):
            self.verify()
        self.assertEqual(self.runner.call_count, 1)

    def test_missing_or_invalid_success_output_is_rejected(self):
        self.runner.side_effect = None
        for output in ('', '[]', '{}', '[{}]', '[{"verificationResult": null}]',
                       '[{"verificationResult": {}}]'):
            with self.subTest(output=output):
                self.runner.return_value = subprocess.CompletedProcess([], 0, output, '')
                with self.assertRaises(EvidenceError):
                    self.verify()

    def test_timeout_is_rejected(self):
        self.runner.side_effect = subprocess.TimeoutExpired('gh', 60)
        with self.assertRaisesRegex(EvidenceError, 'timed out'):
            self.verify()

    def test_changed_files_during_verification_are_rejected(self):
        for name in ('candidate.json', 'bundle.jsonl', 'artifact.jar'):
            with self.subTest(name=name):
                path = self.root / name
                original = path.read_bytes()
                def change(command, **kwargs):
                    result = self.success(command, **kwargs)
                    path.write_bytes(b'changed')
                    return result
                self.runner.side_effect = change
                with self.assertRaisesRegex(EvidenceError, 'changed during verification'):
                    self.verify()
                path.write_bytes(original)

    def test_change_to_previously_verified_file_is_rejected(self):
        def change(command, **kwargs):
            result = self.success(command, **kwargs)
            if Path(command[3]).name == 'sources.jar':
                (self.root / 'artifact.jar').write_bytes(b'changed after verification')
            return result
        self.runner.side_effect = change
        with self.assertRaisesRegex(EvidenceError, 'changed during verification'):
            self.verify()

    def test_wrong_subject_digest_is_rejected(self):
        self.runner.side_effect = None
        self.runner.return_value = subprocess.CompletedProcess([], 0, json.dumps([{
            'verificationResult': {'statement': {'predicateType': 'https://slsa.dev/provenance/v1',
                                                 'subject': [{'digest': {'sha256': '0' * 64}}]}}}]), '')
        with self.assertRaisesRegex(EvidenceError, 'does not identify'):
            self.verify()

    def test_invalid_candidate_is_rejected_before_cli(self):
        (self.root / 'artifact.jar').write_bytes(b'changed')
        with self.assertRaisesRegex(EvidenceError, 'Changed evidence file'):
            self.verify()
        self.runner.assert_not_called()

    def test_missing_bundle_and_invalid_repository_are_rejected(self):
        with self.assertRaisesRegex(EvidenceError, 'owner/name'):
            verify_provenance(self.manifest, self.bundle, '--repo=other/project', run=self.runner)
        self.bundle.unlink()
        with self.assertRaisesRegex(EvidenceError, 'Missing attestation bundle'):
            self.verify()
        self.runner.assert_not_called()


if __name__ == '__main__':
    unittest.main()
