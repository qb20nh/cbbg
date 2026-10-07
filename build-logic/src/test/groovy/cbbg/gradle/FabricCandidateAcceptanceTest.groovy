package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files

import static org.junit.jupiter.api.Assertions.*

class FabricCandidateAcceptanceTest {
    @TempDir File directory

    @Test void resumesOnlyMatchingInputsAndRevalidatesRecordedReceipts() {
        Map fixture = CandidateFixture.create(directory)
        new File(fixture.root, 'targets.json').bytes = new File(fixture.bundle, 'catalog.json').bytes
        Map lock = [schemaVersion: 1, target: fixture.target.id, dependencies: [:],
                    gametestApi: [sha256: CandidateFiles.sha256(new File(fixture.bundle, 'ordinary-driver.jar'))]]
        File lockFile = new File(fixture.bundle, 'dependency-lock.json')
        lockFile.text = JsonOutput.toJson(lock)
        fixture.record.client_tests.dependency_lock = CandidateFiles.reference(fixture.bundle, lockFile.name)
        fixture.file.text = JsonOutput.toJson(fixture.manifest)
        File python = new File(directory, 'jvm/bin/python')
        python.parentFile.mkdirs()
        python.text = 'python executable'
        List<File> displayInputs = ['bin/Xwayland', 'lib/libweston-fixture/headless-backend.so',
                                    'lib/libweston-fixture/gl-renderer.so', 'lib/libweston-fixture/xwayland.so',
                                    'lib/dri/fixture_dri.so'].collect { name ->
            File input = new File(directory, 'jvm/' + name)
            input.parentFile.mkdirs()
            input.text = 'display implementation'
            input
        }
        ['release', 'lib/modules', 'lib/server/libjvm.so'].each { name ->
            File file = new File(directory, 'jvm/' + name)
            file.parentFile.mkdirs()
            file.text = 'JVM identity'
        }
        File scripts = new File(fixture.root, 'scripts')
        scripts.mkdirs()
        new File(scripts, 'runner.py').text = 'runner'
        File runtime = new File(directory, 'runtime')
        runtime.mkdirs()
        new File(runtime, 'cbbg-install-receipt.json').text = JsonOutput.toJson([target: fixture.target.id,
                profile: 'fabric-loader-0.19.5-26.3', installed: true])
        Map requirement = [suite: 'ordinary', profile: 'none', backend: 'opengl', restart: false,
                           startupMode: null, entrypoints: ['example.Test']]
        Map options = [root: fixture.root, candidate: fixture.file, output: new File(directory, 'acceptance'),
                       python: python, java21: python, java25: python, weston: python,
                       runtimes: [(fixture.target.id): runtime],
                       gametestApis: [(fixture.target.id): new File(fixture.bundle, 'ordinary-driver.jar')]]
        // A content-addressed dependency is already local: no network or client is used.
        File dependency = new File(options.output as File, 'dependencies/' + CandidateFiles.sha256(python) + '.jar')
        Map entry = [pin: 'api', sha256: CandidateFiles.sha256(python)]
        int verifications = 0
        Closure command = { List args, File root ->
            if (args[0] == 'git') return args[1] == 'rev-parse' ? 'a' * 40 : ''
            if (args.contains('-c')) return args[args.indexOf('-c') + 1].contains('client_candidate') ?
                    JsonOutput.toJson([runs: [requirement], dependencies: [none: [fabricApi: entry]]]) :
                    JsonOutput.toJson([pythonVersion: 'test', launcherVersion: '8.0'])
            if (args[1].toString().endsWith('fabric_run_evidence.py')) {
                verifications++
                File receipt = new File(args[args.indexOf('--receipt') + 1].toString())
                return receipt.text
            }
            ''
        }
        int launches = 0
        Closure probe = { Map spec ->
            launches++
            assertTrue(spec.strict)
            assertEquals(lockFile, spec.dependencyLock)
            assertEquals(FabricCandidateAcceptance.INITIAL_CONFIG, CandidateFiles.read(spec.config as File))
            File game = new File(spec.output as File, 'runs/client/game')
            game.mkdirs()
            ['.fabric', 'saves', 'natives'].each { new File(game, it).mkdirs() }
            File receipt = new File(game, 'probe.json')
            receipt.text = JsonOutput.toJson([profile: 'none', backend: 'opengl', scenarios: ['example.Test'], startupMode: null])
            [status: 'passed', receipt: receipt.absolutePath]
        }
        // Create dependencies after the input identity is established, as downloads normally do.
        Closure prepareCommand = { List args, File root ->
            String result = command.call(args, root)
            if (args.contains('-c') && args[args.indexOf('-c') + 1].contains('client_candidate')) {
                dependency.parentFile.mkdirs(); dependency.bytes = python.bytes
            }
            result
        }
        FabricCandidateAcceptance.execute(options, prepareCommand, probe)
        assertEquals(1, launches)
        assertEquals(2, verifications)
        File index = new File(options.output as File, fixture.target.id + '/results.json')
        assertEquals(1, (CandidateFiles.read(index) as List).size())
        File game = new File(index.parentFile, 'runs/client/game')
        assertFalse(new File(game, 'saves').exists())
        assertTrue(new File(game, 'probe.json').isFile())
        FabricCandidateAcceptance.execute(options, prepareCommand, probe)
        assertEquals(1, launches)
        assertEquals(3, verifications)
        File alternatePython = new File(directory, 'alternate-python')
        Files.createSymbolicLink(alternatePython.toPath(), python.toPath())
        options.python = alternatePython
        assertTrue(assertThrows(GradleException) {
            FabricCandidateAcceptance.execute(options, prepareCommand, probe)
        }.message.contains('Acceptance inputs changed'))
        assertEquals(1, launches)
        options.python = python
        displayInputs.each { input ->
            input.append('changed')
            GradleException error = assertThrows(GradleException) {
                FabricCandidateAcceptance.execute(options, prepareCommand, probe)
            }
            assertTrue(error.message.contains('Acceptance inputs changed'))
            assertEquals(1, launches)
            input.text = 'display implementation'
        }
        new File(game, 'probe.json').append('changed')
        assertThrows(GradleException) { FabricCandidateAcceptance.execute(options, prepareCommand, probe) }
        python.append('changed')
        GradleException error = assertThrows(GradleException) { FabricCandidateAcceptance.execute(options, prepareCommand, probe) }
        assertTrue(error.message.contains('Acceptance inputs changed'))
    }

    @Test void requiresCleanCandidateCommitBeforeCreatingOutput() {
        Map fixture = CandidateFixture.create(directory)
        File output = new File(directory, 'acceptance')
        [['b' * 40, ''], ['a' * 40, ' M targets.json']].each { responses ->
            int index = 0
            assertThrows(GradleException) {
                FabricCandidateAcceptance.execute([root: fixture.root, candidate: fixture.file, output: output],
                        { List args, File root -> responses[index++] }, { fail('Client must not launch') })
            }
            assertFalse(output.exists())
        }
    }

    @Test void indexesRejectDuplicateAndUnknownCells() {
        Map row = [suite: 'ordinary', profile: 'none', backend: 'opengl']
        FabricCandidateAcceptance.validateRows([row], [row])
        assertThrows(GradleException) { FabricCandidateAcceptance.validateRows([row, row], [row]) }
        assertThrows(GradleException) { FabricCandidateAcceptance.validateRows([row + [backend: 'vulkan']], [row]) }
        File index = new File(directory, 'results.json')
        FabricCandidateAcceptance.writeIndex(index, [row])
        FabricCandidateAcceptance.writeIndex(index, [])
        assertEquals([], CandidateFiles.read(index))
        assertEquals(['results.json'], directory.list().toList())
    }

    @Test void actualGitSourceCheckBlocksUntrackedSourceAndAllowsLocalDocs() {
        Map fixture = CandidateFixture.create(directory)
        File root = fixture.root as File
        Closure git = { List arguments, File workingDirectory ->
            Process process = new ProcessBuilder((['git'] + arguments.collect { it.toString() }) as List<String>)
                    .directory(workingDirectory).redirectErrorStream(true).start()
            String output = process.inputStream.getText('UTF-8')
            assertEquals(0, process.waitFor(), output)
            output
        }
        File docs = new File(root, 'docs')
        docs.mkdirs()
        File notes = new File(docs, 'notes.md')
        notes.text = 'Original local notes'
        git.call(['init', '--quiet'], root)
        git.call(['add', '.'], root)
        git.call(['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                  'commit', '--quiet', '--no-gpg-sign', '-m', 'Fixture'], root)
        fixture.manifest.commit = git.call(['rev-parse', 'HEAD'], root).trim()
        fixture.file.text = JsonOutput.toJson(fixture.manifest)
        CandidateManifest candidate = new CandidateManifest(fixture.file as File)
        Closure command = { List arguments, File workingDirectory -> git.call(arguments.drop(1), workingDirectory) }
        FabricCandidateAcceptance.requireSource(root, candidate, command)
        notes.append('\nEdited local notes')
        new File(docs, 'untracked.md').text = 'Additional local notes'
        FabricCandidateAcceptance.requireSource(root, candidate, command)
        File untracked = new File(root, 'Untracked.java')
        untracked.text = 'class Untracked {}'
        assertThrows(GradleException) { FabricCandidateAcceptance.requireSource(root, candidate, command) }
        assertTrue(untracked.delete())
        new File(root, 'targets.json').append('\n')
        assertThrows(GradleException) { FabricCandidateAcceptance.requireSource(root, candidate, command) }
    }

    @Test void seedMismatchRequiresActualSeedZeroCache() {
        assertThrows(GradleException) { FabricCandidateAcceptance.requireSeedCache(null, '1.21.1-fabric') }
        new File(directory, 'stbn_16x16x8.sha256').text = '# seed 1\n'
        assertThrows(GradleException) { FabricCandidateAcceptance.requireSeedCache(directory, '1.21.1-fabric') }
        new File(directory, 'stbn_16x16x8.sha256').text = '# seed 0\n'
        FabricCandidateAcceptance.requireSeedCache(directory, '1.21.1-fabric')
    }

    @Test void retainedModPathsShareVerifiedContentsAndRejectCorruptPool() {
        List<File> jars = ['first', 'second'].collect { name ->
            File game = new File(directory, name + '/game')
            File mods = new File(game, 'mods')
            mods.mkdirs()
            File jar = new File(mods, 'candidate.jar')
            jar.text = 'identical immutable mod'
            new File(game, 'probe.json').text = '{}'
            jar
        }
        String hash = CandidateFiles.sha256(jars[0])
        assertFalse(Files.isSameFile(jars[0].toPath(), jars[1].toPath()))
        jars.each { jar -> FabricCandidateAcceptance.deduplicateMods(directory, new File(jar.parentFile.parentFile, 'probe.json'), false) }
        File pooled = new File(directory, 'dependencies/' + hash + '.jar')
        assertTrue(Files.isSameFile(jars[0].toPath(), jars[1].toPath()))
        assertTrue(Files.isSameFile(jars[0].toPath(), pooled.toPath()))
        assertEquals('identical immutable mod', jars[1].text)
        Files.delete(pooled.toPath())
        pooled.text = 'corrupted pool'
        assertThrows(GradleException) {
            FabricCandidateAcceptance.deduplicateMods(directory, new File(jars[0].parentFile.parentFile, 'probe.json'), false)
        }
        assertEquals('identical immutable mod', jars[0].text)
        Files.delete(pooled.toPath())
        Files.createSymbolicLink(pooled.toPath(), jars[0].toPath())
        assertThrows(GradleException) {
            FabricCandidateAcceptance.deduplicateMods(directory, new File(jars[0].parentFile.parentFile, 'probe.json'), false)
        }
    }

    @Test void cleanupPreservesEvidenceAndDoesNotTraverseSymlinks() {
        File game = new File(directory, 'cell/game')
        game.mkdirs()
        File saved = new File(game, 'probe.json')
        saved.text = JsonOutput.toJson([evidence: ['.cbbg/proof.json': 'hash']])
        File cache = new File(game, '.cbbg')
        cache.mkdirs()
        new File(cache, 'proof.json').text = 'proof'
        assertThrows(GradleException) { FabricCandidateAcceptance.cleanup(saved, false, true) }
        assertTrue(new File(cache, 'proof.json').isFile())
        saved.text = '{}'
        File external = new File(directory, 'external')
        external.mkdirs()
        File retained = new File(external, 'personal')
        retained.text = 'retain'
        File worlds = new File(game, 'saves')
        worlds.mkdirs()
        Files.createSymbolicLink(new File(worlds, 'link').toPath(), external.toPath())
        FabricCandidateAcceptance.cleanup(saved, false, true)
        assertTrue(saved.isFile())
        assertTrue(retained.isFile())
        assertFalse(worlds.exists())
        assertFalse(cache.exists())
    }
}
