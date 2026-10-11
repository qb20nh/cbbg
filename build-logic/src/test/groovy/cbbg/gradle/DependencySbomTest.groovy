package cbbg.gradle

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

import java.util.zip.ZipOutputStream

import static org.junit.jupiter.api.Assertions.*

class DependencySbomTest {
    @TempDir File directory

    @ParameterizedTest
    @ValueSource(strings = ['net.fabricmc.fabric-loom', 'net.fabricmc.fabric-loom-remap'])
    void inventoryCutsGameOriginsAndKeepsOwnedPathsAndResolutionRules(String loomId) {
        prepare(loomId)
        def result = inventory().build()
        assertTrue(result.output.contains('BUILD SUCCESSFUL'))
        Set names = components()
        assertFalse(names.contains('game-only'))
        assertTrue(names.containsAll(['own', 'shared', 'transitive-shared', 'constrained', 'private-parser', 'analyzer',
                'build-tool', 'loader', 'development']))
        def shared = new JsonSlurper().parse(new File(directory, 'bom.json')).components
                .find { it.name == 'shared' }
        assertEquals('2', shared.version)
        def constrained = new JsonSlurper().parse(new File(directory, 'bom.json')).components
                .find { it.name == 'constrained' }
        assertEquals('2', constrained.version)
        // Ordinary builds keep the original graph and still resolve game libraries.
        assertTrue(runner('verifyGameGraph').build().output.contains('GAME_GRAPH_PRESERVED'))
    }

    @Test void plainProjectsKeepTheirDependencies() {
        prepare(false)
        inventory().build()
        assertTrue(components().contains('game-only'))
    }

    @ParameterizedTest
    @ValueSource(strings = ['net.fabricmc.fabric-loom', 'net.fabricmc.fabric-loom-remap'])
    void graphSubmissionCutsGameOrigins(String loomId) {
        prepare(loomId)
        def result = runner('--init-script', initScript(),
                ':ForceDependencyResolutionPlugin_resolveAllDependencies').build()
        assertTrue(result.output.contains('GRAPH_ORIGINS_FILTERED'))
    }

    @Test void refusesOtherTasksBeforeChangingTheGraph() {
        prepare(true)
        def result = runner('--init-script', initScript(), 'verifyGameGraph').buildAndFail()
        assertTrue(result.output.contains('requires an inventory task'))
    }

    private void prepare(boolean loom) {
        prepare(loom ? 'net.fabricmc.fabric-loom' : null)
    }

    private void prepare(String loomId) {
        write('settings.gradle', "rootProject.name = 'inventory-fixture'\n")
        for (String name : ['game-only', 'private-parser', 'analyzer', 'build-tool',
                'loader', 'development']) {
            module(name, '1', '')
        }
        module('shared', '1', '')
        module('shared', '2', '')
        module('shared', '3', '')
        module('transitive-shared', '1', '')
        module('constrained', '1', '')
        module('constrained', '2', '')
        module('own', '1', '''<dependencies><dependency><groupId>fixture</groupId>
<artifactId>transitive-shared</artifactId><version>1</version></dependency></dependencies>''')
        if (loomId != null) {
            write('buildSrc/build.gradle', '''
plugins { id 'java-gradle-plugin' }
gradlePlugin { plugins { fixture { id = 'LOOM_ID'; implementationClass = 'FixtureLoom' } } }
'''.replace('LOOM_ID', loomId))
            write('buildSrc/src/main/java/FixtureLoom.java', '''
import org.gradle.api.Plugin;
import org.gradle.api.Project;
public class FixtureLoom implements Plugin<Project> { public void apply(Project project) {} }
''')
        }
        write('build.gradle', '''
buildscript {
    repositories { maven { url = uri('repo') } }
    dependencies { classpath 'fixture:build-tool:1' }
}
apply plugin: 'java'
''' + (loomId != null ? "apply plugin: '${loomId}'\n" : '') + '''
repositories { maven { url = uri('repo') } }
configurations {
    minecraftClientLibraries
    minecraftLibraries { extendsFrom minecraftClientLibraries }
    loaderLibraries
    loomDevelopmentDependencies
    minecraftNamedCompile { extendsFrom minecraftLibraries, loaderLibraries }
    minecraftNamedRuntime { extendsFrom minecraftLibraries, loomDevelopmentDependencies }
    compileClasspath { extendsFrom minecraftNamedCompile }
    runtimeClasspath { extendsFrom minecraftNamedRuntime }
    privateGsonInput
    errorprone
}
dependencies {
    minecraftClientLibraries 'fixture:game-only:1'
    minecraftClientLibraries 'fixture:shared:1'
    minecraftClientLibraries 'fixture:transitive-shared:1'
    implementation 'fixture:own:1'
    implementation 'fixture:shared:1'
    implementation 'fixture:constrained:1'
    constraints {
        implementation('fixture:shared:3')
        implementation('fixture:constrained:2')
    }
    loaderLibraries 'fixture:loader:1'
    loomDevelopmentDependencies 'fixture:development:1'
    privateGsonInput 'fixture:private-parser:1'
    errorprone 'fixture:analyzer:1'
}
configurations.compileClasspath.resolutionStrategy.force 'fixture:shared:2'
tasks.register('verifyGameGraph') {
    doLast {
        assert configurations.compileClasspath.files.any { it.name == 'game-only-1.jar' }
        println 'GAME_GRAPH_PRESERVED'
    }
}
tasks.register('ForceDependencyResolutionPlugin_resolveAllDependencies') {
    doLast {
        def files = configurations.runtimeClasspath.files*.name as Set
        assert !files.contains('game-only-1.jar')
        assert files.containsAll(['own-1.jar', 'shared-3.jar', 'development-1.jar'])
        println 'GRAPH_ORIGINS_FILTERED'
    }
}
''')
    }

    private void module(String name, String version, String dependencies) {
        String base = "repo/fixture/${name}/${version}/${name}-${version}"
        write(base + '.pom', """<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
<artifactId>${name}</artifactId><version>${version}</version>${dependencies}</project>""")
        new ZipOutputStream(new FileOutputStream(new File(directory, base + '.jar'))).close()
    }

    private Set components() {
        new JsonSlurper().parse(new File(directory, 'bom.json')).components*.name as Set
    }

    private GradleRunner inventory() {
        runner('--init-script', initScript(), 'cyclonedxBom',
                '-PsecuritySbom=' + new File(directory, 'bom.json').absolutePath)
    }

    private String initScript() {
        new File(System.getProperty('cbbg.repository'), 'gradle/dependency-sbom.init.gradle').absolutePath
    }

    private GradleRunner runner(String... arguments) {
        GradleRunner.create().withProjectDir(directory)
                .withArguments(arguments.toList() + ['--stacktrace', '--console=plain'])
    }

    private void write(String path, String text) {
        File file = new File(directory, path)
        file.parentFile.mkdirs()
        file.text = text
    }
}
