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
    @ValueSource(strings = ['ordinary', 'all-processed', 'selected-processed', 'library'])
    void recordsPackagedDriversAndCurrentSourceState(String selection) {
        boolean library = selection == 'library'
        boolean processed = selection in ['all-processed', 'selected-processed']
        File profile = new File(directory, 'build-config/example')
        profile.mkdirs()
        new File(profile, 'settings.gradle').text = "rootProject.name = 'example'\n"
        new File(profile, 'build.gradle').text = '''
plugins { id 'java'; id 'cbbg.packaging' }
java { withSourcesJar() }
version = 'VERSION'
ext.candidateProduct = 'PRODUCT'
ext.utilitiesArchive = rootProject.file('../../libraries/utilities/build/libs/cbbg-utilities-1.0.0.jar')
ext.utilitiesSourcesArchive = rootProject.file('../../libraries/utilities/build/libs/cbbg-utilities-1.0.0-sources.jar')
ext.libraryArchive = rootProject.file('../../build/libraries/26.2-fabric/libs/cbbg-lib-1.0.0+mc26.2-fabric.jar')
ext.librarySourcesArchive = rootProject.file('../../build/libraries/26.2-fabric/libs/cbbg-lib-1.0.0+mc26.2-fabric-sources.jar')
ext.libraryRelease = 'lib/v1.0.0'
ext.libraryPackageVersion = '1.0.0+mc26.2-fabric'
tasks.register(candidateProduct == 'lib' ? 'prepareLibraryOutputs' : 'prepareBundledLibrary') {
    doLast {
        [utilitiesArchive, utilitiesSourcesArchive, libraryArchive, librarySourcesArchive].each { output ->
            output.parentFile.mkdirs()
            output.text = output.name
        }
    }
}
ext.candidateTargetId = '26.2-fabric'
releaseOptimization {
    outputJar.set(layout.buildDirectory.file('libs/example.jar'))
    mappingFile.set(layout.buildDirectory.file('mapping/example.map'))
}
def fixtureOutputs = {
        for (String path : ['libs/example.jar', 'mapping/example.map', "libs/example-${version}-sources.jar",
                            "libs/example-${version}.cdx.json", 'source-inventory.json']) {
            def output = new File(layout.buildDirectory.get().asFile, path)
            output.parentFile.mkdirs()
            output.text = path
        }
}
if (candidateProduct == 'lib') tasks.named('check') { doLast fixtureOutputs }
else tasks.register('ciCheck') { doLast fixtureOutputs }
tasks.register('sourceInventory')
tasks.register('parityDriverJar', Jar) { archiveClassifier = 'parity-driver' }
EARLY_DRIVER
PROCESSED
apply from: file('../candidate-build-outputs.gradle')
'''.replace('VERSION', library ? '1.0.0+mc26.2-fabric' : '1.4.1+mc26.2')
                .replace('PRODUCT', library ? 'lib' : 'cbbg')
                .replace('EARLY_DRIVER', library ? '' : "tasks.register('earlyStartupDriverJar', Jar) { archiveClassifier = 'early-startup-driver' }")
                .replace('PROCESSED', processed ? '''
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
                    'build/' + (library ? 'libraries' : 'targets') + '/26.2-fabric/candidate-build-outputs.json'))
        }
        def output = run()
        assertFalse(new File(profile, 'build/candidate-build-outputs.json').exists())
        assertEquals('26.2-fabric', output.target)
        assertEquals(library ? '1.0.0+mc26.2-fabric' : '1.4.1+mc26.2', output.version)
        assertEquals(library ? 'lib' : 'cbbg', output.product)
        if (library) {
            assertFalse(output.containsKey('library'))
            assertFalse(output.containsKey('library_sources'))
        } else {
            assertEquals('lib/v1.0.0', output.library_release)
            assertTrue(output.library.path.startsWith('build/libraries/26.2-fabric/'))
        }
        assertEquals('cbbg-utilities-1.0.0.jar', output.utilities.filename)
        assertEquals('cbbg-utilities-1.0.0-sources.jar', output.utilities_sources.filename)
        assertEquals(git('rev-parse', 'HEAD'), output.source_commit)
        assertFalse(output.source_dirty)
        Set expected = (library ? ['ordinary'] : ['ordinary', 'early-startup']) as Set
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
        ([output.artifact, output.mapping, output.sbom, output.sources, output.utilities, output.utilities_sources, output.source_inventory] +
                (library ? [] : [output.library, output.library_sources]) + output.drivers.values()).each { reference ->
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
