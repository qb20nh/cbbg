"""Release validation and changelog extraction. Run from the release checkout."""
import argparse
import json
import os
from pathlib import Path
import re
import struct
import urllib.request
import zipfile


def read_java():
    requirement = json.loads(Path("src/main/resources/fabric.mod.json").read_text())["depends"]["java"]
    match = re.fullmatch(r">=\s*(\d+)", requirement)
    if not match:
        raise SystemExit(f"Unsupported Java requirement: {requirement!r}")
    version = match[1]
    with open(os.environ["GITHUB_OUTPUT"], "a") as out:
        out.write(f"version={version}\n")
    with open(os.environ["GITHUB_ENV"], "a") as out:
        out.write(f"JAVA_VERSION={version}\n")


def validate_artifacts():
    source = json.loads(Path("src/main/resources/fabric.mod.json").read_text())
    with zipfile.ZipFile(os.environ["PUBLISH_JAR"]) as jar:
        metadata = json.loads(jar.read("fabric.mod.json"))
        expected = {
            "id": source["id"],
            "version": os.environ["ARTIFACT_VERSION"],
            "environment": "client",
        }
        for key, value in expected.items():
            if metadata.get(key) != value:
                raise SystemExit(f"Packaged {key} does not match release: {metadata.get(key)!r}")
        depends = metadata["depends"]
        if depends.get("minecraft") != "~" + os.environ["MINECRAFT_VERSION"]:
            raise SystemExit("Packaged Minecraft requirement does not match release")
        for key in ("java", "fabricloader", "fabric-api"):
            if key not in depends or depends[key] != source["depends"].get(key):
                raise SystemExit(f"Packaged {key} requirement does not match release source")
        max_major = int(os.environ["JAVA_VERSION"]) + 44
        for name in jar.namelist():
            if name.endswith(".class") and not name.startswith("META-INF/versions/"):
                major = struct.unpack(">H", jar.read(name)[6:8])[0]
                if major > max_major:
                    raise SystemExit(f"{name} requires a newer Java than the declared minimum")
    with zipfile.ZipFile(os.environ["PUBLISH_SOURCES_JAR"]) as sources:
        if sources.testzip() is not None:
            raise SystemExit("Corrupt sources JAR")
    print("Release metadata and class-file Java requirements verified")


def get_json(url, headers=None):
    request = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def validate_destinations():
    mc = os.environ["MINECRAFT_VERSION"]
    modrinth = get_json("https://api.modrinth.com/v2/tag/game_version")
    if mc not in {v["version"] for v in modrinth}:
        raise SystemExit(f"Modrinth does not recognize Minecraft {mc}")
    headers = {"X-Api-Token": os.environ["CF_API_TOKEN"]}
    versions = get_json("https://minecraft.curseforge.com/api/game/versions", headers)
    types = get_json("https://minecraft.curseforge.com/api/game/version-types", headers)
    print("CurseForge authenticated GET requests succeeded; upload permission is not tested")
    ids = []
    for label in os.environ["CURSEFORGE_GAME_VERSIONS"].split(","):
        type_name, sep, name = label.partition(":")
        if not sep:
            name = type_name
        matches = [v for v in versions if name in (v.get("name"), v.get("slug"))]
        if sep:
            type_ids = {t["id"] for t in types if type_name in (t.get("name"), t.get("slug"))}
            matches = [v for v in matches if v["gameVersionTypeID"] in type_ids]
        elif name == mc:
            # Mod loader version groups can reuse Minecraft version names.
            type_ids = {t["id"] for t in types
                        if re.match(r"^Minecraft(?:\s|$)", t.get("name", ""))}
            matches = [v for v in matches if v["gameVersionTypeID"] in type_ids]
        if len(matches) != 1:
            raise SystemExit(f"CurseForge label {label!r} resolved to {len(matches)} entries; refusing partial metadata")
        ids.append(str(matches[0]["id"]))
    with open(os.environ["GITHUB_ENV"], "a") as out:
        out.write("CURSEFORGE_GAME_VERSIONS=" + ",".join(ids) + "\n")
    print("All destination version labels resolved")


def extract_notes():
    tag = os.environ["MOD_VERSION"]
    changelog = Path("CHANGELOG.md")
    if not changelog.exists():
        raise SystemExit("CHANGELOG.md not found")
    lines = changelog.read_text(encoding="utf-8").splitlines()
    start = None
    # Prefer notes for this Minecraft line; retain legacy version-only sections.
    for version in dict.fromkeys([os.environ.get("ARTIFACT_VERSION", tag), tag]):
        header = re.compile(rf"^## \[{re.escape(version)}\]\s+-\s+\d{{4}}-\d{{2}}-\d{{2}}\s*$")
        start = next((i for i, line in enumerate(lines) if header.match(line)), None)
        if start is not None:
            break
    if start is None:
        raise SystemExit(f"CHANGELOG.md is missing a dated section header for version {tag}")
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## [")), len(lines))
    body = "\n".join(lines[start + 1:end]).strip()
    if not body:
        raise SystemExit(f"CHANGELOG.md section for {tag} is empty")
    Path("release_notes.md").write_text(body + "\n", encoding="utf-8")


def main():
    commands = {"java": read_java, "artifacts": validate_artifacts,
                "destinations": validate_destinations, "notes": extract_notes}
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=commands)
    args = parser.parse_args()
    commands[args.command]()


if __name__ == "__main__":
    main()
