package cbbg.gradle

import groovy.io.FileType
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** Runs one packaged-client dependency probe and classifies its recorded result. */
class FabricCompatibilityProbe {
    static Map dependencyFailure(String gameText, boolean strict) {
        List<Map> records = (gameText =~ /HARD_DEP(?:_NO_CANDIDATE)? ([^\s{}]+) [^\s{}]+ \{depends ([^\s{}]+) @ [^{}]*\}/)
                .collect { [text: it[0], owner: it[1], dependency: it[2]] }
                .unique { it.text }
        List<Map> production = records.findAll { it.owner == 'cbbg' }
        String details = records*.text.join('; ')
        if (!strict && production.any { it.dependency in ['fabricloader', 'fabric-api'] }) {
            return [status: 'blocked', reason: 'Minimum dependency overrides were not applied: ' + details]
        }
        if (production) {
            return [status: 'failed', reason: 'Incompatible CBBG dependencies: ' + details]
        }
        if (records.any { it.owner in ['cbbg-renderer-test', 'fabric-client-gametest-api-v1'] }) {
            return [status: 'blocked', reason: 'Incompatible test dependencies: ' + details]
        }
        [:]
    }

    static void requireLocalInputs(boolean buildOnly, String startupMode, File config,
                                   File startupCache, String profile, boolean restart = false,
                                   String driverName = null) {
        if ((restart || driverName == 'processedIrisRestartDriverJar') &&
                (!restart || driverName != 'processedIrisRestartDriverJar' || !buildOnly ||
                 !profile?.tokenize('+')?.contains('iris') || startupMode != null || startupCache != null || config != null)) {
            throw new GradleException('Iris restart verification requires processedIrisRestartDriverJar, ' +
                    'build-only verification, a profile containing Iris and no initial config')
        }
        if (config != null && (!buildOnly || !config.isFile())) {
            throw new GradleException('compatibilityConfig requires build-only verification and an existing file')
        }
        if (config != null && !(new JsonSlurper().parse(config) instanceof Map)) {
            throw new GradleException('compatibilityConfig must be a JSON object')
        }
        if (startupMode != null && (!buildOnly || profile != 'none' ||
                !(startupMode in ['cold', 'warm', 'damaged', 'seed-mismatch']))) {
            throw new GradleException('Startup verification requires a dedicated build-only driver and profile none')
        }
        if (startupCache != null && (startupMode == null || startupMode == 'cold' ||
                !startupCache.isDirectory())) {
            throw new GradleException('compatibilityStartupCache requires a noncold startup driver and an existing directory')
        }
        if (startupMode != null && startupMode != 'cold' && startupCache == null) {
            throw new GradleException('Noncold startup verification requires -PcompatibilityStartupCache')
        }
    }

    static Map graphicsEnvironment(Map environment = System.getenv()) {
        new TreeMap(environment.findAll { name, value ->
            ['MESA_', 'LIBGL_', 'GALLIUM_', '__GL', '__EGL'].any { name.startsWith(it) } ||
                    name in ['DRI_PRIME', 'GBM_BACKEND']
        })
    }

    private static Map graphicsEnvironmentSnapshot(Map spec) {
        if (!spec.containsKey('graphicsEnvironment')) {
            spec.graphicsEnvironment = graphicsEnvironment()
        }
        spec.graphicsEnvironment as Map
    }

    static int run(List command, File log, def providers, File workingDirectory) {
        log.parentFile.mkdirs()
        def execution = providers.exec {
            commandLine command.collect { it.toString() }
            workingDir workingDirectory
            ignoreExitValue = true
        }
        int exit = execution.result.get().exitValue
        log.text = execution.standardOutput.asText.get() + execution.standardError.asText.get()
        exit
    }

    static Map executionFiles(Map files, File coordinator, File search, String backend) {
        Set<String> policy = [coordinator.canonicalPath, search.canonicalPath] as Set
        files.findAll { String path, String hash ->
            !policy.contains(path) && !(backend == 'opengl' &&
                    new File(path).name ==~ /libvulkan.*\.so.*/ &&
                    (path.startsWith('/usr/lib/') || path.startsWith('/usr/lib64/')))
        }
    }

    static boolean usesX11(Map target) {
        // GLFW needs a keyboard-capable Xwayland display; Minecraft 26.3 switches to SDL.
        List<String> version = target.minecraft.tokenize('.')
        int major = version[0].toInteger()
        major < 26 || (major == 26 && version[1].toInteger() < 3)
    }

    static Map cacheInputs(Map reportInputs, Map spec, File coordinator, File search) {
        Map environment = graphicsEnvironmentSnapshot(spec)
        File gametest = spec.gametest as File
        File api = spec.api as File
        File python = spec.python as File
        File java = spec.java as File
        File runner = new File(spec.root as File, 'scripts/fabric_parity_runtime.py')
        Map mods = (spec.optionalMods as Map).collectEntries { name, mod ->
            [(name): [pin: mod.pin, sha256: CandidateFiles.sha256(mod.file as File),
                      sha512: mod.sha512]]
        }
        File startupCache = spec.startupCache as File
        List<File> startupFiles = []
        if (startupCache != null) {
            startupCache.traverse(type: FileType.FILES) { File file -> startupFiles.add(file) }
        }
        reportInputs + [restart: spec.restart == true, startupMode: spec.startupMode,
                startupCache: startupCache == null ? null : [directory: startupCache.canonicalPath,
                        files: startupFiles.sort { it.canonicalPath }.collectEntries {
                            [(it.canonicalPath): CandidateFiles.sha256(it)]
                        }],
                files: executionFiles(reportInputs.files as Map, coordinator, search,
                        spec.backend as String),
                target: spec.target.id, candidate: CandidateFiles.sha256(spec.candidate as File),
                driver: CandidateFiles.sha256(spec.driver as File),
                gametestApiPin: spec.target.dependencies.fabricApi,
                gametestApiSha256: CandidateFiles.sha256(gametest),
                profile: spec.profile, optionalDependencies: mods,
                initialConfig: CandidateFiles.sha256(spec.config as File),
                display: spec.manageDisplay ? [managed: true, protocol: usesX11(spec.target as Map) ? 'x11' : 'wayland'] :
                        [directory: (spec.displayDirectory as File).canonicalPath, wayland: spec.display],
                minimumOverrides: !spec.strict,
                loader: spec.loaderVersion, fabricApi: spec.apiVersion,
                apiSha256: CandidateFiles.sha256(api), gametest: CandidateFiles.sha256(gametest),
                runtime: CandidateFiles.sha256(spec.runtime.lock as File),
                runtimeDirectory: (spec.runtime.directory as File).canonicalPath,
                backend: spec.backend, graphicsEnvironment: environment,
                python: [path: python.canonicalPath, sha256: CandidateFiles.sha256(python)],
                java: [path: java.canonicalPath, sha256: CandidateFiles.sha256(java)],
                runner: [path: runner.canonicalPath, sha256: CandidateFiles.sha256(runner)],
                weston: spec.manageDisplay ? [path: (spec.weston as File).canonicalPath,
                        sha256: CandidateFiles.sha256(spec.weston as File)] : null,
                eglVendor: spec.eglVendor == null ? null :
                        [path: (spec.eglVendor as File).canonicalPath,
                         sha256: CandidateFiles.sha256(spec.eglVendor as File)]]
    }

    static Map execute(Map spec, def providers) {
        execute(spec) { List command, File log ->
            run(command, log, providers, spec.root as File)
        }
    }

    static Map execute(Map spec, Closure<Integer> run) {
        Map environment = graphicsEnvironmentSnapshot(spec)
        File cell = new File(spec.output as File, 'runs/' + UUID.randomUUID().toString())
        cell.mkdirs()
        File displayDirectory = spec.manageDisplay ?
                Files.createTempDirectory(new File('/tmp').toPath(), 'cbbg-display-').toFile() :
                spec.displayDirectory as File
        String display = spec.manageDisplay ? 'cbbg-test' : spec.display as String
        boolean x11 = spec.manageDisplay && usesX11(spec.target as Map)
        String xDisplay = null
        Process compositor = null
        try {
            if (spec.manageDisplay) {
                Files.setPosixFilePermissions(displayDirectory.toPath(),
                        PosixFilePermissions.fromString('rwx------'))
                List<String> compositorCommand = [(spec.weston as File).absolutePath,
                        '--backend=headless-backend.so', '--renderer=gl', '--socket=' + display,
                        '--width=1280', '--height=720',
                        '--log=' + new File(cell, 'compositor.log').absolutePath]
                if (x11) compositorCommand.add('--xwayland')
                def builder = new ProcessBuilder(compositorCommand)
                builder.environment().put('XDG_RUNTIME_DIR', displayDirectory.absolutePath)
                if (spec.eglVendor != null) {
                    builder.environment().put('__EGL_VENDOR_LIBRARY_FILENAMES',
                            (spec.eglVendor as File).absolutePath)
                }
                builder.redirectErrorStream(true).redirectOutput(new File(cell, 'compositor-output.log'))
                compositor = builder.start()
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (compositor.isAlive() && System.nanoTime() < deadline) {
                    File compositorLog = new File(cell, 'compositor.log')
                    if (x11 && compositorLog.isFile()) {
                        def match = compositorLog.text =~ /xserver listening on display (:\d+)/
                        if (match.find()) xDisplay = match.group(1)
                    }
                    if (new File(displayDirectory, display).exists() && (!x11 || xDisplay != null)) break
                    Thread.sleep(50)
                }
                if (!new File(displayDirectory, display).exists() || (x11 && xDisplay == null)) {
                    throw new GradleException('Fresh test display failed; see ' + cell)
                }
            }
            File lock = spec.dependencyLock as File ?: new File(cell, 'dependencies.json')
            Map locked = [fabricApi: [pin: spec.apiVersion,
                    sha256: CandidateFiles.sha256(spec.api as File)]]
            (spec.optionalMods as Map).each { name, mod ->
                locked[name] = [pin: mod.pin, sha256: mod.sha256]
            }
            if (spec.dependencyLock == null) lock.text = JsonOutput.toJson([schemaVersion: 1, target: spec.target.id,
                    dependencies: locked,
                    gametestApi: [fabricApiPin: spec.target.dependencies.fabricApi,
                            sha256: CandidateFiles.sha256(spec.gametest as File)]])
            List command = [spec.python, new File(spec.root as File, 'scripts/fabric_parity_runtime.py'),
                    '--target', spec.target.id, '--runtime', spec.runtime.directory,
                    '--java', spec.java, '--game-dir', new File(cell, 'game'),
                    '--candidate', spec.candidate, '--driver', spec.driver,
                    '--gametest-api', spec.gametest, '--runtime-lock', spec.runtime.lock,
                    '--dependency-lock', lock, '--dependency', 'fabricApi=' + (spec.api as File).absolutePath,
                    '--compat', spec.profile, '--backend', spec.backend,
                    '--xdg-runtime-dir', displayDirectory, x11 ? '--x-display' : '--wayland-display', x11 ? xDisplay : display,
                    '--timeout', '600',
                    '--loader-version', spec.loaderVersion, '--fabric-api-version', spec.apiVersion,
                    '--gametest-api-version', spec.target.dependencies.fabricApi]
            if (!spec.restart) command.addAll(['--cbbg-config', spec.config])
            (spec.optionalMods as Map).each { name, mod ->
                command.addAll(['--dependency', name + '=' + mod.file.absolutePath])
            }
            if (spec.startupMode != null) command.addAll(['--startup-mode', spec.startupMode])
            if (spec.startupCache != null) command.addAll(['--startup-cache', spec.startupCache])
            if (!spec.strict && !spec.restart) command.add('--test-dependency-minimums')
            Map result
            List<String> phases = spec.restart ? ['control', 'prepare', 'verify'] : [null]
            for (String phase : phases) {
                List phaseCommand = new ArrayList(command)
                File game = new File(cell, phase == 'control' ? 'control-game' : 'game')
                phaseCommand[phaseCommand.indexOf('--game-dir') + 1] = game
                if (phase != null) phaseCommand.addAll(['--restart-phase', phase])
                File log = new File(cell, phase == null ? 'runner.log' : phase + '-runner.log')
                int exit = run.call(phaseCommand, log)
                File receipt = new File(game, phase == null ? 'probe.json' : phase + '-probe.json')
                Map recorded = receipt.isFile() ? (Map) CandidateFiles.read(receipt) : [:]
                if (receipt.isFile()) {
                    recorded.graphicsEnvironment = environment
                    receipt.text = JsonOutput.toJson(recorded)
                }
                boolean passed = exit == 0 && recorded.exitCode == 0 && !recorded.failure && recorded.scenarios
                String status = passed ? 'passed' : 'failed'
                String reason = recorded.failure?.message ?: log.text.takeRight(2000)
                File gameLog = new File(game, phase == null ? 'launch.log' : phase + '-launch.log')
                String gameText = gameLog.isFile() ? gameLog.text : ''
                List<String> gameLines = gameText.readLines()
                List failures = gameLines.findAll {
                    it.contains('java.lang.AssertionError:') || it.startsWith('Caused by:')
                }
                if (!passed && failures) reason = failures.last()
                String signal = gameLines.find { it.startsWith('#  SIG') }
                if (!passed && signal) {
                    reason = 'Native crash: ' + signal.substring(1).trim()
                    int frame = gameLines.indexOf('# Problematic frame:')
                    if (frame >= 0 && frame + 1 < gameLines.size()) {
                        reason += '; ' + gameLines[frame + 1].replaceFirst(/^#\s*/, '')
                    }
                }
                Map dependencyError = passed ? [:] : dependencyFailure(gameText, spec.strict as boolean)
                if (!receipt.isFile() || recorded.failure?.type == 'TimeoutExpired' ||
                        reason =~ /Runtime inputs differ|checksum mismatch|Failed to create window|Failed to initialize Vulkan|GPU timeout|VK_ERROR_DEVICE_LOST/) {
                    status = 'blocked'
                } else if (dependencyError) {
                    status = dependencyError.status
                    reason = dependencyError.reason
                }
                result = [status: status, receipt: receipt.absolutePath, reason: passed ? null : reason,
                          graphicsEnvironment: environment]
                if (!passed) break
            }
            List<File> evidence = []
            cell.traverse(type: FileType.FILES) { File file ->
                if (!file.name.endsWith('.lock')) evidence.add(file)
            }
            result + [files: evidence]
        } finally {
            if (compositor != null) {
                compositor.destroy()
                if (!compositor.waitFor(5, TimeUnit.SECONDS)) compositor.destroyForcibly().waitFor()
            }
            if (spec.manageDisplay) {
                displayDirectory.listFiles()?.each { Files.deleteIfExists(it.toPath()) }
                Files.deleteIfExists(displayDirectory.toPath())
            }
        }
    }
}
