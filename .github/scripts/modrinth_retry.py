"""Compare a candidate with Modrinth before uploading or recording a completed upload."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import quote

import release

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from parity_evidence import checked_file, digest, read_json

API = 'https://api.modrinth.com/v2'


def plan_upload(candidate, metadata_path, target, source_root, fetch=release.get_json):
    candidate = Path(candidate)
    metadata_hash = digest(metadata_path)
    metadata = read_json(metadata_path)
    records = [record for record in metadata['records'] if record['targets'] == [target]]
    if len(records) != 1:
        raise ValueError('Expected one publishing record for the selected target')
    record = records[0]
    expected = release.candidate_metadata(candidate, source_root, record['modrinth']['changelog'])
    expected_records = [item for item in expected['records'] if item['targets'] == [target]]
    if (len(expected_records) != 1 or any(metadata.get(key) != expected[key] for key in
            ('schema', 'release', 'source_commit', 'manifest_sha256')) or
            any(record.get(key) != expected_records[0][key] for key in
                ('targets', 'artifact', 'sources', 'modrinth'))):
        raise ValueError('Publishing metadata differs from the checked candidate')
    upload = record['modrinth']
    project = fetch(API + '/project/' + quote(upload['project_id'], safe=''))
    if project.get('id') != upload['project_id'] or not re.fullmatch(r'[\w-]+', project.get('slug', '')):
        raise ValueError('Unexpected Modrinth project')
    versions = fetch(API + '/project/' + quote(project['id'], safe='') + '/version')
    if not isinstance(versions, list) or any(not isinstance(item, dict) or
            not isinstance(item.get('version_number'), str) for item in versions):
        raise ValueError('Invalid Modrinth version list')
    matches = [item for item in versions if item['version_number'] == upload['version_number']]
    if len(matches) > 1:
        raise ValueError('Multiple Modrinth versions use the requested version number')
    result = {'schema': 1, 'service': 'modrinth', 'target': target,
              'manifest_sha256': expected['manifest_sha256'], 'metadata_sha256': metadata_hash,
              'project_id': project['id'], 'version_number': upload['version_number'],
              'artifact': record['artifact'], 'sources': record['sources'], 'action': 'upload'}
    if matches:
        existing = matches[0]
        fields = {'project_id': project['id'], 'name': upload['version_name'],
                  'version_number': upload['version_number'], 'version_type': upload['version_type'],
                  'changelog': upload['changelog'], 'status': 'listed'}
        if any(existing.get(key) != value for key, value in fields.items()):
            raise ValueError('Existing Modrinth version has different metadata')
        for key, wanted in [('loaders', upload['loaders']), ('game_versions', upload['game_versions'])]:
            actual = existing.get(key)
            if not isinstance(actual, list) or sorted(actual) != sorted(wanted):
                raise ValueError('Existing Modrinth version has different ' + key)
        dependencies = []
        for identifier in upload['required_projects']:
            dependency = fetch(API + '/project/' + quote(identifier, safe=''))
            dependency_id = dependency.get('id')
            if not isinstance(dependency_id, str) or not re.fullmatch(r'[A-Za-z0-9]+', dependency_id):
                raise ValueError('Invalid Modrinth dependency project')
            dependencies.append((dependency_id, None, None, 'required'))
        actual = existing.get('dependencies')
        if not isinstance(actual, list) or any(not isinstance(item, dict) for item in actual):
            raise ValueError('Invalid Modrinth dependencies')
        actual = [(item.get('project_id'), item.get('version_id'), item.get('file_name'),
                   item.get('dependency_type')) for item in actual]
        if len(actual) != len(dependencies) or set(actual) != set(dependencies):
            raise ValueError('Existing Modrinth version has different dependencies')
        files = existing.get('files')
        if not isinstance(files, list) or len(files) != 2:
            raise ValueError('Existing Modrinth version has different files')
        for kind in ('artifact', 'sources'):
            path = checked_file(candidate.parent, record[kind])
            matching = [item for item in files if isinstance(item, dict) and item.get('filename') == path.name]
            if len(matching) != 1:
                raise ValueError('Existing Modrinth version has different filenames')
            with path.open('rb') as stream:
                sha512 = hashlib.file_digest(stream, 'sha512').hexdigest()
            item = matching[0]
            if (item.get('hashes', {}).get('sha512') != sha512 or
                    item.get('primary') is not (kind == 'artifact') or
                    item.get('file_type') != ('sources-jar' if kind == 'sources' else None)):
                raise ValueError('Existing Modrinth file differs from candidate: ' + kind)
        identifier = existing.get('id')
        if not isinstance(identifier, str) or not re.fullmatch(r'[A-Za-z0-9]+', identifier):
            raise ValueError('Invalid Modrinth version ID')
        result.update(action='reuse', version_id=identifier,
                      url='https://modrinth.com/mod/' + project['slug'] + '/version/' + identifier)
    for kind in ('artifact', 'sources'):
        checked_file(candidate.parent, record[kind])
    if digest(candidate) != expected['manifest_sha256'] or digest(metadata_path) != metadata_hash:
        raise ValueError('Candidate or publishing metadata changed during the Modrinth check')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('candidate', 'metadata', 'source-root', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--target', required=True)
    parser.add_argument('--require-existing', action='store_true')
    args = parser.parse_args()
    try:
        if (args.output.resolve().is_relative_to(args.candidate.parent.resolve()) or
                args.output.resolve() == args.metadata.resolve()):
            raise ValueError('Write retry results outside the candidate bundle and metadata file')
        token = os.environ.get('MODRINTH_TOKEN')
        headers = {'Authorization': token} if token else {}
        result = plan_upload(args.candidate, args.metadata, args.target, args.source_root,
                             fetch=lambda url: release.get_json(url, headers))
        if args.require_existing and result['action'] != 'reuse':
            raise ValueError('Uploaded Modrinth version was not found')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.error(str(error))
    print('Modrinth retry plan: ' + result['action'] + ' ' + result['version_number'])


if __name__ == '__main__':
    main()
