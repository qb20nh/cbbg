"""Packaged classic Fabric scenarios and the evidence their client emits."""

import hashlib
import json
import os
import re
import shutil
import struct
import zlib

CLASSIC_DRIVER = 'cbbg_classic_smoke'
CLASSIC_ENTRYPOINT = 'com.qb20nh.cbbg.parity.ClassicPackagedSmoke'
VULKAN_SCENARIOS = {'vulkan-lifecycle': 'vulkanLifecycle', 'vulkan-pixels': 'vulkanPixels',
                    'vulkan-settings': 'vulkanSettings'}
VULKAN_FORMAT_CASES = {'vulkan-fallback-float': ('full', 97),
                       'vulkan-fallback-eight-bit': ('both', 37)}
FORMAT_PROBE_AGENT = 'cbbg-format-probe-agent.jar'
VMA_PROBE_AGENT = 'cbbg-vma-probe-agent.jar'
VULKAN_VMA_CASES = {
    'vulkan-vma-depth': 'depth',
    'vulkan-vma-float': 'float',
    'vulkan-vma-all': 'all',
    'vulkan-vma-resize': 'resize',
}
VULKAN_NATIVE_CASES = {
    'vulkan-native-view': ('ImageView', 'image view', ('Image', 'Image', 'ImageView')),
    'vulkan-native-pass': ('RenderPass', 'render pass',
                           ('Image', 'Image', 'ImageView', 'ImageView', 'RenderPass')),
    'vulkan-native-framebuffer': ('Framebuffer', 'framebuffer',
                                  ('Image', 'Image', 'ImageView', 'ImageView',
                                   'RenderPass', 'RenderPass', 'Framebuffer')),
}
VULKAN_SAMPLER_CASES = {'vulkan-sampler-scene': 'scene', 'vulkan-sampler-noise': 'noise'}
VULKAN_PIPELINE_ERRORS = {
    'shader': 'CBBG test shader failure',
    'native': 'Failed to create graphics pipeline cbbg_vulkanmod_dither: VK_ERROR_OUT_OF_DEVICE_MEMORY',
    'constructor': 'Failed to allocate descriptor sets. Result:-2',
}
VULKAN_PIPELINE_CASES = {'vulkan-pipeline-' + fault: fault for fault in VULKAN_PIPELINE_ERRORS}
VULKAN_PIPELINE_CLASSES = (
    'SPIRVUtils', 'GraphicsPipeline', 'GraphicsPipeline$VertexInputDescription',
    'Pipeline', 'Pipeline$DescriptorSets',
)
SETTINGS_MANIFEST = 'stbn_64x64x8.sha256'
SETTINGS_NOISE_FRAMES = tuple(f'stbn_64x64x8_{index}.png' for index in range(8))
CONTEXT_MODS = {'forcegl2': 'forcegl20', 'threatengl': 'threatengl'}
REDUCED_GL_SCENARIO = 'forcegl2-mesa-3'
CLASSIC_MODE_RESTART = 'mode-restart'
CLASSIC_MODE_RESTART_PROFILES = ('none', 'sodium')
OPENGL_PIXELS = 'opengl-pixels'
OPENGL_LIFECYCLE = 'opengl-lifecycle'
CLASSIC_STACK_PROFILES = (
    'modmenu+sodium+satin+immediatelyfast+chatpatches+renderscale',
    'modmenu+sodium+satin+immediatelyfast+chatpatches+iris+renderscale',
)
CLASSIC_CACHE_SCENARIOS = ('cache-cold', 'cache-warm', 'cache-damaged')
CLASSIC_CACHE_PROFILES = ('none', 'sodium')
CLASSIC_CACHE_MANIFEST = 'stbn_16x16x8.sha256'
CLASSIC_CACHE_FRAMES = tuple(f'stbn_16x16x8_{index}.png' for index in range(8))
CLASSIC_CACHE_TIME = 946684800_000_000_000
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
            scenario == 'world' or scenario in VULKAN_SCENARIOS or vulkan_probe_agent(scenario)):
        return
    if backend == 'opengl':
        if scenario == 'world' and profile != 'vulkanmod':
            return
        if scenario == REDUCED_GL_SCENARIO and profile == 'forcegl2':
            return
        if scenario == CLASSIC_MODE_RESTART and profile in CLASSIC_MODE_RESTART_PROFILES:
            return
        if scenario in (OPENGL_PIXELS, OPENGL_LIFECYCLE) and profile in ('none', 'sodium'):
            return
        if scenario == 'stack' and profile in CLASSIC_STACK_PROFILES:
            return
        if scenario in CLASSIC_CACHE_SCENARIOS and profile in CLASSIC_CACHE_PROFILES:
            return
        if scenario == 'satin' and profile == 'satin':
            return
        if scenario in ('iris', 'renderscale') and scenario in profile.split('+'):
            return
    raise ValueError('Unsupported classic scenario, profile or backend')


def classic_config(backend, scenario='world'):
    settings = scenario == 'vulkan-settings'
    full_precision = (scenario in VULKAN_FORMAT_CASES or scenario in VULKAN_NATIVE_CASES
                      or VULKAN_VMA_CASES.get(scenario) in ('depth', 'float', 'all'))
    demo = backend == 'vulkan' or scenario in ('iris', 'stack', OPENGL_PIXELS, OPENGL_LIFECYCLE)
    return {'mode': 'DEMO' if demo else 'ENABLED',
            'pixelFormat': 'RGBA32F' if full_precision else 'RGBA16F',
            'stbnSize': 64 if settings else 16, 'stbnDepth': 8,
            'stbnSeed': 0, 'strength': 2, 'notifyChat': settings, 'notifyToast': settings}


def classic_viewport(scenario):
    return (803, 601) if scenario in ('vulkan-pixels', OPENGL_PIXELS) else (800, 600)


def includes_sodium(profile):
    return bool({'sodium', 'iris'} & set(profile.split('+')))


def vulkan_probe_agent(scenario):
    if scenario in VULKAN_FORMAT_CASES:
        return FORMAT_PROBE_AGENT
    if (scenario in VULKAN_VMA_CASES or scenario in VULKAN_NATIVE_CASES
            or scenario in VULKAN_SAMPLER_CASES or scenario in VULKAN_PIPELINE_CASES):
        return VMA_PROBE_AGENT
    return None


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
        if scenario in VULKAN_VMA_CASES:
            mode = VULKAN_VMA_CASES[scenario]
            arguments.append('-Dcbbg.test.vulkanAllocation=' + mode)
            if mode == 'resize':
                arguments += ['-Dcbbg.test.vulkanLifecycle=true', '-Dcbbg.test.vulkanResizedFormat=37',
                              '-Dcbbg.test.vulkanReloadedFormat=97']
            else:
                expected = {'depth': 97, 'float': 37, 'all': 44}[mode]
                arguments.append('-Dcbbg.test.vulkanSceneFormat=' + str(expected))
                if mode == 'all':
                    arguments.append('-Dcbbg.test.vulkanOriginalTarget=true')
        if scenario in VULKAN_NATIVE_CASES:
            resource, _, _ = VULKAN_NATIVE_CASES[scenario]
            arguments += ['-Dcbbg.test.vulkanNativeFailure=' + resource, '-Dcbbg.test.vulkanSceneFormat=97']
        if scenario in VULKAN_SAMPLER_CASES:
            arguments += ['-Dcbbg.test.vulkanSamplerRecovery=true',
                          '-Dcbbg.test.vulkanSamplerFault=' + VULKAN_SAMPLER_CASES[scenario]]
        if scenario in VULKAN_PIPELINE_CASES:
            fault = VULKAN_PIPELINE_CASES[scenario]
            arguments += ['-Dcbbg.test.vulkanPipelineRecovery=true', '-Dcbbg.test.vulkanPipelineFault=' + fault]
            if fault != 'native':
                arguments.append('-Dcbbg.test.vulkanTrackConstruction=true')
    else:
        arguments.append('-Dcbbg.test.compatProfile=' + profile)
        if profile in CONTEXT_MODS:
            arguments.append('-Dcbbg.test.contextMod=' + CONTEXT_MODS[profile])
        if scenario == OPENGL_PIXELS:
            arguments.append('-Dcbbg.test.openGlPixels=true')
        elif scenario == OPENGL_LIFECYCLE:
            arguments.append('-Dcbbg.test.openGlLifecycle=true')
        elif scenario == 'satin':
            arguments.append('-Dcbbg.test.satin=true')
        elif scenario == 'renderscale':
            arguments.append('-Dcbbg.test.renderScale=true')
        elif scenario == 'stack':
            arguments.append('-Dcbbg.test.stack=true')
            if 'iris' in profile.split('+'):
                arguments += ['-Dcbbg.test.iris=true', '-Dcbbg.test.irisCycle=true']
        elif scenario == 'iris':
            arguments.append('-Dcbbg.test.iris=true')
            iris_flags = {None: 'irisCycle', 'control': 'irisControl', 'verify': 'restart'}
            if restart_phase in iris_flags:
                arguments.append('-Dcbbg.test.' + iris_flags[restart_phase] + '=true')
        elif scenario == CLASSIC_MODE_RESTART and restart_phase == 'verify':
            arguments.append('-Dcbbg.test.restart=true')
    return arguments


def pixel_cache_files(offset=0):
    def chunk(kind, content):
        return (struct.pack('>I', len(content)) + kind + content
                + struct.pack('>I', zlib.crc32(kind + content)))

    def pixel(x, y):
        rgb = [(x * (11 + channel * 4) + y * (19 + channel * 10) + channel * 37 + offset) % 256
               for channel in range(3)]
        return bytes(rgb + [255])

    rows = [b'\0' + b''.join(pixel(x, y) for x in range(16)) for y in range(16)]
    png = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(b''.join(rows))) + chunk(b'IEND', b''))
    files = dict.fromkeys(CLASSIC_CACHE_FRAMES, png)
    checksum = hashlib.sha256(png).hexdigest()
    manifest = '# seed 0\n# algorithm 3\n' + ''.join(f'{checksum}  {name}\n' for name in files)
    files[CLASSIC_CACHE_MANIFEST] = manifest.encode()
    return files


def classic_cache_input(scenario):
    if scenario == 'cache-cold':
        return {}
    files = pixel_cache_files()
    if scenario == 'cache-damaged':
        # Keep a valid PNG under a checksum for a different tile.
        frame = CLASSIC_CACHE_FRAMES[3]
        files[frame] = pixel_cache_files(offset=1)[frame]
    return files


def validate_pixel_cache(evidence):
    for name, content in pixel_cache_files().items():
        if (evidence / 'pixel-input' / name).read_bytes() != content:
            raise ValueError('Pixel fixture input differs')


def prepare_classic_cache(game, evidence, scenario):
    cache = game / '.cbbg'
    retained = evidence / 'cache-input'
    retained.mkdir()
    files = classic_cache_input(scenario)
    if files:
        cache.mkdir()
    for name, content in files.items():
        path = cache / name
        path.write_bytes(content)
        os.utime(path, ns=(CLASSIC_CACHE_TIME, CLASSIC_CACHE_TIME))
        (retained / name).write_bytes(content)


def retain_classic_cache(game, evidence, scenario):
    cache = game / '.cbbg'
    if {path.name for path in cache.iterdir()} != {CLASSIC_CACHE_MANIFEST, *CLASSIC_CACHE_FRAMES}:
        raise ValueError('Classic startup cache output inventory differs')
    retained = evidence / 'cache-output'
    retained.mkdir()
    for name in (CLASSIC_CACHE_MANIFEST, *CLASSIC_CACHE_FRAMES):
        path = cache / name
        if scenario == 'cache-warm' and path.stat().st_mtime_ns != CLASSIC_CACHE_TIME:
            raise ValueError('Warm classic startup rewrote cache: ' + name)
        shutil.copyfile(path, retained / name)


def classic_cache_captures(log, evidence, scenario):
    expected = classic_cache_input(scenario)
    cached = evidence / 'cache-input'
    actual = {path.name: path.read_bytes() for path in cached.iterdir()} if cached.exists() else {}
    if actual != expected:
        raise ValueError('Classic startup cache input differs')
    markers = re.findall(r'Starting Async STBN Math Generation \(16x16x8\)|STBN Math Complete in '
                         r'|STBN Images generated from math fields\.|STBN Frames loaded from cache\.'
                         r'|CBBG_CLASSIC_SMOKE_PASS', log)
    warm = scenario == 'cache-warm'
    work = (['STBN Frames loaded from cache.'] if warm else
            ['Starting Async STBN Math Generation (16x16x8)', 'STBN Math Complete in ',
             'STBN Images generated from math fields.'])
    if markers != work + ['CBBG_CLASSIC_SMOKE_PASS']:
        raise ValueError('Classic startup did not load or regenerate the expected noise cache')
    output = evidence / 'cache-output'
    files = {path.name: path.read_bytes() for path in output.iterdir()}
    if files.keys() != {CLASSIC_CACHE_MANIFEST, *CLASSIC_CACHE_FRAMES}:
        raise ValueError('Classic startup cache output inventory differs')
    checksums = ''.join(f'{hashlib.sha256(files[name]).hexdigest()}  {name}\n' for name in CLASSIC_CACHE_FRAMES)
    if (files[CLASSIC_CACHE_MANIFEST] != ('# seed 0\n# algorithm 3\n' + checksums).encode()
            or warm and files != actual or not warm and files == pixel_cache_files()):
        raise ValueError('Classic startup cache output does not match its expected content')
    return {f'cache-output/{name}': (16, 16) for name in CLASSIC_CACHE_FRAMES}


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


def validate_satin(log, width, height):
    stages = re.findall(r'CBBG_CLASSIC_SATIN_STAGE ([^\r\n]+)', log)
    size = f'{width}x{height}'
    if stages != [f'initial {size} cases=6', f'reloaded {size} cases=12',
                  f'smaller {width - 64}x{height - 48} cases=18', f'restored {size} cases=24']:
        raise ValueError('Incomplete Satin framebuffer checks')
    one_reading(log, r'CBBG_CLASSIC_SATIN_PASS cases=(24) readableDepth=3(?=\r?\n|\Z)')


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


def iris_captures(log, evidence, width, height, restart_phase, *, initial_config='initial-cbbg.json'):
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
    if (evidence / 'final-cbbg.json').read_bytes() != (evidence / initial_config).read_bytes():
        raise ValueError('Iris changed the saved CBBG configuration')
    other_captures = {
        None: ['iris-inactive.png', 'iris-restored.png'],
        'prepare': ['iris-inactive.png'],
        'verify': ['iris-restored.png'],
        'control': ['iris-control.png'],
    }[restart_phase]
    return {name: (width, height) for name in shader_captures + other_captures}


def classic_stack_captures(log, evidence, profile, config, width, height):
    iris = 'iris' in profile.split('+')
    sequence = re.findall(r'CBBG_CLASSIC_(STACK_\w+_PASS|SATIN_PASS|RENDERSCALE_PASS|IRIS_CYCLE_PASS)\b', log)
    expected = ['STACK_IMMEDIATELYFAST_PASS', 'STACK_MODMENU_PASS', 'STACK_CHAT_PASS',
                'SATIN_PASS', 'RENDERSCALE_PASS']
    if iris:
        expected.append('IRIS_CYCLE_PASS')
    if sequence != expected:
        raise ValueError('Incomplete or unordered combined compatibility checks')
    validate_satin(log, width, height)
    captures = renderscale_captures(log, width, height)
    if iris:
        before_iris = 'stack-before-iris.json'
        if json.loads((evidence / before_iris).read_text()) != config:
            raise ValueError('Classic stack changed CBBG settings before enabling Iris')
        captures.update(iris_captures(log, evidence, width, height, None, initial_config=before_iris))
    elif ('CBBG_CLASSIC_IRIS_' in log
          or json.loads((evidence / 'final-cbbg.json').read_text()) != dict(config, mode='DISABLED')):
        raise ValueError('Classic stack ended with unexpected shader or configuration state')
    return captures


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


def vulkan_lifecycle_captures(log, scenes, width, height, resized_format='97'):
    resized = (width - 64, height - 48)
    actual_size = tuple(map(int, one_reading(log, r'CBBG_CLASSIC_VULKAN_LIFECYCLE width=(\d+) height=(\d+)')))
    expected = ([('97', 'false'), (swap, 'true'), ('109', 'false'), ('97', 'false')]
                + [(resized_format, 'false'), ('97', 'false')] for swap in ('37', '44'))
    if (actual_size != resized or scenes not in expected
            or 'CBBG_CLASSIC_VULKAN_NOISE sampled=32 pending-edit=16' not in log):
        raise ValueError('Incomplete Vulkan lifecycle evidence')
    captures = {name: (width, height) for name in (
        'vulkan-demo-before.png', 'vulkan-disabled.png', 'vulkan-rgba32f.png')}
    captures.update({'vulkan-world.png': resized, 'vulkan-resized.png': resized})
    return captures


def vulkan_vma_captures(log, evidence, scenario, scenes, width, height):
    mode = VULKAN_VMA_CASES[scenario]
    sites = one_reading(log, r'CBBG_ALLOC_AGENT allocationSites=(\d+) freeSites=(\d+)(?=\r?\n|\Z)')
    # Verify the ordered failure and cleanup trace, including cleanup before the failed scene is retried.
    trace = re.findall(r'CBBG_CLASSIC_VULKAN_(REJECT [^\r\n]+|PARTIAL_FREE [^\r\n]+)'
                       r'|VulkanMod scene allocation failed for (\w+) at (\d+x\d+): '
                       r'Failed to create image: VK_ERROR_OUT_OF_DEVICE_MEMORY', log)
    observed = [marker or f'{pixel_format} {extent}' for marker, pixel_format, extent in trace]
    size = f'{width}x{height}'
    resize = f'{width - 64}x{height - 48}'
    float_failures = ['REJECT color format=109', f'RGBA32F {size}',
                      'REJECT color format=97', f'RGBA16F {size}']
    depth_failure = ['REJECT depth format=129', 'PARTIAL_FREE count=1']
    traces = {'depth': depth_failure + [f'RGBA32F {size}'], 'float': float_failures,
              'all': float_failures + ['REJECT color format=37', f'RGBA8 {size}'],
              'resize': depth_failure + [f'RGBA16F {resize}']}
    if sites != ('1', '1') or observed != traces[mode]:
        raise ValueError('Incomplete Vulkan VMA failure and cleanup evidence')
    saved = json.loads((evidence / 'final-cbbg.json').read_text())
    expected_config = classic_config('vulkan', scenario)
    if mode == 'resize':
        expected_config['stbnSize'] = 32
    if saved != expected_config:
        raise ValueError('Vulkan VMA failure changed the saved settings')
    if mode == 'resize':
        return vulkan_lifecycle_captures(log, scenes, width, height, resized_format='37')
    expected_scene = {'depth': ('97', 'false'), 'float': ('37', 'false'), 'all': ('44', 'true')}[mode]
    if scenes != [expected_scene]:
        raise ValueError('Unexpected Vulkan VMA fallback framebuffer')
    return {'vulkan-world.png': (width, height)}


def vulkan_native_captures(log, evidence, scenario, scenes, width, height):
    resource, error_kind, resources = VULKAN_NATIVE_CASES[scenario]
    if log.count('CBBG_ALLOC_AGENT allocationSites=1 freeSites=1') != 1:
        raise ValueError('Vulkan native case did not observe image allocations and releases')
    hooks = re.findall(r'CBBG_NATIVE_AGENT class=(\S+) calls=(\d+)(?=\r?\n|\Z)', log)
    mod_hooks = [(name, calls) for name, calls in hooks if name.startswith('net/vulkanmod/')]
    candidate_hooks = [(name, calls) for name, calls in hooks if name.startswith('com/qb20nh/cbbg/')]
    if (len(hooks) != 4 or len(candidate_hooks) != 1 or candidate_hooks[0][1] != '2'
            or sorted(mod_hooks) != [
                ('net/vulkanmod/vulkan/framebuffer/Framebuffer', '3'),
                ('net/vulkanmod/vulkan/framebuffer/RenderPass', '2'),
                ('net/vulkanmod/vulkan/texture/VulkanImage', '3')]):
        raise ValueError('Vulkan native probe call sites differ')
    failure = 'CBBG_CLASSIC_VULKAN_NATIVE_FAIL resource=' + resource
    error = (f'VulkanMod scene allocation failed for RGBA32F at {width}x{height}: '
             f'Failed to create {error_kind}: VK_ERROR_OUT_OF_DEVICE_MEMORY')
    if (log.count(failure) != 1 or log.count('VulkanMod scene allocation failed for ') != 1
            or error not in log or log.index(error) < log.index(failure)):
        raise ValueError('Vulkan native allocation failure differs')
    before, _, remaining = log.partition(failure)
    rollback, _, recovered = remaining.partition(error)
    created = re.findall(r'CBBG_CLASSIC_VULKAN_NATIVE_CREATED resource=(\w+)', before)
    released = re.findall(r'CBBG_CLASSIC_VULKAN_NATIVE_RELEASED resource=(\w+)', rollback)
    if (sorted(created) != sorted(resources) or sorted(released) != sorted(resources)
            or 'CBBG_CLASSIC_VULKAN_NATIVE_RELEASED' in before + recovered
            or 'CBBG_CLASSIC_VULKAN_NATIVE_CREATED' in remaining):
        raise ValueError('Vulkan native rollback did not release every handle before retry')
    if scenes != [('97', 'false')] or 'CBBG_CLASSIC_VULKAN_SCENE format=97 disabled=false' not in recovered:
        raise ValueError('Vulkan native rollback did not restore the expected framebuffer')
    if (evidence / 'final-cbbg.json').read_bytes() != (evidence / 'initial-cbbg.json').read_bytes():
        raise ValueError('Vulkan native rollback changed the saved precision preference')
    return {'vulkan-world.png': (width, height)}


def vulkan_sampler_captures(log, evidence, scenario, scenes, width, height):
    scene = VULKAN_SAMPLER_CASES[scenario] == 'scene'
    hooks = re.findall(r'CBBG_SAMPLER_AGENT class=(\S+) hooks=(\d+)(?=\r?\n|\Z)', log)
    if sorted(hooks) != [('net/vulkanmod/vulkan/texture/SamplerManager', '2'),
                         ('net/vulkanmod/vulkan/texture/VulkanImage', '1')]:
        raise ValueError('Vulkan sampler probe call sites differ')
    events = re.findall(r'CBBG_CLASSIC_VULKAN_SAMPLER_([^\r\n]+)', log)
    if events != ['REJECTED count=1',
                  f'RECOVERED scene={str(scene).lower()} failures=1 freedImages={1 if scene else 2}']:
        raise ValueError('Vulkan sampler failure, cleanup or recovery differs')
    if log.count('Failed to create texture sampler: VK_ERROR_OUT_OF_DEVICE_MEMORY') != 1:
        raise ValueError('Vulkan sampler failure result differs')
    if scenes != [('37' if scene else '97', 'false'), ('97', 'false')]:
        raise ValueError('Vulkan sampler fallback or reloaded framebuffer differs')
    if (evidence / 'final-cbbg.json').read_bytes() != (evidence / 'initial-cbbg.json').read_bytes():
        raise ValueError('Vulkan sampler failure changed the saved configuration')
    return {name: (width, height) for name in ('vulkan-sampler-fallback.png', 'vulkan-world.png')}


def vulkan_pipeline_captures(log, evidence, scenario, scenes, width, height):
    fault = VULKAN_PIPELINE_CASES[scenario]
    hooks = re.findall(r'CBBG_PIPELINE_AGENT class=net/vulkanmod/vulkan/shader/(\S+)(?=\r?\n|\Z)', log)
    if sorted(hooks) != sorted(VULKAN_PIPELINE_CLASSES):
        raise ValueError('Vulkan pipeline probe call sites differ')
    fallback, _, after_fallback = log.partition('CBBG_CLASSIC_VULKAN_SCENE format=44 disabled=true')
    reloading, _, after_reload = after_fallback.partition('Reloading ResourceManager:')
    fault_name, attempts, cleaned = one_reading(
        log, r'CBBG_CLASSIC_VULKAN_PIPELINE_RECOVERED fault=(\w+) attempts=(\d+) cleaned=(\d+)(?=\r?\n|\Z)')
    attempts, cleaned = int(attempts), int(cleaned)
    error = VULKAN_PIPELINE_ERRORS[fault]
    if (fault_name != fault or attempts not in (1, 2) or cleaned != (attempts if fault == 'native' else 0)
            or log.count(error) != attempts or fallback.count(error) != attempts
            or scenes != [('44', 'true'), ('97', 'false')]
            or 'CBBG_CLASSIC_VULKAN_SCENE format=97 disabled=false' not in after_reload
            or 'CBBG_CLASSIC_VULKAN_PIPELINE_RECOVERED' not in after_reload):
        raise ValueError('Vulkan pipeline failure or reload recovery differs')
    released = r'CBBG_CLASSIC_VULKAN_CONSTRUCTION_RELEASED \{([^\r\n]+)\}'
    summaries = re.findall(released, log)
    if fault == 'native':
        if summaries:
            raise ValueError('Unexpected Vulkan pipeline construction tracking')
    else:
        expected = {'ShaderResult': 2 * attempts}
        if fault == 'constructor':
            expected.update(dict.fromkeys(('DescriptorSetLayout', 'PipelineLayout', 'VertexBindings',
                                           'VertexAttributes'), attempts))
            expected.update(dict.fromkeys(('ShaderModule', 'DescriptorPool', 'DynamicOffsets',
                                           'DescriptorHandles'), 2 * attempts))
        expected_entries = sorted(f'{name}={count}' for name, count in expected.items())
        if (len(summaries) != 2 or not re.search(released, fallback) or not re.search(released, reloading)
                or any(sorted(summary.split(', ')) != expected_entries for summary in summaries)):
            raise ValueError('Vulkan pipeline construction did not release every observed resource')
    if (evidence / 'final-cbbg.json').read_bytes() != (evidence / 'initial-cbbg.json').read_bytes():
        raise ValueError('Vulkan pipeline failure changed the saved configuration')
    return {name: (width, height) for name in ('vulkan-pipeline-fallback.png', 'vulkan-world.png')}


def opengl_pixel_captures(log, evidence, width, height):
    draws = re.findall(r'CBBG_CLASSIC_OPENGL_PIXEL_DRAWS mode=(\w+) draws=(\d+)(?=\r?\n|\Z)', log)
    comparisons = re.findall(
        r'CBBG_CLASSIC_OPENGL_PIXELS mode=(\w+) channels=(\d+) screenshotMismatches=(\d+) '
        r'windowMismatches=(\d+) topOriginMismatches=(\d+) alphaMismatches=(\d+) changedChannels=(\d+)'
        r'(?=\r?\n|\Z)', log)
    modes = ['demo', 'enabled', 'disabled']
    if (draws != [('demo', '2'), ('enabled', '2'), ('disabled', '0')]
            or [row[0] for row in comparisons] != modes
            or any(int(count) != width * height * 3 or shot != '0' or window != '0' or alpha != '0'
                   or (int(top) > 0) != (mode != 'disabled')
                   or (int(changed) > 0) != (mode != 'disabled')
                   for mode, count, shot, window, top, alpha, changed in comparisons)):
        raise ValueError('Incomplete or mismatched OpenGL pixel comparisons')
    validate_pixel_cache(evidence)
    return {f'opengl-{view}-{mode}.png': (width, height) for mode in modes for view in ('shot', 'window')}


def opengl_lifecycle_captures(log, evidence, width, height):
    records = re.findall(
        r'CBBG_CLASSIC_OPENGL_LIFECYCLE stage=(\w+) size=(\d+)x(\d+) fullscreen=(true|false) '
        r'world=(true|false) draws=(\d+)(?=\r?\n|\Z)', log)
    stages = ('initial', 'resized', 'fullscreen', 'windowed', 'reloaded', 'left', 'rejoined')
    if tuple(record[0] for record in records) != stages:
        raise ValueError('Incomplete OpenGL window and world lifecycle')
    normal = (width, height)
    resized = (width - 64, height - 48)
    captures = {}
    for stage, x, y, fullscreen, world, draws in records:
        size = (int(x), int(y))
        expected = resized if stage in ('resized', 'windowed') else normal
        valid_size = size != resized and min(size) > 0 if stage == 'fullscreen' else size == expected
        if ((stage == 'fullscreen') != (fullscreen == 'true')
                or (stage != 'left') != (world == 'true') or int(draws) < 6
                or not valid_size):
            raise ValueError('OpenGL lifecycle framebuffer, window, world or dither count differs')
        captures[f'opengl-lifecycle-{stage}.png'] = size
    if (evidence / 'final-cbbg.json').read_bytes() != (evidence / 'initial-cbbg.json').read_bytes():
        raise ValueError('OpenGL lifecycle changed the saved configuration')
    return captures


def classic_result(scenario, profile, backend, log, evidence, exit_code, restart_phase=None):
    if (type(exit_code) is not int or exit_code != 0
            or re.search(r'VUID-|Validation Error|VK_ERROR_DEVICE_LOST|AssertionError|Game crashed!'
                         r'|GL_INVALID_(?:ENUM|VALUE|OPERATION|FRAMEBUFFER_OPERATION)|GL_OUT_OF_MEMORY', log)):
        raise ValueError('Classic client or graphics validation failed')
    mode_restart = scenario == CLASSIC_MODE_RESTART
    if (mode_restart and (backend != 'opengl' or profile not in CLASSIC_MODE_RESTART_PROFILES
                          or restart_phase not in ('prepare', 'verify', 'control'))
            or restart_phase and not mode_restart and (scenario != 'iris' or 'iris' not in profile.split('+'))):
        raise ValueError('Unsupported classic restart scenario, profile or phase')
    config = classic_config(backend, scenario)
    if mode_restart and restart_phase == 'verify':
        config['mode'] = 'DISABLED'
    if json.loads((evidence / 'initial-cbbg.json').read_text()) != config:
        raise ValueError('Classic initial config or options differ')
    if restart_phase == 'verify':
        if scenario == 'iris':
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
            captures = vulkan_lifecycle_captures(log, scenes, width, height)
        elif scenario in VULKAN_FORMAT_CASES:
            captures = vulkan_fallback_captures(log, evidence, scenario, scenes, width, height)
        elif scenario in VULKAN_VMA_CASES:
            captures = vulkan_vma_captures(log, evidence, scenario, scenes, width, height)
        elif scenario in VULKAN_NATIVE_CASES:
            captures = vulkan_native_captures(log, evidence, scenario, scenes, width, height)
        elif scenario in VULKAN_SAMPLER_CASES:
            captures = vulkan_sampler_captures(log, evidence, scenario, scenes, width, height)
        elif scenario in VULKAN_PIPELINE_CASES:
            captures = vulkan_pipeline_captures(log, evidence, scenario, scenes, width, height)
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
            validate_pixel_cache(evidence)
    else:
        if 'CBBG_CLASSIC_VULKAN_DEVICE ' in log:
            raise ValueError('OpenGL client also reported a VulkanMod device')
        gpu, driver, sodium = one_reading(
            log, r'CBBG_CLASSIC_OPENGL (.+?) / (.+?) sodium=(true|false)(?=\r?\n|\Z)')
        if (sodium == 'true') != includes_sodium(profile):
            raise ValueError('OpenGL profile differs')
        if scenario == 'satin':
            validate_satin(log, width, height)
            captures = {}
        elif scenario == OPENGL_PIXELS:
            captures = opengl_pixel_captures(log, evidence, width, height)
        elif scenario == OPENGL_LIFECYCLE:
            captures = opengl_lifecycle_captures(log, evidence, width, height)
        elif scenario == 'stack':
            captures = classic_stack_captures(log, evidence, profile, config, width, height)
        elif scenario == 'renderscale':
            captures = renderscale_captures(log, width, height)
        elif scenario == 'iris':
            captures = iris_captures(log, evidence, width, height, restart_phase)
        elif mode_restart:
            final_config = dict(config, mode='ENABLED' if restart_phase == 'verify' else 'DISABLED')
            if json.loads((evidence / 'final-cbbg.json').read_text()) != final_config:
                raise ValueError('Classic mode restart did not preserve the other saved settings')
            if restart_phase == 'verify':
                events = re.findall(r'CBBG_CLASSIC_(?:RESTORED_DISABLED [^\r\n]+|RESTART_PASS [^\r\n]+'
                                    r'|SMOKE_PASS [^\r\n]+)', log)
                restored = 'CBBG_CLASSIC_RESTORED_DISABLED format=RGBA8 presentations=0'
                enabled = re.fullmatch(r'CBBG_CLASSIC_RESTART_PASS presentations=(\d+)', events[-1]) if events else None
                if len(events) != 2 or events[0] != restored or not enabled or int(enabled[1]) < 10:
                    raise ValueError('Classic mode restart did not restore disabled and then re-enable dithering')
                if ('Starting Async STBN Math Generation' in log
                        or log.count('STBN Frames loaded from cache.') != 1):
                    raise ValueError('Classic mode restart did not reuse the saved noise cache')
                captures = {name: (width, height) for name in ('restart-disabled.png', 'restart-enabled.png')}
            elif int(one_reading(log, r'CBBG_CLASSIC_SMOKE_PASS presentations=(\d+)')) < 10:
                raise ValueError('Classic mode restart setup did not present enough frames')
        elif int(one_reading(log, r'CBBG_CLASSIC_SMOKE_PASS presentations=(\d+)')) < 10:
            raise ValueError('OpenGL world did not present enough frames')
        if scenario in CLASSIC_CACHE_SCENARIOS:
            captures.update(classic_cache_captures(log, evidence, scenario))
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
