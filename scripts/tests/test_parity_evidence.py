import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from parity_evidence import EvidenceError, catalog_digest, checked_file, digest, verify


class ParityEvidenceTest(unittest.TestCase):
    def test_candidate_tags_require_canonical_version_and_numbered_prerelease(self):
        for tag in ("v02.0.0", "v2.00.0", "v2.0.00", "v2.0.0-rc", "v2.0.0-rc.0",
                    "v2.0.0-rc.01", "v2.0.0-rc..1", "v2.0.0+mc26.2", "v٢.0.0", None):
            candidate = copy.deepcopy(self.manifest)
            candidate["release"] = tag
            self.write(self.manifest_path, candidate)
            with self.subTest(tag=tag), self.assertRaisesRegex(EvidenceError, "Invalid release tag"):
                verify(self.manifest_path, self.receipts, self.catalog)

    def test_numbered_prerelease_receipts_bind_to_the_candidate(self):
        self.manifest["release"] = "v2.0.0-rc.1"
        self.write(self.manifest_path, self.manifest)
        for path in self.paths:
            report = json.loads(path.read_text())
            report["release"] = self.manifest["release"]
            report["manifest_sha256"] = digest(self.manifest_path)
            self.write(path, report)
        self.assertEqual(3, verify(self.manifest_path, self.receipts, self.catalog))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.receipts = self.root / "receipts"
        self.receipts.mkdir()
        target_id = "26.2-fabric"
        self.catalog = {"targets": [{"id": target_id, "implemented": True, "java": 25,
                                     "minecraft": "26.2", "loader": "fabric",
                                     "backends": ["opengl", "vulkan"],
                                     "compatibilityProfiles": {"none": ["opengl", "vulkan"],
                                                               "iris": ["opengl"]}}]}
        target = {"id": target_id}
        payloads = {"artifact": b"unit-test artifact", "sources": b"unit-test sources", "harness": b"unit-test harness",
                    "dependency_lock": b'{"profiles":["none","iris"],"runtime":{"minecraft":"26.2","loader":"fabric","loader_version":"0.19.5","java":25}}',
                    "scenario_contract": b'{"scenarios":["modes","screenshots"]}'}
        for kind, payload in payloads.items():
            path = self.root / kind
            path.write_bytes(payload)
            target[kind] = {"path": kind, "sha256": digest(path)}
        self.manifest = {"schema": 1, "release": "v2.0.0", "commit": "a" * 40,
                         "catalog_sha256": catalog_digest(self.catalog),
                         "targets": [target]}
        self.manifest_path = self.root / "candidate.json"
        self.write(self.manifest_path, self.manifest)
        log = self.receipts / "client.log"
        log.write_text("unit-test evidence", encoding="utf-8")
        self.paths = []
        for backend in ("opengl", "vulkan"):
            for profile in (("none", "iris") if backend == "opengl" else ("none",)):
                report = {"schema": 1, "release": "v2.0.0", "commit": "a" * 40,
                          "manifest_sha256": digest(self.manifest_path), "target": target_id,
                          "backend": backend, "actual_backend": backend, "profile": profile,
                          "status": "passed", "runtime": {"java": 25, "jvm": "test JVM",
                          "minecraft": "26.2", "loader": "fabric", "loader_version": "0.19.5",
                          "os": "test OS", "gpu": "test GPU", "driver": "test driver"},
                          "scenarios": [{"id": scenario, "status": "passed", "timed_out": False, "evidence": [
                              {"path": "client.log", "sha256": digest(log)}]}
                              for scenario in ("modes", "screenshots")]}
                for kind in payloads:
                    report[kind + "_sha256"] = target[kind]["sha256"]
                path = self.receipts / (backend + "-" + profile + ".json")
                self.write(path, report)
                self.paths.append(path)

    @staticmethod
    def write(path, value):
        path.write_text(json.dumps(value), encoding="utf-8")

    def check(self):
        return verify(self.manifest_path, self.receipts, self.catalog)

    def change_report(self, mutate):
        path = self.paths[0]
        report = json.loads(path.read_text(encoding="utf-8"))
        mutate(report)
        self.write(path, report)

    def test_complete_bound_receipts_pass(self):
        self.assertEqual(3, self.check())

    def test_scenarios_must_follow_contract_order(self):
        self.change_report(lambda report: report['scenarios'].reverse())
        with self.assertRaisesRegex(EvidenceError, 'Scenario order mismatch'):
            self.check()

    def rebind_manifest(self):
        self.manifest['catalog_sha256'] = catalog_digest(self.catalog)
        self.write(self.manifest_path, self.manifest)
        for path in self.receipts.glob('*.json'):
            report = json.loads(path.read_text())
            report['manifest_sha256'] = digest(self.manifest_path)
            self.write(path, report)

    def test_explicit_selection_excludes_unrelated_pending_target(self):
        other = copy.deepcopy(self.catalog['targets'][0])
        other.update(id='1.20.1-forge', implemented=False, loader='forge')
        self.catalog['targets'].append(other)
        self.manifest['selected_targets'] = ['26.2-fabric']
        self.rebind_manifest()
        self.assertEqual(3, self.check())

    def test_invalid_explicit_selections_fail(self):
        for selection in ([], '26.2-fabric', ['unknown'], ['26.2-fabric'] * 2, [None]):
            self.manifest['selected_targets'] = selection
            self.rebind_manifest()
            with self.subTest(selection=selection), self.assertRaisesRegex(
                    EvidenceError, 'Invalid explicit target selection'):
                self.check()

    def test_shared_runtime_cannot_be_omitted_from_selection(self):
        self.add_shared_quilt_target((self.root / 'artifact').read_bytes())
        for selection in (['26.2-fabric'], ['26.2-quilt']):
            self.manifest['selected_targets'] = selection
            self.rebind_manifest()
            with self.assertRaisesRegex(EvidenceError, 'shared-artifact runtime'):
                self.check()
        self.manifest['selected_targets'] = ['26.2-fabric', '26.2-quilt']
        self.rebind_manifest()
        self.assertEqual(6, self.check())

    def add_shared_quilt_target(self, artifact_bytes):
        specification = copy.deepcopy(self.catalog["targets"][0])
        specification.update(id="26.2-quilt", loader="quilt", artifactOf="26.2-fabric")
        self.catalog["targets"].append(specification)
        self.manifest["catalog_sha256"] = catalog_digest(self.catalog)
        target = copy.deepcopy(self.manifest["targets"][0])
        target["id"] = "26.2-quilt"
        artifact = self.root / "quilt-artifact"
        artifact.write_bytes(artifact_bytes)
        target["artifact"] = {"path": artifact.name, "sha256": digest(artifact)}
        lock = json.loads((self.root / "dependency_lock").read_text())
        lock["runtime"].update(loader="quilt", loader_version="0.30.1")
        lock_path = self.root / "quilt-lock"
        self.write(lock_path, lock)
        target["dependency_lock"] = {"path": lock_path.name, "sha256": digest(lock_path)}
        self.manifest["targets"].append(target)
        self.write(self.manifest_path, self.manifest)
        for path in self.paths:
            report = json.loads(path.read_text())
            report["manifest_sha256"] = digest(self.manifest_path)
            self.write(path, report)
            report["target"] = "26.2-quilt"
            report["runtime"].update(loader="quilt", loader_version="0.30.1")
            for kind in ("artifact", "dependency_lock"):
                report[kind + "_sha256"] = target[kind]["sha256"]
            self.write(self.receipts / ("quilt-" + path.name), report)

    def test_shared_artifact_requires_identical_bytes(self):
        self.add_shared_quilt_target(b"different jar despite matching receipts")
        with self.assertRaisesRegex(EvidenceError, "Shared artifact differs"):
            self.check()

    def test_dependency_change_invalidates_candidate_catalog(self):
        self.catalog["targets"][0]["dependencies"] = {"loader": "changed"}
        with self.assertRaisesRegex(EvidenceError, "target catalog has changed"):
            self.check()

    def test_catalog_object_key_order_does_not_change_identity(self):
        reordered = {"targets": [dict(reversed(list(self.catalog["targets"][0].items())))]}
        self.assertEqual(catalog_digest(self.catalog), catalog_digest(reordered))

    def test_changed_sources_are_rejected(self):
        (self.root / "sources").write_bytes(b"different source archive")
        with self.assertRaisesRegex(EvidenceError, "Changed evidence file"):
            self.check()

    def test_source_receipt_hash_must_match(self):
        self.change_report(lambda report: report.update(sources_sha256="0" * 64))
        with self.assertRaisesRegex(EvidenceError, "artifact mismatch: sources"):
            self.check()

    def test_shared_sources_must_match_owner(self):
        self.add_shared_quilt_target((self.root / "artifact").read_bytes())
        self.manifest["targets"][1]["sources"] = {"path": "other-sources", "sha256": "0" * 64}
        self.write(self.manifest_path, self.manifest)
        with self.assertRaisesRegex(EvidenceError, "Shared sources differs"):
            self.check()

    def test_shared_bytes_allow_separate_paths_but_need_independent_receipts(self):
        self.add_shared_quilt_target((self.root / "artifact").read_bytes())
        self.assertEqual(6, self.check())
        receipts = sorted(self.receipts.glob("quilt-*.json"))
        self.assertTrue(receipts, "Shared Quilt target must create receipts")
        receipts[0].unlink()
        with self.assertRaisesRegex(EvidenceError, "coverage mismatch"):
            self.check()

    def test_wrong_game_or_loader_cannot_reuse_receipt(self):
        original = self.paths[0].read_bytes()
        for key, value in (("minecraft", "26.1.2"), ("loader", "quilt"),
                           ("loader_version", "0.19.4"), ("loader_version", "")):
            with self.subTest(key=key, value=value):
                self.paths[0].write_bytes(original)
                self.change_report(lambda report: report["runtime"].update({key: value}))
                with self.assertRaises(EvidenceError):
                    self.check()

    def test_lock_runtime_must_match_target(self):
        self.catalog["targets"][0]["loader"] = "quilt"
        with self.assertRaises(EvidenceError):
            self.check()

    def test_missing_or_malformed_locked_runtime_fails_even_with_fresh_hashes(self):
        path = self.root / "dependency_lock"
        original = json.loads(path.read_text())
        for runtime in (None, {}, dict(original["runtime"], loader_version=""),
                        dict(original["runtime"], java=True)):
            with self.subTest(runtime=runtime):
                lock = dict(original, runtime=runtime)
                self.write(path, lock)
                checksum = digest(path)
                self.manifest["targets"][0]["dependency_lock"]["sha256"] = checksum
                self.write(self.manifest_path, self.manifest)
                for receipt in self.paths:
                    report = json.loads(receipt.read_text())
                    report.update(dependency_lock_sha256=checksum,
                                  manifest_sha256=digest(self.manifest_path))
                    self.write(receipt, report)
                with self.assertRaisesRegex(EvidenceError, "Missing locked runtime identity"):
                    self.check()

    def test_unsupported_profile_backend_receipt_fails(self):
        report = json.loads(self.paths[1].read_text(encoding="utf-8"))
        report.update(backend="vulkan", actual_backend="vulkan")
        self.write(self.receipts / "vulkan-iris.json", report)
        with self.assertRaisesRegex(EvidenceError, "coverage mismatch"):
            self.check()

    def test_malformed_lock_profiles_are_reported_as_evidence_errors(self):
        path = self.root / "dependency_lock"
        original = json.loads(path.read_text())
        for profiles in (["none", {}], ["none", ["iris"]], ["none", 1], ["none", ""]):
            with self.subTest(profiles=profiles):
                self.write(path, dict(original, profiles=profiles))
                self.manifest["targets"][0]["dependency_lock"]["sha256"] = digest(path)
                self.write(self.manifest_path, self.manifest)
                with self.assertRaisesRegex(EvidenceError, "compatibility profiles"):
                    self.check()

    def test_missing_receipt_and_duplicate_receipt_fail(self):
        saved = self.paths[0].read_bytes()
        self.paths[0].unlink()
        with self.assertRaisesRegex(EvidenceError, "coverage mismatch"):
            self.check()
        self.paths[0].write_bytes(saved)
        (self.receipts / "duplicate.json").write_bytes(saved)
        with self.assertRaisesRegex(EvidenceError, "Duplicate receipt"):
            self.check()

    def test_unimplemented_target_or_unknown_compatibility_inventory_fails(self):
        self.catalog["targets"][0]["implemented"] = False
        with self.assertRaisesRegex(EvidenceError, "Unimplemented"):
            self.check()
        self.catalog["targets"][0]["implemented"] = True
        del self.catalog["targets"][0]["compatibilityProfiles"]
        with self.assertRaisesRegex(EvidenceError, "Missing compatibility inventory"):
            self.check()

    def test_wrong_runtime_backend_and_stale_bindings_fail(self):
        original = self.paths[0].read_bytes()
        for field, value in (("actual_backend", "vulkan"), ("commit", "b" * 40),
                             ("manifest_sha256", "0" * 64), ("harness_sha256", "0" * 64),
                             ("status", "skipped")):
            with self.subTest(field=field):
                self.paths[0].write_bytes(original)
                self.change_report(lambda report: report.update({field: value}))
                with self.assertRaises(EvidenceError):
                    self.check()
        self.paths[0].write_bytes(original)
        self.change_report(lambda report: report["runtime"].update({"java": 21}))
        with self.assertRaisesRegex(EvidenceError, "Wrong Java"):
            self.check()

    def test_scenario_skips_timeouts_omissions_and_duplicates_fail(self):
        original = self.paths[0].read_bytes()
        changes = [lambda r: r["scenarios"].pop(),
                   lambda r: r["scenarios"].append(copy.deepcopy(r["scenarios"][0])),
                   lambda r: r["scenarios"][0].update({"status": "skipped"}),
                   lambda r: r["scenarios"][0].update({"timed_out": True}),
                   lambda r: r["scenarios"][0].pop("timed_out"),
                   lambda r: r["scenarios"][0].update({"evidence": []})]
        for change in changes:
            self.paths[0].write_bytes(original)
            self.change_report(change)
            with self.assertRaises(EvidenceError):
                self.check()

    def test_changed_candidate_and_changed_evidence_fail(self):
        (self.receipts / "client.log").write_text("changed", encoding="utf-8")
        with self.assertRaisesRegex(EvidenceError, "Changed evidence"):
            self.check()
        (self.root / "artifact").write_bytes(b"changed")
        with self.assertRaisesRegex(EvidenceError, "Changed evidence"):
            self.check()

    def test_partial_candidate_fails(self):
        self.manifest["targets"] = []
        self.write(self.manifest_path, self.manifest)
        with self.assertRaisesRegex(EvidenceError, "full target catalog"):
            self.check()

    def test_missing_locked_profile_fails(self):
        self.catalog["targets"][0]["compatibilityProfiles"]["renderscale"] = ["opengl"]
        with self.assertRaisesRegex(EvidenceError, "omits compatibility profiles"):
            self.check()

    def test_duplicate_json_keys_fail(self):
        original = self.manifest_path.read_text(encoding="utf-8")
        self.manifest_path.write_text('{"schema":1,' + original[1:], encoding="utf-8")
        with self.assertRaisesRegex(EvidenceError, "Duplicate JSON key"):
            self.check()

    def test_path_escape_and_external_symlink_fail(self):
        outside = self.root / "artifact"
        reference = {"path": "../artifact", "sha256": digest(outside)}
        with self.assertRaisesRegex(EvidenceError, "escaping"):
            checked_file(self.receipts, reference)
        (self.receipts / "link").symlink_to(outside)
        reference["path"] = "link"
        with self.assertRaisesRegex(EvidenceError, "escaping"):
            checked_file(self.receipts, reference)


if __name__ == "__main__":
    unittest.main()
