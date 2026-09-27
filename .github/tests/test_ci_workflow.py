"""Check CI workflow routing without invoking build or GitHub services."""

import unittest

from workflow_support import ROOT, script


WORKFLOW = ROOT / ".github/workflows/gradle.yml"


class CiWorkflowTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
