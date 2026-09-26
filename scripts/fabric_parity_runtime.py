"""Run a locked packaged Fabric client on an explicitly supplied local test display."""

import argparse
import hashlib
from importlib.metadata import version
import json
import os
import platform
from pathlib import Path
import shutil
import subprocess
import zipfile

from fabric_dependency_lock import verify_dependencies, verify_gametest_api
from fabric_runtime_lock import verify_runtime
from fabric_scenario_evidence import graphics_identity, validate_scenarios
from runtime_catalog import load_catalog, select_targets

ROOT = Path(__file__).resolve().parents[1]

RESTART_DRIVERS = {
    ('opengl', 'com.qb20nh.cbbg.gametest.IrisRestartGameTest'): 'iris',
    ('opengl', 'com.qb20nh.cbbg.gametest.ReleaseIrisRestartGameTest'): 'iris',
    ('vulkan', 'com.qb20nh.cbbg.gametest.SulkanRestartGameTest'): 'sulkan',
    ('vulkan', 'com.qb20nh.cbbg.gametest.ReleaseSulkanRestartGameTest'): 'sulkan',
    ('vulkan', 'com.qb20nh.cbbg.gametest.SulkanExternalRestartGameTest'): 'sulkan',
    ('vulkan', 'com.qb20nh.cbbg.gametest.ReleaseSulkanExternalRestartGameTest'): 'sulkan',
}


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def source_dirty(root):
    # Local plans and test records are deliberately excluded from version control.
    return bool(subprocess.check_output(
        ['git', 'status', '--porcelain', '--untracked-files=all', '--', '.',
         ':(top,exclude)docs/**'], cwd=root))


def java_identity(executable):
    executable = Path(executable).resolve()
    result = subprocess.run([str(executable), '-version'], capture_output=True,
                            text=True, check=True, timeout=15)
    home = executable.parent.parent
    return {'executable': str(executable), 'versionOutput': (result.stdout + result.stderr).strip(),
            'files': {name: digest(path) for name, path in {
                'java': executable, 'release': home / 'release',
                'modules': home / 'lib/modules',
                'libjvm': home / 'lib/server/libjvm.so'}.items()}}


def restart_state(game, shader='iris'):
    if shader == 'sulkan':
        files = [game / 'config/cbbg.json', game / 'config/sulkan-shaders.json']
        selected = json.loads(files[1].read_text()).get('selectedPackId', '__builtin__')
        if selected != '__builtin__':
            if selected != 'cbbg-native-test':
                raise ValueError('Unexpected Sulkan restart pack')
            pack = game / 'shaders/cbbg-native-test'
            if not (pack / 'sulkan.json').is_file() or not (pack / 'color.fsh').is_file():
                raise ValueError('Sulkan restart pack is missing')
            files.extend(path for path in sorted(pack.rglob('*')) if path.is_file())
        return {path.relative_to(game).as_posix(): digest(path) for path in files}
    settings = [game / 'config/cbbg.json', game / 'config/iris.properties']
    pack = sorted((game / 'shaderpacks/cbbg-parity').rglob('*'))
    files = settings + [path for path in pack if path.is_file()]
    if len(files) == len(settings):
        raise ValueError('Restart shader pack is missing')
    return {path.relative_to(game).as_posix(): digest(path) for path in files}


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
    parser.add_argument('--dsa-mode', choices=['auto', 'emulated'])
    parser.add_argument('--restart-phase', choices=['prepare', 'verify', 'control'])
    parser.add_argument('--cbbg-config', type=Path,
                        help='Initial CBBG settings for a fresh, non-restart run')
    display = parser.add_mutually_exclusive_group(required=True)
    display.add_argument('--wayland-display')
    display.add_argument('--x-display')
    args = parser.parse_args()
    if not 1 <= args.timeout <= 600:
        parser.error('Timeout must be between 1 and 600 seconds')
    initial_config = None
    if args.cbbg_config:
        if args.restart_phase:
            parser.error('Initial config cannot replace restart settings')
        initial_config = args.cbbg_config.read_bytes()
        try:
            if not isinstance(json.loads(initial_config), dict):
                parser.error('Initial CBBG config must be a JSON object')
        except (ValueError, UnicodeError):
            parser.error('Initial CBBG config must be a JSON object')
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
    verify_gametest_api(target, args.gametest_api, dependency_lock)
    with zipfile.ZipFile(args.driver) as jar:
        metadata = json.loads(jar.read('fabric.mod.json'))
        expected = metadata['entrypoints']['fabric-client-gametest']
        if metadata['id'] != 'cbbg-renderer-test' or not expected:
            raise ValueError('Unexpected packaged test driver')
    if args.dsa_mode and (args.backend != 'opengl'
                         or 'com.qb20nh.cbbg.gametest.DsaBenchmarkGameTest' not in expected):
        parser.error('DSA selection requires the OpenGL benchmark driver')
    restart_shader = None
    if args.restart_phase:
        restart_shader = RESTART_DRIVERS.get((args.backend, expected[0])) if len(expected) == 1 else None
        if restart_shader is None:
            parser.error('Restart phases require the dedicated Iris/OpenGL or Sulkan/Vulkan driver')

    runtime = args.runtime.resolve()
    game = args.game_dir.resolve()
    display_jvm_arguments = ['-DMC_DEBUG_ENABLED=true',
                             '-DMC_DEBUG_PREFER_WAYLAND=' + str(bool(args.wayland_display)).lower()]
    evidence = game / ('evidence-' + args.restart_phase if args.restart_phase else 'evidence')
    log_path = game / (args.restart_phase + '-launch.log' if args.restart_phase else 'launch.log')
    receipt_path = game / (args.restart_phase + '-probe.json' if args.restart_phase else 'probe.json')
    identity = 'fabric-loader-' + target['dependencies']['loader'] + '-' + target['minecraft']
    command = get_minecraft_command(identity, str(runtime), {
        'username': 'CbbgParity', 'uuid': '00000000000000000000000000000001', 'token': '0',
        'executablePath': str(args.java.resolve()), 'gameDirectory': str(game),
        'jvmArguments': ['-Xmx2G', *display_jvm_arguments, '-Dfabric.client.gametest',
                        '-Dcbbg.test.dsa=' + (args.dsa_mode or 'auto'),
                        '-Dcbbg.test.restart=' + (args.restart_phase or ''),
                        '-Dfabric.client.gametest.modid=cbbg-renderer-test',
                        '-Dcbbg.test.backend=' + args.backend,
                        '-Dcbbg.test.compat=' + args.compat,
                        '-Dcbbg.test.modmenu.version=' + target['dependencies']['modMenu'],
                        '-Dcbbg.test.evidence=' + str(evidence)],
        'customResolution': True, 'resolutionWidth': '960', 'resolutionHeight': '540'})
    command += ['--graphicsBackend', args.backend, '--vulkanValidation', '--renderDebugLabels']
    runtime_bytes = args.runtime_lock.read_bytes()
    verify_runtime(runtime, identity, command, json.loads(runtime_bytes))
    java = java_identity(args.java)
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
    if args.restart_phase != 'verify':
        game.mkdir(parents=True, exist_ok=False)
    mods = game / 'mods'
    if args.restart_phase != 'verify':
        mods.mkdir()
    sources = {'candidate.jar': args.candidate, 'driver.jar': args.driver,
               'fabric-gametest-api.jar': args.gametest_api}
    sources.update({name + '.jar': path for name, path in dependencies.items()})
    receipt = {'target': args.target, 'backendRequested': args.backend, 'profile': args.compat,
               'timeoutSeconds': args.timeout, 'releaseAcceptance': False,
               'java': java, 'host': {'system': platform.system(), 'machine': platform.machine()},
               'catalogSha256': digest(ROOT / 'targets.json'),
               'scenarioSha256': hashlib.sha256(json.dumps(expected, separators=(',', ':')).encode()).hexdigest(),
               'renderer': target['renderer'], 'loaderProfile': identity,
               'requestedDsaMode': args.dsa_mode,
               'runtimeLockSha256': hashlib.sha256(runtime_bytes).hexdigest(),
               'dependencyLockSha256': hashlib.sha256(dependency_bytes).hexdigest(),
               'sourceHead': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT,
                                                     text=True).strip(),
               'sourceDirty': source_dirty(ROOT),
               'artifacts': {}}
    receipt['restartPhase'] = args.restart_phase
    if args.restart_phase == 'verify':
        previous_path = game / 'prepare-probe.json'
        previous = json.loads(previous_path.read_text())
        if (previous.get('restartPhase') != 'prepare' or previous.get('exitCode') != 0
                or 'failure' in previous or 'scenarios' not in previous):
            raise ValueError('Restart requires a successful prepare receipt')
        for key in ('target', 'backendRequested', 'profile', 'java', 'catalogSha256',
                    'scenarioSha256', 'runtimeLockSha256', 'dependencyLockSha256',
                    'sourceHead', 'sourceDirty'):
            if previous[key] != receipt[key]:
                raise ValueError('Restart identity changed: ' + key)
        if any(path.exists() for path in (evidence, log_path, receipt_path)):
            raise FileExistsError('Restart verify output already exists')
        if {p.name for p in mods.iterdir()} != set(previous['artifacts']):
            raise ValueError('Restart mod set changed')
        if {name: digest(source) for name, source in sources.items()} != previous['artifacts']:
            raise ValueError('Restart source artifacts changed')
        for name, expected_hash in previous['artifacts'].items():
            if digest(mods / name) != expected_hash:
                raise ValueError('Restart installed artifact changed: ' + name)
        for name, expected_hash in previous['evidence'].items():
            if digest(game / name) != expected_hash:
                raise ValueError('Restart prepare evidence changed: ' + name)
        state = restart_state(game, restart_shader)
        if state != previous['persistedState']:
            raise ValueError('Restart persisted state changed')
        receipt['prepareReceiptSha256'] = digest(previous_path)
        receipt['inputState'] = state
    try:
        if initial_config is not None:
            (game / 'config').mkdir()
            (game / 'config/cbbg.json').write_bytes(initial_config)
            evidence.mkdir(parents=True)
            (evidence / 'initial-cbbg.json').write_bytes(initial_config)
            receipt['initialConfigSha256'] = hashlib.sha256(initial_config).hexdigest()
        for name, source in sources.items():
            if args.restart_phase != 'verify':
                shutil.copyfile(source, mods / name)
            receipt['artifacts'][name] = digest(mods / name)
        verify_dependencies(target, args.compat,
                            {name: mods / (name + '.jar') for name in dependencies}, dependency_lock)
        verify_gametest_api(target, mods / 'fabric-gametest-api.jar', dependency_lock)
        with log_path.open('w') as log:
            result = subprocess.run(command, cwd=game, env=environment, stdout=log,
                                    stderr=subprocess.STDOUT, timeout=args.timeout)
        receipt['exitCode'] = result.returncode
        log_text = log_path.read_text()
        scenarios = validate_scenarios(expected,
            (evidence / 'scenarios.tsv').read_text(), log_text,
            result.returncode, args.backend)
        receipt['graphics'] = graphics_identity(log_text, args.backend)
        context = json.loads((evidence / 'graphics-context.json').read_text())
        if context.get('backend') != args.backend:
            raise ValueError('Recorded context backend mismatch')
        receipt['graphics']['context'] = context
        receipt['graphics']['contextProfile'] = context.get('profile')
        receipt['scenarios'] = scenarios
        if args.restart_phase:
            receipt['persistedState'] = restart_state(game, restart_shader)
    except Exception as failure:
        receipt['failure'] = {'type': type(failure).__name__, 'message': str(failure)}
        raise
    finally:
        evidence_files = [log_path, *sorted(evidence.rglob('*'))]
        receipt['evidence'] = {path.relative_to(game).as_posix(): digest(path)
                               for path in evidence_files if path.is_file()}
        receipt_path.write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'receipt': str(receipt_path), 'scenarios': receipt['scenarios']}))


if __name__ == '__main__':
    main()
