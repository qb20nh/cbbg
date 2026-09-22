"""Execute workflow validation blocks against isolated release fixtures; never upload."""
import copy
import json
import io
import os
from pathlib import Path
import struct
import subprocess
import shutil
import sys
import tempfile
import textwrap
import unittest
import urllib.error
from unittest.mock import patch
import zipfile


ROOT = Path(__file__).resolve().parents[2]
PUBLISH = ROOT / ".github/workflows/publish.yml"
sys.path.insert(0, str(ROOT / ".github/scripts"))
import release


def script(name, path=PUBLISH):
    lines = path.read_text().splitlines()
    start = next(i for i, line in enumerate(lines) if line.strip() == "- name: " + name)
    start = next(i for i in range(start + 1, len(lines)) if lines[i].strip().startswith("run:"))
    if lines[start].strip() != "run: |":
        return lines[start].strip().removeprefix("run: ") + "\n"
    start += 1
    end = start
    while end < len(lines) and (not lines[end].strip() or lines[end].startswith("          ")):
        end += 1
    return textwrap.dedent("\n".join(lines[start:end])) + "\n"


class ReleaseValidationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.env = dict(os.environ, GITHUB_OUTPUT=str(self.root / "output"),
                        GITHUB_ENV=str(self.root / "env"))
        self.source = self.root / "src/main/resources/fabric.mod.json"
        self.source.parent.mkdir(parents=True)
        # Publishing tools come from the workflow revision, not an old release tag.
        tools = self.root / ".release-tools/.github/scripts"
        tools.mkdir(parents=True)
        shutil.copyfile(ROOT / ".github/scripts/release.py", tools / "release.py")

    def run_step(self, name, success=True):
        result = subprocess.run(["bash", "-euo", "pipefail", "-c", script(name)],
                                cwd=self.root, env=self.env, text=True, capture_output=True)
        self.assertEqual(result.returncode == 0, success, result.stdout + result.stderr)
        return result

    def fixture(self, mc="26.2", java=25):
        metadata = {"id": "cbbg", "version": "1.3.0+mc" + mc, "environment": "client",
                    "depends": {"minecraft": "~" + mc, "java": ">=" + str(java),
                                "fabricloader": ">=0.19.3", "fabric-api": "*"}}
        self.source.write_text(json.dumps(metadata))
        self.env.update(ARTIFACT_VERSION=metadata["version"], MINECRAFT_VERSION=mc,
                        JAVA_VERSION=str(java), PUBLISH_JAR=str(self.root / "mod.jar"),
                        PUBLISH_SOURCES_JAR=str(self.root / "sources.jar"))
        with zipfile.ZipFile(self.env["PUBLISH_SOURCES_JAR"], "w") as jar:
            jar.writestr("Example.java", "class Example {}")
        self.write_jar(metadata, java)
        return metadata

    def write_jar(self, metadata, java=25):
        with zipfile.ZipFile(self.env["PUBLISH_JAR"], "w") as jar:
            jar.writestr("fabric.mod.json", json.dumps(metadata))
            jar.writestr("Example.class", struct.pack(">IHH", 0xCAFEBABE, 0, java + 44))

    def test_current_and_maintenance_java(self):
        for mc, java in [("26.2", 25), ("1.21.1", 21), ("1.20.1", 17)]:
            with self.subTest(mc=mc):
                self.fixture(mc, java)
                self.run_step("Read release Java requirement")
                self.assertIn(f"version={java}\n", Path(self.env["GITHUB_OUTPUT"]).read_text())
                self.run_step("Validate packaged release metadata")

    def test_reject_unknown_java_requirement(self):
        metadata = self.fixture()
        metadata["depends"]["java"] = "*"
        self.source.write_text(json.dumps(metadata))
        self.run_step("Read release Java requirement", False)

    def test_reject_wrong_artifact(self):
        original = self.fixture()
        for key, value in [("id", "other"), ("version", "1.3.0+mc1.21.1"),
                           ("environment", "server")]:
            with self.subTest(key=key):
                metadata = copy.deepcopy(original)
                metadata[key] = value
                self.write_jar(metadata)
                self.run_step("Validate packaged release metadata", False)

    def test_reject_wrong_requirements(self):
        original = self.fixture()
        for key, value in [("minecraft", "~1.21.1"), ("java", ">=21"),
                           ("fabricloader", "*"), ("fabric-api", None)]:
            with self.subTest(key=key):
                metadata = copy.deepcopy(original)
                metadata["depends"][key] = value
                self.write_jar(metadata)
                self.run_step("Validate packaged release metadata", False)

    def test_reject_newer_bytecode(self):
        metadata = self.fixture()
        self.write_jar(metadata, 26)
        self.run_step("Validate packaged release metadata", False)

    def test_manual_tag_validation(self):
        for tag, valid in [("v1.3.0+mc26.2", True), ("v1.3.1+mc1.21.1", True),
                           ("v1.3.0-rc.1+mc26.2", True), ("v1.3.0", False),
                           ("main", False), ("v1.3.0+mc26.2\nevil", False)]:
            with self.subTest(tag=tag):
                self.env["REQUESTED_TAG"] = tag
                self.run_step("Validate selected release tag (manual)", valid)

    def test_tag_matches_release_properties_and_java_label(self):
        for mc, java in [("26.2", 25), ("1.21.1", 21), ("1.20.1", 17)]:
            with self.subTest(mc=mc):
                (self.root / "gradle.properties").write_text(
                    f"mod_version=1.3.0\nminecraft_version={mc}\narchives_base_name=cbbg\n"
                    "modrinth_project_id=UBlXUQbC\ncurseforge_project_id=1408371\n")
                self.env.update(RELEASE_TAG_INPUT=f"v1.3.0+mc{mc}",
                                IS_GH_PRERELEASE="false", JAVA_VERSION=str(java))
                self.run_step("Read metadata + validate tag")
                self.assertIn(f",Java {java},Fabric,Environment:Client",
                              Path(self.env["GITHUB_ENV"]).read_text())
                self.env["RELEASE_TAG_INPUT"] = "v1.3.0+mc99.9"
                self.run_step("Read metadata + validate tag", False)
                self.env["RELEASE_TAG_INPUT"] = f"v1.3.1+mc{mc}"
                self.run_step("Read metadata + validate tag", False)
                self.env.update(RELEASE_TAG_INPUT=f"v1.3.0+mc{mc}", IS_GH_PRERELEASE="true")
                self.run_step("Read metadata + validate tag", False)

    def test_destination_labels_must_all_resolve(self):
        self.env.update(MINECRAFT_VERSION="26.2", CF_API_TOKEN="test-only",
                        CURSEFORGE_GAME_VERSIONS="26.2,Java 25,Fabric,Environment:Client")
        versions = [
            {"id": 1, "name": "26.2", "gameVersionTypeID": 10},
            {"id": 99, "name": "26.2", "gameVersionTypeID": 99},
            {"id": 2, "name": "Java 25", "gameVersionTypeID": 11},
            {"id": 3, "name": "Fabric", "gameVersionTypeID": 12},
            {"id": 4, "name": "Client", "gameVersionTypeID": 13},
        ]
        types = [{"id": 10, "name": "Minecraft 26.2"}, {"id": 13, "name": "Environment"}]
        for complete in (True, False):
            with self.subTest(complete=complete):
                def response(request, timeout):
                    url = request.full_url
                    self.assertEqual(request.get_method(), "GET")
                    if "curseforge" in url:
                        self.assertEqual(request.get_header("X-api-token"), "test-only")
                    data = ([{"version": "26.2"}] if "modrinth" in url else
                            types if url.endswith("version-types") else
                            versions if complete else versions[:1])
                    return io.BytesIO(json.dumps(data).encode())
                with patch.dict(os.environ, self.env), patch("urllib.request.urlopen", response):
                    if complete:
                        release.validate_destinations()
                        self.assertIn("CURSEFORGE_GAME_VERSIONS=1,2,3,4\n",
                                      Path(self.env["GITHUB_ENV"]).read_text())
                    else:
                        with self.assertRaises(SystemExit):
                            release.validate_destinations()

    def test_curseforge_authentication_failure_stops_validation(self):
        self.env.update(MINECRAFT_VERSION="26.2", CF_API_TOKEN="invalid-test-token")
        def response(request, timeout):
            if "modrinth" in request.full_url:
                return io.BytesIO(b'[{"version":"26.2"}]')
            error = urllib.error.HTTPError(request.full_url, 401, "Unauthorized", {}, io.BytesIO())
            self.addCleanup(error.close)
            raise error
        with patch.dict(os.environ, self.env), patch("urllib.request.urlopen", response):
            with self.assertRaises(urllib.error.HTTPError):
                release.validate_destinations()
        self.assertFalse(Path(self.env["GITHUB_ENV"]).exists())

    def test_minotaur_debug_init_is_used_only_for_dry_run(self):
        wrapper = self.root / "gradlew"
        wrapper.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > gradle-arguments\n')
        wrapper.chmod(0o755)
        for dry_run in ("true", "false"):
            with self.subTest(dry_run=dry_run):
                self.env["DRY_RUN"] = dry_run
                self.run_step("Validate or publish to Modrinth")
                arguments = (self.root / "gradle-arguments").read_text().splitlines()
                expected = ["modrinth"]
                if dry_run == "true":
                    expected += ["--init-script", ".release-tools/.github/scripts/modrinth-dry-run.init.gradle"]
                self.assertEqual(arguments, expected)

    def test_release_notes_select_only_requested_version(self):
        changelog = self.root / "CHANGELOG.md"
        changelog.write_text("## [Unreleased]\nFuture\n\n## [1.3.0] - 2026-09-22\n"
                             "Port notes\n\n## [1.2.0] - 2025-12-01\nOld notes\n")
        self.env["MOD_VERSION"] = "1.3.0"
        result = subprocess.run([sys.executable, str(ROOT / ".github/scripts/release.py"), "notes"],
                                cwd=self.root, env=self.env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "release_notes.md").read_text(), "Port notes\n")
        for content in ["## [1.2.0] - 2025-12-01\nOther version\n",
                        "## [1.3.0] - 2026-09-22\n\n"]:
            changelog.write_text(content)
            result = subprocess.run([sys.executable, str(ROOT / ".github/scripts/release.py"), "notes"],
                                    cwd=self.root, env=self.env, text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)

    def test_release_notes_prefer_minecraft_line(self):
        (self.root / "CHANGELOG.md").write_text(
            "## [1.3.0] - 2025-12-25\nOld release\n\n"
            "## [1.3.0+mc26.2] - 2026-09-22\n26.2 port\n")
        self.env.update(MOD_VERSION="1.3.0", ARTIFACT_VERSION="1.3.0+mc26.2")
        result = subprocess.run([sys.executable, str(ROOT / ".github/scripts/release.py"), "notes"],
                                cwd=self.root, env=self.env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "release_notes.md").read_text(), "26.2 port\n")


if __name__ == "__main__":
    unittest.main()
