"""Create a local candidate bundle from recorded Gradle outputs."""

import argparse
import json
from pathlib import Path
import shutil
import subprocess

from fabric_acceptance import required_runs
from fabric_package import verify_candidate
from parity_evidence import EvidenceError, checked_file, digest, read_json, validate_release_identity
from prepare_candidate import prepare
from targets import load_catalog, select_targets


def source_state(root):
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    dirty = subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=all',
                                     '--', '.', ':(top,exclude)docs/**'], cwd=root, text=True)
    return commit, bool(dirty)


def bundle_candidate(root, outputs_path, destination, release, contract_path,
                     runtime_lock_path, dependency_lock_path):
    root = Path(root).resolve()
    destination = Path(destination).resolve()
    if destination.exists():
        raise EvidenceError('Candidate destination already exists')
    commit, dirty = source_state(root)
    validate_release_identity(release, commit)
    if dirty:
        raise EvidenceError('Commit source changes before bundling a candidate')
    outputs = read_json(outputs_path)
    if type(outputs.get('schema')) is not int or outputs['schema'] != 1:
        raise EvidenceError('Invalid build-output schema')
    if outputs.get('source_commit') != commit or outputs.get('source_dirty') is not False:
        raise EvidenceError('Build outputs do not identify the current clean source')
    catalog_path = root / 'targets.json'
    catalog = load_catalog(catalog_path)
    target, = select_targets(catalog, outputs['target'])
    if target['loader'] != 'fabric' or target.get('buildProfile') != 'fabric-modern':
        raise EvidenceError('Candidate bundling is not configured for this target')
    version = release[1:] + '+mc' + target['minecraft'] + '-fabric'
    if outputs.get('version') != version:
        raise EvidenceError('Build version differs from requested release')
    contract_path = Path(contract_path).resolve()
    contract = read_json(contract_path)
    metadata_path = (root / contract['ordinaryMetadata']).resolve()
    if not metadata_path.is_relative_to(root):
        raise EvidenceError('Scenario metadata is outside the source checkout')
    suites = {run['suite'] for run in required_runs(target, contract, root=root)}
    drivers = outputs.get('drivers')
    if not isinstance(drivers, dict) or not suites <= drivers.keys():
        raise EvidenceError('Build outputs omit required test drivers')
    files = {}

    def add(name, path):
        if (not isinstance(name, str) or not name or '/' in name or '\\' in name
                or name in ('.', '..', 'candidate.json', 'SHA256SUMS') or name in files):
            raise EvidenceError('Invalid or duplicate candidate filename: ' + str(name))
        path = Path(path).resolve()
        if not path.is_file():
            raise EvidenceError('Missing candidate input: ' + str(path))
        files[name] = (path, digest(path))
        return name

    inventory = {'id': target['id']}
    for kind in ('artifact', 'sources', 'source_inventory'):
        reference = outputs[kind]
        inventory[kind] = add(reference['filename'], checked_file(root, reference))
    tests = {
        'catalog': add('catalog.json', catalog_path),
        'contract': add('contract.json', contract_path),
        'ordinary_metadata': add('ordinary-metadata.json', metadata_path),
        'runtime_lock': add('runtime-lock.json', runtime_lock_path),
        'dependency_lock': add('dependency-lock.json', dependency_lock_path),
        'drivers': {suite: add(drivers[suite]['filename'], checked_file(root, drivers[suite]))
                    for suite in sorted(suites)}
    }
    inventory['client_tests'] = tests
    destination.mkdir(parents=True)
    try:
        for name, (path, expected) in files.items():
            shutil.copyfile(path, destination / name)
            if digest(destination / name) != expected:
                raise EvidenceError('Candidate input changed while copying: ' + name)
        manifest = prepare(destination, [inventory], release, commit, catalog, [target['id']])
        manifest_path = destination / 'candidate.json'
        manifest_path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
        verify_candidate(manifest_path, target['id'], root)
        if source_state(root) != (commit, False):
            raise EvidenceError('Source changed while bundling candidate')
        checksums = [digest(path) + '  ' + path.name for path in sorted(destination.iterdir())]
        (destination / 'SHA256SUMS').write_text('\n'.join(checksums) + '\n', encoding='utf-8')
    except Exception:
        shutil.rmtree(destination)
        raise
    return manifest_path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, required=True)
    parser.add_argument('--build-outputs', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--release', required=True)
    parser.add_argument('--contract', type=Path, required=True)
    parser.add_argument('--runtime-lock', type=Path, required=True)
    parser.add_argument('--dependency-lock', type=Path, required=True)
    args = parser.parse_args()
    try:
        manifest = bundle_candidate(args.source_root, args.build_outputs, args.output, args.release,
                                    args.contract, args.runtime_lock, args.dependency_lock)
    except (ValueError, KeyError, TypeError, OSError, subprocess.CalledProcessError) as error:
        parser.error(str(error))
    print('Created local candidate bundle: ' + str(manifest))


if __name__ == '__main__':
    main()
