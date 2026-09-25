"""Read CurseForge file metadata and compare a downloaded file with a local artifact."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import urllib.parse
import urllib.request

import release

API = 'https://api.curseforge.com/v1'


def positive_id(value):
    if type(value) is not int or value <= 0:
        raise ValueError('Expected a positive CurseForge ID')
    return value


def project_files(project, fetch):
    positive_id(project)
    files = []
    identifiers = set()
    total = None
    while True:
        index = len(files)
        page_size = min(50, 10000 - index)
        response = fetch(f'{API}/mods/{project}/files?index={index}&pageSize={page_size}')
        page = response['data']
        pagination = response['pagination']
        if (not isinstance(page, list) or any(type(pagination.get(key)) is not int for key in
                ('index', 'pageSize', 'resultCount', 'totalCount')) or
                pagination['index'] != index or not 0 < pagination['pageSize'] <= 50 or
                pagination['resultCount'] != len(page) or len(page) > pagination['pageSize'] or
                pagination['totalCount'] < index + len(page) or pagination['totalCount'] > 10000):
            raise ValueError('Incomplete or invalid CurseForge pagination')
        if total is not None and pagination['totalCount'] != total:
            raise ValueError('CurseForge file count changed during listing')
        total = pagination['totalCount']
        for item in page:
            identifier = positive_id(item['id'])
            if item.get('modId') != project or identifier in identifiers:
                raise ValueError('Wrong project or duplicate CurseForge file')
            identifiers.add(identifier)
            files.append(item)
        if len(files) == total:
            return files
        if not page:
            raise ValueError('CurseForge listing ended before all files were read')


def download_sha256(url, size):
    if not isinstance(url, str) or urllib.parse.urlsplit(url).scheme != 'https':
        raise ValueError('Expected an HTTPS CurseForge download URL')
    checksum = hashlib.sha256()
    length = 0
    # The file host receives no API credentials.
    request = urllib.request.Request(url, headers={'User-Agent': 'cbbg-release-tools'})
    with urllib.request.urlopen(request, timeout=30) as response:
        while data := response.read(min(1024 * 1024, size - length + 1)):
            length += len(data)
            if length > size:
                raise ValueError('Downloaded CurseForge file is larger than the artifact')
            checksum.update(data)
    if length != size:
        raise ValueError('Downloaded CurseForge file is shorter than the artifact')
    return checksum.hexdigest()


def file_snapshot(project, file_id, artifact, fetch, download=download_sha256):
    positive_id(project)
    positive_id(file_id)
    artifact = Path(artifact)
    with artifact.open('rb') as stream:
        expected_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
    size = artifact.stat().st_size
    endpoint = f'{API}/mods/{project}/files/{file_id}'
    metadata = fetch(endpoint)['data']
    if metadata.get('id') != file_id or metadata.get('modId') != project:
        raise ValueError('CurseForge returned a different file or project')
    if metadata.get('isAvailable') is not True or metadata.get('fileStatus') not in (4, 10):
        raise ValueError('CurseForge file is not approved and available')
    if metadata.get('fileName') != artifact.name or metadata.get('fileLength') != size:
        raise ValueError('CurseForge filename or size differs from the artifact')
    changelog = fetch(endpoint + '/changelog')['data']
    if not isinstance(changelog, str):
        raise ValueError('Invalid CurseForge changelog')
    if download(metadata.get('downloadUrl'), size) != expected_hash:
        raise ValueError('CurseForge file content differs from the artifact')
    if fetch(endpoint)['data'] != metadata or fetch(endpoint + '/changelog')['data'] != changelog:
        raise ValueError('CurseForge metadata changed during verification')
    with artifact.open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != expected_hash:
            raise ValueError('Local artifact changed during verification')
    return {'schema': 1, 'project_id': project, 'file_id': file_id, 'sha256': expected_hash,
            'metadata': metadata, 'changelog': changelog,
            'api_url': endpoint}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=int, required=True)
    parser.add_argument('--file', type=int)
    parser.add_argument('--artifact', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if (args.file is None) != (args.artifact is None):
        parser.error('--file and --artifact must be supplied together')
    try:
        key = os.environ.get('CURSEFORGE_API_KEY')
        if not key:
            raise ValueError('Set CURSEFORGE_API_KEY for read access; CF_API_TOKEN is the upload token')
        fetch = lambda url: release.get_json(url, {'x-api-key': key})
        if args.file is None:
            result = {'schema': 1, 'project_id': args.project, 'files': project_files(args.project, fetch)}
        else:
            result = file_snapshot(args.project, args.file, args.artifact, fetch)
        with args.output.open('x', encoding='utf-8') as stream:
            stream.write(json.dumps(result, indent=2) + '\n')
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.error(str(error))
    print('Saved CurseForge file data: ' + str(args.output))


if __name__ == '__main__':
    main()
