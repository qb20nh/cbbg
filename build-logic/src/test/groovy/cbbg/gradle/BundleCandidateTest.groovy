package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import static org.junit.jupiter.api.Assertions.*

class BundleCandidateTest {
    @TempDir File directory

    private String git(File root, String... args) {
        Process process = new ProcessBuilder(['git'] + args.toList()).directory(root).redirectErrorStream(true).start()
        String output = process.inputStream.getText('UTF-8')
        assertEquals(0, process.waitFor(), output)
        output.trim()
    }

    private Map setup() {
        def fixture = CandidateFixture.create(directory, false, true)
        File root = fixture.root
        new File(root, '.gitignore').text = 'build/\n.gradle/\n'
        Files.copy(new File(fixture.bundle, 'ordinary-metadata.json').toPath(), new File(root, 'ordinary-metadata.json').toPath())
        git(root, 'init')
        git(root, 'add', '.')
        git(root, '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                '-c', 'commit.gpgsign=false', 'commit', '-m', 'Fixture')
        File inputs = new File(root, 'build/inputs')
        inputs.mkdirs()
        def copy = { Map reference ->
            File target = new File(inputs, reference.path)
            Files.copy(new File(fixture.bundle, reference.path).toPath(), target.toPath())
            CandidateFiles.reference(root, 'build/inputs/' + reference.path) + [filename: reference.path]
        }
        Map outputs = [schema: 2, target: fixture.target.id, version: '1.4.0+mc26.3-fabric',
                       processing: fixture.record.processing, mapping: copy(fixture.record.mapping), sbom: copy(fixture.record.sbom),
                       source_commit: git(root, 'rev-parse', 'HEAD'), source_dirty: false,
                       artifact: copy(fixture.record.artifact), sources: copy(fixture.record.sources),
                       source_inventory: copy(fixture.record.source_inventory),
                       drivers: [ordinary: copy(fixture.record.client_tests.drivers.ordinary)]]
        File outputsFile = new File(root, 'build/outputs.json')
        CandidateFiles.writeNew(outputsFile, outputs)
        def project = ProjectBuilder.builder().withProjectDir(root)
                .withGradleUserHomeDir(new File(directory, 'gradle-home')).build()
        assertEquals('', git(root, 'status', '--porcelain'), 'Fixture must have clean source')
        def task = project.tasks.create('bundleCandidate', BundleCandidate)
        task.sourceRoot.set(root)
        task.buildOutputs.set(outputsFile)
        task.contract.set(new File(fixture.bundle, 'contract.json'))
        task.runtimeLock.set(new File(fixture.bundle, 'runtime-lock.json'))
        task.dependencyLock.set(new File(fixture.bundle, 'dependency-lock.json'))
        task.releaseTag.set('v1.4.0')
        task.destination.set(new File(directory, 'assembled'))
        fixture + [task: task, outputsFile: outputsFile, outputs: outputs]
    }

    @Test void bundlesRecordedFilesAndPreservesCandidateSchema() {
        def fixture = setup()
        fixture.task.bundle()
        File output = fixture.task.destination.get().asFile
        def candidate = new CandidateManifest(new File(output, 'candidate.json'))
        assertEquals(fixture.outputs.source_commit, candidate.data.commit)
        assertEquals(fixture.record.artifact.sha256, candidate.records['26.3-fabric'].artifact.sha256)
        assertTrue(new File(output, 'SHA256SUMS').isFile())
        candidate.verifyPackages(fixture.root)
        assertThrows(Exception) { fixture.task.bundle() }
    }

    @Test void bundlesThroughGradleWhenItCreatesTheOutputDirectory() {
        def fixture = setup()
        File project = new File(directory, 'gradle-project')
        project.mkdirs()
        new File(project, 'settings.gradle').text = "rootProject.name = 'candidate-test'\n"
        new File(project, 'build.gradle').text = '''import cbbg.gradle.BundleCandidate
plugins { id 'cbbg.packaging' apply false }
tasks.register('assembleCandidate', BundleCandidate) {
''' + [sourceRoot: fixture.root, buildOutputs: fixture.outputsFile,
       contract: fixture.task.contract.get().asFile,
       runtimeLock: fixture.task.runtimeLock.get().asFile,
       dependencyLock: fixture.task.dependencyLock.get().asFile,
       destination: fixture.task.destination.get().asFile].collect { name, file ->
            '    ' + name + '.set(file(' + JsonOutput.toJson(file.absolutePath) + '))'
        }.join('\n') + "\n    releaseTag.set('v1.4.0')\n}\n"
        def runner = GradleRunner.create().withProjectDir(project).withPluginClasspath()
                .withArguments('assembleCandidate', '--stacktrace')
        runner.build()
        File manifest = new File(fixture.task.destination.get().asFile, 'candidate.json')
        assertEquals(fixture.outputs.source_commit, new CandidateManifest(manifest).data.commit)
        assertTrue(runner.buildAndFail().output.contains('Candidate destination already exists'))
        assertTrue(manifest.isFile())
    }

    @Test void dirtySourceAndStaleBuildIdentityAreRejected() {
        def fixture = setup()
        fixture.outputs.source_commit = 'b' * 40
        fixture.outputsFile.text = JsonOutput.toJson(fixture.outputs)
        assertThrows(Exception) { fixture.task.bundle() }
        assertFalse(fixture.task.destination.get().asFile.exists())
        new File(fixture.root, 'gradle.properties').append('changed=yes\n')
        assertThrows(Exception) { fixture.task.bundle() }
    }

    @Test void missingDriverAndDuplicateNamesAreRejected() {
        def fixture = setup()
        fixture.outputs.drivers = [:]
        fixture.outputsFile.text = JsonOutput.toJson(fixture.outputs)
        assertThrows(Exception) { fixture.task.bundle() }
        assertFalse(fixture.task.destination.get().asFile.exists())
    }

    @Test void invalidFilenamesHashesAndVersionsLeaveNoBundle() {
        List<Closure> edits = [
                { it.artifact.filename = '../outside.jar' },
                { it.artifact.filename = 'candidate.json' },
                { it.artifact.filename = 'SHA256SUMS' },
                { it.artifact.filename = 'provenance.jsonl' },
                { it.sources.filename = it.artifact.filename },
                { it.remove('mapping') },
                { it.mapping.sha256 = '0' * 64 },
                { it.processing.version = 'unknown' },
                { it.artifact.sha256 = '0' * 64 },
                { it.version = '2.0.0+mc26.3-fabric' },
                { it.source_dirty = true }]
        def fixture = setup()
        String original = fixture.outputsFile.text
        edits.each { edit ->
            Map outputs = CandidateFiles.parse(new StringReader(original))
            edit(outputs)
            fixture.outputsFile.text = JsonOutput.toJson(outputs)
            assertThrows(Exception) { fixture.task.bundle() }
            assertFalse(fixture.task.destination.get().asFile.exists())
        }
    }

    @Test void packageFailureRemovesOnlyNewDestination() {
        def fixture = setup()
        File artifact = CandidateFiles.checked(fixture.root, fixture.outputs.artifact)
        artifact.text = 'invalid jar'
        fixture.outputs.artifact.sha256 = CandidateFiles.sha256(artifact)
        fixture.outputsFile.text = JsonOutput.toJson(fixture.outputs)
        assertThrows(Exception) { fixture.task.bundle() }
        assertFalse(fixture.task.destination.get().asFile.exists())
        assertTrue(artifact.isFile())
        File destination = fixture.task.destination.get().asFile
        destination.mkdirs()
        File existing = new File(destination, 'keep.txt')
        existing.text = 'keep'
        assertThrows(Exception) { fixture.task.bundle() }
        assertEquals('keep', existing.text)
    }

    private void conventionalInputs(Map fixture, List<String> ids) {
        def task = fixture.task
        File outputs = new File(fixture.root, 'build/targets/26.3-fabric/candidate-build-outputs.json')
        outputs.parentFile.mkdirs()
        outputs.text = fixture.outputsFile.text
        File locks = new File(fixture.root, 'runtime-locks')
        locks.mkdirs()
        ids.each { id ->
            Map contract = CandidateFiles.read(task.contract.get().asFile) + [target: id]
            contract.ordinaryMetadata = id + '-ordinary-metadata.json'
            new File(fixture.root, contract.ordinaryMetadata).text = new File(fixture.root, 'ordinary-metadata.json').text
            new File(locks, id + '-scenarios.json').text = JsonOutput.toJson(contract)
            new File(locks, id + '-linux-x86_64.json').text = task.runtimeLock.get().asFile.text
            new File(locks, id + '-mods.json').text = task.dependencyLock.get().asFile.text
        }
        git(fixture.root, 'add', '.')
        git(fixture.root, '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                '-c', 'commit.gpgsign=false', 'commit', '-m', 'Runtime inputs')
        fixture.outputs.source_commit = git(fixture.root, 'rev-parse', 'HEAD')
        outputs.text = JsonOutput.toJson(fixture.outputs)
        [task.buildOutputs, task.contract, task.runtimeLock, task.dependencyLock].each { it.unset() }
        task.selectedTargets.set(ids)
    }

    @Test void explicitSelectionBundlesOwnerAndAliasWithIndependentRuntimeInputs() {
        def fixture = setup()
        fixture.catalog.targets.add(fixture.target + [id: '26.3-quilt', loader: 'quilt', artifactOf: '26.3-fabric'])
        new File(fixture.root, 'targets.json').text = JsonOutput.toJson(fixture.catalog)
        conventionalInputs(fixture, ['26.3-fabric', '26.3-quilt'])
        fixture.task.bundle()
        def candidate = new CandidateManifest(new File(fixture.task.destination.get().asFile, 'candidate.json'))
        assertEquals(['26.3-fabric', '26.3-quilt'] as Set, candidate.verifyPackages(fixture.root).keySet())
        assertEquals(candidate.records['26.3-fabric'].artifact, candidate.records['26.3-quilt'].artifact)
        assertNotEquals(candidate.records['26.3-fabric'].client_tests.contract.path,
                candidate.records['26.3-quilt'].client_tests.contract.path)
        assertEquals(['26.3-fabric', '26.3-quilt'], candidate.data.selected_targets)
    }

    @Test void explicitSelectionRejectsDuplicatesAndSingleInputOverrides() {
        def fixture = setup()
        conventionalInputs(fixture, ['26.3-fabric'])
        fixture.task.selectedTargets.set(['26.3-fabric', '26.3-fabric'])
        assertThrows(Exception) { fixture.task.bundle() }
        fixture.task.selectedTargets.set(['26.3-fabric'])
        fixture.task.buildOutputs.set(fixture.outputsFile)
        assertThrows(Exception) { fixture.task.bundle() }
        assertFalse(fixture.task.destination.get().asFile.exists())
    }

    @Test void explicitSelectionBundlesDistinctArtifactsAndRejectsMixedBuildIdentity() {
        def fixture = setup()
        def second = CandidateFixture.create(new File(directory, 'second'), false, true, '26.2')
        fixture.catalog.targets.add(second.target)
        new File(fixture.root, 'targets.json').text = JsonOutput.toJson(fixture.catalog)
        conventionalInputs(fixture, ['26.3-fabric', '26.2-fabric'])
        File inputs = new File(fixture.root, 'build/second-inputs')
        inputs.mkdirs()
        def copy = { Map reference ->
            File input = new File(inputs, reference.path)
            Files.copy(new File(second.bundle, reference.path).toPath(), input.toPath())
            CandidateFiles.reference(fixture.root, 'build/second-inputs/' + reference.path) + [filename: reference.path]
        }
        Map outputs = [schema: 2, target: second.target.id, version: '1.4.0+mc26.2-fabric',
                       processing: second.record.processing, source_commit: fixture.outputs.source_commit, source_dirty: false,
                       drivers: [ordinary: copy(second.record.client_tests.drivers.ordinary)]]
        ['artifact', 'sources', 'source_inventory', 'mapping', 'sbom'].each { outputs[it] = copy(second.record[it]) }
        File outputsFile = new File(fixture.root, 'build/targets/26.2-fabric/candidate-build-outputs.json')
        outputsFile.parentFile.mkdirs()
        for (Map invalid : [outputs + [source_commit: 'b' * 40], outputs + [version: '2.0.0+mc26.2-fabric']]) {
            outputsFile.text = JsonOutput.toJson(invalid)
            assertThrows(Exception) { fixture.task.bundle() }
            assertFalse(fixture.task.destination.get().asFile.exists())
        }
        outputsFile.text = JsonOutput.toJson(outputs)
        fixture.task.bundle()
        def candidate = new CandidateManifest(new File(fixture.task.destination.get().asFile, 'candidate.json'))
        assertEquals(['26.3-fabric', '26.2-fabric'] as Set, candidate.verifyPackages(fixture.root).keySet())
        assertNotEquals(candidate.records['26.3-fabric'].artifact, candidate.records['26.2-fabric'].artifact)
    }
}
