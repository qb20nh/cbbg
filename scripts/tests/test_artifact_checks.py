from pathlib import Path
import struct
import sys
import tempfile
import unittest
import warnings
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from artifact_checks import ArtifactError, verify_artifact


class ArtifactChecksTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.core = self.root / "core"
        source = self.core / "com/example/Core.java"
        source.parent.mkdir(parents=True)
        source.write_text("package com.example; public class Core {}\n")
        self.source_bytes = source.read_bytes()
        self.jar = self.root / "mod.jar"
        self.sources = self.root / "sources.jar"
        self.classes = {"com/example/Core.class": self.header(52),
                        "com/example/Adapter.class": self.header(61)}
        self.write_jars()

    @staticmethod
    def header(major):
        return struct.pack(">IHH", 0xCAFEBABE, 0, major)

    def write_jars(self, sources=None):
        with zipfile.ZipFile(self.jar, "w") as jar:
            for name, data in self.classes.items():
                jar.writestr(name, data)
        with zipfile.ZipFile(self.sources, "w") as jar:
            for name, data in (sources if sources is not None else
                               {"com/example/Core.java": self.source_bytes}).items():
                jar.writestr(name, data)

    def check(self):
        return verify_artifact(self.jar, self.sources, 17, self.core)

    def test_embedded_core_and_sources_pass(self):
        self.assertEqual({"classes": 2, "core_classes": 1, "core_sources": 1}, self.check())

    def test_corrupt_resource_is_rejected_in_binary_and_sources(self):
        for destination in (self.jar, self.sources):
            self.write_jars()
            name = "assets/cbbg/shaders/example.glsl"
            with zipfile.ZipFile(destination, "a") as archive:
                archive.writestr(name, b"original shader payload", compress_type=zipfile.ZIP_STORED)
                offset = archive.getinfo(name).header_offset
            data = bytearray(destination.read_bytes())
            name_length, extra_length = struct.unpack_from("<HH", data, offset + 26)
            data[offset + 30 + name_length + extra_length] ^= 1
            destination.write_bytes(data)
            with self.subTest(destination=destination.name), self.assertRaisesRegex(
                    ArtifactError, "Corrupt archive entry.*example.glsl"):
                self.check()

    def test_legacy_smoke_drivers_and_fault_injection_are_rejected(self):
        entries = {
            "com/qb20nh/cbbg/smoke/ForgeSmoke.class": self.header(52),
            "com/qb20nh/cbbg/smoke/ForgeSmoke.java": b"test driver source",
            "com/qb20nh/cbbg/mixin/ShaderFailureMixin.class": self.header(52),
            "com/qb20nh/cbbg/mixin/ShaderFailureMixin.java": b"fault injector source",
            "cbbg.forge.smoke.mixins.json": b"{}",
        }
        for name, data in entries.items():
            for destination in (self.jar, self.sources):
                self.write_jars()
                with zipfile.ZipFile(destination, "a") as archive:
                    archive.writestr(name, data)
                with self.subTest(name=name, archive=destination.name), self.assertRaisesRegex(ArtifactError, "Test-only"):
                    self.check()

    def test_newer_adapter_and_non_java8_core_fail(self):
        for name, major in (("com/example/Adapter.class", 65), ("com/example/Core.class", 61)):
            original = dict(self.classes)
            self.classes[name] = self.header(major)
            self.write_jars()
            with self.subTest(name=name), self.assertRaises(ArtifactError):
                self.check()
            self.classes = original

    def test_missing_core_and_changed_sources_fail(self):
        del self.classes["com/example/Core.class"]
        self.write_jars()
        with self.assertRaisesRegex(ArtifactError, "Missing core class"):
            self.check()
        self.classes["com/example/Core.class"] = self.header(52)
        for sources in ({}, {"com/example/Core.java": b"stale"}):
            self.write_jars(sources)
            with self.assertRaisesRegex(ArtifactError, "core source"):
                self.check()

    def test_test_code_resources_and_preview_classes_fail(self):
        for name, data in (("com/qb20nh/cbbg/parity/Driver.class", self.header(61)),
                           ("cbbg.parity.mixins.json", b"{}"),
                           ("com/example/Adapter.class", struct.pack(">IHH", 0xCAFEBABE, 65535, 61)),
                           ("com/example/Adapter.class", b"not a class")):
            original = dict(self.classes)
            self.classes[name] = data
            self.write_jars()
            with self.subTest(name=name), self.assertRaises(ArtifactError):
                self.check()
            self.classes = original

    def test_duplicate_entries_and_binary_sources_fail(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.jar, "a") as jar:
                jar.writestr("com/example/Core.class", self.header(52))
        with self.assertRaisesRegex(ArtifactError, "Duplicate"):
            self.check()
        self.write_jars({"com/example/Core.java": self.source_bytes,
                         "com/example/Core.class": self.header(52)})
        with self.assertRaisesRegex(ArtifactError, "Binary class"):
            self.check()

    def test_parity_refmap_is_rejected_in_binary_and_source_archives(self):
        for destination in (self.jar, self.sources):
            self.write_jars()
            with zipfile.ZipFile(destination, "a") as jar:
                jar.writestr("cbbg.parity.refmap.json", b"{}")
            with self.subTest(destination=destination.name), self.assertRaisesRegex(
                    ArtifactError, "Test-only entry"):
                self.check()


if __name__ == "__main__":
    unittest.main()
