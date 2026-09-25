"""List the packaged client runs required for a selected Fabric target."""

import argparse
import json
from pathlib import Path

from parity_evidence import EvidenceError, read_json
from targets import load_catalog, select_targets

ROOT = Path(__file__).resolve().parents[1]


def required_runs(target, contract, root=ROOT):
    if contract.get('schemaVersion') != 1 or contract.get('target') != target['id']:
        raise EvidenceError('Scenario contract target or version differs')
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', default='26.3-fabric')
    parser.add_argument('--contract', type=Path, required=True)
    args = parser.parse_args()
    try:
        target = select_targets(load_catalog(), args.target)[0]
        runs = required_runs(target, read_json(args.contract))
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.error(str(error))
    print(json.dumps({'target': target['id'], 'runs': runs, 'releaseAcceptance': False}, indent=2))


if __name__ == '__main__':
    main()
