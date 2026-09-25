"""Check saved Fabric run files against the expected source, jars and locks."""

import argparse
import hashlib
import json
from pathlib import Path
import zipfile

from fabric_dependency_lock import verify_dependencies, verify_gametest_api
from fabric_scenario_evidence import graphics_identity, validate_scenarios
from parity_evidence import EvidenceError, checked_file, digest, read_json
from targets import load_catalog, select_targets


def verify_run(receipt_path, target, *, source_commit, candidate_sha256,
               driver_sha256, catalog_path, runtime_lock_path, dependency_lock_path):
    receipt_path = Path(receipt_path)
    game = receipt_path.parent.resolve()
    report = read_json(receipt_path)
    if ('failure' in report or type(report.get('exitCode')) is not int
            or report['exitCode'] != 0):
        raise EvidenceError('Client run failed')
    if report.get('sourceDirty') is not False or report.get('sourceHead') != source_commit:
        raise EvidenceError('Run source differs from expected clean commit')
    if report.get('target') != target['id'] or report.get('renderer') != target['renderer']:
        raise EvidenceError('Run target or renderer differs')
    profile = report['profile']
    backend = report['backendRequested']
    if backend not in target['compatibilityProfiles'].get(profile, []):
        raise EvidenceError('Unexpected compatibility profile or backend')
    loader = 'fabric-loader-' + target['dependencies']['loader'] + '-' + target['minecraft']
    if report.get('loaderProfile') != loader:
        raise EvidenceError('Run loader profile differs')
    for key, path in [('catalogSha256', catalog_path), ('runtimeLockSha256', runtime_lock_path),
                      ('dependencyLockSha256', dependency_lock_path)]:
        if report.get(key) != digest(path):
            raise EvidenceError('Run input differs: ' + key)
    catalog_target = select_targets(read_json(catalog_path), target['id'])[0]
    if catalog_target != target:
        raise EvidenceError('Target differs from recorded catalog')

    # Restart runs need their prepare/verify relationship checked separately.
    if report.get('restartPhase') is not None or report.get('requestedDsaMode') is not None:
        raise EvidenceError('Use the dedicated validator for restart or benchmark runs')
    artifacts = report['artifacts']
    if (artifacts.get('candidate.jar') != candidate_sha256
            or artifacts.get('driver.jar') != driver_sha256):
        raise EvidenceError('Candidate or test driver differs')
    installed = {}
    for name, checksum in artifacts.items():
        if Path(name).name != name or not name.endswith('.jar'):
            raise EvidenceError('Invalid installed artifact name')
        installed[name] = checked_file(game, {'path': 'mods/' + name, 'sha256': checksum})
    if {p.name for p in (game / 'mods').iterdir()} != set(installed):
        raise EvidenceError('Installed mod inventory differs')
    lock = read_json(dependency_lock_path)
    dependencies = {name[:-4]: path for name, path in installed.items()
                    if name not in ('candidate.jar', 'driver.jar', 'fabric-gametest-api.jar')}
    verify_dependencies(target, profile, dependencies, lock)
    if 'fabric-gametest-api.jar' not in installed:
        raise EvidenceError('Missing gametest API')
    verify_gametest_api(target, installed['fabric-gametest-api.jar'], lock)
    with zipfile.ZipFile(installed['driver.jar']) as jar:
        metadata = json.loads(jar.read('fabric.mod.json'))
    if metadata.get('id') != 'cbbg-renderer-test':
        raise EvidenceError('Unexpected test driver')
    expected = metadata['entrypoints']['fabric-client-gametest']
    expected_hash = hashlib.sha256(json.dumps(expected, separators=(',', ':')).encode()).hexdigest()
    if report.get('scenarioSha256') != expected_hash:
        raise EvidenceError('Scenario contract differs from test driver')

    evidence = report['evidence']
    required = {'launch.log', 'evidence/scenarios.tsv', 'evidence/graphics-context.json'}
    if not required <= evidence.keys():
        raise EvidenceError('Missing log, scenario trace or graphics context')
    actual_files = {'launch.log'} | {
        p.relative_to(game).as_posix() for p in (game / 'evidence').rglob('*') if p.is_file()}
    if set(evidence) != actual_files:
        raise EvidenceError('Saved result inventory differs')
    paths = {name: checked_file(game, {'path': name, 'sha256': checksum})
             for name, checksum in evidence.items()}
    log = paths['launch.log'].read_text()
    results = validate_scenarios(expected, paths['evidence/scenarios.tsv'].read_text(),
                                 log, report['exitCode'], backend)
    if report.get('scenarios') != results:
        raise EvidenceError('Scenario summary differs from trace')
    graphics = graphics_identity(log, backend)
    context = read_json(paths['evidence/graphics-context.json'])
    if context.get('backend') != backend:
        raise EvidenceError('Graphics context backend differs')
    graphics.update(context=context, contextProfile=context.get('profile'))
    if report.get('graphics') != graphics:
        raise EvidenceError('Graphics summary differs from saved files')
    return {'target': target['id'], 'profile': profile, 'backend': backend,
            'source_commit': source_commit, 'receipt_sha256': digest(receipt_path),
            'scenarios': expected, 'evidence_files': len(paths), 'releaseAcceptance': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--target', required=True)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--candidate-sha256', required=True)
    parser.add_argument('--driver-sha256', required=True)
    for name in ('catalog', 'runtime-lock', 'dependency-lock'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    try:
        target = select_targets(load_catalog(args.catalog), args.target)[0]
        result = verify_run(args.receipt, target, source_commit=args.source_commit,
                            candidate_sha256=args.candidate_sha256,
                            driver_sha256=args.driver_sha256, catalog_path=args.catalog,
                            runtime_lock_path=args.runtime_lock,
                            dependency_lock_path=args.dependency_lock)
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile) as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
