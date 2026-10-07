package cbbg.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.api.GradleException

import static org.junit.jupiter.api.Assertions.*

class FabricCompatibilityProbeTest {
    @TempDir File directory

    @Test void admitsOnlyMatchingStartupAndSuppliedConfigInputs() {
        File config = new File(directory, 'settings.json')
        config.text = '{"mode":"ENABLED","pixelFormat":"RGBA16F","stbnSize":16,' +
                '"stbnDepth":8,"stbnSeed":74123,"strength":2}'
        File cache = new File(directory, 'startup-cache')
        cache.mkdirs()
        FabricCompatibilityProbe.requireLocalInputs(true, null, config, null, 'none')
        FabricCompatibilityProbe.requireLocalInputs(true, 'cold', config, null, 'none')
        ['warm', 'damaged', 'seed-mismatch'].each { mode ->
            FabricCompatibilityProbe.requireLocalInputs(true, mode, config, cache, 'none')
            assertThrows(GradleException) {
                FabricCompatibilityProbe.requireLocalInputs(true, mode, config, null, 'none')
            }
        }
        [null, 'cold'].each { mode ->
            assertThrows(GradleException) {
                FabricCompatibilityProbe.requireLocalInputs(true, mode, config, cache, 'none')
            }
        }
        assertThrows(GradleException) {
            FabricCompatibilityProbe.requireLocalInputs(false, null, config, null, 'none')
        }
        assertThrows(GradleException) {
            FabricCompatibilityProbe.requireLocalInputs(true, null, new File(directory, 'absent'), null, 'none')
        }
        assertThrows(GradleException) {
            FabricCompatibilityProbe.requireLocalInputs(true, 'warm', config, config, 'none')
        }
        assertThrows(GradleException) {
            FabricCompatibilityProbe.requireLocalInputs(true, 'cold', config, null, 'sodium')
        }
        assertThrows(GradleException) {
            FabricCompatibilityProbe.requireLocalInputs(true, 'unknown', config, null, 'none')
        }
        assertEquals('ENABLED', (new JsonSlurper().parse(config) as Map).mode)
        assertEquals(74123, (new JsonSlurper().parse(config) as Map).stbnSeed)
        config.text = '[]'
        assertThrows(GradleException) {
            FabricCompatibilityProbe.requireLocalInputs(true, null, config, null, 'none')
        }
    }

    @Test void runnerCapturesTheRealCommandExitAndOutput() {
        File log = new File(directory, 'run/runner.log')
        File java = new File(System.getProperty('java.home'), 'bin/java')
        int exit = FabricCompatibilityProbe.run([java, '-version'], log,
                ProjectBuilder.builder().withProjectDir(directory).build().providers, directory)
        assertEquals(0, exit)
        assertTrue(log.text.contains('version'))
    }

    @Test void admitsRestartOnlyForTheDedicatedBuildOnlyIrisDriver() {
        FabricCompatibilityProbe.requireLocalInputs(true, null, null, null, 'iris', true,
                'processedIrisRestartDriverJar')
        FabricCompatibilityProbe.requireLocalInputs(true, null, null, null,
                'modmenu+sodium+iris+renderscale+chatpatches', true, 'processedIrisRestartDriverJar')
        assertThrows(GradleException) {
            FabricCompatibilityProbe.requireLocalInputs(true, null, new File(directory, 'settings.json'),
                    null, 'iris', true, 'processedIrisRestartDriverJar')
        }
        [[false, 'iris', true, 'processedIrisRestartDriverJar'],
         [true, 'none', true, 'processedIrisRestartDriverJar'],
         [true, 'modmenu+sodium', true, 'processedIrisRestartDriverJar'],
         [true, 'iris', false, 'processedIrisRestartDriverJar'],
         [true, 'iris', true, 'processedDriverJar']].each { inputs ->
            assertThrows(GradleException) {
                FabricCompatibilityProbe.requireLocalInputs(inputs[0] as boolean, null, null, null,
                        inputs[1] as String, inputs[2] as boolean, inputs[3] as String)
            }
        }
    }

    @Test void cacheTracksProbeInputsButNotSearchPolicyOrOpenGlVulkanLoader() {
        File coordinator = new File(directory, 'fabric-compatibility.gradle')
        File search = new File(directory, 'FabricCompatibilitySearch.groovy')
        File probe = new File(directory, 'FabricCompatibilityProbe.groovy')
        File python = new File(directory, 'python')
        File java = new File(directory, 'java')
        Map sources = [(coordinator.canonicalPath): 'policy-1', (search.canonicalPath): 'search-1',
                       (probe.canonicalPath): 'probe-1', '/usr/lib/libvulkan.so.1': 'vulkan-1',
                       '/usr/lib/libGL.so.1': 'gl-1', '/usr/lib/libnvidia-glcore.so.1': 'gpu-1',
                       (python.canonicalPath): 'python-1', (java.canonicalPath): 'jvm-1',
                       (new File(directory, 'fabric_parity_runtime.py').canonicalPath): 'runner-1']
        Map gl = FabricCompatibilityProbe.executionFiles(sources, coordinator, search, 'opengl')
        assertFalse(gl.containsKey(coordinator.canonicalPath))
        assertFalse(gl.containsKey(search.canonicalPath))
        assertFalse(gl.containsKey('/usr/lib/libvulkan.so.1'))
        assertEquals('gpu-1', gl['/usr/lib/libnvidia-glcore.so.1'])
        Map vk = FabricCompatibilityProbe.executionFiles(sources, coordinator, search, 'vulkan')
        assertEquals('vulkan-1', vk['/usr/lib/libvulkan.so.1'])

        File evidence = new File(directory, 'receipt.json')
        evidence.text = 'passed'
        File cache = new File(directory, 'cache')
        Map inputs = [candidate: 'jar', driver: 'driver', target: '26.1-fabric',
                      gametestApiSha256: 'gametest', apiSha256: 'api', runtime: 'runtime',
                      initialConfig: 'config', backend: 'opengl', files: gl]
        int runs = 0
        Closure run = { runs++; [status: 'passed', files: [evidence]] }
        assertFalse(FabricCompatibilitySearch.cached(cache, inputs, run).cached)
        assertTrue(FabricCompatibilitySearch.cached(cache, inputs, run).cached)
        Map policyChanged = sources + [(coordinator.canonicalPath): 'policy-2',
                                       (search.canonicalPath): 'search-2',
                                       '/usr/lib/libvulkan.so.1': 'vulkan-2']
        assertTrue(FabricCompatibilitySearch.cached(cache, inputs + [files:
                FabricCompatibilityProbe.executionFiles(policyChanged, coordinator, search, 'opengl')], run).cached)
        [probe.canonicalPath, '/usr/lib/libGL.so.1', '/usr/lib/libnvidia-glcore.so.1',
         python.canonicalPath, java.canonicalPath,
         new File(directory, 'fabric_parity_runtime.py').canonicalPath].each { path ->
            Map changed = sources + [(path): 'changed']
            assertFalse(FabricCompatibilitySearch.cached(cache, inputs + [files:
                    FabricCompatibilityProbe.executionFiles(changed, coordinator, search, 'opengl')], run).cached)
        }
        ['candidate', 'driver', 'gametestApiSha256', 'apiSha256', 'runtime',
         'initialConfig', 'backend'].each { key ->
            assertFalse(FabricCompatibilitySearch.cached(cache, inputs + [(key): 'changed'], run).cached)
        }
        assertFalse(FabricCompatibilitySearch.cached(cache, inputs + [backend: 'vulkan', files: vk], run).cached)
        assertFalse(FabricCompatibilitySearch.cached(cache, inputs + [backend: 'vulkan',
                files: FabricCompatibilityProbe.executionFiles(policyChanged, coordinator, search, 'vulkan')], run).cached)
        assertEquals(16, runs)
    }

    @Test void cacheTracksOnlyGraphicsEnvironmentOverrides() {
        Map inherited = [MESA_GL_VERSION_OVERRIDE: '4.6', LIBGL_ALWAYS_SOFTWARE: '1',
                         GALLIUM_DRIVER: 'llvmpipe', __GL_SYNC_TO_VBLANK: '0',
                         __EGL_VENDOR_LIBRARY_FILENAMES: '/vendor.json', DRI_PRIME: '1',
                         GBM_BACKEND: 'nvidia-drm', LANG: 'en_US.UTF-8', PATH: '/bin']
        Map snapshot = FabricCompatibilityProbe.graphicsEnvironment(inherited)
        assertEquals(inherited.keySet().findAll { !(it in ['LANG', 'PATH']) }.sort(),
                snapshot.keySet().toList())
        inherited.MESA_GL_VERSION_OVERRIDE = '3.3'
        assertEquals('4.6', snapshot.MESA_GL_VERSION_OVERRIDE)

        File evidence = new File(directory, 'receipt.json')
        evidence.text = 'passed'
        File cache = new File(directory, 'cache')
        Map inputs = [candidate: 'same-artifact', backend: 'opengl', graphicsEnvironment: snapshot]
        int runs = 0
        Closure run = { runs++; [status: 'passed', files: [evidence]] }
        assertFalse(FabricCompatibilitySearch.cached(cache, inputs, run).cached)
        assertTrue(FabricCompatibilitySearch.cached(cache, inputs, run).cached)
        assertFalse(FabricCompatibilitySearch.cached(cache, inputs + [graphicsEnvironment:
                FabricCompatibilityProbe.graphicsEnvironment(inherited)], run).cached)
        inherited.LANG = 'ko_KR.UTF-8'
        inherited.PATH = '/usr/bin'
        assertTrue(FabricCompatibilitySearch.cached(cache, inputs + [graphicsEnvironment:
                FabricCompatibilityProbe.graphicsEnvironment(inherited)], run).cached)
        assertEquals(2, runs)
    }

    @Test void executesThePinnedRuntimeCommandAndClassifiesReceipts() {
        File api = new File(directory, 'api.jar')
        File gametest = new File(directory, 'gametest.jar')
        File optional = new File(directory, 'optional.jar')
        [api, gametest, optional].each { it.text = it.name }
        File runtime = new File(directory, 'runtime')
        runtime.mkdirs()
        File runtimeLock = new File(runtime, 'cbbg-runtime-lock.json')
        runtimeLock.text = '{}'
        File display = new File(directory, 'display')
        display.mkdirs()
        Map spec = [root: directory, output: directory,
                    target: [id: '26.1.1-fabric', dependencies: [fabricApi: '0.145.4+26.1.1']],
                    loaderVersion: '0.18.4', apiVersion: '0.143.12+26.1',
                    api: api, gametest: gametest,
                    runtime: [directory: runtime, lock: runtimeLock],
                    optionalMods: [sodium: [pin: 'pin', sha256: 'hash', file: optional]],
                    candidate: new File(directory, 'candidate.jar'),
                    driver: new File(directory, 'driver.jar'), profile: 'none', backend: 'opengl',
                    config: new File(directory, 'initial-cbbg.json'), python: new File(directory, 'python'),
                    java: new File(directory, 'java'), strict: false, manageDisplay: false,
                    displayDirectory: display, display: 'wayland-test', weston: null, eglVendor: null]
        [spec.candidate, spec.driver, spec.config, spec.python, spec.java].each {
            (it as File).text = (it as File).name
        }
        File runner = new File(directory, 'scripts/fabric_parity_runtime.py')
        runner.parentFile.mkdirs()
        runner.text = 'runner'
        File coordinator = new File(directory, 'fabric-compatibility.gradle')
        File search = new File(directory, 'FabricCompatibilitySearch.groovy')
        Map reportInputs = [files: [(coordinator.canonicalPath): 'policy-1',
                                    (search.canonicalPath): 'search-1',
                                    (runner.canonicalPath): 'runner-1']]
        Map cacheInputs = FabricCompatibilityProbe.cacheInputs(reportInputs, spec, coordinator, search)
        Map restartInputs = FabricCompatibilityProbe.cacheInputs(reportInputs, spec + [restart: true],
                coordinator, search)
        assertEquals(false, cacheInputs.restart)
        assertEquals(true, restartInputs.restart)
        assertNotEquals(cacheInputs, restartInputs)
        File restartEvidence = new File(directory, 'restart-cache-evidence.json')
        restartEvidence.text = 'passed'
        File restartCache = new File(directory, 'restart-cache')
        Closure restartRun = { [status: 'passed', files: [restartEvidence]] }
        assertFalse(FabricCompatibilitySearch.cached(restartCache, cacheInputs, restartRun).cached)
        assertFalse(FabricCompatibilitySearch.cached(restartCache, restartInputs, restartRun).cached)
        assertTrue(FabricCompatibilitySearch.cached(restartCache, restartInputs, restartRun).cached)
        File startupCache = new File(directory, 'startup-cache')
        startupCache.mkdirs()
        File cachedNoise = new File(startupCache, 'noise.png')
        cachedNoise.text = 'noise'
        Map startupSpec = spec + [startupMode: 'warm', startupCache: startupCache]
        Map startupInputs = FabricCompatibilityProbe.cacheInputs(reportInputs, startupSpec, coordinator, search)
        assertEquals('warm', startupInputs.startupMode)
        assertEquals(startupCache.canonicalPath, startupInputs.startupCache.directory)
        assertEquals(CandidateFiles.sha256(cachedNoise), startupInputs.startupCache.files[cachedNoise.canonicalPath])
        File startupEvidence = new File(directory, 'startup-receipt.json')
        startupEvidence.text = 'passed'
        File resultCache = new File(directory, 'startup-results')
        int startupRuns = 0
        Closure startupRun = { startupRuns++; [status: 'passed', files: [startupEvidence]] }
        assertFalse(FabricCompatibilitySearch.cached(resultCache, startupInputs, startupRun).cached)
        assertTrue(FabricCompatibilitySearch.cached(resultCache, startupInputs, startupRun).cached)
        ['damaged', 'seed-mismatch'].each { mode ->
            assertFalse(FabricCompatibilitySearch.cached(resultCache,
                    FabricCompatibilityProbe.cacheInputs(reportInputs, startupSpec + [startupMode: mode],
                            coordinator, search), startupRun).cached)
        }
        cachedNoise.text = 'changed noise'
        assertFalse(FabricCompatibilitySearch.cached(resultCache,
                FabricCompatibilityProbe.cacheInputs(reportInputs, startupSpec, coordinator, search), startupRun).cached)
        File added = new File(startupCache, 'nested/additional.png')
        added.parentFile.mkdirs()
        added.text = 'additional'
        assertFalse(FabricCompatibilitySearch.cached(resultCache,
                FabricCompatibilityProbe.cacheInputs(reportInputs, startupSpec, coordinator, search), startupRun).cached)
        cachedNoise.delete()
        assertFalse(FabricCompatibilitySearch.cached(resultCache,
                FabricCompatibilityProbe.cacheInputs(reportInputs, startupSpec, coordinator, search), startupRun).cached)
        File otherCache = new File(directory, 'other-startup-cache')
        new File(otherCache, 'nested').mkdirs()
        new File(otherCache, 'nested/additional.png').text = added.text
        assertFalse(FabricCompatibilitySearch.cached(resultCache,
                FabricCompatibilityProbe.cacheInputs(reportInputs, startupSpec + [startupCache: otherCache],
                        coordinator, search), startupRun).cached)
        assertEquals(7, startupRuns)
        Map environment = cacheInputs.graphicsEnvironment
        assertSame(environment, spec.graphicsEnvironment)
        assertFalse(cacheInputs.files.containsKey(coordinator.canonicalPath))
        assertEquals(CandidateFiles.sha256(spec.python as File), cacheInputs.python.sha256)
        assertEquals(CandidateFiles.sha256(spec.java as File), cacheInputs.java.sha256)
        (spec.python as File).text = 'changed interpreter'
        assertNotEquals(cacheInputs, FabricCompatibilityProbe.cacheInputs(reportInputs, spec, coordinator, search))
        (spec.python as File).text = 'python'
        (spec.java as File).text = 'changed JVM'
        assertNotEquals(cacheInputs, FabricCompatibilityProbe.cacheInputs(reportInputs, spec, coordinator, search))
        List command = []
        Map passed = FabricCompatibilityProbe.execute(spec) { List args, File log ->
            command = args
            File game = args[args.indexOf('--game-dir') + 1] as File
            game.mkdirs()
            new File(game, 'probe.json').text = JsonOutput.toJson([exitCode: 0, scenarios: [ok: true]])
            log.text = 'runner completed'
            0
        }
        assertEquals('passed', passed.status)
        assertNull(passed.reason)
        assertSame(environment, passed.graphicsEnvironment)
        assertEquals(environment, (new JsonSlurper().parse(new File(passed.receipt)) as Map).graphicsEnvironment)
        assertEquals('26.1.1-fabric', command[command.indexOf('--target') + 1])
        assertEquals('0.145.4+26.1.1', command[command.indexOf('--gametest-api-version') + 1])
        assertEquals(gametest, command[command.indexOf('--gametest-api') + 1])
        assertEquals(runtimeLock, command[command.indexOf('--runtime-lock') + 1])
        assertTrue(command.contains('--test-dependency-minimums'))
        assertTrue(command.contains('sodium=' + optional.absolutePath))
        assertFalse(command.contains('--startup-mode'))
        assertFalse(command.contains('--startup-cache'))
        ['cold', 'warm', 'damaged', 'seed-mismatch'].each { mode ->
            File selectedCache = mode == 'cold' ? null : startupCache
            FabricCompatibilityProbe.execute(spec + [startupMode: mode, startupCache: selectedCache]) { List args, File log ->
                assertEquals(mode, args[args.indexOf('--startup-mode') + 1])
                assertEquals(selectedCache != null, args.contains('--startup-cache'))
                if (selectedCache != null) assertEquals(selectedCache, args[args.indexOf('--startup-cache') + 1])
                assertEquals(spec.config, args[args.indexOf('--cbbg-config') + 1])
                log.text = 'forwarded startup inputs'
                1
            }
        }
        File lock = command[command.indexOf('--dependency-lock') + 1] as File
        Map recorded = new JsonSlurper().parse(lock) as Map
        assertEquals('26.1.1-fabric', recorded.target)
        assertEquals('0.145.4+26.1.1', recorded.gametestApi.fabricApiPin)
        assertEquals('0.143.12+26.1', recorded.dependencies.fabricApi.pin)

        Map restartSpec = spec + [restart: true, profile: 'iris']
        List<String> restartPhases = []
        List<File> restartGames = []
        Map restarted = FabricCompatibilityProbe.execute(restartSpec) { List args, File log ->
            assertFalse(args.contains('--cbbg-config'))
            assertFalse(args.contains('--test-dependency-minimums'))
            String phase = args[args.indexOf('--restart-phase') + 1]
            File game = args[args.indexOf('--game-dir') + 1] as File
            restartPhases.add(phase)
            restartGames.add(game)
            game.mkdirs()
            new File(game, phase + '-probe.json').text = JsonOutput.toJson(
                    [exitCode: 0, scenarios: [ok: true]])
            new File(game, phase + '-launch.log').text = phase
            log.text = phase
            0
        }
        assertEquals(['control', 'prepare', 'verify'], restartPhases)
        assertNotEquals(restartGames[0], restartGames[1])
        assertEquals(restartGames[1], restartGames[2])
        assertEquals('passed', restarted.status)
        assertEquals(new File(restartGames[2], 'verify-probe.json').absolutePath, restarted.receipt)
        ['control', 'prepare', 'verify'].each { phase ->
            assertTrue(restarted.files.any { it.name == phase + '-runner.log' })
            assertTrue(restarted.files.any { it.name == phase + '-launch.log' })
            assertTrue(restarted.files.any { it.name == phase + '-probe.json' })
        }
        ['control', 'prepare', 'verify'].eachWithIndex { failingPhase, phaseIndex ->
            [false, true].each { missingReceipt ->
                List<String> attempted = []
                Map stopped = FabricCompatibilityProbe.execute(restartSpec) { List args, File log ->
                    String phase = args[args.indexOf('--restart-phase') + 1]
                    File game = args[args.indexOf('--game-dir') + 1] as File
                    attempted.add(phase)
                    game.mkdirs()
                    boolean failedPhase = phase == failingPhase
                    if (!failedPhase || !missingReceipt) {
                        new File(game, phase + '-probe.json').text = JsonOutput.toJson(failedPhase ?
                                [exitCode: 1, failure: [type: 'AssertionError', message: 'phase failed']] :
                                [exitCode: 0, scenarios: [ok: true]])
                    }
                    log.text = 'phase completed'
                    failedPhase && !missingReceipt ? 1 : 0
                }
                assertEquals(['control', 'prepare', 'verify'].take(phaseIndex + 1), attempted)
                assertEquals(missingReceipt ? 'blocked' : 'failed', stopped.status)
                assertTrue(stopped.receipt.endsWith(failingPhase + '-probe.json'))
            }
        }

        Map blocked = FabricCompatibilityProbe.execute(spec + [strict: true]) { List args, File log ->
            assertFalse(args.contains('--test-dependency-minimums'))
            log.text = 'runner failed before receipt'
            1
        }
        assertEquals('blocked', blocked.status)
        Map failed = FabricCompatibilityProbe.execute(spec) { List args, File log ->
            File game = args[args.indexOf('--game-dir') + 1] as File
            game.mkdirs()
            new File(game, 'probe.json').text = JsonOutput.toJson(
                    [exitCode: 1, failure: [type: 'AssertionError', message: 'scenario failed']])
            new File(game, 'launch.log').text = 'Caused by: broken shader\n'
            log.text = 'runner failed'
            1
        }
        assertEquals('failed', failed.status)
        assertEquals('Caused by: broken shader', failed.reason)
    }
}
