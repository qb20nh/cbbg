"""Validate local Fabric entrypoint execution; not full release acceptance."""

import json
import hashlib
import re

STARTUP_DRIVER = 'com.qb20nh.cbbg.gametest.ReleaseEarlyStartupGameTest'
STARTUP_CACHE_FILES = ['stbn_16x16x8.sha256'] + [f'stbn_16x16x8_{z}.png' for z in range(8)]


def validate_startup(expected, mode, evidence, cache, log, backend):
    """Check initial cache use and work observed before Minecraft startup."""
    if STARTUP_DRIVER not in expected:
        if mode is not None:
            raise ValueError('Startup mode requires its dedicated driver')
        return
    if expected != [STARTUP_DRIVER] or mode not in ('cold', 'warm', 'damaged'):
        raise ValueError('Startup requires a dedicated driver and cache mode')
    inputs = json.loads((evidence / 'startup-input.json').read_text())
    result = json.loads((evidence / 'startup.json').read_text())
    early = json.loads((evidence / 'startup-prelaunch.json').read_text())
    if (not all(isinstance(item, dict) for item in (inputs, result, early))
            or inputs.get('mode') != mode or result.get('backend') != backend
            or result.get('size') != 16 or result.get('depth') != 8
            or result.get('seed') != 74123
            or result.get('pixelsSha256') != '4f953f23c7a2a7de8960caa4272090458b2ec986ceeea65796467a0c9109a076'
            or result.get('preLaunchWorkers') != 1
            or early.get('preLaunchWorkers') != 1
            or early.get('preLaunchMillis') != result.get('preLaunchMillis')):
        raise ValueError('Startup settings, pixels or prelaunch observation differ')
    times = [result.get(key) for key in ('preLaunchMillis', 'firstDrawMillis', 'titleMillis', 'cacheReadyMillis')]
    if (any(type(value) is not int or value <= 0 for value in times)
            or times[0] > times[1] or times[0] > times[2] or times[2] > times[3]):
        raise ValueError('Missing or inconsistent startup timing')
    starts = log.count('Starting Async STBN Math Generation (16x16x8)')
    finishes = log.count('STBN Math Complete in ')
    if starts != (0 if mode == 'warm' else 1) or finishes != starts:
        raise ValueError('Unexpected startup generation count')
    files = inputs.get('files')
    if not isinstance(files, dict) or set(files) != (set() if mode == 'cold' else set(STARTUP_CACHE_FILES)):
        raise ValueError('Startup cache input inventory differs')
    if mode == 'warm':
        if 'Valid STBN cache found for 16x16x8' not in log:
            raise ValueError('Missing startup cache reuse')
        for name, previous in files.items():
            path = cache / name
            if (hashlib.sha256(path.read_bytes()).hexdigest() != previous.get('sha256')
                    or path.stat().st_mtime_ns != previous.get('mtimeNs')):
                raise ValueError('Warm startup rewrote cache: ' + name)
    if mode == 'damaged':
        damaged = files['stbn_16x16x8_7.png']['sha256']
        if damaged != hashlib.sha256(bytes([1, 2, 3])).hexdigest():
            raise ValueError('Startup cache was not damaged before launch')
        if hashlib.sha256((cache / 'stbn_16x16x8_7.png').read_bytes()).hexdigest() == damaged:
            raise ValueError('Damaged startup cache was not repaired')


def validate_shutdown(expected, path):
    """Require cleanup observed before Minecraft destroys its renderer."""
    drivers = {
        'com.qb20nh.cbbg.gametest.ReleaseShutdownGameTest': 'idle',
        'com.qb20nh.cbbg.gametest.ReleaseGeneratingShutdownGameTest': 'generating',
    }
    selected = [name for name in expected if name in drivers]
    if not selected:
        return
    if len(expected) != 1:
        raise ValueError('Shutdown requires a dedicated test driver')
    record = json.loads(path.read_text())
    kind = drivers[selected[0]]
    if (not isinstance(record, dict) or 'failure' in record
            or record.get('kind') != kind
            or record.get('workerTerminated') is not True
            or record.get('resourcesClosed') is not True
            or record.get('generationStarted') is not (kind == 'generating')
            or record.get('generationCompleted') is not False
            or type(record.get('resourcesChecked')) is not int
            or (record['resourcesChecked'] <= 0 if kind == 'idle'
                else record['resourcesChecked'] != 0)):
        raise ValueError('Incomplete Minecraft shutdown checks')


def graphics_identity(log, backend):
    """Extract observed identity without inferring an unreported GL context profile."""
    readings = set(re.findall(r'Readback backend=(\S+) GPU=(.+?) driver=([^\r\n]+)', log))
    if len(readings) != 1:
        raise ValueError('Missing or conflicting graphics identity')
    actual, gpu, driver = readings.pop()
    if actual.lower() != backend:
        raise ValueError('Graphics identity backend mismatch')
    extensions = set()
    for value in re.findall(r'Using graphics device extensions: ([^\r\n]+)', log):
        extensions.update(value.split(', '))
    return {'backend': actual.lower(), 'gpu': gpu, 'driver': driver,
            'reportedExtensions': sorted(extensions), 'contextProfile': None}


def validate_scenarios(expected, trace, log, exit_code, backend):
    """Require every declared scenario, actual backend identity and clean GPU validation."""
    if backend not in ("opengl", "vulkan"):
        raise ValueError("Unknown requested backend")
    if type(exit_code) is not int or exit_code != 0:
        raise ValueError("Client did not exit successfully")
    if (not expected or any(not isinstance(name, str) or not name for name in expected)
            or len(set(expected)) != len(expected)):
        raise ValueError("Missing or duplicate expected scenarios")
    required = [state + "\t" + name for name in expected for state in ("started", "passed")]
    if trace.splitlines() != required:
        raise ValueError("Incomplete, reordered or diagnostic scenario trace")
    observed = re.findall(r"Readback backend=(\S+)", log)
    if not observed or any(value.lower() != backend for value in observed):
        raise ValueError("Missing or mismatched actual graphics backend")
    if re.search(r"VUID-|Validation Error|VK_ERROR_DEVICE_LOST|AssertionError|Game crashed!"
                 r"|Minecraft has crashed!|Client gametests failed with an exception"
                 r"|GL_INVALID_(?:ENUM|VALUE|OPERATION|FRAMEBUFFER_OPERATION)|GL_OUT_OF_MEMORY", log):
        raise ValueError("Graphics validation or runtime failure in client log")
    return {"completedEntrypoints": len(expected), "backend": backend,
            "releaseAcceptance": False}
