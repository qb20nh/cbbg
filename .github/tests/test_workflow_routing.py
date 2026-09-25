"""Check that hosted workflows invoke the native Gradle entry points."""

from pathlib import Path
import unittest

from workflow_support import ROOT, script


WORKFLOWS = ROOT / ".github/workflows"


class WorkflowRoutingTest(unittest.TestCase):
    def test_ci_and_dev_use_native_target_tasks(self):
        ci = WORKFLOWS / "gradle.yml"
        dev = WORKFLOWS / "dev.yml"
        self.assertIn("selectChecks", script("Select catalog targets", ci))
        self.assertIn("ciCheck", script("Compile and check target packages", ci))
        self.assertIn("targetMatrix", script("Select catalog targets", dev))
        build = script("Check and build development jars", dev)
        self.assertIn("ciCheck", build)
        self.assertIn(" dev", build)
        self.assertIn("matrix.buildProfile == 'fabric-upstream'", dev.read_text())
        self.assertIn("build/libs/*-dev-*.jar", dev.read_text())
        self.assertNotIn("scripts/build_targets.py", ci.read_text() + dev.read_text())

    def test_dependency_submission_uses_java_25_build_jvm(self):
        job = (WORKFLOWS / "gradle.yml").read_text().split("  dependency-submission:", 1)[1]
        self.assertIn("java-version: ${{ matrix.java }}", job)
        self.assertIn("java-version: '25'", job)

    def test_release_keeps_draft_attestation_and_native_bundle(self):
        release = WORKFLOWS / "release.yml"
        text = release.read_text()
        self.assertIn("-PrequireImplemented=true", script("Validate release selection", release))
        self.assertIn("bundleCandidate", script("Build and bundle candidate", release))
        self.assertIn("verifyProvenance", script("Verify candidate provenance", release))
        self.assertIn("actions/attest-build-provenance@v3", text)
        self.assertIn("gh release create", script("Create draft release", release))
        self.assertIn("--draft", script("Create draft release", release))

    def test_codeql_selects_old_upstream_target_explicitly(self):
        codeql = WORKFLOWS / "codeql.yml"
        self.assertIn("targetMatrix", codeql.read_text())
        self.assertIn("-Ptarget=26.2-fabric", codeql.read_text())

    def test_publication_never_builds_artifact_and_retains_retry_gate(self):
        publish = WORKFLOWS / "publish.yml"
        text = publish.read_text()
        self.assertIn("preparePublication", script("Prepare publication", publish))
        modrinth = script("Validate or publish to Modrinth", publish)
        self.assertIn("planModrinthUpload", modrinth)
        self.assertIn("-PlegacyAssets=", modrinth)
        self.assertIn("-Pcandidate=", modrinth)
        self.assertIn("recordCurseForgeUpload", script("Save CurseForge upload record", publish))
        self.assertNotIn("candidateBuildOutputs", text)
        self.assertNotIn("bundleCandidate", text)
        self.assertIn("github.run_attempt != '1'", text)


if __name__ == "__main__":
    unittest.main()
