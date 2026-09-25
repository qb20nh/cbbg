"""Select required Fabric runs and validate their saved results."""

import argparse
import json
from pathlib import Path
import re
import zipfile

from fabric_run_evidence import verify_run, verify_restart
from parity_evidence import (EvidenceError, catalog_digest, checked_file, digest, read_json,
                             selected_target_specs, unique_by, validate_release_identity)
from targets import load_catalog, select_targets

ROOT = Path(__file__).resolve().parents[1]


def required_runs(target, contract, root=ROOT, metadata_path=None):
    if contract.get('schemaVersion') != 1 or contract.get('target') != target['id']:
        raise EvidenceError('Scenario contract target or version differs')
    if metadata_path is None:
        metadata_path = (root / contract['ordinaryMetadata']).resolve()
        if root.resolve() not in metadata_path.parents:
            raise EvidenceError('Ordinary metadata is outside the repository')
    metadata = read_json(metadata_path)
    ordinary = metadata['entrypoints']['fabric-client-gametest']
    if metadata.get('id') != 'cbbg-renderer-test':
        raise EvidenceError('Unexpected ordinary test metadata')
    profiles = target['compatibilityProfiles']
    suites = [{'suite': 'ordinary', 'profiles': list(profiles),
               'backends': target['backends'], 'restart': False, 'entrypoints': ordinary}]
    for extra in contract['additionalRuns']:
        suite = dict(extra)
        if ('profiles' in suite) == ('requiresMod' in suite):
            raise EvidenceError('Each extra suite needs profiles or a required mod')
        if 'requiresMod' in suite:
            suite['profiles'] = [profile for profile in profiles
                                 if suite['requiresMod'] in profile.split('+')]
        suite['entrypoints'] = ['com.qb20nh.cbbg.gametest.' + name for name in suite['entrypoints']]
        suites.append(suite)
    names = set()
    runs = []
    for suite in suites:
        name = suite['suite']
        if not isinstance(name, str) or not name or name in names:
            raise EvidenceError('Invalid or duplicate suite name')
        names.add(name)
        scenarios = suite['entrypoints']
        if (not isinstance(scenarios, list) or not scenarios
                or any(not isinstance(value, str) or not value for value in scenarios)
                or len(scenarios) != len(set(scenarios))):
            raise EvidenceError('Invalid suite entrypoints: ' + name)
        selected = suite['profiles']
        backends = suite['backends']
        if (not isinstance(selected, list) or not selected
                or len(selected) != len(set(selected)) or not set(selected) <= profiles.keys()
                or not isinstance(backends, list) or not backends
                or len(backends) != len(set(backends)) or not set(backends) <= set(target['backends'])
                or type(suite['restart']) is not bool):
            raise EvidenceError('Invalid suite selection: ' + name)
        count = 0
        for profile in selected:
            for backend in backends:
                if backend in profiles[profile]:
                    runs.append({'suite': name, 'profile': profile, 'backend': backend,
                                 'restart': suite['restart'], 'entrypoints': scenarios})
                    count += 1
        if not count:
            raise EvidenceError('Suite has no applicable configurations: ' + name)
    return runs


def verify_results(index_path, target, contract_path, driver_hashes, *, metadata_path=None, **inputs):
    index_path = Path(index_path)
    required = required_runs(target, read_json(contract_path), metadata_path=metadata_path)
    key = lambda run: (run['suite'], run['profile'], run['backend'])
    expected = unique_by(required, key, 'required run')
    supplied = unique_by(read_json(index_path), key, 'run result')
    if supplied.keys() != expected.keys():
        raise EvidenceError('Run coverage differs; missing=' + str(sorted(expected.keys() - supplied.keys()))
                            + '; unexpected=' + str(sorted(supplied.keys() - expected.keys())))
    suites = {run['suite'] for run in required}
    if (driver_hashes.keys() != suites or any(
            not isinstance(value, str) or not re.fullmatch('[0-9a-f]{64}', value)
            for value in driver_hashes.values())):
        raise EvidenceError('Expected driver hashes must cover every required suite')
    results = []
    paths = set()
    for cell, requirement in expected.items():
        receipt = checked_file(index_path.parent, supplied[cell]['receipt'])
        if receipt in paths:
            raise EvidenceError('Receipt reused for different required runs')
        paths.add(receipt)
        verifier = verify_restart if requirement['restart'] else verify_run
        result = verifier(receipt, target, driver_sha256=driver_hashes[cell[0]], **inputs)
        if (result['profile'] != cell[1] or result['backend'] != cell[2]
                or result['scenarios'] != requirement['entrypoints']):
            raise EvidenceError('Saved run differs from required configuration: ' + str(cell))
        results.append(dict(result, suite=cell[0]))
    if metadata_path is None:
        metadata_path = ROOT / read_json(contract_path)['ordinaryMetadata']
    return {'target': target['id'], 'source_commit': inputs['source_commit'],
            'candidate_sha256': inputs['candidate_sha256'], 'drivers': driver_hashes,
            'contract_sha256': digest(contract_path), 'ordinary_metadata_sha256': digest(metadata_path),
            'index_sha256': digest(index_path), 'runs': results, 'releaseAcceptance': False}


def verify_candidate_results(manifest_path, target_id, index_path):
    manifest_path = Path(manifest_path)
    manifest = read_json(manifest_path)
    if type(manifest.get('schema')) is not int or manifest['schema'] != 2:
        raise EvidenceError('Client result validation requires candidate schema 2')
    validate_release_identity(manifest['release'], manifest['commit'])
    targets = unique_by(manifest['targets'], lambda item: item['id'], 'candidate target')
    if target_id not in targets:
        raise EvidenceError('Target is absent from candidate')
    target = targets[target_id]
    base = manifest_path.parent
    checked_file(base, target['artifact'])
    checked_file(base, target['sources'])
    tests = target['client_tests']
    files = {kind: checked_file(base, tests[kind]) for kind in
             ('catalog', 'contract', 'ordinary_metadata', 'runtime_lock', 'dependency_lock')}
    catalog = load_catalog(files['catalog'])
    selected = selected_target_specs(catalog, manifest['selected_targets'])
    if targets.keys() != selected.keys() or manifest['catalog_sha256'] != catalog_digest(catalog):
        raise EvidenceError('Candidate catalog or target selection differs')
    specification = selected[target_id]
    for suite, reference in tests['drivers'].items():
        checked_file(base, reference)
    result = verify_results(index_path, specification, files['contract'],
                            {suite: reference['sha256'] for suite, reference in tests['drivers'].items()},
                            metadata_path=files['ordinary_metadata'], source_commit=manifest['commit'],
                            candidate_sha256=target['artifact']['sha256'], catalog_path=files['catalog'],
                            runtime_lock_path=files['runtime_lock'], dependency_lock_path=files['dependency_lock'])
    result.update(manifest_sha256=digest(manifest_path), release=manifest['release'])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', default='26.3-fabric')
    parser.add_argument('--contract', type=Path)
    parser.add_argument('--candidate', type=Path, help='Use the candidate manifest for all expected inputs')
    parser.add_argument('--results', type=Path, help='Index of saved run receipts to validate')
    parser.add_argument('--drivers', type=Path, help='Expected SHA-256 by suite name')
    parser.add_argument('--source-commit')
    parser.add_argument('--candidate-sha256')
    parser.add_argument('--catalog', type=Path, default=ROOT / 'targets.json')
    parser.add_argument('--runtime-lock', type=Path)
    parser.add_argument('--dependency-lock', type=Path)
    args = parser.parse_args()
    try:
        if args.candidate:
            if not args.results:
                parser.error('Candidate validation requires a result index')
            if any(value is not None for value in (args.contract, args.drivers, args.source_commit,
                    args.candidate_sha256, args.runtime_lock, args.dependency_lock)):
                parser.error('Candidate validation takes its expected inputs from the manifest')
            print(json.dumps(verify_candidate_results(args.candidate, args.target, args.results), indent=2))
            return
        if not args.contract:
            parser.error('Run selection requires a contract')
        target = select_targets(load_catalog(args.catalog), args.target)[0]
        if args.results:
            if any(value is None for value in (args.drivers, args.source_commit,
                    args.candidate_sha256, args.runtime_lock, args.dependency_lock)):
                parser.error('Result validation requires drivers, source commit, candidate hash and both locks')
            result = verify_results(args.results, target, args.contract, read_json(args.drivers),
                                    source_commit=args.source_commit, candidate_sha256=args.candidate_sha256,
                                    catalog_path=args.catalog, runtime_lock_path=args.runtime_lock,
                                    dependency_lock_path=args.dependency_lock)
        else:
            result = {'target': target['id'], 'runs': required_runs(target, read_json(args.contract)),
                      'releaseAcceptance': False}
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile) as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
