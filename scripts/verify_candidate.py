"""Check every selected candidate target before release finalization."""

import argparse
import json
from pathlib import Path
import zipfile

from candidate_provenance import verify_provenance
from fabric_acceptance import verify_candidate_results
from fabric_package import verify_candidate as verify_package
from parity_evidence import EvidenceError, digest, read_json


def verify_candidate(manifest_path, results, bundle_path, source_root, repository):
    manifest_path = Path(manifest_path)
    manifest_hash = digest(manifest_path)
    manifest = read_json(manifest_path)
    selected = manifest.get('selected_targets')
    if (not isinstance(selected, list) or not selected
            or any(not isinstance(target, str) or not target for target in selected)
            or len(selected) != len(set(selected))):
        raise EvidenceError('Candidate needs unique, explicit target IDs')
    if not isinstance(results, dict) or results.keys() != set(selected):
        raise EvidenceError('Result indexes must cover exactly the selected targets')
    paths = [Path(results[target]).resolve() for target in selected]
    if len(paths) != len(set(paths)):
        raise EvidenceError('Each runtime target needs its own result index')

    expected = {'manifest_sha256': manifest_hash, 'source_commit': manifest['commit'],
                'release': manifest['release']}

    def check_identity(report, target=None):
        for field, value in expected.items():
            if report.get(field) != value:
                raise EvidenceError('Validation result differs from candidate: ' + field)
        if target is not None and report.get('target') != target:
            raise EvidenceError('Validation result differs from selected target: ' + target)

    targets = {}
    for target in selected:
        package = verify_package(manifest_path, target, source_root)
        check_identity(package, target)
        runtime = verify_candidate_results(manifest_path, target, results[target])
        check_identity(runtime, target)
        targets[target] = {'package': package, 'runtime': runtime}
    provenance = verify_provenance(manifest_path, bundle_path, repository)
    check_identity(provenance)
    if provenance.get('repository') != repository:
        raise EvidenceError('Provenance repository differs from expected repository')
    if digest(manifest_path) != manifest_hash:
        raise EvidenceError('Candidate manifest changed during validation')
    return {**expected, 'targets': targets, 'provenance': provenance,
            'releaseAcceptance': False}


def result_selection(values):
    results = {}
    for value in values:
        target, separator, path = value.partition('=')
        if not separator or not target or not path or target in results:
            raise EvidenceError('Use one --results TARGET=INDEX per selected target')
        results[target] = Path(path)
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--results', action='append', required=True, metavar='TARGET=INDEX')
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--source-root', type=Path, required=True)
    parser.add_argument('--repo', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.output.exists():
            raise EvidenceError('Validation output already exists')
        result = verify_candidate(args.candidate, result_selection(args.results), args.bundle,
                                  args.source_root, args.repo)
        with args.output.open('x', encoding='utf-8') as stream:
            stream.write(json.dumps(result, indent=2) + '\n')
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile) as error:
        parser.error(str(error))
    print('Verified candidate checks: ' + str(args.output))


if __name__ == '__main__':
    main()
