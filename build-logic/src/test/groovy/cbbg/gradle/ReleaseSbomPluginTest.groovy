package cbbg.gradle

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.util.jar.JarOutputStream
import static org.junit.jupiter.api.Assertions.*

class ReleaseSbomPluginTest {
    @TempDir File directory

    private void module(String group, String name, String version) {
        File folder = new File(directory, "repo/${group.replace('.', '/')}/${name}/${version}")
        folder.mkdirs()
        new JarOutputStream(new FileOutputStream(new File(folder, "${name}-${version}.jar"))).close()
        new File(folder, "${name}-${version}.pom").text = """<project>
<modelVersion>4.0.0</modelVersion><groupId>${group}</groupId><artifactId>${name}</artifactId>
<version>${version}</version><licenses><license><name>Apache-2.0</name></license></licenses></project>"""
    }

    @Test void inventoriesOnlyEmbeddedInputsWithUpstreamIdentityAndTransformationNotice() {
        module('com.google.code.gson', 'gson', '2.8.9')
        module('fixture', 'bundled', '1.0')
        module('fixture', 'game', '1.0')
        module('fixture', 'tool', '1.0')
        new File(directory, 'settings.gradle').text = "rootProject.name = 'release-test'\ninclude 'core'\n"
        new File(directory, 'core').mkdirs()
        new File(directory, 'core/build.gradle').text = '''
configurations { privateGsonInput { transitive = false } }
dependencies { privateGsonInput 'com.google.code.gson:gson:2.8.9' }
'''
        new File(directory, 'build.gradle').text = '''
plugins { id 'java'; id 'cbbg.release-sbom' }
group = 'com.qb20nh'
version = '1.4.0+mc26.3-fabric'
allprojects { repositories { maven { url = rootProject.file('repo') } } }
evaluationDependsOn(':core')
configurations { include; minecraftLibraries; pmd }
dependencies {
    include 'fixture:bundled:1.0'
    implementation 'fixture:game:1.0'
    minecraftLibraries 'fixture:game:1.0'
    pmd 'fixture:tool:1.0'
}
'''
        def runner = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
        runner.withArguments('releaseSbom', '--offline', '--stacktrace').build()
        File output = new File(directory, 'build/libs/release-test-1.4.0+mc26.3-fabric.cdx.json')
        Map bom = new JsonSlurper().parse(output) as Map
        assertEquals('CycloneDX', bom.bomFormat)
        assertEquals('cbbg', bom.metadata.component.name)
        assertEquals('1.4.0+mc26.3-fabric', bom.metadata.component.version)
        assertEquals(['bundled', 'gson'] as Set, bom.components*.name as Set)
        Map gson = bom.components.find { it.name == 'gson' }
        assertEquals('com.google.code.gson', gson.group)
        assertEquals('2.8.9', gson.version)
        assertEquals('pkg:maven/com.google.code.gson/gson@2.8.9?type=jar', gson.purl)
        assertTrue(gson.properties.any { it.name == 'cbbg:distribution' && it.value.contains('shrunk and relocated') })
        byte[] original = output.bytes
        runner.withArguments('releaseSbom', '--offline', '--rerun-tasks', '--stacktrace').build()
        assertArrayEquals(original, output.bytes, 'The same dependency inputs must produce the same SBOM')
    }
}
