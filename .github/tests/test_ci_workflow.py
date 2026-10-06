"""Check CI workflow routing without invoking build or GitHub services."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml

from workflow_support import ROOT, script


WORKFLOW = ROOT / ".github/workflows/gradle.yml"


class CiWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.jobs = yaml.safe_load(WORKFLOW.read_text())["jobs"]

    def step(self, job, name):
        return next(step for step in self.jobs[job]["steps"] if step.get("name") == name)

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
        self.assertIn("-p build-logic --no-daemon --build-cache test", command)
        self.assertIn("unittest discover -s scripts/tests", command)
        self.assertIn("unittest discover -s .github/tests", command)

    def test_java_quality_checks_are_required(self):
        self.assertIn("spotlessCheck", script("Check Java formatting", WORKFLOW))
        self.assertIn("-p core --no-daemon --build-cache check",
                      self.step("core", "Check core quality")["run"])
        workflow = WORKFLOW.read_text()
        self.assertIn("if: matrix.java == 25", workflow)
        self.assertIn("core/*/build/reports/", workflow)
        self.assertIn("build/reports/", workflow)

    def test_target_jobs_start_after_selection_without_waiting_for_tooling(self):
        selection = self.jobs["selection"]
        self.assertNotIn("needs", selection)
        self.assertNotIn("Check Java formatting", str(selection["steps"]))
        self.assertNotIn("Test build and release tooling", str(selection["steps"]))
        for name in ("core", "build", "dependency-submission", "tooling"):
            self.assertEqual(self.jobs[name]["needs"], "selection")
            self.assertIn("needs.selection.outputs.", self.jobs[name]["if"])
        for name in ("build", "dependency-submission"):
            self.assertEqual(self.jobs[name]["strategy"]["matrix"],
                             "${{ fromJSON(needs.selection.outputs.matrix) }}")
        self.assertEqual(self.jobs["tooling"]["if"],
                         "needs.selection.outputs.tooling == 'true'")
        self.assertEqual(selection["outputs"]["tooling"],
                         "${{ steps.batch.outputs.tooling || steps.targets.outputs.tooling }}")

    def test_plan_remains_an_always_run_aggregate_gate(self):
        plan = self.jobs["plan"]
        self.assertEqual(plan["needs"], ["selection", "tooling"])
        self.assertEqual(plan["if"], "always()")
        gate = self.step("plan", "Verify selection and tooling")
        self.assertEqual(gate["env"], {
            "SELECTION_RESULT": "${{ needs.selection.result }}",
            "TOOLING_RESULT": "${{ needs.tooling.result }}",
            "TOOLING_REQUIRED": "${{ needs.selection.outputs.tooling }}",
        })
        for selection in ("success", "failure", "cancelled", "skipped"):
            for required in ("true", "false", "", "unexpected"):
                for tooling in ("success", "failure", "cancelled", "skipped"):
                    with self.subTest(selection=selection, required=required, tooling=tooling):
                        result = subprocess.run(
                            ["bash", "-e", "-o", "pipefail", "-c", gate["run"]],
                            env=dict(os.environ, SELECTION_RESULT=selection,
                                     TOOLING_REQUIRED=required, TOOLING_RESULT=tooling),
                            capture_output=True, text=True,
                        )
                        expected = (selection == "success" and required in ("true", "false")
                                    and (tooling == "success"
                                         or (required == "false" and tooling == "skipped")))
                        self.assertEqual(result.returncode == 0, expected, result.stdout + result.stderr)

    def test_core_runs_one_cached_invocation_per_java_with_test_runtime(self):
        runtime = self.step("core", "Test core on selected runtime")
        quality = self.step("core", "Check core quality")
        self.assertEqual(runtime["if"], "matrix.java != 25")
        self.assertEqual(quality["if"], "matrix.java == 25")
        self.assertEqual(self.jobs["core"]["strategy"]["matrix"]["java"], [8, 17, 21, 25])
        with tempfile.TemporaryDirectory() as directory:
            launcher = Path(directory) / "gradlew"
            launcher.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
            launcher.chmod(0o755)
            for java in (8, 17, 21, 25):
                with self.subTest(java=java):
                    step = quality if java == 25 else runtime
                    self.assertEqual(step["env"]["TEST_JAVA"], "${{ matrix.java }}")
                    self.assertEqual(step["env"]["TEST_JAVA_HOME"], "${{ steps.test-java.outputs.path }}")
                    result = subprocess.run(
                        ["bash", "-e", "-c", step["run"]], cwd=directory,
                        env=dict(os.environ, TEST_JAVA=str(java), TEST_JAVA_HOME="/test java"),
                        capture_output=True, text=True, check=True,
                    )
                    arguments = result.stdout.splitlines()
                    self.assertIn("--build-cache", arguments)
                    self.assertIn("-PtestJava=" + str(java), arguments)
                    self.assertIn("-Dorg.gradle.java.installations.paths=/test java", arguments)
                    self.assertIn("check" if java == 25 else "test", arguments)
                    self.assertNotIn("test" if java == 25 else "check", arguments)

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
