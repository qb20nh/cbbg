"""Check CI workflow routing without invoking build or GitHub services."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from workflow_support import ROOT, script


WORKFLOW = ROOT / ".github/workflows/gradle.yml"


class CiWorkflowTest(unittest.TestCase):
    def run_batch(self, catalog, matrix):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'targets.json').write_text(json.dumps(catalog))
            wrapper = root / 'gradlew'
            wrapper.write_text('#!/bin/sh\nmkdir -p build\nprintf "%s" "$MATRIX" > build/batch-matrix.json\n')
            wrapper.chmod(0o755)
            return subprocess.run(
                ['bash', '-euo', 'pipefail', '-c', script('Select full batch CI', WORKFLOW)],
                cwd=root, env={**os.environ, 'MATRIX': json.dumps(matrix),
                               'GITHUB_OUTPUT': str(root / 'output')},
                text=True, capture_output=True,
            )

    def test_empty_batch_catalog_explains_the_required_selection(self):
        result = self.run_batch({'ciTargets': []}, {'include': []})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('::error::', result.stdout)
        self.assertIn('ciTargets', result.stdout)
        self.assertIn('targets.json', result.stdout)

    def test_empty_batch_matrix_names_the_failed_output(self):
        result = self.run_batch({'ciTargets': ['26.3-fabric']}, {'include': []})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('::error::', result.stdout)
        self.assertIn('build/batch-matrix.json', result.stdout)
        self.assertIn('targetMatrix', result.stdout)

    def test_valid_batch_selection_still_passes(self):
        result = self.run_batch({'ciTargets': ['26.3-fabric']}, {'include': [{'id': '26.3-fabric'}]})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_plan_writes_impact_report_and_github_outputs(self):
        command = script("Select catalog targets", WORKFLOW)
        self.assertIn('"-Pbase=$BASE_COMMIT"', command)
        self.assertIn("-Poutput=build/change-impact.json", command)
        self.assertIn('"-PgithubOutput=$GITHUB_OUTPUT"', command)
        self.assertIn("selectChecks", command)

    def test_build_uses_selected_target_without_runtime_launch(self):
        command = script("Compile and check target packages", WORKFLOW)
        self.assertIn('"-Ptarget=$TARGET_ID"', command)
        self.assertIn("ciCheck", command)
        self.assertNotIn("runClient", WORKFLOW.read_text())
        self.assertNotIn("runGameTest", WORKFLOW.read_text())

    def test_full_tooling_suite_remains_a_gate(self):
        command = script("Test build and release tooling", WORKFLOW)
        self.assertIn("-p build-logic --no-daemon test", command)
        self.assertIn("unittest discover -s scripts/tests", command)
        self.assertIn("unittest discover -s .github/tests", command)

    def test_java_quality_checks_are_required(self):
        self.assertIn("spotlessCheck", script("Check Java formatting", WORKFLOW))
        self.assertIn("-p core --no-daemon check", script("Check core quality", WORKFLOW))
        workflow = WORKFLOW.read_text()
        self.assertIn("if: matrix.java == 25", workflow)
        self.assertIn("core/*/build/reports/", workflow)
        self.assertIn("build/reports/", workflow)

    def test_dependency_graph_excludes_minecraft_origins(self):
        workflow = WORKFLOW.read_text().split(
            "- name: Generate and submit target dependency graph", 1
        )[1]
        self.assertIn(
            "--init-script ${{ github.workspace }}/gradle/dependency-sbom.init.gradle",
            workflow,
        )
        self.assertIn(
            "^(minecraft|minecraftClientLibraries|minecraftClientRuntimeLibraries|"
            "minecraftServerLibraries|minecraftServerRuntimeLibraries|minecraftNatives)$",
            workflow,
        )


if __name__ == "__main__":
    unittest.main()
