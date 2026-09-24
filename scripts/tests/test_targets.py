import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from targets import load_catalog, select_targets, select_build_profile, select_artifacts


class TargetCatalogTest(unittest.TestCase):
    def setUp(self):
        self.catalog = load_catalog()

    def test_catalog_covers_required_distribution_matrix(self):
        required = {
            "1.7.10": {"legacy-fabric", "forge"},
            "1.12.2": {"legacy-fabric", "forge"},
            "1.16.5": {"fabric", "forge", "quilt"},
            "1.18.2": {"fabric", "forge", "quilt"},
            "1.19.2": {"fabric", "forge", "quilt"},
            "1.20.1": {"fabric", "forge", "quilt"},
            "1.21.11": {"fabric", "forge", "neoforge", "quilt"},
            "26.1.2": {"fabric", "forge", "neoforge", "quilt"},
            "26.2": {"fabric", "forge", "neoforge", "quilt"},
            "26.3": {"fabric", "forge", "quilt"},
        }
        actual = {target["id"] for target in self.catalog["targets"]}
        self.assertEqual({version + "-" + loader for version, loaders in required.items()
                          for loader in loaders}, actual)
        for target in self.catalog["targets"]:
            expected = {"opengl", "vulkan"} if target["minecraft"] in {"26.2", "26.3"} else {"opengl"}
            self.assertEqual(expected, set(target["backends"]), target["id"])
        owners = {target.get("artifactOf", target["id"]) for target in self.catalog["targets"]}
        self.assertEqual(owners, {target["id"] for target in select_artifacts(self.catalog)})

    def test_unknown_empty_and_duplicate_selection_fail(self):
        for selection in ("unknown", "", "1.20.1-forge,", "1.20.1-forge,1.20.1-forge"):
            with self.subTest(selection=selection), self.assertRaises(ValueError):
                select_targets(self.catalog, selection)

    def test_classic_projection_api_must_be_explicit(self):
        for api in (None, "unknown"):
            catalog = copy.deepcopy(self.catalog)
            select_targets(catalog, "1.20.1-fabric")[0]["projectionApi"] = api
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "targets.json"
                path.write_text(json.dumps(catalog), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "projection API"):
                    load_catalog(path)

    def test_classic_profiles_require_source_groups(self):
        for groups in (None, [], "renderers/classic-gl", [None]):
            catalog = copy.deepcopy(self.catalog)
            select_targets(catalog, "1.19.2-fabric")[0]["sourceGroups"] = groups
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "targets.json"
                path.write_text(json.dumps(catalog), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "source groups"):
                    load_catalog(path)

    def test_pending_targets_cannot_be_silently_omitted(self):
        with self.assertRaisesRegex(ValueError, "not implemented"):
            select_targets(self.catalog, require_implemented=True)
        selected = select_targets(self.catalog, "1.21.11-fabric", require_implemented=True)
        self.assertEqual(["1.21.11-fabric"], [t["id"] for t in selected])

    def test_build_profile_selects_configured_pending_targets(self):
        selected = select_build_profile(self.catalog, "neoforge-modern")
        self.assertEqual(["1.21.11-neoforge", "26.1.2-neoforge", "26.2-neoforge"], [t["id"] for t in selected])
        self.assertEqual([21, 25, 25], [t["java"] for t in selected])
        with self.assertRaisesRegex(ValueError, "not implemented"):
            select_build_profile(self.catalog, "neoforge-modern", require_implemented=True)
        with self.assertRaisesRegex(ValueError, "Unknown or empty build profile"):
            select_build_profile(self.catalog, "missing-profile")

    def test_catalog_rejects_duplicate_ids_and_unsupported_vulkan(self):
        duplicate = copy.deepcopy(self.catalog)
        duplicate["targets"].append(duplicate["targets"][0])
        bad_backend = copy.deepcopy(self.catalog)
        bad_backend["targets"][0]["backends"].append("vulkan")
        for catalog in (duplicate, bad_backend):
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "targets.json"
                path.write_text(json.dumps(catalog), encoding="utf-8")
                with self.assertRaises(ValueError):
                    load_catalog(path)

    def test_iris_fixture_is_limited_to_opengl(self):
        target = select_targets(self.catalog, "26.3-fabric")[0]
        self.assertEqual(["opengl"], target["compatibilityProfiles"]["iris"])
        self.assertEqual(target["backends"], target["compatibilityProfiles"]["none"])

    def test_shared_artifact_cannot_require_newer_java_than_runtime(self):
        catalog = copy.deepcopy(self.catalog)
        select_targets(catalog, "1.20.1-quilt")[0]["java"] = 8
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "targets.json"
            path.write_text(json.dumps(catalog), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "shared artifact"):
                load_catalog(path)

    def test_shared_artifact_allows_higher_loader_java_requirement(self):
        catalog = copy.deepcopy(self.catalog)
        select_targets(catalog, "1.20.1-quilt")[0]["java"] = 21
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "targets.json"
            path.write_text(json.dumps(catalog), encoding="utf-8")
            checked = load_catalog(path)
            owner = select_artifacts(checked, "1.20.1-quilt")
            self.assertEqual(["1.20.1-fabric"], [t["id"] for t in owner])
            self.assertEqual(17, owner[0]["java"])

    def test_invalid_compatibility_backend_inventory_is_rejected(self):
        for profiles in ({}, [], {"modmenu": ["opengl"]}, {"none": ["opengl"]},
                         {"none": ["opengl", "vulkan"], "iris": []},
                         {"none": ["opengl", "vulkan"], "iris": ["metal"]},
                         {"none": ["opengl", "vulkan"], "iris": ["opengl", "opengl"]},
                         {"none": ["opengl", "vulkan"], "iris": [{}]}):
            with self.subTest(profiles=profiles), tempfile.TemporaryDirectory() as directory:
                catalog = copy.deepcopy(self.catalog)
                select_targets(catalog, "26.3-fabric")[0]["compatibilityProfiles"] = profiles
                path = Path(directory) / "targets.json"
                path.write_text(json.dumps(catalog), encoding="utf-8")
                with self.assertRaises(ValueError):
                    load_catalog(path)


if __name__ == "__main__":
    unittest.main()
