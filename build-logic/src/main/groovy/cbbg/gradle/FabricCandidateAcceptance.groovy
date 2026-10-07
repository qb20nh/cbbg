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

/** Serial local acceptance of the immutable packaged Fabric candidate. */
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
            if (file != null) inputs.executables[name] = [path: file.canonicalPath, sha256: CandidateFiles.sha256(file)]
        }
        targets.each { id ->
            File seed = options.seedCaches?.get(id) as File
            if (seed != null) inputs.seedCaches[id] = inventory(seed)
            File api = options.gametestApis?.get(id) as File
            if (api != null) inputs.gametestApis[id] = [path: api.canonicalPath, sha256: CandidateFiles.sha256(api)]
        }
        inputs.scripts = inventory(new File(root, 'scripts'))
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
            File config = new File(targetOutput, 'initial-cbbg.json')
            if (!config.exists()) CandidateFiles.writeNew(config, INITIAL_CONFIG)
            if (CandidateFiles.read(config) != INITIAL_CONFIG) throw new GradleException('Acceptance config changed: ' + config)
            runs.each { Map requirement ->
                if (rows.any { key(it) == key(requirement) }) return
                requireSource(root, candidate, command)
                if (output.usableSpace < 512L * 1024 * 1024) throw new GradleException('Candidate acceptance needs at least 512 MiB free before the next client: ' + output + '. Free space and resume this same output.')
                Map dependencies = (plan.dependencies[requirement.profile] as Map).collectEntries { name, entry ->
                    [(name): entry + [file: dependency(entry as Map, new File(output, 'dependencies'))]]
                }
                File startupCache = requirement.startupMode in ['warm', 'damaged'] ? coldCaches[requirement.backend] :
                        requirement.startupMode == 'seed-mismatch' ? options.seedCaches[id] as File : null
                if (requirement.startupMode in ['warm', 'damaged'] && startupCache == null) {
                    throw new GradleException('Startup ' + requirement.startupMode + ' requires an earlier verified cold cell for ' + id)
                }
                Map spec = [root: root, output: targetOutput, target: target, python: options.python,
                            java: options['java' + target.java], weston: options.weston,
                            eglVendor: options.eglVendor, manageDisplay: true, strict: true,
                            candidate: CandidateFiles.checked(base, record.artifact as Map),
                            driver: CandidateFiles.checked(base, tests.drivers[requirement.suite] as Map),
                            gametest: gametest, api: dependencies.fabricApi.file,
                            apiVersion: target.dependencies.fabricApi, loaderVersion: target.dependencies.loader,
                            optionalMods: dependencies.findAll { name, entry -> name != 'fabricApi' },
                            runtime: [directory: runtime, lock: files.runtime_lock], dependencyLock: files.dependency_lock,
                            profile: requirement.profile, backend: requirement.backend, restart: requirement.restart,
                            config: config, startupMode: requirement.startupMode, startupCache: startupCache]
                Map result = probe.call(spec)
                if (result.status != 'passed') throw new GradleException('Acceptance failed for ' + id + ' ' + key(requirement) +
                        ': ' + result.reason + '. Saved receipt: ' + result.receipt)
                File saved = new File(result.receipt as String)
                Map row = [suite: requirement.suite, profile: requirement.profile, backend: requirement.backend,
                           receipt: CandidateFiles.reference(targetOutput, relative(targetOutput, saved))]
                if (requirement.restart) {
                    File control = new File(saved.parentFile.parentFile, 'control-game/control-probe.json')
                    row.control_receipt = CandidateFiles.reference(targetOutput, relative(targetOutput, control))
                }
                verify(candidate, target, files, requirement, row, targetOutput, options, command)
                if (requirement.startupMode == 'cold') coldCaches[requirement.backend] = new File(saved.parentFile, '.cbbg')
                deduplicateMods(output, saved, requirement.restart as boolean)
                cleanup(saved, requirement.restart as boolean, requirement.startupMode == null && !requirement.restart)
                verify(candidate, target, files, requirement, row, targetOutput, options, command)
                rows.add(row)
                writeIndex(index, rows)
            }
            command.call([options.python, new File(root, 'scripts/fabric_acceptance.py'), '--candidate', candidate.file,
                          '--target', id, '--results', index].collect { it.toString() }, root)
        }
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
        List<File> libraries = ['/usr/lib', '/usr/lib64'].collect { new File(it) } +
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
        List args = [options.python, new File(options.root as File, 'scripts/fabric_run_evidence.py'),
                     '--receipt', saved, '--target', target.id, '--source-commit', candidate.data.commit,
                     '--candidate-sha256', candidate.records[target.id].artifact.sha256,
                     '--driver-sha256', candidate.records[target.id].client_tests.drivers[requirement.suite].sha256,
                     '--catalog', files.catalog, '--runtime-lock', files.runtime_lock, '--dependency-lock', files.dependency_lock]
        if (requirement.restart) args.addAll(['--restart', '--control-receipt', CandidateFiles.checked(base, row.control_receipt as Map)])
        else if (row.containsKey('control_receipt')) throw new GradleException('Ordinary cell has a restart control receipt')
        Map verified = parse(command.call(args.collect { it.toString() }, options.root as File))
        if (verified.profile != requirement.profile || verified.backend != requirement.backend ||
                verified.scenarios != requirement.entrypoints || verified.startupMode != requirement.startupMode) {
            throw new GradleException('Verified receipt differs from acceptance cell: ' + key(requirement))
        }
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
