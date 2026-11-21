"""Packaged classic Fabric scenarios and the evidence their client emits."""

import hashlib
import json
import re
import struct
import zlib

CLASSIC_DRIVER = 'cbbg_classic_smoke'
CLASSIC_ENTRYPOINT = 'com.qb20nh.cbbg.parity.ClassicPackagedSmoke'
VULKAN_SCENARIOS = {'vulkan-lifecycle': 'vulkanLifecycle', 'vulkan-pixels': 'vulkanPixels'}
CONTEXT_MODS = {'forcegl2': 'forcegl20', 'threatengl': 'threatengl'}
REDUCED_GL_SCENARIO = 'forcegl2-mesa-3'
GLFW_CORE_PROFILE = 0x00032001
GLFW_COMPAT_PROFILE = 0x00032002
CLASSIC_OPTIONS = 'renderDistance:4\nsimulationDistance:5\nmaxFps:60\nguiScale:2\npauseOnLostFocus:false\n'


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
            scenario == 'world' or scenario in VULKAN_SCENARIOS):
        return
    if backend == 'opengl':
        if scenario == 'world' and profile != 'vulkanmod':
            return
        if scenario == REDUCED_GL_SCENARIO and profile == 'forcegl2':
            return
        if scenario == 'satin' and profile == 'satin':
            return
    raise ValueError('Unsupported classic scenario, profile or backend')


def classic_config(backend):
    return {'mode': 'DEMO' if backend == 'vulkan' else 'ENABLED',
            'pixelFormat': 'RGBA16F', 'stbnSize': 16, 'stbnDepth': 8, 'stbnSeed': 0,
            'strength': 2, 'notifyChat': False, 'notifyToast': False}


def classic_viewport(scenario):
    return (803, 601) if scenario == 'vulkan-pixels' else (800, 600)


def includes_sodium(profile):
    return bool({'sodium', 'iris'} & set(profile.split('+')))


def classic_jvm_arguments(candidate, scenario, backend, profile):
    arguments = ['-Xmx1200m', '-XX:ActiveProcessorCount=4',
                 '-Dcbbg.test.jar=' + str(candidate),
                 '-Dcbbg.test.sodium=' + str(includes_sodium(profile)).lower()]
    if backend == 'vulkan':
        arguments += ['-Dorg.lwjgl.system.stackSize=1024', '-Dcbbg.test.vulkanMod=true']
        if scenario in VULKAN_SCENARIOS:
            arguments.append('-Dcbbg.test.' + VULKAN_SCENARIOS[scenario] + '=true')
    elif scenario in ('world', REDUCED_GL_SCENARIO):
        arguments.append('-Dcbbg.test.worldProfile=' + profile)
        if profile in CONTEXT_MODS:
            arguments.append('-Dcbbg.test.contextMod=' + CONTEXT_MODS[profile])
    elif scenario == 'satin':
        arguments.append('-Dcbbg.test.satin=true')
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


def classic_result(scenario, profile, backend, log, evidence, exit_code):
    if (type(exit_code) is not int or exit_code != 0
            or re.search(r'VUID-|Validation Error|VK_ERROR_DEVICE_LOST|AssertionError|Game crashed!'
                         r'|GL_INVALID_(?:ENUM|VALUE|OPERATION|FRAMEBUFFER_OPERATION)|GL_OUT_OF_MEMORY', log)):
        raise ValueError('Classic client or graphics validation failed')
    if (json.loads((evidence / 'initial-cbbg.json').read_text()) != classic_config(backend)
            or (evidence / 'initial-options.txt').read_text() != CLASSIC_OPTIONS):
        raise ValueError('Classic initial config or options differ')
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
        if scenario == 'world':
            if scenes != [('97', 'false')]:
                raise ValueError('Vulkan world did not use the expected RGBA16F scene')
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
