"""Verify a release draft locally; publish only with --publish after approval."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import quote
import zipfile

from bundle_candidate import source_state
from candidate_manifest import client_candidate
from parity_evidence import EvidenceError, checked_file, digest, read_json
from verify_candidate import result_selection, verify_candidate


def command(arguments):
    try:
        result = subprocess.run(['gh', *arguments], capture_output=True, text=True, timeout=120)
    except subprocess.TimeoutExpired as error:
        raise EvidenceError('GitHub command timed out; check remote state before retrying') from error
    if result.returncode:
        raise EvidenceError('GitHub command failed: ' + result.stderr.strip())
    return result.stdout


def api(endpoint):
    return json.loads(command(['api', endpoint]))


def candidate_files(manifest_path):
    manifest = read_json(manifest_path)
    base = manifest_path.parent
    files = {'candidate.json': digest(manifest_path)}
    for identifier in manifest['selected_targets']:
        _, target, specification = client_candidate(manifest_path, identifier)
        if specification.get('implemented') is not True:
            raise EvidenceError('Target is not marked implemented: ' + identifier)
        tests = target['client_tests']
        references = [target[key] for key in ('artifact', 'sources', 'source_inventory')]
        references += [tests[key] for key in
                       ('catalog', 'contract', 'ordinary_metadata', 'runtime_lock', 'dependency_lock')]
        references += list(tests['drivers'].values())
        for reference in references:
            path = checked_file(base, reference)
            if reference['path'] != path.name or path.parent != base.resolve():
                raise EvidenceError('Release assets must use flat filenames')
            previous = files.setdefault(path.name, reference['sha256'])
            if previous != reference['sha256']:
                raise EvidenceError('Conflicting release asset: ' + path.name)
    expected_sums = ''.join(value + '  ' + name + '\n' for name, value in sorted(files.items()))
    if (base / 'SHA256SUMS').read_text(encoding='utf-8') != expected_sums:
        raise EvidenceError('SHA256SUMS differs from candidate files')
    for name in ('SHA256SUMS', 'provenance.jsonl'):
        files[name] = digest(base / name)
    return manifest, files


def draft_snapshot(release, expected_tag, expected_files):
    if (release.get('tag_name') != expected_tag or release.get('draft') is not True
            or release.get('prerelease') is not ('-' in expected_tag)):
        raise EvidenceError('Expected the selected draft and release channel')
    if type(release.get('id')) is not int or release['id'] <= 0:
        raise EvidenceError('Missing GitHub release ID')
    assets = {}
    for asset in release['assets']:
        name = asset['name']
        if name in assets or asset.get('state') != 'uploaded':
            raise EvidenceError('Duplicate or incomplete draft asset')
        assets[name] = {key: asset.get(key) for key in ('id', 'size', 'digest', 'updated_at')}
    if assets.keys() != expected_files.keys():
        raise EvidenceError('Draft asset names differ from the candidate')
    return {'id': release['id'], 'tag': expected_tag, 'assets': assets,
            'name': release.get('name'), 'body': release.get('body'),
            'prerelease': release['prerelease']}


def finalize(manifest_path, results, source_root, repository, release_tag, notes_path,
             *, publish=False, output_path=None):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise EvidenceError('Expected repository owner/name')
    manifest_path = Path(manifest_path).resolve()
    if manifest_path.name != 'candidate.json':
        raise EvidenceError('Expected candidate.json from the release bundle')
    manifest, files = candidate_files(manifest_path)
    if manifest['release'] != release_tag:
        raise EvidenceError('Requested release differs from the candidate')
    if source_state(source_root) != (manifest['commit'], False):
        raise EvidenceError('Use a clean source checkout at the candidate commit')
    notes = Path(notes_path).read_text(encoding='utf-8')
    if not notes.strip():
        raise EvidenceError('Release notes are required')
    validation = verify_candidate(manifest_path, results,
                                  manifest_path.parent / 'provenance.jsonl', source_root, repository)
    endpoint = 'repos/' + repository
    tag_endpoint = endpoint + '/commits/' + quote('refs/tags/' + release_tag, safe='')
    if api(tag_endpoint).get('sha') != manifest['commit']:
        raise EvidenceError('Release tag differs from the candidate commit')
    if api(endpoint + '/immutable-releases').get('enabled') is not True:
        raise EvidenceError('Enable immutable releases before finalization')
    release_endpoint = endpoint + '/releases/tags/' + release_tag
    snapshot = draft_snapshot(api(release_endpoint), release_tag, files)
    with tempfile.TemporaryDirectory(prefix='cbbg-finalize-') as directory:
        destination = Path(directory)
        command(['release', 'download', release_tag, '--repo', repository,
                 '--dir', str(destination), '--pattern', '*'])
        downloaded = {path.name: digest(path) for path in destination.iterdir() if path.is_file()}
        if downloaded != files:
            raise EvidenceError('Downloaded draft files differ from the verified candidate')
    if draft_snapshot(api(release_endpoint), release_tag, files) != snapshot:
        raise EvidenceError('Draft changed during finalization checks')
    if any(digest(manifest_path.parent / name) != value for name, value in files.items()):
        raise EvidenceError('Local candidate changed during finalization checks')
    if api(tag_endpoint).get('sha') != manifest['commit']:
        raise EvidenceError('Release tag changed during finalization checks')
    report = {'release': release_tag, 'repository': repository,
              'source_commit': manifest['commit'], 'manifest_sha256': files['candidate.json'],
              'files': files, 'draft': snapshot, 'validation': validation,
              'notes': notes, 'published': False}
    if output_path is not None:
        output_path = Path(output_path)
        if output_path.resolve().is_relative_to(manifest_path.parent):
            raise EvidenceError('Keep the local report outside the candidate directory')
        with output_path.open('x', encoding='utf-8') as output:
            output.write(json.dumps(report, indent=2) + '\n')
    if publish:
        # The caller must obtain approval before invoking this branch.
        if api(endpoint + '/immutable-releases').get('enabled') is not True:
            raise EvidenceError('Immutable releases were disabled before publication')
        if api(tag_endpoint).get('sha') != manifest['commit']:
            raise EvidenceError('Release tag changed before publication')
        report['published'] = None
        if output_path is not None:
            output_path.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        with tempfile.TemporaryDirectory(prefix='cbbg-release-request-') as directory:
            request = Path(directory) / 'request.json'
            request.write_text(json.dumps({'draft': False, 'body': notes}), encoding='utf-8')
            try:
                command(['api', '--method', 'PATCH', endpoint + '/releases/' + str(snapshot['id']),
                         '--input', str(request)])
            except EvidenceError as error:
                raise EvidenceError('Publication outcome needs manual inspection; '
                                    'do not automatically retry: ' + str(error)) from error
        published = api(release_endpoint)
        if (published.get('id') != snapshot['id'] or published.get('draft') is not False
                or published.get('immutable') is not True or published.get('body') != notes
                or api(tag_endpoint).get('sha') != manifest['commit']
                or draft_snapshot({**published, 'draft': True}, release_tag, files)
                   != {**snapshot, 'body': notes}):
            raise EvidenceError('Publication outcome needs manual inspection; do not automatically retry')
        report.update(published=True, url=published['html_url'])
        if output_path is not None:
            output_path.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--results', action='append', required=True, metavar='TARGET=INDEX')
    parser.add_argument('--source-root', type=Path, required=True)
    parser.add_argument('--repo', required=True)
    parser.add_argument('--release', required=True)
    parser.add_argument('--notes', type=Path, required=True)
    parser.add_argument('--publish', action='store_true', help='Publish after explicit user approval')
    parser.add_argument('--output', type=Path, required=True, help='New local report, outside the candidate')
    args = parser.parse_args()
    try:
        if args.output.exists() or args.output.resolve().is_relative_to(args.candidate.resolve().parent):
            raise EvidenceError('Use a new output file outside the candidate directory')
        report = finalize(args.candidate, result_selection(args.results), args.source_root,
                          args.repo, args.release, args.notes, publish=args.publish,
                          output_path=args.output)
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        parser.error(str(error))
    print(('Published release; local report: ' if report['published'] else 'Checked draft; local report: ')
          + str(args.output))


if __name__ == '__main__':
    main()
