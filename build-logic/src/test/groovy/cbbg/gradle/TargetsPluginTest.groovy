package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import static org.junit.jupiter.api.Assertions.*

class TargetsPluginTest {
    @TempDir File directory

    private void fixture() {
        new File(directory, 'settings.gradle').text = "rootProject.name = 'dispatch-test'\n"
        new File(directory, 'build.gradle').text = "plugins { id 'cbbg.targets' }\n"
        def fabric = [id: '26.3-fabric', minecraft: '26.3', loader: 'fabric', java: 25,
                      renderer: 'renderpearl', backends: ['opengl', 'vulkan'],
                      implemented: false, buildProfile: 'fixture']
        def quilt = fabric + [id: '26.3-quilt', loader: 'quilt', artifactOf: '26.3-fabric']
        new File(directory, 'targets.json').text = JsonOutput.toJson(
                [schema: 1, ciTargets: ['26.3-fabric'], targets: [fabric, quilt]])
        def profile = new File(directory, 'build-config/fixture')
        profile.mkdirs()
        new File(profile, 'build.gradle').text = '// External build fixture\n'
        def wrapper = new File(directory, 'gradlew')
        wrapper.text = '''#!/bin/sh
mkdir -p build
printf '%s\\n' "$@" >> build/arguments.txt
printf '%s\\n' "$JAVA_HOME" > build/java-home.txt
case "$*" in *-Pcompat=fail*) exit 7;; esac
'''
        assertTrue(wrapper.setExecutable(true))
    }

    private GradleRunner runner(String... arguments) {
        GradleRunner.create().withProjectDir(directory).withPluginClasspath()
                .withArguments((arguments as List) + ['--stacktrace'])
    }

    @Test void defaultsBuildWithoutPythonOrImplementationFlag() {
        fixture()
        def result = runner('build', '--offline', '-Pbackend=vulkan').build()
        assertTrue(result.output.contains('BUILD SUCCESSFUL'))
        def args = new File(directory, 'build/arguments.txt').readLines()
        assertTrue(args.contains('build'))
        assertTrue(args.contains('--offline'))
        assertTrue(args.contains('-Pbackend=vulkan'))
        assertTrue(args.contains('-Ptarget=26.3-fabric'))
        assertFalse(args.any { it.contains('python') })
        assertTrue(new File(new File(directory, 'build/java-home.txt').text.trim(), 'bin/java').isFile())
    }

    @Test void sharedArtifactBuildsOnce() {
        fixture()
        runner('build', '-Ptargets=26.3-fabric,26.3-quilt', '--parallel').build()
        assertEquals(1, new File(directory, 'build/arguments.txt').readLines().count('build'))
    }

    @Test void clientRequiresSingleRuntimeAndCiRejectsLaunch() {
        fixture()
        assertTrue(runner('runClient', '-Ptargets=26.3-fabric,26.3-quilt')
                .buildAndFail().output.contains('runClient requires exactly one target'))
        def env = new HashMap(System.getenv())
        env.CI = 'true'
        assertTrue(runner('runClient').withEnvironment(env)
                .buildAndFail().output.contains('Minecraft runtime tests are local-only'))
        assertFalse(new File(directory, 'build/arguments.txt').exists())
    }

    @Test void selectionErrorsAndChildFailuresFailRootBuild() {
        fixture()
        assertTrue(runner('build', '-Ptarget=26.3-fabric', '-Ptargets=26.3-quilt')
                .buildAndFail().output.contains('Use either -Ptarget or -Ptargets'))
        runner('build', '-Ptarget=missing').buildAndFail()
        assertFalse(new File(directory, 'build/arguments.txt').exists())
        assertTrue(runner('build', '-Pcompat=fail').buildAndFail().output.contains('exit value 7'))
    }

    @Test void profileWrapperOverridesRootWrapper() {
        fixture()
        def wrapper = new File(directory, 'build-config/fixture/gradlew')
        wrapper.text = '#!/bin/sh\nexit 12\n'
        assertTrue(wrapper.setExecutable(true))
        assertTrue(runner('build').buildAndFail().output.contains('exit value 12'))
    }

    @Test void matrixWritesJsonAndCanRequireImplementedTargets() {
        fixture()
        runner('targetMatrix', '-Poutput=matrix.json').build()
        Map matrix = CandidateFiles.read(new File(directory, 'matrix.json'))
        assertEquals(['26.3-fabric'], matrix.include*.id)
        assertFalse(new File(directory, 'build/arguments.txt').exists())
        assertTrue(runner('targetMatrix', '-PrequireImplemented=true').buildAndFail().output
                .contains('not implemented'))
    }

    @Test void windowsUsesBatchWrapperFromSelectedProfile() {
        fixture()
        File profile = new File(directory, 'build-config/fixture')
        File rootWrapper = new File(directory, 'gradlew.bat')
        rootWrapper.text = '@echo off\r\n'
        assertEquals(['cmd', '/d', '/c', rootWrapper.absolutePath],
                TargetBuild.wrapperCommand(directory, profile, true))
        File profileWrapper = new File(profile, 'gradlew.bat')
        profileWrapper.text = '@echo off\r\n'
        assertEquals(['cmd', '/d', '/c', profileWrapper.absolutePath],
                TargetBuild.wrapperCommand(directory, profile, true))
    }
}
