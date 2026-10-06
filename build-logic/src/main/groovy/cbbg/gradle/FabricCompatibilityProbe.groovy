package cbbg.gradle

import groovy.io.FileType
import groovy.json.JsonOutput
import org.gradle.api.GradleException

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** Runs one packaged-client dependency probe and classifies its recorded result. */
class FabricCompatibilityProbe {
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

    static Map cacheInputs(Map reportInputs, Map spec, File coordinator, File search) {
        File gametest = spec.gametest as File
        File api = spec.api as File
        File python = spec.python as File
        File java = spec.java as File
        File runner = new File(spec.root as File, 'scripts/fabric_parity_runtime.py')
        Map mods = (spec.optionalMods as Map).collectEntries { name, mod ->
            [(name): [pin: mod.pin, sha256: CandidateFiles.sha256(mod.file as File),
                      sha512: mod.sha512]]
        }
        reportInputs + [files: executionFiles(reportInputs.files as Map, coordinator, search,
                        spec.backend as String),
                target: spec.target.id, candidate: CandidateFiles.sha256(spec.candidate as File),
                driver: CandidateFiles.sha256(spec.driver as File),
                gametestApiPin: spec.target.dependencies.fabricApi,
                gametestApiSha256: CandidateFiles.sha256(gametest),
                profile: spec.profile, optionalDependencies: mods,
                initialConfig: CandidateFiles.sha256(spec.config as File),
                display: spec.manageDisplay ? [managed: true, protocol: (spec.target.java ?: 25) < 25 ? 'x11' : 'wayland'] :
                        [directory: (spec.displayDirectory as File).canonicalPath, wayland: spec.display],
                minimumOverrides: !spec.strict,
                loader: spec.loaderVersion, fabricApi: spec.apiVersion,
                apiSha256: CandidateFiles.sha256(api), gametest: CandidateFiles.sha256(gametest),
                runtime: CandidateFiles.sha256(spec.runtime.lock as File),
                runtimeDirectory: (spec.runtime.directory as File).canonicalPath,
                backend: spec.backend,
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
        File cell = new File(spec.output as File, 'runs/' + UUID.randomUUID().toString())
        cell.mkdirs()
        File displayDirectory = spec.manageDisplay ?
                Files.createTempDirectory(new File('/tmp').toPath(), 'cbbg-display-').toFile() :
                spec.displayDirectory as File
        String display = spec.manageDisplay ? 'cbbg-test' : spec.display as String
        boolean x11 = spec.manageDisplay && (spec.target.java ?: 25) < 25
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
            File lock = new File(cell, 'dependencies.json')
            Map locked = [fabricApi: [pin: spec.apiVersion,
                    sha256: CandidateFiles.sha256(spec.api as File)]]
            (spec.optionalMods as Map).each { name, mod ->
                locked[name] = [pin: mod.pin, sha256: mod.sha256]
            }
            lock.text = JsonOutput.toJson([schemaVersion: 1, target: spec.target.id,
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
                    '--timeout', '600', '--cbbg-config', spec.config,
                    '--loader-version', spec.loaderVersion, '--fabric-api-version', spec.apiVersion,
                    '--gametest-api-version', spec.target.dependencies.fabricApi]
            (spec.optionalMods as Map).each { name, mod ->
                command.addAll(['--dependency', name + '=' + mod.file.absolutePath])
            }
            if (!spec.strict) command.add('--test-dependency-minimums')
            File log = new File(cell, 'runner.log')
            int exit = run.call(command, log)
            File receipt = new File(cell, 'game/probe.json')
            Map recorded = receipt.isFile() ? (Map) CandidateFiles.read(receipt) : [:]
            boolean passed = exit == 0 && recorded.exitCode == 0 && !recorded.failure && recorded.scenarios
            String status = passed ? 'passed' : 'failed'
            String reason = recorded.failure?.message ?: log.text.takeRight(2000)
            File gameLog = new File(cell, 'game/launch.log')
            String gameText = gameLog.isFile() ? gameLog.text : ''
            List failures = gameText.readLines().findAll {
                it.contains('java.lang.AssertionError:') || it.startsWith('Caused by:')
            }
            if (!passed && failures) reason = failures.last()
            if (!receipt.isFile() || recorded.failure?.type == 'TimeoutExpired' ||
                    (!spec.strict && gameText =~ /HARD_DEP(?:_NO_CANDIDATE)? cbbg(?:-renderer-test)? .*\{depends (?:fabricloader|fabric-api) @/) ||
                    reason =~ /Runtime inputs differ|checksum mismatch|Failed to create window|Failed to initialize Vulkan|GPU timeout|VK_ERROR_DEVICE_LOST/) {
                status = 'blocked'
            }
            List<File> evidence = []
            cell.traverse(type: FileType.FILES) { File file ->
                if (!file.name.endsWith('.lock')) evidence.add(file)
            }
            [status: status, receipt: receipt.absolutePath, reason: passed ? null : reason,
             files: evidence]
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
