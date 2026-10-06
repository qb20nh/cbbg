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

    private void familyFixture() {
        fixture()
        def owner = [id: '26.1-fabric', minecraft: '26.1', loader: 'fabric', java: 25,
                     renderer: 'blaze-texture-format', backends: ['opengl'],
                     implemented: false, buildProfile: 'fixture',
                     compatibleMinecraft: ['26.1.1', '26.1.2'], minecraftDependency: '>=26.1 <26.2']
        def aliases = ['26.1.1', '26.1.2'].collect { minecraft ->
            [id: minecraft + '-fabric', minecraft: minecraft, loader: 'fabric', java: 25,
             renderer: 'blaze-texture-format', backends: ['opengl'], implemented: false,
             buildProfile: 'fixture', artifactOf: owner.id]
        }
        new File(directory, 'targets.json').text = JsonOutput.toJson(
                [schema: 1, ciTargets: [owner.id], targets: [owner] + aliases])
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

    @Test void forwardsBothBuildCacheChoices() {
        fixture()
        File arguments = new File(directory, 'build/arguments.txt')
        runner('build', '--build-cache').build()
        assertTrue(arguments.readLines().contains('--build-cache'))
        assertFalse(arguments.readLines().contains('--no-build-cache'))
        arguments.delete()
        runner('build', '--no-build-cache').build()
        assertTrue(arguments.readLines().contains('--no-build-cache'))
        assertFalse(arguments.readLines().contains('--build-cache'))
    }

    @Test void checksAndDevelopmentArtifactsUseOneChildBuildPerOwner() {
        fixture()
        runner(':ciCheck', ':dev', '-Ptargets=26.3-fabric,26.3-quilt').build()
        List<String> args = new File(directory, 'build/arguments.txt').readLines()
        assertEquals(1, args.count('-p'))
        assertEquals(['ciCheck', 'dev'], args.findAll { it in ['ciCheck', 'dev'] })
        assertTrue(runner('ciCheck', 'dev', '-Pcompat=fail').buildAndFail().output.contains('exit value 7'))
    }

    @Test void developmentArtifactsAloneKeepTheirOwnBuild() {
        fixture()
        runner('dev').build()
        List<String> args = new File(directory, 'build/arguments.txt').readLines()
        assertEquals(1, args.count('-p'))
        assertTrue(args.contains('dev'))
        assertFalse(args.contains('ciCheck'))
    }

    @Test void developmentArtifactsRequestedFirstKeepTheirOrder() {
        fixture()
        runner('dev', 'ciCheck').build()
        List<String> args = new File(directory, 'build/arguments.txt').readLines()
        assertEquals(2, args.count('-p'))
        assertEquals(['dev', 'ciCheck'], args.findAll { it in ['ciCheck', 'dev'] })
    }

    @Test void intermediateTasksKeepDevelopmentBuildSeparate() {
        fixture()
        File wrapper = new File(directory, 'gradlew')
        wrapper.text = wrapper.text.replace('build/arguments.txt', 'arguments.txt')
        runner('ciCheck', 'clean', 'dev').build()
        List<String> args = new File(directory, 'arguments.txt').readLines()
        assertEquals(2, args.count('-p'))
        assertEquals(['ciCheck', 'dev'], args.findAll { it in ['ciCheck', 'dev'] })
    }

    @Test void continueRunsDevelopmentBuildAfterFailedCheck() {
        fixture()
        runner('ciCheck', 'dev', '--continue', '-Pcompat=fail').buildAndFail()
        List<String> args = new File(directory, 'build/arguments.txt').readLines()
        assertEquals(2, args.count('-p'))
        assertEquals(['ciCheck', 'dev'], args.findAll { it in ['ciCheck', 'dev'] })
    }

    @Test void excludedTasksKeepDevelopmentBuildSeparate() {
        fixture()
        runner('ciCheck', 'dev', '-x', 'ciCheck').build()
        File arguments = new File(directory, 'build/arguments.txt')
        assertEquals(['dev'], arguments.readLines().findAll { it in ['ciCheck', 'dev'] })
        arguments.delete()
        runner('ciCheck', 'dev', '-x', 'dev').build()
        assertEquals(['ciCheck'], arguments.readLines().findAll { it in ['ciCheck', 'dev'] })
    }

    @Test void minimumSearchUpdatesThenVerifiesInFreshBuilds() {
        fixture()
        def env = new HashMap(System.getenv())
        env.remove('CI')
        env.remove('GITHUB_ACTIONS')
        runner('determineFabricMinimums', '-Ptarget=26.3-fabric', '-PcompatibilityRuntime=/local/runtime')
                .withEnvironment(env).build()
        List args = new File(directory, 'build/arguments.txt').readLines()
        assertTrue(args.indexOf('updateFabricMinimums') < args.indexOf('verifyFabricCompatibility'))
        assertEquals(2, args.count('-PcompatibilityRuntime=/local/runtime'))
        assertTrue(args.contains('-PverifyDeclaredMinimums=false'))
        assertTrue(args.contains('-PverifyDeclaredMinimums=true'))
    }

    @Test void ciCannotRunTheMinimumSearch() {
        fixture()
        ['CI', 'GITHUB_ACTIONS'].each { name ->
            def env = new HashMap(System.getenv())
            env.remove('CI')
            env.remove('GITHUB_ACTIONS')
            env[name] = 'true'
            assertTrue(runner('determineFabricMinimums').withEnvironment(env).buildAndFail()
                    .output.contains('Minecraft runtime tests are local-only'))
        }
        assertFalse(new File(directory, 'build/arguments.txt').exists())
    }

    @Test void upstreamFabricMinimumSearchUsesTheUpstreamBuild() {
        fixture()
        def target = [id: '26.2-fabric', minecraft: '26.2', loader: 'fabric', java: 25,
                      renderer: 'blaze-gpu-format', backends: ['opengl', 'vulkan'],
                      implemented: false, buildProfile: 'fabric-upstream']
        new File(directory, 'targets.json').text = JsonOutput.toJson(
                [schema: 1, ciTargets: [target.id], targets: [target]])
        def profile = new File(directory, 'build-config/fabric-upstream')
        profile.mkdirs()
        new File(profile, 'build.gradle').text = '// Upstream build fixture\n'
        def env = new HashMap(System.getenv())
        env.remove('CI')
        env.remove('GITHUB_ACTIONS')
        runner('determineFabricMinimums', '-Ptarget=26.2-fabric', '-PcompatibilityRuntime=/local/runtime')
                .withEnvironment(env).build()
        List args = new File(directory, 'build/arguments.txt').readLines()
        assertEquals(['updateFabricMinimums', 'verifyFabricCompatibility'],
                args.findAll { it in ['updateFabricMinimums', 'verifyFabricCompatibility'] })
        assertEquals(2, args.count('-p'))
        assertEquals(2, args.count(profile.absolutePath))
        assertEquals(2, args.count('-PcompatibilityRuntime=/local/runtime'))
    }

    @Test void sharedFabricMinimumSearchRunsOnceThenUpdatesOwnerAndVerifiesEachRuntime() {
        familyFixture()
        def env = new HashMap(System.getenv())
        env.remove('CI')
        env.remove('GITHUB_ACTIONS')
        runner('determineFabricMinimums', '-Ptarget=26.1.2-fabric', '-PcompatibilityRuntime=/local/runtime')
                .withEnvironment(env).build()
        List args = new File(directory, 'build/arguments.txt').readLines()
        assertEquals(['verifyFabricCompatibility', 'updateFabricMinimums',
                      'verifyFabricCompatibility', 'verifyFabricCompatibility',
                      'verifyFabricCompatibility'],
                args.findAll { it in ['verifyFabricCompatibility', 'updateFabricMinimums'] })
        assertEquals(['-Ptarget=26.1-fabric', '-Ptarget=26.1-fabric', '-Ptarget=26.1-fabric',
                      '-Ptarget=26.1.1-fabric', '-Ptarget=26.1.2-fabric'],
                args.findAll { it.startsWith('-Ptarget=') })
        assertEquals((['-PverifyDeclaredMinimums=false'] * 2) +
                     (['-PverifyDeclaredMinimums=true'] * 3),
                args.findAll { it.startsWith('-PverifyDeclaredMinimums=') })
        assertEquals(5, args.count('-PcompatibilityRuntime=/local/runtime'))
    }

    @Test void minimumSearchRequiresASingleFabricTarget() {
        fixture()
        assertTrue(runner('determineFabricMinimums', '-Ptargets=26.3-fabric,26.3-quilt').buildAndFail()
                .output.contains('determineFabricMinimums requires one supported Fabric target'))
        assertFalse(new File(directory, 'build/arguments.txt').exists())
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
        String failure = runner('build', '-Pcompat=fail').buildAndFail().output
        assertTrue(failure.contains('exit value 7'), failure)
        assertTrue(failure.contains('Target 26.3-fabric failed (primary task build'), failure)
        assertTrue(failure.contains('profile fixture'), failure)
        assertTrue(failure.contains(new File(directory, 'build-config/fixture').toString()), failure)
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
