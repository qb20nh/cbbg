package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.testfixtures.ProjectBuilder
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
        def fixture = CandidateFixture.create(directory, false)
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
                       processing: fixture.record.processing, mapping: copy(fixture.record.mapping),
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
}
