"""Verify candidate build attestations with GitHub CLI."""

import argparse
import json
from pathlib import Path
import re
import subprocess

from candidate_manifest import client_candidate
from parity_evidence import EvidenceError, checked_file, digest, read_json


def verify_provenance(manifest_path, bundle_path, repository, *, run=subprocess.run):
    if not isinstance(repository, str) or not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise EvidenceError('Expected repository owner/name')
    manifest_path = Path(manifest_path).resolve()
    bundle_path = Path(bundle_path).resolve()
    if not bundle_path.is_file():
        raise EvidenceError('Missing attestation bundle')
    manifest_hash = digest(manifest_path)
    bundle_hash = digest(bundle_path)
    manifest = read_json(manifest_path)
    selected = manifest.get('selected_targets')
    if not isinstance(selected, list) or not selected:
        raise EvidenceError('Candidate needs an explicit target selection')
    subjects = {manifest_hash: manifest_path}
    files = {manifest_path: manifest_hash}
    for target_id in selected:
        _, target, _ = client_candidate(manifest_path, target_id)
        for kind in ('artifact', 'sources'):
            path = checked_file(manifest_path.parent, target[kind])
            files[path] = target[kind]['sha256']
            subjects.setdefault(target[kind]['sha256'], path)
    results = []
    for expected_hash, path in subjects.items():
        if digest(path) != expected_hash or digest(bundle_path) != bundle_hash:
            raise EvidenceError('Candidate or attestation bundle changed during verification')
        command = [
            'gh', 'attestation', 'verify', str(path), '--bundle', str(bundle_path),
            '--repo', repository,
            '--signer-workflow', repository + '/.github/workflows/release.yml',
            '--signer-digest', manifest['commit'],
            '--source-digest', manifest['commit'],
            '--source-ref', 'refs/tags/' + manifest['release'],
            '--deny-self-hosted-runners', '--predicate-type', 'https://slsa.dev/provenance/v1',
            '--format', 'json'
        ]
        try:
            completed = run(command, capture_output=True, text=True, timeout=60)
        except subprocess.TimeoutExpired as error:
            raise EvidenceError('Attestation verification timed out: ' + path.name) from error
        if completed.returncode:
            raise EvidenceError('Attestation verification failed for ' + path.name + ': '
                                + completed.stderr.strip())
        try:
            verified = json.loads(completed.stdout)
        except (ValueError, TypeError) as error:
            raise EvidenceError('Invalid attestation verification output: ' + path.name) from error
        if (not isinstance(verified, list) or not verified
                or any(not isinstance(item, dict)
                       or not isinstance(item.get('verificationResult'), dict) for item in verified)):
            raise EvidenceError('Missing verified attestations: ' + path.name)
        for item in verified:
            statement = item['verificationResult'].get('statement')
            if (not isinstance(statement, dict)
                    or statement.get('predicateType') != 'https://slsa.dev/provenance/v1'
                    or not isinstance(statement.get('subject'), list)
                    or not any(isinstance(subject, dict)
                               and isinstance(subject.get('digest'), dict)
                               and subject['digest'].get('sha256') == expected_hash
                               for subject in statement['subject'])):
                raise EvidenceError('Verified attestation does not identify the candidate file: ' + path.name)
        if digest(path) != expected_hash or digest(bundle_path) != bundle_hash:
            raise EvidenceError('Candidate or attestation bundle changed during verification')
        results.append({'sha256': expected_hash, 'attestations': verified})
    if any(digest(path) != expected for path, expected in files.items()):
        raise EvidenceError('Candidate files changed during verification')
    return {'manifest_sha256': manifest_hash, 'bundle_sha256': bundle_hash,
            'repository': repository, 'source_commit': manifest['commit'],
            'release': manifest['release'], 'subjects': results, 'releaseAcceptance': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--repo', required=True, help='Expected repository owner/name')
    args = parser.parse_args()
    try:
        result = verify_provenance(args.candidate, args.bundle, args.repo)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
