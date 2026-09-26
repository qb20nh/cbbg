"""Check saved Fabric run files against the expected source, jars and locks."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

from fabric_dependency_lock import verify_dependencies, verify_gametest_api
from fabric_parity_runtime import RESTART_DRIVERS, restart_state
from fabric_scenario_evidence import graphics_identity, validate_scenarios, validate_shutdown, validate_startup
from parity_evidence import EvidenceError, checked_file, digest, read_json
from runtime_catalog import load_catalog, select_targets


def _verify_run(receipt_path, target, *, source_commit, candidate_sha256,
                driver_sha256, catalog_path, runtime_lock_path, dependency_lock_path,
                restart_phase=None):
    if target.get('loader') != 'fabric':
        raise EvidenceError('Fabric run validation requires a Fabric target')
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

    if report.get('restartPhase') != restart_phase or report.get('requestedDsaMode') is not None:
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
    log_name = restart_phase + '-launch.log' if restart_phase else 'launch.log'
    directory = 'evidence-' + restart_phase if restart_phase else 'evidence'
    trace_name = directory + '/scenarios.tsv'
    context_name = directory + '/graphics-context.json'
    required = {log_name, trace_name, context_name}
    if not required <= evidence.keys():
        raise EvidenceError('Missing log, scenario trace or graphics context')
    actual_files = {log_name} | {
        p.relative_to(game).as_posix() for p in (game / directory).rglob('*') if p.is_file()}
    if set(evidence) != actual_files:
        raise EvidenceError('Saved result inventory differs')
    paths = {name: checked_file(game, {'path': name, 'sha256': checksum})
             for name, checksum in evidence.items()}
    log = paths[log_name].read_text()
    results = validate_scenarios(expected, paths[trace_name].read_text(),
                                 log, report['exitCode'], backend)
    validate_shutdown(expected, game / directory / 'shutdown.json')
    validate_startup(expected, report.get('startupMode'), game / directory, game / '.cbbg', log, backend)
    if report.get('scenarios') != results:
        raise EvidenceError('Scenario summary differs from trace')
    graphics = graphics_identity(log, backend)
    context = read_json(paths[context_name])
    if context.get('backend') != backend:
        raise EvidenceError('Graphics context backend differs')
    graphics.update(context=context, contextProfile=context.get('profile'))
    if report.get('graphics') != graphics:
        raise EvidenceError('Graphics summary differs from saved files')
    return {'target': target['id'], 'profile': profile, 'backend': backend,
            'startupMode': report.get('startupMode'),
            'source_commit': source_commit, 'receipt_sha256': digest(receipt_path),
            'scenarios': expected, 'evidence_files': len(paths), 'releaseAcceptance': False}


def verify_run(receipt_path, target, **inputs):
    return _verify_run(receipt_path, target, **inputs)


def verify_restart(receipt_path, target, **inputs):
    receipt_path = Path(receipt_path)
    if receipt_path.name != 'verify-probe.json':
        raise EvidenceError('Restart validation requires the verify receipt')
    game = receipt_path.parent.resolve()
    prepare_path = game / 'prepare-probe.json'
    prepare = read_json(prepare_path)
    verified = read_json(receipt_path)
    first = _verify_run(prepare_path, target, restart_phase='prepare', **inputs)
    second = _verify_run(receipt_path, target, restart_phase='verify', **inputs)
    scenarios = second['scenarios']
    shader = RESTART_DRIVERS.get((second['backend'], scenarios[0])) if len(scenarios) == 1 else None
    if shader is None:
        raise EvidenceError('Unexpected restart driver or backend')
    if shader not in second['profile'].split('+'):
        raise EvidenceError('Restart profile omits the shader mod')
    if verified.get('prepareReceiptSha256') != digest(prepare_path):
        raise EvidenceError('Prepare receipt changed')
    for key in ('target', 'profile', 'backendRequested', 'loaderProfile', 'java', 'host',
                'catalogSha256', 'runtimeLockSha256', 'dependencyLockSha256',
                'sourceHead', 'sourceDirty', 'artifacts', 'scenarioSha256'):
        if key not in prepare or prepare[key] != verified.get(key):
            raise EvidenceError('Restart identity differs: ' + key)
    before = prepare.get('persistedState')
    after = verified.get('persistedState')
    if (not isinstance(before, dict) or not before or not isinstance(after, dict)
            or before.keys() != after.keys() or verified.get('inputState') != before
            or any(not isinstance(value, str) or not re.fullmatch('[0-9a-f]{64}', value)
                   for value in before.values())):
        raise EvidenceError('Restart input state differs from prepare result')
    for name, checksum in after.items():
        checked_file(game, {'path': name, 'sha256': checksum})
    if restart_state(game, shader) != after:
        raise EvidenceError('Restart saved state inventory differs')
    second.update(prepare_receipt_sha256=first['receipt_sha256'], restart=True,
                  evidence_files=first['evidence_files'] + second['evidence_files'])
    return second


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--restart', action='store_true', help='Validate a prepare/verify pair')
    parser.add_argument('--target', required=True)
    parser.add_argument('--source-commit', required=True)
    parser.add_argument('--candidate-sha256', required=True)
    parser.add_argument('--driver-sha256', required=True)
    for name in ('catalog', 'runtime-lock', 'dependency-lock'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    try:
        target = select_targets(load_catalog(args.catalog), args.target)[0]
        verifier = verify_restart if args.restart else verify_run
        result = verifier(args.receipt, target, source_commit=args.source_commit,
                            candidate_sha256=args.candidate_sha256,
                            driver_sha256=args.driver_sha256, catalog_path=args.catalog,
                            runtime_lock_path=args.runtime_lock,
                            dependency_lock_path=args.dependency_lock)
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile) as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
