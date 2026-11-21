"""Packaged classic Fabric scenarios and the evidence their client emits."""

import hashlib
import json
import re
import struct
import zlib

CLASSIC_DRIVER = 'cbbg_classic_smoke'
CLASSIC_ENTRYPOINT = 'com.qb20nh.cbbg.parity.ClassicPackagedSmoke'
VULKAN_SCENARIOS = {'vulkan-lifecycle': 'vulkanLifecycle', 'vulkan-pixels': 'vulkanPixels',
                    'vulkan-settings': 'vulkanSettings'}
VULKAN_FORMAT_CASES = {'vulkan-fallback-float': ('full', 97),
                       'vulkan-fallback-eight-bit': ('both', 37)}
FORMAT_PROBE_AGENT = 'cbbg-format-probe-agent.jar'
SETTINGS_MANIFEST = 'stbn_64x64x8.sha256'
SETTINGS_NOISE_FRAMES = tuple(f'stbn_64x64x8_{index}.png' for index in range(8))
CONTEXT_MODS = {'forcegl2': 'forcegl20', 'threatengl': 'threatengl'}
REDUCED_GL_SCENARIO = 'forcegl2-mesa-3'
GLFW_CORE_PROFILE = 0x00032001
GLFW_COMPAT_PROFILE = 0x00032002
CLASSIC_OPTIONS = 'renderDistance:4\nsimulationDistance:5\nmaxFps:60\nguiScale:2\npauseOnLostFocus:false\n'
CLASSIC_IRIS_OPTIONS = 'enableShaders=false\n'
CLASSIC_RESTART_FILES = {'options.txt': 'options.txt', 'saves/cbbg-smoke/level.dat': 'world.dat'}


def classic_scenarios(metadata):
    declared = metadata.get('custom', {}).get('cbbg:scenarios', [])
    if (metadata.get('id') != CLASSIC_DRIVER
            or metadata.get('entrypoints', {}).get('client') != [CLASSIC_ENTRYPOINT]
            or not isinstance(declared, list) or 'world' not in declared
            or any(not isinstance(name, str) or not name for name in declared)
            or len(set(declared)) != len(declared)):
        raise ValueError('Unexpected classic test driver metadata')
    return declared


def validate_classic_choice(metadata, scenario, profile, backend):
    if scenario not in classic_scenarios(metadata):
        raise ValueError('Classic scenario is not declared by its packaged driver')
    if profile == 'vulkanmod' and backend == 'vulkan' and (
            scenario == 'world' or scenario in VULKAN_SCENARIOS or scenario in VULKAN_FORMAT_CASES):
        return
    if backend == 'opengl':
        if scenario == 'world' and profile != 'vulkanmod':
            return
        if scenario == REDUCED_GL_SCENARIO and profile == 'forcegl2':
            return
        if scenario == 'satin' and profile == 'satin':
            return
        if scenario in ('iris', 'renderscale') and scenario in profile.split('+'):
            return
    raise ValueError('Unsupported classic scenario, profile or backend')


def classic_config(backend, scenario='world'):
    settings = scenario == 'vulkan-settings'
    return {'mode': 'DEMO' if backend == 'vulkan' or scenario == 'iris' else 'ENABLED',
            'pixelFormat': 'RGBA32F' if scenario in VULKAN_FORMAT_CASES else 'RGBA16F',
            'stbnSize': 64 if settings else 16, 'stbnDepth': 8,
            'stbnSeed': 0, 'strength': 2, 'notifyChat': settings, 'notifyToast': settings}


def classic_viewport(scenario):
    return (803, 601) if scenario == 'vulkan-pixels' else (800, 600)


def includes_sodium(profile):
    return bool({'sodium', 'iris'} & set(profile.split('+')))


def classic_jvm_arguments(candidate, scenario, backend, profile, restart_phase=None):
    arguments = ['-Xmx1200m', '-XX:ActiveProcessorCount=4',
                 '-Dcbbg.test.jar=' + str(candidate),
                 '-Dcbbg.test.sodium=' + str(includes_sodium(profile)).lower()]
    if backend == 'vulkan':
        arguments += ['-Dorg.lwjgl.system.stackSize=1024', '-Dcbbg.test.vulkanMod=true']
        if scenario in VULKAN_SCENARIOS:
            arguments.append('-Dcbbg.test.' + VULKAN_SCENARIOS[scenario] + '=true')
        if scenario in VULKAN_FORMAT_CASES:
            mask, expected_format = VULKAN_FORMAT_CASES[scenario]
            arguments += ['-Dcbbg.test.vulkanFormatWarning=true', '-Dcbbg.test.vulkanFormatMask=' + mask,
                          '-Dcbbg.test.vulkanSceneFormat=' + str(expected_format)]
    else:
        arguments.append('-Dcbbg.test.compatProfile=' + profile)
        if profile in CONTEXT_MODS:
            arguments.append('-Dcbbg.test.contextMod=' + CONTEXT_MODS[profile])
        if scenario == 'satin':
            arguments.append('-Dcbbg.test.satin=true')
        elif scenario == 'renderscale':
            arguments.append('-Dcbbg.test.renderScale=true')
        elif scenario == 'iris':
            arguments.append('-Dcbbg.test.iris=true')
            iris_flags = {None: 'irisCycle', 'control': 'irisControl', 'verify': 'restart'}
            if restart_phase in iris_flags:
                arguments.append('-Dcbbg.test.' + iris_flags[restart_phase] + '=true')
    return arguments


def pixel_cache_files():
    def chunk(kind, content):
        return (struct.pack('>I', len(content)) + kind + content
                + struct.pack('>I', zlib.crc32(kind + content)))

    def pixel(x, y):
        rgb = [(x * (11 + channel * 4) + y * (19 + channel * 10) + channel * 37) % 256
               for channel in range(3)]
        return bytes(rgb + [255])

    rows = [b'\0' + b''.join(pixel(x, y) for x in range(16)) for y in range(16)]
    png = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(b''.join(rows))) + chunk(b'IEND', b''))
    files = {f'stbn_16x16x8_{index}.png': png for index in range(8)}
    checksum = hashlib.sha256(png).hexdigest()
    manifest = '# seed 0\n# algorithm 3\n' + ''.join(f'{checksum}  {name}\n' for name in files)
    files['stbn_16x16x8.sha256'] = manifest.encode()
    return files


def one_reading(log, pattern):
    readings = re.findall(pattern, log)
    if len(readings) != 1:
        raise ValueError('Missing or conflicting classic client evidence: ' + pattern)
    return readings[0]


def classic_gl_context(log, scenario, profile, gpu):
    mod, version, glfw_profile, gl30, gl32, glsl = one_reading(
        log, r'CBBG_CLASSIC_CONTEXT mod=(\w+) reference=false actual=(\d+\.\d+) profile=(\d+) '
             r'gl30=(true|false) gl32=(true|false) glsl=([^\r\n]+)')
    actual_version = tuple(map(int, version.split('.')))
    glfw_profile = int(glfw_profile)
    if (mod != CONTEXT_MODS[profile] or actual_version < (3, 0) or gl30 != 'true'
            or (gl32 == 'true') != (actual_version >= (3, 2))):
        raise ValueError('Unexpected OpenGL changer or capabilities')
    if profile == 'forcegl2':
        if (glfw_profile != (GLFW_COMPAT_PROFILE if gl32 == 'true' else 0)
                or 'Overriding GLFW_CONTEXT_VERSION_MAJOR: 3 -> 2' not in log
                or 'Overriding GLFW_CONTEXT_VERSION_MINOR: 2 -> 0' not in log):
            raise ValueError('ForceGL2 did not produce a usable compatibility context')
        if (scenario == REDUCED_GL_SCENARIO
                and (actual_version != (3, 0) or gl32 != 'false' or not gpu.startswith('llvmpipe'))):
            raise ValueError('ForceGL2 reduced-context run requires actual Mesa software OpenGL 3.0')
    elif (actual_version < (4, 6) or glfw_profile != GLFW_CORE_PROFILE
          or 'Threatened Minecraft to use GLFW_CONTEXT_VERSION_MAJOR 4' not in log
          or 'Threatened Minecraft to use GLFW_CONTEXT_VERSION_MINOR 6' not in log):
        raise ValueError('ThreatenGL did not produce the requested OpenGL core context')
    return {'changer': mod, 'version': version, 'glfwProfile': glfw_profile,
            'openGL30': gl30 == 'true', 'openGL32': gl32 == 'true', 'glsl': glsl}


def renderscale_captures(log, width, height):
    expected = []
    captures = {}
    for scale in (0.5, 1.0, 2.0):
        world = f'{int(width * scale)}x{int(height * scale)}'
        default_filter = 'linear' if scale > 1 else 'nearest'
        block = 2 if scale < 1 else 1
        for mode, format_id, filtering, grid in (
                ('enabled', 34842, default_filter, block), ('enabled', 34842, 'linear', block),
                ('enabled', 34836, default_filter, block), ('disabled', 32856, default_filter, 0)):
            if grid:
                captures[f'grid-{len(expected)}.png'] = (width, height)
            expected.append(f'scale={scale} mode={mode} format={format_id} filter={filtering} '
                            f'world={world} block={grid}')
    cases = re.findall(r'CBBG_CLASSIC_RENDERSCALE_CASE ([^\r\n]+)', log)
    passed = int(one_reading(log, r'CBBG_CLASSIC_RENDERSCALE_PASS cases=(\d+)(?=\r?\n|\Z)'))
    if cases != expected or passed != len(expected):
        raise ValueError('Incomplete RenderScale framebuffer and grid checks')
    return captures


def iris_shader_state(path, enabled):
    lines = path.read_text().splitlines()
    if ([line for line in lines if line.startswith('enableShaders=')]
            != ['enableShaders=' + str(enabled).lower()]
            or enabled and 'shaderPack=cbbg-parity' not in lines):
        raise ValueError('Classic Iris persisted shader state differs')


def iris_captures(log, evidence, width, height, restart_phase):
    events = re.findall(r'CBBG_CLASSIC_IRIS_([^\r\n]+)', log)
    shader_captures = {
        None: ['iris-active.png', 'iris-reloaded.png'],
        'prepare': ['iris-active.png', 'iris-reloaded.png'],
        'verify': ['iris-restart-active.png'],
        'control': [],
    }[restart_phase]
    expected = ['SUPPRESSED ' + name + ' format=RGBA8 draws=0' for name in shader_captures]
    if restart_phase in (None, 'prepare'):
        expected.append('RELOAD_PASS')
    marker = {None: 'CYCLE', 'verify': 'RESTART', 'control': 'CONTROL'}.get(restart_phase)
    if marker:
        presentations = re.fullmatch(marker + r'_PASS presentations=(\d+)', events[-1]) if events else None
        if not presentations or int(presentations[1]) < 10:
            raise ValueError('Iris shader check did not show resumed or control presentations')
        events = events[:-1]
    if events != expected:
        raise ValueError('Incomplete Iris activation, reload or restart evidence')
    if restart_phase:
        iris_shader_state(evidence / 'final-iris.properties', restart_phase == 'prepare')
    if (evidence / 'final-cbbg.json').read_bytes() != (evidence / 'initial-cbbg.json').read_bytes():
        raise ValueError('Iris changed the saved CBBG configuration')
    other_captures = {
        None: ['iris-inactive.png', 'iris-restored.png'],
        'prepare': ['iris-inactive.png'],
        'verify': ['iris-restored.png'],
        'control': ['iris-control.png'],
    }[restart_phase]
    return {name: (width, height) for name in shader_captures + other_captures}


def vulkan_settings_captures(log, evidence, width, height):
    events = re.findall(r'CBBG_CLASSIC_VULKAN_((?:CONFIRMATION|TOAST|SETTINGS) [^\r\n]+)', log)
    if events != ['CONFIRMATION cancelled=unchanged', 'TOAST rendered=generating',
                  'TOAST rendered=complete', 'SETTINGS confirmations=2 chat=2 toasts=2']:
        raise ValueError('Incomplete Vulkan settings confirmation or notifications')
    if (log.count('Starting Async STBN Math Generation (64x64x8)') != 2
            or log.count('STBN Math Complete in ') != 2):
        raise ValueError('Expected one cold generation and one confirmed regeneration')
    cache = evidence / 'settings-cache'
    manifest = (cache / SETTINGS_MANIFEST).read_bytes()
    if manifest != (cache / 'warm.sha256').read_bytes():
        raise ValueError('Vulkan settings regeneration changed the frozen noise manifest')
    lines = manifest.decode('utf-8').splitlines()
    if lines[:2] != ['# seed 0', '# algorithm 3'] or len(lines) != 2 + len(SETTINGS_NOISE_FRAMES):
        raise ValueError('Unexpected Vulkan settings noise manifest')
    for name, line in zip(SETTINGS_NOISE_FRAMES, lines[2:]):
        checksum = hashlib.sha256((cache / name).read_bytes()).hexdigest()
        if line != f'{checksum}  {name}':
            raise ValueError('Vulkan settings noise frame differs: ' + name)
    captures = {name: (width, height) for name in (
        'vulkan-world.png', 'vulkan-settings.png', 'vulkan-confirmation.png',
        'vulkan-generation-complete.png')}
    captures.update({f'settings-cache/{name}': (64, 64) for name in SETTINGS_NOISE_FRAMES})
    return captures


def vulkan_fallback_captures(log, evidence, scenario, scenes, width, height):
    mask, expected_format = VULKAN_FORMAT_CASES[scenario]
    queries = int(one_reading(log, r'CBBG_FORMAT_AGENT querySites=(\d+)(?=\r?\n|\Z)'))
    actual_mask = set(re.findall(r'CBBG_CLASSIC_VULKAN_MASK format=(\d+) feature=blend', log))
    expected_mask = {'97', '109'} if mask == 'both' else {'109'}
    if (queries != 1 or actual_mask != expected_mask
            or scenes != [(str(expected_format), 'false')] * 2):
        raise ValueError('Vulkan format probe or framebuffer fallback differs')
    format_id, draws = one_reading(log, r'CBBG_CLASSIC_VULKAN_FORMAT_WARNING format=(\d+) draws=(\d+)')
    labels = re.findall(r'CBBG_CLASSIC_VULKAN_FORMAT_LABEL x=\d+ y=\d+ width=[1-9]\d* height=[1-9]\d* '
                        r'text=([^\r\n]+)', log)
    if (int(format_id) != expected_format or (int(draws) > 0) != (expected_format == 37)
            or len(labels) != (1 if expected_format == 37 else 0)):
        raise ValueError('Vulkan localized fallback warning differs')
    if (evidence / 'final-cbbg.json').read_bytes() != (evidence / 'initial-cbbg.json').read_bytes():
        raise ValueError('Vulkan fallback changed the saved precision preference')
    return {name: (width, height) for name in ('vulkan-world.png', 'vulkan-format-settings.png')}


def classic_result(scenario, profile, backend, log, evidence, exit_code, restart_phase=None):
    if (type(exit_code) is not int or exit_code != 0
            or re.search(r'VUID-|Validation Error|VK_ERROR_DEVICE_LOST|AssertionError|Game crashed!'
                         r'|GL_INVALID_(?:ENUM|VALUE|OPERATION|FRAMEBUFFER_OPERATION)|GL_OUT_OF_MEMORY', log)):
        raise ValueError('Classic client or graphics validation failed')
    if restart_phase and (scenario != 'iris' or 'iris' not in profile.split('+')):
        raise ValueError('Classic restart phases require the Iris scenario')
    if json.loads((evidence / 'initial-cbbg.json').read_text()) != classic_config(backend, scenario):
        raise ValueError('Classic initial config or options differ')
    if restart_phase == 'verify':
        iris_shader_state(evidence / 'initial-iris.properties', True)
    else:
        if (evidence / 'initial-options.txt').read_text() != CLASSIC_OPTIONS:
            raise ValueError('Classic initial config or options differ')
        if ('iris' in profile.split('+')
                and (evidence / 'initial-iris.properties').read_text() != CLASSIC_IRIS_OPTIONS):
            raise ValueError('Classic Iris initial config differs')
    width, height = classic_viewport(scenario)
    captures = {'enabled.png': (width, height), 'disabled.png': (width, height)}
    if backend == 'vulkan':
        if 'CBBG_CLASSIC_OPENGL ' in log:
            raise ValueError('VulkanMod client also reported an OpenGL context')
        mod, gpu, api = one_reading(
            log, r'CBBG_CLASSIC_VULKAN_DEVICE mod=(\S+) device=(.+?) api=(\d+(?:\.\d+)+) glfw=no-api reference=false(?=\r?\n|\Z)')
        presentations = int(one_reading(log, r'CBBG_CLASSIC_VULKAN_WORLD presentations=(\d+)'))
        if presentations < 12:
            raise ValueError('Vulkan world did not present enough frames')
        graphics = {'backend': 'vulkan', 'provider': 'vulkanmod', 'gpu': gpu,
                    'apiVersion': api, 'providerVersion': mod, 'glfwClientApi': 'none'}
        captures = {'vulkan-world.png': (width, height)}
        scenes = re.findall(r'CBBG_CLASSIC_VULKAN_SCENE format=(\d+) disabled=(true|false)', log)
        if scenario in ('world', 'vulkan-settings'):
            if scenes != [('97', 'false')]:
                raise ValueError('Vulkan world did not use the expected RGBA16F scene')
            if scenario == 'vulkan-settings':
                captures = vulkan_settings_captures(log, evidence, width, height)
        elif scenario == 'vulkan-lifecycle':
            resized = (width - 64, height - 48)
            if (tuple(map(int, one_reading(log, r'CBBG_CLASSIC_VULKAN_LIFECYCLE width=(\d+) height=(\d+)'))) != resized
                    or scenes not in ([('97', 'false'), (swap, 'true'), ('109', 'false')]
                                      + [('97', 'false')] * 3 for swap in ('37', '44'))
                    or 'CBBG_CLASSIC_VULKAN_NOISE sampled=32 pending-edit=16' not in log):
                raise ValueError('Incomplete Vulkan lifecycle evidence')
            captures = {name: (width, height) for name in (
                'vulkan-demo-before.png', 'vulkan-disabled.png', 'vulkan-rgba32f.png')}
            captures.update({'vulkan-world.png': resized, 'vulkan-resized.png': resized})
        elif scenario in VULKAN_FORMAT_CASES:
            captures = vulkan_fallback_captures(log, evidence, scenario, scenes, width, height)
        else:
            comparisons = re.findall(r'CBBG_CLASSIC_VULKAN_PIXELS mode=(\w+) channels=(\d+) mismatches=(\d+) '
                                     r'topOriginMismatches=\d+ alphaMismatches=(\d+) changedChannels=(\d+)', log)
            if (scenes not in ([('97', 'false')] * 2 + [(swap, 'true')] for swap in ('37', '44'))
                    or [row[0] for row in comparisons] != ['demo', 'enabled', 'disabled']
                    or any(int(count) != width * height * 3 or rgb != '0' or alpha != '0'
                           or (int(changed) > 0) != (mode != 'disabled')
                           for mode, count, rgb, alpha, changed in comparisons)):
                raise ValueError('Incomplete or mismatched Vulkan pixel comparisons')
            captures.update({f'vulkan-pixels-{mode}.png': (width, height)
                             for mode, *_ in comparisons})
            for name, content in pixel_cache_files().items():
                if (evidence / 'pixel-input' / name).read_bytes() != content:
                    raise ValueError('Vulkan pixel fixture input differs')
    else:
        if 'CBBG_CLASSIC_VULKAN_DEVICE ' in log:
            raise ValueError('OpenGL client also reported a VulkanMod device')
        gpu, driver, sodium = one_reading(
            log, r'CBBG_CLASSIC_OPENGL (.+?) / (.+?) sodium=(true|false)(?=\r?\n|\Z)')
        if (sodium == 'true') != includes_sodium(profile):
            raise ValueError('OpenGL profile differs')
        if scenario == 'satin':
            stages = re.findall(r'CBBG_CLASSIC_SATIN_STAGE ([^\r\n]+)', log)
            size = f'{width}x{height}'
            if stages != [f'initial {size} cases=6', f'reloaded {size} cases=12',
                          f'smaller {width - 64}x{height - 48} cases=18', f'restored {size} cases=24']:
                raise ValueError('Incomplete Satin framebuffer checks')
            one_reading(log, r'CBBG_CLASSIC_SATIN_PASS cases=(24) readableDepth=3(?=\r?\n|\Z)')
            captures = {}
        elif scenario == 'renderscale':
            captures = renderscale_captures(log, width, height)
        elif scenario == 'iris':
            captures = iris_captures(log, evidence, width, height, restart_phase)
        elif int(one_reading(log, r'CBBG_CLASSIC_SMOKE_PASS presentations=(\d+)')) < 10:
            raise ValueError('OpenGL world did not present enough frames')
        graphics = {'backend': 'opengl', 'provider': 'minecraft', 'gpu': gpu, 'driver': driver}
        if profile in CONTEXT_MODS:
            graphics['context'] = classic_gl_context(log, scenario, profile, gpu)
    for name, dimensions in captures.items():
        with (evidence / name).open('rb') as stream:
            header = stream.read(24)
            stream.seek(0, 2)
            if stream.tell() >= 36:
                stream.seek(-12, 2)
            trailer = stream.read()
        if (len(header) != 24 or header[:16] != b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR'
                or struct.unpack('>II', header[16:24]) != dimensions
                or trailer != b'\x00\x00\x00\x00IEND\xaeB\x60\x82'):
            raise ValueError('Classic screenshot dimensions or PNG structure differ: ' + name)
    return {'completedEntrypoints': 1, 'backend': backend, 'releaseAcceptance': False}, graphics
