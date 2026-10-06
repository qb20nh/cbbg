"""Exercise dependency inventory and scan-result gates without network access."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from workflow_support import ROOT, script


WORKFLOW = ROOT / ".github/workflows/osv-scanner.yml"


class OsvWorkflowTest(unittest.TestCase):
    def run_gate(self, results, compare=False):
        with tempfile.TemporaryDirectory() as directory:
            for name, value in results.items():
                Path(directory, name).write_text(json.dumps(value))
            return subprocess.run(
                ["bash", "-c", script("Require completed scans", WORKFLOW)],
                cwd=directory,
                env={**os.environ, "COMPARE": str(compare).lower()},
                capture_output=True,
                text=True,
                check=False,
            )

    def test_missing_current_results_fail(self):
        result = self.run_gate({})
        self.assertNotEqual(0, result.returncode)
        self.assertIn('::error::', result.stdout)
        self.assertIn('new-results.json', result.stdout)
        self.assertIn('Scan proposed dependencies', result.stdout)

    def test_missing_base_results_fail(self):
        result = self.run_gate({"new-results.json": {"results": []}}, compare=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('old-results.json', result.stdout)
        self.assertIn('Scan base dependencies', result.stdout)

    def test_invalid_result_schema_fails(self):
        result = self.run_gate({"new-results.json": {}})
        self.assertNotEqual(0, result.returncode)
        self.assertIn('results array', result.stdout)

    def test_completed_clean_scans_pass(self):
        results = {name: {"results": []} for name in ("new-results.json", "old-results.json")}
        self.assertEqual(0, self.run_gate(results, compare=True).returncode)

    def generate(self, bom, compare=False, fail_build=False, build_profile='fabric-modern'):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            current = root / "current"
            current.mkdir()
            (current / "settings.gradle").touch()
            profile = current / "build-config/fabric-modern"
            profile.mkdir(parents=True)
            (profile / "settings.gradle").touch()
            publishing = current / "build-config/publishing"
            publishing.mkdir()
            (publishing / "settings.gradle").touch()
            (current / "targets.json").write_text(json.dumps({
                "ciTargets": ["26.3-fabric"],
                "targets": [{"id": "26.3-fabric", "buildProfile": build_profile}],
            }))
            wrapper = current / "gradlew"
            wrapper.write_text(
                "#!/usr/bin/env python3\n"
                "import os, pathlib, sys\n"
                "if os.environ['FAIL_BUILD'] == 'true': sys.exit(42)\n"
                "directory = pathlib.Path(sys.argv[sys.argv.index('-p') + 1])\n"
                "assert (directory / 'settings.gradle').is_file()\n"
                "output = next(a.split('=', 1)[1] for a in sys.argv if a.startswith('-PsecuritySbom='))\n"
                "pathlib.Path(output).write_text(os.environ['TEST_SBOM'])\n"
            )
            wrapper.chmod(0o755)
            if compare:
                baseline = root / "baseline"
                baseline.mkdir()
                (baseline / "settings.gradle").touch()
                shutil.copy2(wrapper, baseline / "gradlew")
            output = root / "outputs"
            result = subprocess.run(
                ["bash", "-c", script("Resolve Gradle dependencies", WORKFLOW)],
                cwd=directory,
                env={**os.environ, "GITHUB_WORKSPACE": directory, "GITHUB_OUTPUT": str(output),
                     "TEST_SBOM": json.dumps(bom), "FAIL_BUILD": str(fail_build).lower()},
                capture_output=True,
                text=True,
                check=False,
            )
            return result, output.read_text() if output.exists() else ""

    def test_empty_dependency_inventory_fails(self):
        result, _ = self.generate({"bomFormat": "CycloneDX", "components": []})
        self.assertNotEqual(0, result.returncode)
        self.assertIn('::error::', result.stdout)
        self.assertIn('CycloneDX', result.stdout)
        self.assertIn('osv-dependencies', result.stdout)

    def test_failed_build_names_the_dependency_report(self):
        result, _ = self.generate({}, fail_build=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('::error::', result.stdout)
        self.assertIn('root.cdx.json', result.stdout)
        self.assertIn('Gradle failure above', result.stdout)

    def test_invalid_profile_has_an_escaped_annotation(self):
        result, _ = self.generate(
            {'bomFormat': 'CycloneDX', 'components': [{'purl': 'pkg:maven/example/library@1.0'}]},
            build_profile='bad%profile/path',
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn('::error::', result.stdout)
        self.assertIn('bad%25profile/path', result.stdout)
        self.assertIn('26.3-fabric', result.stdout)

    def test_unidentified_components_alone_fail(self):
        result, _ = self.generate({"bomFormat": "CycloneDX", "components": [{"name": "project"}]})
        self.assertNotEqual(0, result.returncode)

    def test_project_component_does_not_hide_resolved_dependencies(self):
        result, _ = self.generate({
            "bomFormat": "CycloneDX",
            "components": [{"name": "project"}, {"purl": "pkg:maven/example/library@1.0"}],
        })
        self.assertEqual(0, result.returncode, result.stderr)

    def test_invalid_inventory_format_fails(self):
        result, _ = self.generate({"components": [{"purl": "pkg:maven/example/library@1.0"}]})
        self.assertNotEqual(0, result.returncode)

    def test_root_and_catalog_target_are_scanned_explicitly(self):
        result, output = self.generate({
            "bomFormat": "CycloneDX", "components": [{"purl": "pkg:maven/example/library@1.0"}],
        })
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--sbom=.osv/current/root.cdx.json", output)
        self.assertIn("--sbom=.osv/current/26.3-fabric.cdx.json", output)
        self.assertIn("--sbom=.osv/current/build-config-publishing.cdx.json", output)

    def test_base_without_target_catalog_gets_its_own_inventory(self):
        result, output = self.generate({
            "bomFormat": "CycloneDX", "components": [{"purl": "pkg:maven/example/library@1.0"}],
        }, compare=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("--sbom=.osv/baseline/root.cdx.json", output)
        self.assertIn("baseline-args<<SBOM_ARGS", output)


if __name__ == "__main__":
    unittest.main()
