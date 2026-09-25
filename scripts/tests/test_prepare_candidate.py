from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_candidate import FILES, prepare
from parity_evidence import EvidenceError, checked_file
from targets import ROOT, load_catalog


class PrepareCandidateTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.catalog = {"targets": [{"id": "fabric"}, {"id": "quilt", "artifactOf": "fabric"}]}
        for name in FILES:
            (self.root / name).write_bytes(name.encode())
        self.inventory = [{"id": target, **{kind: kind for kind in FILES}}
                          for target in ("quilt", "fabric")]

    def prepare(self):
        return prepare(self.root, self.inventory, "v2.0.0-rc.1", "a" * 40, self.catalog)

    def test_deterministic_catalog_order_and_checked_hashes(self):
        manifest = self.prepare()
        self.inventory.reverse()
        self.assertEqual(manifest, self.prepare())
        self.assertEqual(["fabric", "quilt"], [t["id"] for t in manifest["targets"]])
        for target in manifest["targets"]:
            for kind in FILES:
                self.assertEqual(self.root / kind, checked_file(self.root, target[kind]))

    def test_selected_bundle_excludes_unrelated_target_and_records_scope(self):
        self.catalog['targets'].append({'id': 'unrelated', 'implemented': False})
        manifest = prepare(self.root, self.inventory, 'v2.0.0', 'a' * 40,
                           self.catalog, ['quilt', 'fabric'])
        self.assertEqual(['fabric', 'quilt'], manifest['selected_targets'])
        self.assertEqual(['fabric', 'quilt'], [t['id'] for t in manifest['targets']])

    def test_selected_bundle_requires_all_shared_runtimes(self):
        with self.assertRaisesRegex(EvidenceError, 'shared-artifact runtime'):
            prepare(self.root, self.inventory, 'v2.0.0', 'a' * 40,
                    self.catalog, ['fabric'])

    def test_unimplemented_shared_runtime_does_not_block_owner_selection(self):
        self.catalog['targets'][1]['implemented'] = False
        inventory = [entry for entry in self.inventory if entry['id'] == 'fabric']
        manifest = prepare(self.root, inventory, 'v2.0.0', 'a' * 40, self.catalog, ['fabric'])
        self.assertEqual(manifest['selected_targets'], ['fabric'])
        with self.assertRaisesRegex(EvidenceError, 'shared-artifact runtime'):
            prepare(self.root, [self.inventory[0]], 'v2.0.0', 'a' * 40, self.catalog, ['quilt'])

    def test_client_bundle_records_all_driver_and_input_hashes(self):
        inputs = {'catalog': 'artifact', 'contract': 'scenario_contract',
                  'ordinary_metadata': 'harness', 'runtime_lock': 'dependency_lock',
                  'dependency_lock': 'dependency_lock', 'drivers': {'ordinary': 'harness'}}
        for entry in self.inventory:
            entry['client_tests'] = inputs
        manifest = self.prepare()
        self.assertEqual(manifest['schema'], 2)
        self.assertEqual(manifest['selected_targets'], ['fabric', 'quilt'])
        tests = manifest['targets'][0]['client_tests']
        for kind in ('catalog', 'contract', 'ordinary_metadata', 'runtime_lock', 'dependency_lock'):
            self.assertTrue(checked_file(self.root, tests[kind]).is_file())
        self.assertEqual(checked_file(self.root, tests['drivers']['ordinary']), self.root / 'harness')

    def test_mixed_client_bundle_formats_rejected(self):
        self.inventory[0]['client_tests'] = {}
        with self.assertRaisesRegex(EvidenceError, 'Every selected target'):
            self.prepare()

    def test_missing_duplicate_and_unexpected_targets_fail(self):
        original = self.inventory
        for records in (original[:1], original + original[:1], original + [{"id": "unknown"}]):
            self.inventory = records
            with self.assertRaises(EvidenceError):
                self.prepare()

    def test_missing_absolute_and_escaping_files_fail(self):
        for path in ("missing", "../outside", str(self.root / "artifact")):
            self.inventory[0]["artifact"] = path
            with self.assertRaises(EvidenceError):
                self.prepare()

    def test_shared_sources_cannot_diverge(self):
        (self.root / "different").write_bytes(b"different sources")
        self.inventory[0]["sources"] = "different"
        with self.assertRaisesRegex(EvidenceError, "Shared sources differs"):
            self.prepare()

    def test_invalid_explicit_identity_fails(self):
        for tag, commit in (("v2.0.0-rc", "a" * 40), ("v2.0.0", "HEAD")):
            with self.assertRaises(EvidenceError):
                prepare(self.root, self.inventory, tag, commit, self.catalog)

    def test_cli_preserves_existing_manifest_on_retry(self):
        catalog = load_catalog()
        inventory = [{"id": target["id"], **{kind: kind for kind in FILES}}
                     for target in catalog["targets"]]
        inventory_path = self.root / "inventory.json"
        inventory_path.write_text(json.dumps(inventory), encoding="utf-8")
        output = self.root / "candidate.json"
        command = [sys.executable, str(ROOT / "scripts/prepare_candidate.py"),
                   "--inventory", str(inventory_path), "--output", str(output),
                   "--release", "v2.0.0-rc.1", "--commit", "a" * 40]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        original = output.read_bytes()
        manifest = json.loads(original)
        self.assertEqual(len(catalog["targets"]), len(manifest["targets"]))
        (self.root / "artifact").write_bytes(b"changed candidate bytes")
        retry = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(2, retry.returncode)
        self.assertIn("File exists", retry.stderr)
        self.assertEqual(original, output.read_bytes())

    def test_cli_missing_files_do_not_leave_a_candidate(self):
        inventory = [{"id": target["id"], **{kind: kind for kind in FILES}}
                     for target in load_catalog()["targets"]]
        inventory_path = self.root / "inventory.json"
        inventory_path.write_text(json.dumps(inventory), encoding="utf-8")
        (self.root / "sources").unlink()
        output = self.root / "candidate.json"
        result = subprocess.run([sys.executable, str(ROOT / "scripts/prepare_candidate.py"),
                                 "--inventory", str(inventory_path), "--output", str(output),
                                 "--release", "v2.0.0", "--commit", "a" * 40],
                                capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertFalse(output.exists())
