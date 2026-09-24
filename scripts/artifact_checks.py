#!/usr/bin/env python3
"""Check production bytecode, embedded core and source packaging without launching Minecraft."""

import argparse
from pathlib import Path
import struct
import zipfile

from targets import load_catalog, select_artifacts

ROOT = Path(__file__).resolve().parents[1]
TEST_PREFIXES = ("com/qb20nh/cbbg/parity/", "com/qb20nh/cbbg/gametest/",
                 "com/qb20nh/cbbg/smoke/",
                 "cbbg-iris-fixture/")
TEST_RESOURCES = {"cbbg.parity.mixins.json", "cbbg.parity.refmap.json",
                  "cbbg.scenario-progress.mixins.json",
                  "cbbg.forge.smoke.mixins.json",
                  "com/qb20nh/cbbg/mixin/ShaderFailureMixin.class",
                  "com/qb20nh/cbbg/mixin/ShaderFailureMixin.java",
                  "assets/cbbg/shaders/post/satin_parity.json"}


class ArtifactError(ValueError):
    pass


def production_entries(archive):
    names = archive.namelist()
    if len(names) != len(set(names)):
        raise ArtifactError("Duplicate archive entry")
    for name in names:
        if name.startswith(TEST_PREFIXES) or name in TEST_RESOURCES:
            raise ArtifactError("Test-only entry in production archive: " + name)
    corrupt = archive.testzip()
    if corrupt is not None:
        raise ArtifactError("Corrupt archive entry: " + corrupt)
    return names


def verify_artifact(artifact, sources, java, core_sources=ROOT / "core/src/main/java"):
    if type(java) is not int or java < 8:
        raise ArtifactError("Invalid target Java baseline")
    core_sources = Path(core_sources)
    expected = {path.relative_to(core_sources).as_posix(): path.read_bytes()
                for path in core_sources.rglob("*.java")
                if path.name not in ("package-info.java", "module-info.java")}
    if not expected:
        raise ArtifactError("No core sources to verify")
    core_names = {name[:-5] for name in expected}
    classes = set()
    core_classes = 0
    with zipfile.ZipFile(artifact) as archive:
        for name in production_entries(archive):
            if not name.endswith(".class"):
                continue
            with archive.open(name) as stream:
                header = stream.read(8)
            if len(header) != 8:
                raise ArtifactError("Truncated class header: " + name)
            magic, minor, major = struct.unpack(">IHH", header)
            if magic != 0xCAFEBABE or major < 45 or minor == 65535:
                raise ArtifactError("Invalid or preview class header: " + name)
            if major > java + 44:
                raise ArtifactError("Class exceeds target Java baseline: " + name)
            classes.add(name)
            if name[:-6].split("$", 1)[0] in core_names:
                if major != 52:
                    raise ArtifactError("Core class is not Java 8: " + name)
                core_classes += 1
        for name in core_names:
            if name + ".class" not in classes:
                raise ArtifactError("Missing core class: " + name)
    with zipfile.ZipFile(sources) as archive:
        names = set(production_entries(archive))
        if any(name.endswith(".class") for name in names):
            raise ArtifactError("Binary class in source archive")
        for name, content in expected.items():
            if name not in names or archive.read(name) != content:
                raise ArtifactError("Missing or changed core source: " + name)
    return {"classes": len(classes), "core_classes": core_classes, "core_sources": len(expected)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--sources", type=Path, required=True)
    args = parser.parse_args()
    try:
        targets = select_artifacts(load_catalog(), args.target)
        if len(targets) != 1:
            raise ArtifactError("Select exactly one artifact owner")
        result = verify_artifact(args.artifact, args.sources, targets[0]["java"])
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        parser.error(str(error))
    print("Verified production packaging: " + str(result))


if __name__ == "__main__":
    main()
