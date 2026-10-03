package cbbg.gradle

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

import java.security.MessageDigest

import static org.junit.jupiter.api.Assertions.*

class CandidateBuildOutputsTest {
    @TempDir File directory

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    void recordsPackagedDriversAndCurrentSourceState(boolean processed) {
        File profile = new File(directory, 'build-config/example')
        profile.mkdirs()
        new File(profile, 'settings.gradle').text = "rootProject.name = 'example'\n"
        new File(profile, 'build.gradle').text = '''
plugins { id 'java'; id 'cbbg.packaging' }
java { withSourcesJar() }
version = '1.4.1+mc26.2'
ext.candidateTargetId = '26.2-fabric'
releaseOptimization {
    outputJar.set(layout.buildDirectory.file('libs/example.jar'))
    mappingFile.set(layout.buildDirectory.file('mapping/example.map'))
}
tasks.register('ciCheck') {
    doLast {
        for (String path : ['libs/example.jar', 'mapping/example.map', 'libs/example-1.4.1+mc26.2-sources.jar',
                            'libs/example-1.4.1+mc26.2.cdx.json', 'source-inventory.json']) {
            def output = new File(layout.buildDirectory.get().asFile, path)
            output.parentFile.mkdirs()
            output.text = path
        }
    }
}
tasks.register('sourceInventory')
tasks.register('parityDriverJar', Jar) { archiveClassifier = 'parity-driver' }
tasks.register('earlyStartupDriverJar', Jar) { archiveClassifier = 'early-startup-driver' }
PROCESSED
apply from: file('../candidate-build-outputs.gradle')
'''.replace('PROCESSED', processed ? '''
tasks.register('processedDriverJar', Jar) { archiveClassifier = 'processed-driver' }
tasks.register('processedEarlyStartupDriverJar', Jar) { archiveClassifier = 'processed-early-startup-driver' }
''' : '')
        new File(directory, 'build-config/candidate-build-outputs.gradle').text =
                new File(System.getProperty('cbbg.repository'), 'build-config/candidate-build-outputs.gradle').text
        new File(directory, '.gitignore').text = '**/build/\n**/.gradle/\n'
        git('init')
        git('add', '.')
        git('-c', 'commit.gpgsign=false', '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                'commit', '-m', 'Fixture')
        def run = {
            GradleRunner.create().withProjectDir(profile).withPluginClasspath()
                    .withArguments('candidateBuildOutputs', '--offline', '--stacktrace').build()
            new JsonSlurper().parse(new File(profile, 'build/candidate-build-outputs.json'))
        }
        def output = run()
        assertEquals('26.2-fabric', output.target)
        assertEquals('1.4.1+mc26.2', output.version)
        assertEquals(git('rev-parse', 'HEAD'), output.source_commit)
        assertFalse(output.source_dirty)
        assertEquals(['ordinary', 'early-startup'].toSet(), output.drivers.keySet())
        output.drivers.values().each { driver ->
            assertEquals(processed, driver.filename.contains('processed'))
        }
        ([output.artifact, output.mapping, output.sbom, output.sources, output.source_inventory] +
                output.drivers.values()).each { reference ->
            File file = new File(directory, reference.path)
            assertTrue(file.isFile(), reference.path as String)
            assertEquals(file.name, reference.filename)
            assertEquals(MessageDigest.getInstance('SHA-256').digest(file.bytes).encodeHex().toString(),
                    reference.sha256)
        }
        new File(directory, 'docs').mkdirs()
        new File(directory, 'docs/local.md').text = 'Local notes'
        assertFalse(run().source_dirty)
        new File(directory, 'source.txt').text = 'Pending source change'
        assertTrue(run().source_dirty)
    }

    private String git(String... arguments) {
        Process process = new ProcessBuilder(['git'] + arguments.toList()).directory(directory)
                .redirectErrorStream(true).start()
        String output = process.inputStream.text.trim()
        assertEquals(0, process.waitFor(), output)
        output
    }
}
