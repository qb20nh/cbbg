package cbbg.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

import java.util.jar.JarFile

import static org.junit.jupiter.api.Assertions.*

class UtilitiesPluginTest {
    @TempDir File directory

    @ParameterizedTest
    @ValueSource(strings = ['1.4.2', '1.5.0'])
    void optimizedLibraryRunsWithoutMinecraftOrConfigDependencies(String version) {
        File repository = new File(System.getProperty('cbbg.repository'))
        new File(directory, 'settings.gradle').text = "include 'core', 'core:rendering'\n"
        new File(directory, 'gradle.properties').text = 'mod_version=1.4.2\n'
        new File(directory, 'LICENSE').text = 'Fixture license'
        new File(directory, 'build.gradle').text = '''
plugins { id 'java-library' }
'''
        for (String project : ['core', 'core/rendering']) {
            File folder = new File(directory, project)
            folder.mkdirs()
            new File(folder, 'build.gradle').text = """
plugins { id 'java-library'; ${project == 'core' ? "id 'cbbg.utilities'" : ''} }
repositories { mavenCentral() }
dependencies { compileOnly 'org.jspecify:jspecify:1.0.1'
    ${project == 'core/rendering' ? "implementation project(':core')" : ''}
}
tasks.withType(JavaCompile).configureEach { options.release = 8 }
"""
        }
        List<String> sources = ['core/src/main/java/com/qb20nh/cbbg/math/MiniFFT.java',
                'core/rendering/src/main/java/com/qb20nh/cbbg/math/BlueNoise.java']
        new File(repository, 'core/rendering/src/main/java/com/qb20nh/cbbg/api').eachFile {
            sources.add('core/rendering/src/main/java/com/qb20nh/cbbg/api/' + it.name)
        }
        sources.add('src/main/resources/assets/cbbg/shaders/include/dither.glsl')
        sources.each { path ->
            File destination = new File(directory, path)
            destination.parentFile.mkdirs()
            destination.bytes = new File(repository, path).bytes
        }
        List<String> arguments = [':core:utilitiesSourcesJar', '--offline', '--stacktrace']
        if (version != '1.4.2') arguments.add("-Pmod_version=${version}".toString())
        GradleRunner.create().withProjectDir(directory).withPluginClasspath()
                .withArguments(arguments).build()
        File artifact = new File(directory, "core/build/libs/cbbg-utilities-${version}.jar")
        try (JarFile jar = new JarFile(artifact)) {
            List names = jar.entries().toList()*.name
            assertTrue(names.contains('com/qb20nh/cbbg/api/NoiseVolume.class'))
            assertTrue(names.contains('com/qb20nh/cbbg/api/shaders/dither.glsl'))
            assertFalse(names.any { it.contains('/config/') || it.contains('/gson/') || it == 'fabric.mod.json' })
            names.findAll { it.endsWith('.class') }.each {
                byte[] bytes = jar.getInputStream(jar.getEntry(it)).readAllBytes()
                assertEquals(52, ((bytes[6] & 255) << 8) | (bytes[7] & 255))
            }
        }
        try (URLClassLoader loader = new URLClassLoader([artifact.toURI().toURL()] as URL[], null)) {
            Class noise = loader.loadClass('com.qb20nh.cbbg.api.NoiseVolume')
            Object volume = noise.getMethod('generate', Integer.TYPE, Integer.TYPE, Integer.TYPE, Long.TYPE)
                    .invoke(null, 2, 2, 1, 7L)
            assertEquals(2, noise.getMethod('width').invoke(volume))
            Class options = loader.loadClass('com.qb20nh.cbbg.api.DitherOptions')
            Object settings = options.getConstructor(Float.TYPE, Float.TYPE, Float.TYPE, Boolean.TYPE)
                    .newInstance(0f, 1f, 1f, false)
            Class dithering = loader.loadClass('com.qb20nh.cbbg.api.Dithering')
            byte[] result = dithering.getMethod('rgba8', float[].class, Integer.TYPE, Integer.TYPE,
                    noise, Integer.TYPE, options).invoke(null, [0f, 1f, 0.4f, 0.2f] as float[],
                    1, 1, volume, 0, settings)
            assertArrayEquals([0, -1, 102, 51] as byte[], result)
        }
        try (JarFile sourcesJar = new JarFile(new File(directory, "core/build/libs/cbbg-utilities-${version}-sources.jar"))) {
            assertNotNull(sourcesJar.getEntry('META-INF/cbbg/proguard.map'))
            assertNotNull(sourcesJar.getEntry('com/qb20nh/cbbg/api/NoiseVolume.java'))
        }
    }
}
