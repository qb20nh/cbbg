"""Check that hosted workflows invoke the native Gradle entry points."""

from pathlib import Path
import unittest

import yaml

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
        workflow = yaml.safe_load(text)
        steps = workflow["jobs"]["candidate"]["steps"]
        attestation = next(step for step in steps if step.get("name") == "Attest candidate files")
        self.assertRegex(attestation["uses"], r"^actions/attest-build-provenance@\S+$")
        self.assertEqual(attestation["id"], "provenance")
        self.assertEqual(attestation["with"]["subject-path"], "build/release-candidate/*")
        for permission in ("id-token", "attestations"):
            self.assertEqual(workflow["permissions"][permission], "write")
        verification = next(step for step in steps if step.get("name") == "Verify candidate provenance")
        self.assertEqual(verification["env"]["PROVENANCE_BUNDLE"],
                         "${{ steps.provenance.outputs.bundle-path }}")
        self.assertIn("gh release create", script("Create draft release", release))
        self.assertIn("--draft", script("Create draft release", release))

    def test_codeql_has_one_java_job_and_keeps_historical_coverage(self):
        workflow = yaml.safe_load((WORKFLOWS / "codeql.yml").read_text())
        jobs = workflow["jobs"]
        self.assertEqual(set(jobs), {"analyze-targets", "analyze"})
        java = jobs["analyze-targets"]
        self.assertNotIn("strategy", java)
        steps = java["steps"]
        setup = next(step for step in steps if step.get("id") == "codeql")
        self.assertEqual(setup["name"], "Install CodeQL CLI")
        self.assertNotIn("uses", setup)
        self.assertRegex(setup["env"]["CODEQL_BUNDLE_VERSION"], r"^\d+\.\d+\.\d+$")
        self.assertRegex(setup["env"]["CODEQL_BUNDLE_SHA256"], r"^[0-9a-f]{64}$")
        install = setup["run"]
        self.assertIn('gh release download "codeql-bundle-v$CODEQL_BUNDLE_VERSION"', install)
        self.assertIn("--repo github/codeql-action", install)
        self.assertLess(install.index("sha256sum --check"), install.index("tar --zstd"))
        self.assertIn('"$install_dir/codeql/codeql" version', install)
        self.assertIn('printf \'codeql-path=%s\\n\'', install)
        self.assertIn('>> "$GITHUB_OUTPUT"', install)
        build = script("Analyze Java artifact builds", WORKFLOWS / "codeql.yml")
        self.assertIn("codeqlScan", build)
        self.assertIn('"-PcodeqlExecutable=$CODEQL_EXECUTABLE"', build)
        self.assertIn("-PcodeqlLegacyTarget=26.2-fabric", build)
        upload = next(step for step in steps if step.get("name") == "Upload Java analyses")
        self.assertEqual(upload["with"]["sarif_file"], ".gradle/codeql-results/sarif")
        self.assertNotIn("category", upload["with"])
        self.assertNotIn("if", upload)
        self.assertEqual(jobs["analyze"]["strategy"]["matrix"]["language"], ["actions", "python"])

    def test_codeql_restores_gradle_dependencies_before_analysis(self):
        workflow = yaml.safe_load((WORKFLOWS / "codeql.yml").read_text())
        steps = workflow["jobs"]["analyze-targets"]["steps"]
        cache_index = next(
            i for i, step in enumerate(steps)
            if step.get("uses", "").startswith("gradle/actions/setup-gradle@")
        )
        build_index = next(
            i for i, step in enumerate(steps)
            if step.get("name") == "Analyze Java artifact builds"
        )
        self.assertLess(cache_index, build_index)
        cache = steps[cache_index]
        self.assertEqual(cache["name"], "Cache Gradle dependencies")
        self.assertNotIn("cache-read-only", cache.get("with", {}))
        self.assertFalse(cache.get("with", {}).get("cache-disabled", False))

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
