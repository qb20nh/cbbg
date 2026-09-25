"""Release validation and changelog extraction. Run from the release checkout."""
import argparse
import json
import os
from pathlib import Path
import re
import struct
import sys
import urllib.request
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))


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
        corrupt_entry = jar.testzip()
        if corrupt_entry is not None:
            raise SystemExit(f"Corrupt release JAR entry: {corrupt_entry}")
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


def curseforge_version_ids(mc, labels, versions, types):
    ids = []
    for label in labels:
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
                        if t.get("slug", "").startswith("minecraft-")
                        or re.match(r"^Minecraft(?:\s|$)", t.get("name", ""))}
            matches = [v for v in matches if v["gameVersionTypeID"] in type_ids]
        if len(matches) != 1:
            candidates = [v for v in versions if name in (v.get("name"), v.get("slug"))]
            candidate_types = {v.get("gameVersionTypeID") for v in candidates}
            print("Matching version entries: " + json.dumps(candidates))
            print("Matching version types: " + json.dumps(
                [t for t in types if t.get("id") in candidate_types]))
            raise SystemExit(f"CurseForge label {label!r} resolved to {len(matches)} entries; refusing partial metadata")
        ids.append(str(matches[0]["id"]))
    return ids


def validate_destinations():
    mc = os.environ["MINECRAFT_VERSION"]
    modrinth = get_json("https://api.modrinth.com/v2/tag/game_version")
    if mc not in {v["version"] for v in modrinth}:
        raise SystemExit(f"Modrinth does not recognize Minecraft {mc}")
    headers = {"X-Api-Token": os.environ["CF_API_TOKEN"]}
    versions = get_json("https://minecraft.curseforge.com/api/game/versions", headers)
    types = get_json("https://minecraft.curseforge.com/api/game/version-types", headers)
    print("CurseForge authenticated GET requests succeeded; upload permission is not tested")
    ids = curseforge_version_ids(mc, os.environ["CURSEFORGE_GAME_VERSIONS"].split(","), versions, types)
    with open(os.environ["GITHUB_ENV"], "a") as out:
        out.write("CURSEFORGE_GAME_VERSIONS=" + ",".join(ids) + "\n")
    print("All destination version labels resolved")


def candidate_metadata(candidate, source_root, notes):
    # Historical release checkouts need only the commands above.
    from candidate_manifest import client_candidate
    from fabric_package import verify_candidate
    from parity_evidence import digest, read_json

    candidate = Path(candidate)
    source_root = Path(source_root)
    if not isinstance(notes, str) or not notes.strip():
        raise ValueError('Release notes are empty')
    manifest_hash = digest(candidate)
    manifest = read_json(candidate)
    if not isinstance(manifest.get('selected_targets'), list) or not manifest['selected_targets']:
        raise ValueError('Candidate requires an explicit target selection')
    properties = (source_root / 'gradle.properties').read_text()

    def project_id(key, pattern):
        values = re.findall(r'^' + key + r'=([^\r\n]+)', properties, re.MULTILINE)
        if len(values) != 1 or not re.fullmatch(pattern, values[0]):
            raise ValueError('Invalid publishing project: ' + key)
        return values[0]

    modrinth = project_id('modrinth_project_id', r'[A-Za-z0-9]+')
    curseforge = project_id('curseforge_project_id', r'[1-9][0-9]*')
    records = []
    for identifier in manifest['selected_targets']:
        current, target, specification = client_candidate(candidate, identifier)
        if current != manifest:
            raise ValueError('Candidate changed during metadata generation')
        verify_candidate(candidate, identifier, source_root)
        # Add other loaders when their package checks and release paths are ready.
        if specification['loader'] != 'fabric':
            raise ValueError('Publishing metadata is not configured for ' + identifier)
        minecraft = specification['minecraft']
        version = manifest['release'][1:] + '+mc' + minecraft + '-fabric'
        channel = 'beta' if '-' in manifest['release'] else 'release'
        records.append({
            'targets': [identifier], 'artifact': target['artifact'], 'sources': target['sources'],
            'modrinth': {'project_id': modrinth, 'version_number': version,
                         'version_name': 'cbbg ' + version, 'version_type': channel,
                         'game_versions': [minecraft], 'loaders': [specification['loader']],
                         'required_projects': ['fabric-api'], 'changelog': notes},
            'curseforge': {'project_id': curseforge, 'display_name': 'cbbg ' + version,
                           'release_type': channel,
                           'version_labels': [minecraft, 'Java ' + str(specification['java']),
                                              'Fabric', 'Environment:Client'],
                           'relations': 'fabric-api:requiredDependency',
                           'changelog': notes, 'changelog_type': 'markdown'}})
    if digest(candidate) != manifest_hash:
        raise ValueError('Candidate changed during metadata generation')
    return {'schema': 1, 'release': manifest['release'], 'source_commit': manifest['commit'],
            'manifest_sha256': manifest_hash, 'records': records}


def checked_publication_record(candidate, metadata_path, target, source_root):
    from parity_evidence import digest, read_json

    metadata_hash = digest(metadata_path)
    metadata = read_json(metadata_path)
    records = [record for record in metadata['records'] if record['targets'] == [target]]
    if len(records) != 1:
        raise ValueError('Expected one publishing record for the selected target')
    record = records[0]
    expected = candidate_metadata(candidate, source_root, record['modrinth']['changelog'])
    expected_records = [item for item in expected['records'] if item['targets'] == [target]]
    if (len(expected_records) != 1 or any(metadata.get(key) != expected[key] for key in
            ('schema', 'release', 'source_commit', 'manifest_sha256')) or
            any(record.get(key) != expected_records[0][key] for key in
                ('targets', 'artifact', 'sources', 'modrinth'))):
        raise ValueError('Publishing metadata differs from the checked candidate')
    if 'curseforge' in expected_records[0]:
        curseforge = dict(record['curseforge'])
        curseforge.pop('game_versions', None)
        if curseforge != expected_records[0]['curseforge']:
            raise ValueError('CurseForge metadata differs from the checked candidate')
    if digest(metadata_path) != metadata_hash:
        raise ValueError('Publishing metadata changed during validation')
    return expected, record, metadata_hash


def resolve_candidate_destinations(metadata):
    modrinth_versions = get_json('https://api.modrinth.com/v2/tag/game_version')
    modrinth_loaders = get_json('https://api.modrinth.com/v2/tag/loader')
    headers = {'X-Api-Token': os.environ['CF_API_TOKEN']}
    versions = get_json('https://minecraft.curseforge.com/api/game/versions', headers)
    types = get_json('https://minecraft.curseforge.com/api/game/version-types', headers)
    resolved = json.loads(json.dumps(metadata))
    for record in resolved['records']:
        modrinth = record['modrinth']
        if not set(modrinth['game_versions']) <= {item['version'] for item in modrinth_versions}:
            raise ValueError('Modrinth does not recognize the selected Minecraft versions')
        if not set(modrinth['loaders']) <= {item['name'] for item in modrinth_loaders}:
            raise ValueError('Modrinth does not recognize the selected loaders')
        curseforge = record['curseforge']
        curseforge['game_versions'] = curseforge_version_ids(
            modrinth['game_versions'][0], curseforge['version_labels'], versions, types)
    return resolved


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
    parser.add_argument("command", choices=[*commands, 'candidate'])
    parser.add_argument('--candidate', type=Path)
    parser.add_argument('--source-root', type=Path)
    parser.add_argument('--notes', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--resolve-destinations', action='store_true')
    args = parser.parse_args()
    if args.command == 'candidate':
        if any(value is None for value in (args.candidate, args.source_root, args.notes, args.output)):
            parser.error('Candidate metadata requires --candidate, --source-root, --notes and --output')
        try:
            result = candidate_metadata(args.candidate, args.source_root, args.notes.read_text())
            if args.resolve_destinations:
                result = resolve_candidate_destinations(result)
            with args.output.open('x', encoding='utf-8') as stream:
                stream.write(json.dumps(result, indent=2) + '\n')
        except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile) as error:
            parser.error(str(error))
        print('Created publishing metadata: ' + str(args.output))
        return
    commands[args.command]()


if __name__ == "__main__":
    main()
