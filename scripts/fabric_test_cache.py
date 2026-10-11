"""Compare execution inputs and packaged test dependencies before reusing a run."""

import hashlib
from pathlib import Path
import re
import zipfile

from parity_evidence import EvidenceError, checked_file, digest, read_json


def execution_inputs(inputs, target):
    """Ignore bookkeeping and other runtimes, retaining inputs used by this client."""
    java = 'java21' if target['java'] == 21 else 'java25'
    if not isinstance(inputs.get('executionEnvironment'), dict):
        raise EvidenceError('Cached execution environment was not recorded')
    if inputs['executionEnvironment']:
        raise EvidenceError('Custom execution environment requires a fresh run; referenced files were not recorded')
    scripts = inputs['scripts']['files']
    names = ('fabric_parity_runtime.py', 'fabric_dependency_lock.py', 'fabric_runtime_lock.py',
             'fabric_scenario_evidence.py', 'runtime_catalog.py')
    return {
        'root': inputs['root'], 'graphicsEnvironment': inputs['graphicsEnvironment'],
        'executionEnvironment': inputs['executionEnvironment'],
        'host': inputs['host'], 'python': inputs['python'],
        'executables': {name: inputs['executables'].get(name)
                        for name in ('python', java, 'weston', 'eglVendor')},
        'scripts': {name: scripts.get(name) for name in names},
        'runtime': inputs['runtimes'].get(target['id']),
        'sharedRuntime': inputs['sharedRuntime'],
        'gametestApi': inputs['gametestApis'].get(target['id']),
        'seedCache': inputs['seedCaches'].get(target['id']),
    }


def check_driver_dependencies(driver, expected):
    if (expected.get('schema') != 1 or expected.get('complete') is not True
            or not isinstance(expected.get('files'), dict) or not expected['files']):
        raise EvidenceError('Test dependencies are incomplete; rerun this suite')
    with zipfile.ZipFile(driver) as jar:
        if len(jar.namelist()) != len(set(jar.namelist())):
            raise EvidenceError('Duplicate test driver entry')
        for name, checksum in expected['files'].items():
            if (not isinstance(checksum, str) or not re.fullmatch('[0-9a-f]{64}', checksum)
                    or name not in jar.namelist()
                    or hashlib.sha256(jar.read(name)).hexdigest() != checksum):
                raise EvidenceError('Test dependency changed: ' + name)
        names = {entry.filename for entry in jar.infolist() if not entry.is_dir()}
        resources = {name for name in names if not name.endswith('.class')}
        required_resources = {name for name in expected['files'] if not name.endswith('.class')}
        if resources != required_resources:
            raise EvidenceError('Test resource set changed')
        if expected.get('full_archive') is True and names != expected['files'].keys():
            raise EvidenceError('Test driver entry set changed')


def reuse_arguments(row, receipt, target, *, current_driver, dependencies, current_inputs, base):
    reuse = row['reuse']
    original = read_json(checked_file(base, reuse['inputs']))
    current = read_json(current_inputs)
    if execution_inputs(original, target) != execution_inputs(current, target):
        raise EvidenceError('Cached client execution inputs changed')
    if (reuse.get('execution_sources') != current.get('executionSources')
            or not reuse.get('execution_sources')):
        raise EvidenceError('Cached Gradle client runner changed')
    report = read_json(receipt)
    if report.get('restartPhase') is None and current['initialConfig'] is not None:
        config = checked_file(base, reuse['config'])
        if (read_json(config) != current.get('initialConfig')
                or report.get('initialConfigSha256') != digest(config)):
            raise EvidenceError('Cached initial configuration changed')
    # Restart clients use the fixture's persisted prepare/control settings,
    # which verify_restart checks together with their logs and state files.
    source = original['source']
    if not isinstance(source, str) or not re.fullmatch('[0-9a-f]{40}', source):
        raise EvidenceError('Invalid cached source commit')
    driver_hash = report['artifacts']['driver.jar']
    old_driver = checked_file(Path(receipt).parent, {'path': 'mods/driver.jar', 'sha256': driver_hash})
    # Compare against the graph produced for the current candidate, never a graph
    # supplied by the old result. Both archives must contain every required byte.
    expected = dependencies[row['suite']]
    if expected.get('driver_sha256') != digest(current_driver):
        raise EvidenceError('Current test dependencies identify another driver')
    if driver_hash != expected['driver_sha256']:
        check_driver_dependencies(current_driver, expected)
        check_driver_dependencies(old_driver, expected)
    return {'source_commit': source, 'driver_sha256': driver_hash}
