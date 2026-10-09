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
    @ValueSource(strings = ['1.0.0', '1.1.0'])
    void optimizedLibraryRunsWithoutMinecraftOrConfigDependencies(String version) {
        File repository = new File(System.getProperty('cbbg.repository'))
        new File(directory, 'settings.gradle').text = "rootProject.name = 'cbbg-utilities'\n"
        new File(directory, 'gradle.properties').text = 'library_version=1.0.0\n'
        new File(directory, 'build.gradle').text = """
plugins { id 'java-library'; id 'cbbg.utilities' }
repositories { mavenCentral() }
dependencies { compileOnly 'org.jspecify:jspecify:1.0.1' }
"""
        File sourceRoot = new File(repository, 'libraries/utilities/src/main')
        sourceRoot.eachFileRecurse { source ->
            if (source.isFile()) {
                File destination = new File(directory, 'src/main/' + sourceRoot.toPath().relativize(source.toPath()))
                destination.parentFile.mkdirs()
                destination.bytes = source.bytes
            }
        }
        def rawBuild = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
                .withArguments('jar', '--offline', '--stacktrace').build()
        assertNull(rawBuild.task(':optimizeUtilitiesJar'))
        assertTrue(new File(directory, 'build/libs/cbbg-utilities-1.0.0-raw.jar').isFile())
        List<String> arguments = ['utilitiesSourcesJar', '--offline', '--stacktrace']
        if (version != '1.0.0') arguments.add("-Plibrary_version=${version}".toString())
        GradleRunner.create().withProjectDir(directory).withPluginClasspath()
                .withArguments(arguments).build()
        File artifact = new File(directory, "build/libs/cbbg-utilities-${version}.jar")
        try (JarFile jar = new JarFile(artifact)) {
            List names = jar.entries().toList()*.name
            assertTrue(names.contains('com/qb20nh/cbbg/api/NoiseVolume.class'))
            assertTrue(names.contains('com/qb20nh/cbbg/api/shaders/dither.glsl'))
            assertFalse(names.contains('com/qb20nh/cbbg/math/BlueNoise.class'))
            assertFalse(names.contains('com/qb20nh/cbbg/math/MiniFFT.class'))
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
        try (JarFile sourcesJar = new JarFile(new File(directory, "build/libs/cbbg-utilities-${version}-sources.jar"))) {
            assertNotNull(sourcesJar.getEntry('META-INF/cbbg/proguard.map'))
            assertNotNull(sourcesJar.getEntry('com/qb20nh/cbbg/api/NoiseVolume.java'))
            assertNotNull(sourcesJar.getEntry('com/qb20nh/cbbg/api/shaders/dither.glsl'))
        }
    }
}
