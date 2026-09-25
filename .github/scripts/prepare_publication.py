"""Check a published GitHub release before downstream publication."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import quote
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))

from bundle_candidate import source_state
from candidate_manifest import client_candidate
from candidate_provenance import verify_provenance
from finalize_candidate import candidate_files
from parity_evidence import EvidenceError, digest, validate_release_identity
import release


TARGET = '26.3-fabric'


def command(arguments):
    try:
        result = subprocess.run(['gh', *arguments], capture_output=True, text=True, timeout=120)
    except subprocess.TimeoutExpired as error:
        raise EvidenceError('GitHub command timed out') from error
    if result.returncode:
        raise EvidenceError('GitHub command failed: ' + result.stderr.strip())
    return result.stdout


def api(endpoint):
    return json.loads(command(['api', endpoint]))


def release_snapshot(value, tag, expected=None):
    if (value.get('tag_name') != tag or value.get('draft') is not False
            or value.get('prerelease') is not ('-' in tag)
            or value.get('immutable') is not True):
        raise EvidenceError('Expected an immutable published release in the selected channel')
    if not isinstance(value.get('body'), str) or not value['body'].strip():
        raise EvidenceError('Published release notes are required')
    assets = {}
    for asset in value['assets']:
        name = asset['name']
        if (name in assets or Path(name).name != name or name in ('.', '..')
                or asset.get('state') != 'uploaded'):
            raise EvidenceError('Duplicate, unsafe, or incomplete release asset')
        assets[name] = asset.get('digest')
    if expected is not None and assets.keys() != expected.keys():
        raise EvidenceError('Published asset names differ from the candidate')
    if expected is not None:
        for name, sha in expected.items():
            if assets[name] not in (None, 'sha256:' + sha):
                raise EvidenceError('Published asset digest differs: ' + name)
    return {'id': value.get('id'), 'body': value['body'], 'assets': assets}


def github_output(record, specification, artifact):
    curseforge = record['curseforge']
    result = {
        'target': TARGET,
        'java': str(specification['java']),
        'artifact': str(artifact),
        'cf_project_id': curseforge['project_id'],
        'cf_game_versions': ','.join(curseforge['version_labels']),
        'cf_display_name': curseforge['display_name'],
        'cf_release_type': curseforge['release_type'],
        'cf_relations': curseforge['relations'],
        'cf_changelog': curseforge['changelog'],
    }
    lines = []
    for key, value in result.items():
        if '\n' in value or '\r' in value:
            marker = 'EOF_' + uuid.uuid4().hex
            lines.extend((key + '<<' + marker, value, marker))
        else:
            lines.append(key + '=' + value)
    return '\n'.join(lines) + '\n'


def prepare(tag, repository, source_root, assets, output, github_output_path, services='both'):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise EvidenceError('Expected repository owner/name')
    validate_release_identity(tag, '0' * 40)
    source_root = Path(source_root).resolve()
    assets = Path(assets).resolve()
    output = Path(output).resolve()
    if assets.exists() or output.exists():
        raise EvidenceError('Use new asset directory and metadata output')
    if output.is_relative_to(assets) or assets.is_relative_to(source_root):
        raise EvidenceError('Keep output and assets outside the source checkout')
    commit, dirty = source_state(source_root)
    validate_release_identity(tag, commit)
    if dirty:
        raise EvidenceError('Use a clean tagged source checkout')
    local_tag = subprocess.check_output(
        ['git', 'rev-parse', '--verify', 'refs/tags/' + tag + '^{commit}'],
        cwd=source_root, text=True).strip()
    if local_tag != commit:
        raise EvidenceError('Source checkout differs from the selected tag')
    endpoint = 'repos/' + repository
    tag_endpoint = endpoint + '/commits/' + quote('refs/tags/' + tag, safe='')
    if api(tag_endpoint).get('sha') != commit:
        raise EvidenceError('Live release tag differs from source checkout')
    release_endpoint = endpoint + '/releases/tags/' + tag
    published = api(release_endpoint)
    snapshot = release_snapshot(published, tag)
    assets.mkdir(parents=True)
    command(['release', 'download', tag, '--repo', repository, '--dir', str(assets), '--pattern', '*'])
    manifest_path = assets / 'candidate.json'
    manifest, files = candidate_files(manifest_path)
    if manifest.get('release') != tag or manifest.get('commit') != commit:
        raise EvidenceError('Published candidate differs from the selected tag or source')
    if manifest.get('selected_targets') != [TARGET]:
        raise EvidenceError('Expected exactly the selected Fabric target')
    _, target, specification = client_candidate(manifest_path, TARGET)
    if specification.get('implemented') is not True:
        raise EvidenceError('Selected target is not implemented')
    release_snapshot(published, tag, files)
    downloaded = {path.name: digest(path) for path in assets.iterdir()
                  if path.is_file() and not path.is_symlink()}
    if downloaded != files or len(list(assets.iterdir())) != len(files):
        raise EvidenceError('Downloaded release assets differ from the candidate')
    verify_provenance(manifest_path, assets / 'provenance.jsonl', repository)
    metadata = release.resolve_candidate_destinations(
        release.candidate_metadata(manifest_path, source_root, snapshot['body']), services)
    if (api(tag_endpoint).get('sha') != commit
            or release_snapshot(api(release_endpoint), tag, files) != snapshot):
        raise EvidenceError('Published release changed during preflight')
    if any(digest(assets / name) != sha for name, sha in files.items()):
        raise EvidenceError('Downloaded assets changed during preflight')
    record, = metadata['records']
    artifact = (assets / target['artifact']['path']).resolve()
    output.write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8')
    with Path(github_output_path).open('a', encoding='utf-8') as stream:
        stream.write(github_output(record, specification, artifact))
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True)
    parser.add_argument('--repo', required=True)
    parser.add_argument('--source-root', type=Path, required=True)
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--github-output', type=Path, required=True)
    parser.add_argument('--services', choices=('both', 'modrinth', 'curseforge'), default='both')
    args = parser.parse_args()
    try:
        prepare(args.release, args.repo, args.source_root, args.assets, args.output,
                args.github_output, args.services)
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
        parser.error(str(error))


if __name__ == '__main__':
    main()
