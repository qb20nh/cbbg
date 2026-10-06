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
    @ValueSource(strings = ['ordinary', 'all-processed', 'selected-processed'])
    void recordsPackagedDriversAndCurrentSourceState(String selection) {
        boolean processed = selection != 'ordinary'
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
sourceSets.create('processedGametest')
SELECTED_SOURCES
apply from: file('../fabric-startup-tests.gradle')
'''.replace('SELECTED_SOURCES', selection == 'selected-processed' ? '''
def mapped = tasks.register('mapProcessedGametestApiImports', Sync) {
    from('src/processedGametest/java')
    into(layout.buildDirectory.dir('generated/processedGametest-api-imports'))
}
sourceSets.processedGametest.java.setSrcDirs([mapped])
''' : '') : '')
        if (processed) {
            File resources = new File(profile, 'src/processedGametest/resources')
            resources.mkdirs()
            new File(resources, 'fabric.mod.json').text = '{"entrypoints":{},"mixins":[]}'
            List classes = selection == 'selected-processed' ?
                    ['ReleaseGenerationGameTest', 'ReleaseNotificationsGameTest'] :
                    ['ReleaseEarlyStartupGameTest', 'ReleaseStartupPreLaunch',
                     'ReleaseGenerationGameTest', 'ReleaseMaximumNoiseCacheGameTest',
                     'ReleaseShutdownGameTest', 'ReleaseGeneratingShutdownGameTest',
                     'ReleaseIrisRestartGameTest', 'ReleaseAllocationGameTest',
                     'ReleaseWorldPixelsGameTest', 'ReleaseTransparencyGameTest',
                     'ReleaseRenderScaleGameTest', 'ReleaseShaderFailureGameTest',
                     'ReleaseDebugOverlayGameTest', 'ReleaseNotificationsGameTest']
            File java = new File(profile, 'src/processedGametest/java/com/qb20nh/cbbg/gametest')
            java.mkdirs()
            classes.each { name ->
                new File(java, name + '.java').text =
                        "package com.qb20nh.cbbg.gametest; public class ${name} {}\n"
            }
        }
        new File(directory, 'build-config/candidate-build-outputs.gradle').text =
                new File(System.getProperty('cbbg.repository'), 'build-config/candidate-build-outputs.gradle').text
        new File(directory, 'build-config/fabric-startup-tests.gradle').text =
                new File(System.getProperty('cbbg.repository'), 'build-config/fabric-startup-tests.gradle').text
        new File(directory, '.gitignore').text = '**/build/\n**/.gradle/\n'
        git('init')
        git('add', '.')
        git('-c', 'commit.gpgsign=false', '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                'commit', '-m', 'Fixture')
        def run = {
            GradleRunner.create().withProjectDir(profile).withPluginClasspath()
                    .withArguments('candidateBuildOutputs', '--offline', '--stacktrace').build()
            new JsonSlurper().parse(new File(directory,
                    'build/targets/26.2-fabric/candidate-build-outputs.json'))
        }
        def output = run()
        assertFalse(new File(profile, 'build/candidate-build-outputs.json').exists())
        assertEquals('26.2-fabric', output.target)
        assertEquals('1.4.1+mc26.2', output.version)
        assertEquals(git('rev-parse', 'HEAD'), output.source_commit)
        assertFalse(output.source_dirty)
        Set expected = ['ordinary', 'early-startup'] as Set
        if (selection == 'all-processed') {
            expected.addAll(['startup-cold', 'startup-warm', 'startup-damaged', 'startup-seed-mismatch',
                             'generation', 'maximum-noise-cache', 'shutdown', 'generating-shutdown',
                             'iris-restart', 'allocation', 'world-pixels', 'shader-failure',
                             'debug-overlay', 'notifications', 'transparency', 'render-scale'])
        }
        if (selection == 'selected-processed') expected.addAll(['generation', 'notifications'])
        assertEquals(expected, output.drivers.keySet())
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
