package cbbg.gradle

import groovy.io.FileType
import groovy.json.JsonOutput
import org.gradle.api.GradleException

import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Resumable local acceptance of the immutable packaged Fabric candidate. */
class FabricCandidateAcceptance {
    static final Map INITIAL_CONFIG = [mode: 'ENABLED', pixelFormat: 'RGBA16F', stbnSize: 16,
                                       stbnDepth: 8, stbnSeed: 74123, strength: 2.0]
    private static final String PLAN = '''import json,sys
from pathlib import Path
from candidate_manifest import client_candidate
from fabric_acceptance import required_runs
from fabric_dependency_lock import locked_dependencies
from parity_evidence import checked_file,read_json
manifest,target,spec=client_candidate(Path(sys.argv[1]),sys.argv[2])
base=Path(sys.argv[1]).parent
tests=target['client_tests']
lock=read_json(checked_file(base,tests['dependency_lock']))
runs=required_runs(spec,read_json(checked_file(base,tests['contract'])),metadata_path=checked_file(base,tests['ordinary_metadata']))
print(json.dumps({'runs':runs,'dependencies':{p:locked_dependencies(spec,p,lock) for p in {r['profile'] for r in runs}}}))'''

    static void execute(Map options, Closure<String> command, Closure<Map> probe) {
        File root = (options.root as File).canonicalFile
        CandidateManifest candidate = new CandidateManifest(options.candidate as File)
        requireSource(root, candidate, command)
        List<String> targets = options.targets ?: candidate.records.keySet().toList()
        if (!targets || targets.unique(false).size() != targets.size() ||
                targets.any { !candidate.records.containsKey(it) || candidate.specifications[it].loader != 'fabric' }) {
            throw new GradleException('Acceptance requires distinct Fabric targets present in the candidate')
        }
        File output = (options.output as File).canonicalFile
        output.mkdirs()
        Map inputs = [manifest: CandidateFiles.sha256(candidate.file), source: candidate.data.commit,
                      targets: targets, root: root.canonicalPath,
                      graphicsEnvironment: FabricCompatibilityProbe.graphicsEnvironment(),
                      executables: [:], seedCaches: [:], gametestApis: [:],
                      runtimes: (options.runtimes ?: [:]).collectEntries { id, path -> [(id): (path as File).canonicalPath] },
                      sharedRuntime: options.sharedRuntime == null ? null : (options.sharedRuntime as File).canonicalPath]
        ['python', 'java21', 'java25', 'weston', 'eglVendor'].each { name ->
            File file = options[name] as File
            if (file != null) inputs.executables[name] = [path: file.absolutePath,
                    resolvedPath: file.canonicalPath, sha256: CandidateFiles.sha256(file)]
        }
        targets.each { id ->
            File seed = options.seedCaches?.get(id) as File
            if (seed != null) inputs.seedCaches[id] = inventory(seed)
            File api = options.gametestApis?.get(id) as File
            if (api != null) inputs.gametestApis[id] = [path: api.canonicalPath, sha256: CandidateFiles.sha256(api)]
        }
        inputs.scripts = inventory(new File(root, 'scripts'))
        inputs.executionSources = executionSources(root)
        inputs.initialConfig = candidate.identity.product == 'lib' ? null : INITIAL_CONFIG
        inputs.executionEnvironment = ['VK_DRIVER_FILES', 'VK_ICD_FILENAMES', 'VK_ADD_DRIVER_FILES',
                'VK_LOADER_DRIVERS_SELECT', 'VK_LOADER_DRIVERS_DISABLE',
                'VK_LAYER_PATH', 'VK_ADD_LAYER_PATH', 'VK_INSTANCE_LAYERS', 'VK_LOADER_LAYERS_ENABLE',
                'VK_LOADER_LAYERS_DISABLE', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS',
                'LD_LIBRARY_PATH', 'LD_PRELOAD', '__EGL_VENDOR_LIBRARY_FILENAMES',
                '__EGL_VENDOR_LIBRARY_DIRS', 'LIBGL_DRIVERS_PATH'].findAll { System.getenv(it) != null }.collectEntries { name ->
            [(name): java.security.MessageDigest.getInstance('SHA-256')
                    .digest(System.getenv(name).getBytes('UTF-8')).encodeHex().toString()]
        }
        inputs.host = hostInputs(options)
        inputs.python = parse(command.call(python(options, root, '''import json,sys
from importlib.metadata import version
print(json.dumps({'pythonVersion':sys.version,'launcherVersion':version('minecraft-launcher-lib')}))''', []), root))
        if (inputs.python.launcherVersion != '8.0') throw new GradleException('Candidate acceptance requires minecraft-launcher-lib==8.0')
        File identity = new File(output, 'inputs.json')
        if (identity.exists()) {
            if (CandidateFiles.read(identity) != inputs) {
                throw new GradleException('Acceptance inputs changed: ' + identity + '. Use a fresh output directory for this candidate and host.')
            }
        } else {
            if (output.listFiles().length != 0) throw new GradleException('Acceptance output has no matching input identity: ' + output)
            CandidateFiles.writeNew(identity, inputs)
        }
        int workers = options.workers == null ? 1 : options.workers as int
        if (workers < 1) throw new GradleException('Acceptance workers must be a positive integer')
        List<Map> contexts = []
        targets.each { String id ->
            Map record = candidate.records[id]
            Map target = candidate.specifications[id]
            Map tests = record.client_tests
            File base = candidate.file.parentFile
            Map files = ['catalog', 'contract', 'ordinary_metadata', 'runtime_lock', 'dependency_lock']
                    .collectEntries { [(it): CandidateFiles.checked(base, tests[it] as Map)] }
            if (CandidateFiles.sha256(new File(root, 'targets.json')) != CandidateFiles.sha256(files.catalog as File)) {
                throw new GradleException('Source catalog differs from packaged catalog for ' + id)
            }
            Map plan = parse(command.call(python(options, root, PLAN, [candidate.file, id]), root))
            List<Map> runs = plan.runs
            if (runs.any { it.startupMode == 'seed-mismatch' }) requireSeedCache(options.seedCaches?.get(id) as File, id)
            File targetOutput = new File(output, id)
            targetOutput.mkdirs()
            File index = new File(targetOutput, 'results.json')
            List<Map> rows = index.exists() ? CandidateFiles.read(index) as List<Map> : []
            validateRows(rows, runs)
            if (options.reuse != null) {
                List previous = options.reuse instanceof List ? options.reuse : [options.reuse]
                previous.each { directory ->
                    try {
                        importResults(candidate, target, files, runs, rows, targetOutput, inputs,
                                options + [reuse: directory], command)
                    } catch (GradleException | IOException error) {
                        options.cacheDecision?.call(id, [], 'run', 'Cached input unavailable: ' + error.message)
                    }
                }
                writeIndex(index, rows)
            }
            File runtime = options.runtimes?.get(id) as File ?: new File(output, 'runtimes/' + id)
            String profile = 'fabric-loader-' + target.dependencies.loader + '-' + target.minecraft
            FabricRuntimeInstallation.ensure(runtime, id, profile) { boolean resume ->
                List install = [options.python, new File(root, 'scripts/install_fabric_parity_runtime.py'),
                                '--target', id, '--runtime', runtime]
                if (resume) install.add('--resume')
                if (options.sharedRuntime != null) install.addAll(['--shared-runtime', options.sharedRuntime])
                command.call(install.collect { it.toString() }, root)
                0
            }
            command.call([options.python, new File(root, 'scripts/fabric_runtime_lock.py'), '--runtime', runtime,
                          '--profile', profile, '--lock', files.runtime_lock, '--verify'].collect { it.toString() }, root)
            Map lock = CandidateFiles.read(files.dependency_lock as File) as Map
            File gametest = options.gametestApis?.get(id) as File
            if (gametest == null) {
                String coordinate = lock.gametestApi.coordinate
                if (!(coordinate ==~ /net\.fabricmc\.fabric-api:fabric-client-gametest-api-v1:[^\/\\]+/)) {
                    throw new GradleException('Provide -PacceptanceGametestApi.' + id + ' for the packaged test-only backport')
                }
                String version = coordinate.tokenize(':')[2]
                gametest = download(new File(output, 'dependencies'), lock.gametestApi.sha256 as String,
                        'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-client-gametest-api-v1/' +
                                version + '/fabric-client-gametest-api-v1-' + version + '.jar')
            }
            if (CandidateFiles.sha256(gametest) != lock.gametestApi.sha256) throw new GradleException('Gametest API checksum differs for ' + id)
            Map<String, File> coldCaches = [:]
            rows.each { Map row ->
                Map requirement = runs.find { key(it) == key(row) }
                verify(candidate, target, files, requirement, row, targetOutput, options, command)
                if (requirement.startupMode == 'cold') {
                    coldCaches[requirement.backend] = new File(receipt(targetOutput, row).parentFile, '.cbbg')
                }
            }
            File config = null
            if (candidate.identity.product != 'lib') {
                config = new File(targetOutput, 'initial-cbbg.json')
                if (!config.exists()) CandidateFiles.writeNew(config, INITIAL_CONFIG)
                if (CandidateFiles.read(config) != INITIAL_CONFIG) throw new GradleException('Acceptance config changed: ' + config)
            }
            contexts.add([id: id, record: record, target: target, tests: tests, base: base,
                          files: files, plan: plan, runs: runs, rows: rows, output: targetOutput,
                          index: index, runtime: runtime, gametest: gametest, config: config,
                          coldCaches: coldCaches])
        }
        List<Map> pending = contexts.collectMany { Map context ->
            context.runs.findAll { requirement -> !context.rows.any { key(it) == key(requirement) } }
                    .collect { requirement -> [context: context, requirement: requirement] }
        }
        runPending(pending, workers, { Map job ->
            Map context = job.context
            Map requirement = job.requirement
            requireSource(root, candidate, command)
            if (output.usableSpace < 512L * 1024 * 1024) throw new GradleException('Candidate acceptance needs at least 512 MiB free before the next client: ' + output + '. Free space and resume this same output.')
            Map dependencies = (context.plan.dependencies[requirement.profile] as Map).collectEntries { name, entry ->
                [(name): entry + [file: dependency(entry as Map, new File(output, 'dependencies'))]]
            }
            File startupCache = requirement.startupMode in ['warm', 'damaged'] ? context.coldCaches[requirement.backend] :
                    requirement.startupMode == 'seed-mismatch' ? options.seedCaches[context.id] as File : null
            Map target = context.target
            if (requirement.externalLibrary == true && !(context.record.library instanceof Map)) {
                throw new GradleException('External library suite requires a packaged library reference for ' + context.id)
            }
            [root: root, output: context.output, target: target, python: options.python,
             java: options['java' + target.java], weston: options.weston,
             eglVendor: options.eglVendor, manageDisplay: true, strict: true,
             candidate: CandidateFiles.checked(context.base as File, context.record.artifact as Map),
             externalLibrary: requirement.externalLibrary == true ?
                     CandidateFiles.checked(context.base as File, context.record.library as Map) : null,
             driver: CandidateFiles.checked(context.base as File, context.tests.drivers[requirement.suite] as Map),
             gametest: context.gametest, api: dependencies.fabricApi.file,
             apiVersion: target.dependencies.fabricApi, loaderVersion: target.dependencies.loader,
             optionalMods: dependencies.findAll { name, entry -> name != 'fabricApi' },
             runtime: [directory: context.runtime, lock: context.files.runtime_lock], dependencyLock: context.files.dependency_lock,
             profile: requirement.profile, backend: requirement.backend, restart: requirement.restart,
             config: context.config, startupMode: requirement.startupMode, startupCache: startupCache]
        }, probe, { Map job, Map result ->
            Map context = job.context
            Map requirement = job.requirement
            if (result.status != 'passed') throw new GradleException('Acceptance failed for ' + context.id + ' ' + key(requirement) +
                    ': ' + result.reason + '. Saved receipt: ' + result.receipt)
            File saved = new File(result.receipt as String)
            File targetOutput = context.output as File
            Map row = [suite: requirement.suite, profile: requirement.profile, backend: requirement.backend,
                       receipt: CandidateFiles.reference(targetOutput, relative(targetOutput, saved))]
            if (requirement.restart) {
                File control = new File(saved.parentFile.parentFile, 'control-game/control-probe.json')
                row.control_receipt = CandidateFiles.reference(targetOutput, relative(targetOutput, control))
            }
            verify(candidate, context.target as Map, context.files as Map, requirement, row, targetOutput, options, command)
            deduplicateMods(output, saved, requirement.restart as boolean)
            cleanup(saved, requirement.restart as boolean, requirement.startupMode == null && !requirement.restart)
            verify(candidate, context.target as Map, context.files as Map, requirement, row, targetOutput, options, command)
            context.rows.add(row)
            writeIndex(context.index as File, context.rows as List)
            if (requirement.startupMode == 'cold') context.coldCaches[requirement.backend] = new File(saved.parentFile, '.cbbg')
        })
        contexts.each { Map context ->
            command.call([options.python, new File(root, 'scripts/fabric_acceptance.py'), '--candidate', candidate.file,
                          '--target', context.id, '--results', context.index].collect { it.toString() }, root)
        }
    }

    static void runPending(List<Map> pending, int workers, Closure<Map> prepare,
                           Closure<Map> probe, Closure finished) {
        if (workers < 1) throw new GradleException('Acceptance workers must be a positive integer')
        def executor = Executors.newFixedThreadPool(workers)
        def completions = new ExecutorCompletionService<Map>(executor)
        int running = 0
        Exception failure = null
        try {
            while (pending || running) {
                if (failure == null) {
                    try {
                        while (running < workers) {
                            Map job = pending.find { Map candidate ->
                                !(candidate.requirement.startupMode in ['warm', 'damaged']) ||
                                        candidate.context.coldCaches[candidate.requirement.backend] != null
                            }
                            if (job == null) break
                            pending.remove(job)
                            Map spec = prepare.call(job)
                            completions.submit({ -> [job: job, result: probe.call(spec)] } as Callable<Map>)
                            running++
                        }
                    } catch (Exception error) { failure = error }
                }
                if (running == 0) {
                    if (failure == null && pending) throw new GradleException('Pending startup tests require a verified cold cache')
                    break
                }
                Future<Map> completed = completions.take()
                running--
                try {
                    Map result = completed.get()
                    finished.call(result.job, result.result)
                } catch (java.util.concurrent.ExecutionException error) {
                    if (failure == null) failure = new GradleException('Acceptance client failed', error.cause)
                } catch (Exception error) {
                    if (failure == null) failure = error
                }
            }
        } finally { executor.shutdown() }
        if (failure != null) throw failure
    }

    static void requireSource(File root, CandidateManifest candidate, Closure<String> command) {
        String head = command.call(['git', 'rev-parse', 'HEAD'], root).trim()
        String changed = command.call(['git', 'status', '--porcelain', '--untracked-files=all',
                                       '--', '.', ':(top,exclude)docs/**'], root).trim()
        if (head != candidate.data.commit || changed) throw new GradleException('Acceptance requires clean source HEAD ' + candidate.data.commit + ': ' + root)
    }

    static Map inventory(File directory) {
        if (!directory.isDirectory()) throw new GradleException('Missing acceptance input directory: ' + directory)
        Map files = new TreeMap()
        directory.traverse(type: FileType.FILES) { File file ->
            if (!file.toPath().any { it.toString() == '__pycache__' }) files[relative(directory, file)] = CandidateFiles.sha256(file)
        }
        [path: directory.canonicalPath, files: files]
    }

    static void requireSeedCache(File directory, String target) {
        if (directory == null || !directory.isDirectory() ||
                !new File(directory, 'stbn_16x16x8.sha256').isFile() ||
                !new File(directory, 'stbn_16x16x8.sha256').getText('UTF-8').startsWith('# seed 0\n')) {
            throw new GradleException('Provide -PacceptanceSeedZeroCache.' + target + '=<actual seed-zero cache directory>')
        }
    }

    private static Map hostInputs(Map options) {
        List<File> files = []
        ['java21', 'java25'].each { name ->
            File executable = options[name] as File
            File home = executable.canonicalFile.parentFile.parentFile
            files.addAll(['release', 'lib/modules', 'lib/server/libjvm.so'].collect { new File(home, it) })
        }
        File machine = new File('/etc/machine-id')
        if (machine.isFile()) files.add(machine)
        File graphics = new File('/sys/class/drm')
        if (graphics.isDirectory()) graphics.listFiles().findAll { it.name ==~ /card\d+/ }.each { card ->
            ['device/vendor', 'device/device', 'device/subsystem_device'].each { name ->
                File file = new File(card, name)
                if (file.isFile()) files.add(file)
            }
        }
        File displayPrefix = (options.weston as File).canonicalFile.parentFile.parentFile
        File xwayland = new File(displayPrefix, 'bin/Xwayland')
        if (xwayland.isFile()) files.add(xwayland)
        List<File> libraries = (options.hostLibraryDirectories == null ? ['/usr/lib', '/usr/lib64'].collect { new File(it) } :
                options.hostLibraryDirectories as List<File>) +
                ['lib', 'lib64'].collect { new File(displayPrefix, it) }
        libraries.findAll { it.isDirectory() }.unique().each { directory ->
            List<File> directories = [directory] + directory.listFiles().findAll {
                it.isDirectory() && it.name.endsWith('-linux-gnu')
            }
            directories.each { File libraryDirectory ->
                files.addAll(libraryDirectory.listFiles().findAll {
                    it.isFile() && it.name ==~ /lib(GL|EGL|vulkan|nvidia|gallium|drm|wayland|gbm).*\.so.*/
                })
                libraryDirectory.listFiles().findAll {
                    it.isDirectory() && (it.name.startsWith('libweston-') || it.name in ['weston', 'dri'])
                }.each { File modules ->
                    modules.traverse(type: FileType.FILES) { File module ->
                        if (module.name ==~ /.*\.so.*/) files.add(module)
                    }
                }
            }
        }
        [os: System.getProperty('os.name') + '/' + System.getProperty('os.arch') + '/' + System.getProperty('os.version'),
         files: files.collectEntries { [(it.canonicalPath): CandidateFiles.sha256(it)] }]
    }

    static List key(Map row) { [row.suite, row.profile, row.backend] }

    static void validateRows(List<Map> rows, List<Map> runs) {
        if (rows.collect { key(it) }.unique(false).size() != rows.size() ||
                rows.any { row -> !runs.any { key(it) == key(row) } }) {
            throw new GradleException('Acceptance index has duplicate or unexpected cells')
        }
    }

    private static void verify(CandidateManifest candidate, Map target, Map files, Map requirement, Map row,
                               File base, Map options, Closure<String> command) {
        File saved = receipt(base, row)
        String source = candidate.data.commit
        String driver = candidate.records[target.id].client_tests.drivers[requirement.suite].sha256
        if (row.containsKey('reuse')) {
            Map tests = candidate.records[target.id].client_tests
            File dependencies = CandidateFiles.checked(candidate.file.parentFile, tests.test_dependencies as Map)
            File currentDriver = CandidateFiles.checked(candidate.file.parentFile, tests.drivers[requirement.suite] as Map)
            String code = '''import json,sys
from pathlib import Path
from fabric_test_cache import reuse_arguments
from parity_evidence import read_json
print(json.dumps(reuse_arguments(json.loads(sys.argv[1]),Path(sys.argv[2]),json.loads(sys.argv[3]),current_driver=Path(sys.argv[4]),dependencies=read_json(sys.argv[5]),current_inputs=Path(sys.argv[6]),base=Path(sys.argv[7]))))'''
            Map reused = parse(command.call(python(options, options.root as File, code,
                    [JsonOutput.toJson(row), saved, JsonOutput.toJson(target), currentDriver, dependencies,
                     new File(base.parentFile, 'inputs.json'), base]), options.root as File))
            source = reused.source_commit
            driver = reused.driver_sha256
        }
        List args = [options.python, new File(options.root as File, 'scripts/fabric_run_evidence.py'),
                     '--receipt', saved, '--target', target.id, '--source-commit', source,
                     '--candidate-sha256', candidate.records[target.id].artifact.sha256,
                     '--driver-sha256', driver,
                     '--catalog', files.catalog, '--runtime-lock', files.runtime_lock, '--dependency-lock', files.dependency_lock]
        if (requirement.restart) args.addAll(['--restart', '--control-receipt', CandidateFiles.checked(base, row.control_receipt as Map)])
        else if (row.containsKey('control_receipt')) throw new GradleException('Ordinary cell has a restart control receipt')
        Map verified = parse(command.call(args.collect { it.toString() }, options.root as File))
        if (verified.profile != requirement.profile || verified.backend != requirement.backend ||
                verified.scenarios != requirement.entrypoints || verified.startupMode != requirement.startupMode) {
            throw new GradleException('Verified receipt differs from acceptance cell: ' + key(requirement))
        }
    }

    private static final List<String> EXECUTION_SOURCES = [
            'build-logic/src/main/groovy/cbbg/gradle/FabricCompatibilityProbe.groovy',
            'build-logic/src/main/groovy/cbbg/gradle/FabricRuntimeInstallation.groovy']

    static Map executionSources(File root) {
        EXECUTION_SOURCES.collectEntries { name -> [(name): CandidateFiles.sha256(new File(root, name))] }
    }

    private static void importResults(CandidateManifest candidate, Map target, Map files, List<Map> runs,
                                      List<Map> rows, File output, Map inputs, Map options, Closure<String> command) {
        File previous = (options.reuse as File).canonicalFile
        Path currentRoot = output.parentFile.canonicalFile.toPath()
        if (previous.toPath().startsWith(currentRoot) || currentRoot.startsWith(previous.toPath())) {
            throw new GradleException('Reuse must reference a separate acceptance directory')
        }
        File oldIndex = new File(previous, target.id + '/results.json')
        if (!oldIndex.isFile()) return
        File oldIdentity = new File(previous, 'inputs.json')
        Map oldInputs = CandidateFiles.read(oldIdentity) as Map
        if (!(oldInputs.executionEnvironment instanceof Map) || oldInputs.executionEnvironment || inputs.executionEnvironment) {
            options.cacheDecision?.call(target.id, [], 'run', 'Cached execution environment is unknown or uses custom overrides')
            return
        }
        Map sources = EXECUTION_SOURCES.collectEntries { name ->
            String text = command.call(['git', 'show', oldInputs.source + ':' + name], options.root as File)
            [(name): java.security.MessageDigest.getInstance('SHA-256').digest(text.getBytes('UTF-8')).encodeHex().toString()]
        }
        if (sources != inputs.executionSources) return
        String prefix = 'reuse/' + CandidateFiles.sha256(oldIdentity)
        File copied = new File(output, prefix)
        copyResults(oldIndex.parentFile, copied)
        File copiedInputs = new File(copied, 'inputs.json')
        if (!copiedInputs.exists()) Files.createLink(copiedInputs.toPath(), oldIdentity.toPath())
        List<Map> priorRows = CandidateFiles.read(oldIndex) as List<Map>
        priorRows.each { old ->
            Map requirement = runs.find { key(it) == key(old) }
            if (requirement == null || rows.any { key(it) == key(old) }) return
            try {
                Map row = old + [receipt: old.receipt + [path: prefix + '/' + old.receipt.path],
                                 reuse: [inputs: CandidateFiles.reference(output, prefix + '/inputs.json'),
                                         execution_sources: sources]]
                if (candidate.identity.product != 'lib') row.reuse.config = CandidateFiles.reference(output, prefix + '/initial-cbbg.json')
                if (old.reuse != null) {
                    row.reuse.inputs = old.reuse.inputs + [path: prefix + '/' + old.reuse.inputs.path]
                    if (old.reuse.config != null) row.reuse.config = old.reuse.config + [path: prefix + '/' + old.reuse.config.path]
                }
                if (old.control_receipt != null) row.control_receipt = old.control_receipt + [path: prefix + '/' + old.control_receipt.path]
                verify(candidate, target, files, requirement, row, output, options, command)
                rows.add(row)
                options.cacheDecision?.call(target.id, key(row), 'reuse', null)
            } catch (GradleException | IOException error) {
                // Changed dependencies or missing evidence require a new client run.
                options.cacheDecision?.call(target.id, key(old), 'run', error.message)
            }
        }
    }

    static void copyResults(File source, File destination) {
        destination.mkdirs()
        Files.walkFileTree(source.toPath(), new SimpleFileVisitor<Path>() {
            @Override FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
                if (path.fileName.toString() in ['.fabric', 'natives', 'saves']) return FileVisitResult.SKIP_SUBTREE
                new File(destination, source.toPath().relativize(path).toString()).mkdirs()
                FileVisitResult.CONTINUE
            }
            @Override FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                if (Files.isSymbolicLink(path)) throw new GradleException('Cached result contains a symlink: ' + path)
                File target = new File(destination, source.toPath().relativize(path).toString())
                if (target.exists()) {
                    if (CandidateFiles.sha256(target) != CandidateFiles.sha256(path.toFile())) {
                        throw new GradleException('Cached result copy differs: ' + target)
                    }
                } else if (Files.getFileStore(target.parentFile.toPath()) == Files.getFileStore(path)) {
                    Files.createLink(target.toPath(), path)
                } else {
                    Files.copy(path, target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                }
                FileVisitResult.CONTINUE
            }
        })
    }

    private static File receipt(File base, Map row) { CandidateFiles.checked(base, row.receipt as Map) }
    private static Map parse(String json) { CandidateFiles.parse(new StringReader(json)) as Map }
    private static List<String> python(Map options, File root, String code, List args) {
        [options.python.toString(), '-c', 'import sys;sys.path.insert(0,' + JsonOutput.toJson(new File(root, 'scripts').absolutePath) + ')\n' + code] + args.collect { it.toString() }
    }
    private static String relative(File base, File file) {
        base.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace(File.separator, '/')
    }

    static void writeIndex(File index, List<Map> rows) {
        File staged = File.createTempFile('results-', '.json', index.parentFile)
        try {
            staged.setText(JsonOutput.prettyPrint(JsonOutput.toJson(rows)) + '\n', 'UTF-8')
            Files.move(staged.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { staged.delete() }
    }

    static void cleanup(File saved, boolean restart, boolean ordinary = false) {
        List<File> games = [saved.parentFile]
        if (restart) games.add(new File(saved.parentFile.parentFile, 'control-game'))
        games.each { File game ->
            List<String> disposable = ['.fabric', 'saves', 'natives']
            if (ordinary) disposable.add('.cbbg')
            List<Map> reports = game.listFiles().findAll { it.name == 'probe.json' || it.name.endsWith('-probe.json') }
                    .collect { CandidateFiles.read(it) as Map }
            disposable.each { String name ->
                if (reports.any { report -> (report.evidence ?: [:]).keySet().any { it == name || it.startsWith(name + '/') } }) {
                    throw new GradleException('Generated directory is used by receipt evidence: ' + name)
                }
                File generated = new File(game, name)
                if (Files.isSymbolicLink(generated.toPath())) throw new GradleException('Generated acceptance directory is a symlink: ' + generated)
                if (generated.exists()) {
                    Files.walkFileTree(generated.toPath(), new SimpleFileVisitor<Path>() {
                        @Override FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                            Files.delete(file)
                            FileVisitResult.CONTINUE
                        }
                        @Override FileVisitResult postVisitDirectory(Path directory, IOException error) {
                            if (error != null) throw error
                            Files.delete(directory)
                            FileVisitResult.CONTINUE
                        }
                    })
                }
            }
        }
    }

    static void deduplicateMods(File output, File saved, boolean restart) {
        File root = output.canonicalFile
        File pool = new File(root, 'dependencies')
        if (Files.isSymbolicLink(pool.toPath())) throw new GradleException('Acceptance jar pool is a symlink: ' + pool)
        pool.mkdirs()
        List<File> games = [saved.parentFile]
        if (restart) games.add(new File(saved.parentFile.parentFile, 'control-game'))
        games.each { File game ->
            File mods = new File(game, 'mods')
            if (!mods.canonicalFile.toPath().startsWith(root.toPath()) || Files.isSymbolicLink(mods.toPath())) {
                throw new GradleException('Acceptance mods directory escapes output: ' + mods)
            }
            mods.listFiles()?.each { File jar ->
                if (!jar.name.endsWith('.jar') || !jar.isFile() || Files.isSymbolicLink(jar.toPath())) {
                    throw new GradleException('Unexpected retained acceptance mod: ' + jar)
                }
                String hash = CandidateFiles.sha256(jar)
                File shared = new File(pool, hash + '.jar')
                if (Files.isSymbolicLink(shared.toPath())) throw new GradleException('Acceptance pooled jar is a symlink: ' + shared)
                if (!shared.exists()) Files.createLink(shared.toPath(), jar.toPath())
                if (!shared.isFile() || CandidateFiles.sha256(shared) != hash) {
                    throw new GradleException('Acceptance pooled jar checksum differs: ' + shared)
                }
                if (!Files.isSameFile(jar.toPath(), shared.toPath())) {
                    File staged = File.createTempFile('mod-', '.jar', mods)
                    try {
                        Files.delete(staged.toPath())
                        Files.createLink(staged.toPath(), shared.toPath())
                        Files.move(staged.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } finally { staged.delete() }
                }
            }
        }
    }

    private static File dependency(Map entry, File directory) {
        File cached = new File(directory, entry.sha256 + '.jar')
        if (cached.isFile() && CandidateFiles.sha256(cached) == entry.sha256) return cached
        URI source = new URI(entry.source as String)
        if (source.scheme != 'https' || source.host != 'api.modrinth.com') throw new GradleException('Unexpected locked dependency source: ' + source)
        Map version = parse(readUrl(source.toURL()))
        List<Map> jars = version.files?.findAll { it.primary == true && it.filename?.endsWith('.jar') } ?: []
        if (jars.size() != 1) throw new GradleException('Expected one primary locked dependency jar: ' + source)
        URI url = new URI(jars[0].url as String)
        if (url.scheme != 'https' || url.host != 'cdn.modrinth.com') throw new GradleException('Unexpected dependency download: ' + url)
        download(directory, entry.sha256 as String, url.toString())
    }

    private static String readUrl(URL url) {
        URLConnection connection = url.openConnection()
        connection.connectTimeout = 30000
        connection.readTimeout = 30000
        connection.inputStream.withCloseable { it.getText('UTF-8') }
    }

    private static File download(File directory, String hash, String url) {
        if (!(hash ==~ /[0-9a-f]{64}/)) throw new GradleException('Invalid dependency SHA-256')
        directory.mkdirs()
        File file = new File(directory, hash + '.jar')
        if (file.isFile() && CandidateFiles.sha256(file) == hash) return file
        File staged = File.createTempFile('dependency-', '.jar', directory)
        try {
            URLConnection connection = new URI(url).toURL().openConnection()
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.inputStream.withCloseable { Files.copy(it, staged.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            if (CandidateFiles.sha256(staged) != hash) throw new GradleException('Locked dependency checksum mismatch: ' + url)
            Files.move(staged.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { staged.delete() }
        file
    }
}
