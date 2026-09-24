"""Run a locked packaged Fabric client on an explicitly supplied local test display."""

import argparse
import hashlib
from importlib.metadata import version
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

from fabric_dependency_lock import verify_dependencies
from fabric_runtime_lock import verify_runtime
from fabric_scenario_evidence import validate_scenarios
from targets import load_catalog, select_targets

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', choices=['26.3-fabric'], default='26.3-fabric')
    for name in ('runtime', 'java', 'game-dir', 'candidate', 'driver', 'gametest-api',
                 'runtime-lock', 'dependency-lock', 'xdg-runtime-dir'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--dependency', action='append', default=[], metavar='NAME=JAR')
    parser.add_argument('--backend', choices=['opengl', 'vulkan'], required=True)
    parser.add_argument('--compat', default='none')
    parser.add_argument('--timeout', type=int, default=240)
    display = parser.add_mutually_exclusive_group(required=True)
    display.add_argument('--wayland-display')
    display.add_argument('--x-display')
    args = parser.parse_args()
    if not 1 <= args.timeout <= 600:
        parser.error('Timeout must be between 1 and 600 seconds')
    target = select_targets(load_catalog(), args.target)[0]
    if args.backend not in target['compatibilityProfiles'].get(args.compat, []):
        parser.error('Backend is not supported by the selected catalog profile')
    if version('minecraft-launcher-lib') != '8.0':
        raise ValueError('Use minecraft-launcher-lib==8.0 in an isolated environment')
    from minecraft_launcher_lib.command import get_minecraft_command

    dependencies = {}
    for value in args.dependency:
        name, separator, path = value.partition('=')
        if not separator or not name or not path or name in dependencies:
            parser.error('Dependencies must be unique NAME=JAR entries')
        dependencies[name] = Path(path).resolve()
    dependency_bytes = args.dependency_lock.read_bytes()
    dependency_lock = json.loads(dependency_bytes)
    verify_dependencies(target, args.compat, dependencies, dependency_lock)
    with zipfile.ZipFile(args.driver) as jar:
        metadata = json.loads(jar.read('fabric.mod.json'))
        expected = metadata['entrypoints']['fabric-client-gametest']
        if metadata['id'] != 'cbbg-renderer-test' or not expected:
            raise ValueError('Unexpected packaged test driver')

    runtime = args.runtime.resolve()
    game = args.game_dir.resolve()
    identity = 'fabric-loader-' + target['dependencies']['loader'] + '-' + target['minecraft']
    command = get_minecraft_command(identity, str(runtime), {
        'username': 'CbbgParity', 'uuid': '00000000000000000000000000000001', 'token': '0',
        'executablePath': str(args.java.resolve()), 'gameDirectory': str(game),
        'jvmArguments': ['-Xmx2G', '-Dfabric.client.gametest',
                        '-Dfabric.client.gametest.modid=cbbg-renderer-test',
                        '-Dcbbg.test.backend=' + args.backend,
                        '-Dcbbg.test.compat=' + args.compat,
                        '-Dcbbg.test.modmenu.version=' + target['dependencies']['modMenu'],
                        '-Dcbbg.test.evidence=' + str(game / 'evidence')],
        'customResolution': True, 'resolutionWidth': '960', 'resolutionHeight': '540'})
    command += ['--graphicsBackend', args.backend, '--vulkanValidation', '--renderDebugLabels']
    runtime_bytes = args.runtime_lock.read_bytes()
    verify_runtime(runtime, identity, command, json.loads(runtime_bytes))
    environment = dict(os.environ, XDG_RUNTIME_DIR=str(args.xdg_runtime_dir.resolve()),
                       ALSOFT_DRIVERS='null', DISABLE_MANGOHUD='1', DISABLE_VKBASALT='1',
                       DISABLE_GAMESCOPE_WSI='1')
    environment.pop('DISPLAY', None)
    environment.pop('WAYLAND_DISPLAY', None)
    if args.wayland_display:
        environment.update(WAYLAND_DISPLAY=args.wayland_display, XDG_SESSION_TYPE='wayland',
                           SDL_VIDEODRIVER='wayland')
    else:
        environment.update(DISPLAY=args.x_display, XDG_SESSION_TYPE='x11', SDL_VIDEODRIVER='x11')

    # A new directory prevents interference with personal profiles and stale evidence.
    game.mkdir(parents=True, exist_ok=False)
    mods = game / 'mods'
    mods.mkdir()
    sources = {'candidate.jar': args.candidate, 'driver.jar': args.driver,
               'fabric-gametest-api.jar': args.gametest_api}
    sources.update({name + '.jar': path for name, path in dependencies.items()})
    receipt = {'target': args.target, 'backendRequested': args.backend, 'profile': args.compat,
               'timeoutSeconds': args.timeout, 'releaseAcceptance': False,
               'runtimeLockSha256': hashlib.sha256(runtime_bytes).hexdigest(),
               'dependencyLockSha256': hashlib.sha256(dependency_bytes).hexdigest(),
               'sourceHead': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT,
                                                     text=True).strip(),
               'sourceDirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT)),
               'artifacts': {}}
    try:
        for name, source in sources.items():
            shutil.copyfile(source, mods / name)
            receipt['artifacts'][name] = digest(mods / name)
        verify_dependencies(target, args.compat,
                            {name: mods / (name + '.jar') for name in dependencies}, dependency_lock)
        with (game / 'launch.log').open('w') as log:
            result = subprocess.run(command, cwd=game, env=environment, stdout=log,
                                    stderr=subprocess.STDOUT, timeout=args.timeout)
        receipt['exitCode'] = result.returncode
        receipt['scenarios'] = validate_scenarios(expected,
            (game / 'evidence/scenarios.tsv').read_text(), (game / 'launch.log').read_text(),
            result.returncode, args.backend)
    except Exception as failure:
        receipt['failure'] = {'type': type(failure).__name__, 'message': str(failure)}
        raise
    finally:
        (game / 'probe.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'receipt': str(game / 'probe.json'), 'scenarios': receipt['scenarios']}))


if __name__ == '__main__':
    main()
