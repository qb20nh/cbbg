package cbbg.gradle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.gradle.testkit.runner.GradleRunner

import java.time.LocalDateTime
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

import static org.junit.jupiter.api.Assertions.*

class ReproducibleJarTest {
    @TempDir File directory

    @Test void normalizesBothGradleJarTypes() {
        checkJarTypes('cbbg.packaging')
    }

    @Test void normalizesUtilitiesJarTypes() {
        checkJarTypes('cbbg.utilities')
    }

    private void checkJarTypes(String plugin) {
        new File(directory, 'settings.gradle').text = "rootProject.name = 'portable-jars'\n"
        new File(directory, 'payload.txt').setText(payload('a.txt'), 'UTF-8')
        new File(directory, 'build.gradle').text = '''
plugins { id 'java'; id 'cbbg.packaging' }
tasks.register('legacyJar', org.gradle.api.tasks.bundling.Jar) {
    archiveFileName = 'legacy.jar'
    from('payload.txt')
}
tasks.register('modernJar', org.gradle.jvm.tasks.Jar) {
    archiveFileName = 'modern.jar'
    from('payload.txt')
}
'''.replace('cbbg.packaging', plugin)
        GradleRunner.create().withProjectDir(directory).withPluginClasspath()
                .withArguments('legacyJar', 'modernJar', '--offline', '--stacktrace').build()
        File modern = new File(directory, 'build/libs/modern.jar')
        byte[] original = modern.bytes
        ReproducibleJar.normalize(modern)
        assertArrayEquals(original, modern.bytes, 'Modern Gradle JAR tasks must already be normalized')
        assertArrayEquals(new File(directory, 'build/libs/legacy.jar').bytes, modern.bytes)
    }

    @Test void ignoresOriginalCompressionOrderAndTimestamps() {
        File first = archive('first.jar', 1, ['z.txt', 'a.txt'])
        File second = archive('second.jar', 9, ['a.txt', 'z.txt'])
        ReproducibleJar.normalize(first)
        ReproducibleJar.normalize(second)
        assertArrayEquals(first.bytes, second.bytes)
        byte[] normalized = first.bytes
        ReproducibleJar.normalize(first)
        assertArrayEquals(normalized, first.bytes)
        new ZipFile(first).withCloseable { zip ->
            assertEquals(['a.txt', 'z.txt'], zip.entries().toList()*.name)
            zip.entries().toList().each { entry ->
                assertEquals(LocalDateTime.of(1980, 1, 1, 0, 0), entry.timeLocal)
                assertEquals(payload(entry.name), zip.getInputStream(entry).getText('UTF-8'))
            }
        }
    }

    @Test void writesPortableDeflateRatherThanTheHostCompressor() {
        File jar = archive('portable.jar', 1, ['a.txt'])
        ReproducibleJar.normalize(jar)
        byte[] bytes = jar.bytes
        int offset = 30 + (bytes[26] & 255) + ((bytes[27] & 255) << 8) +
                (bytes[28] & 255) + ((bytes[29] & 255) << 8)
        int size = new ZipFile(jar).withCloseable { it.getEntry('a.txt').compressedSize as int }
        // Fixed output from pinned JZlib, independent of native zlib / zlib-ng.
        assertEquals('d349700aa943dba53b75c6b21ff19b3b4dadd68daf5b87dc705a557f83ba0a80', MessageDigest.getInstance('SHA-256')
                .digest(bytes[offset..<offset + size] as byte[]).encodeHex().toString())
    }

    @Test void preservesNestedArchivesAndDirectoryEntries() {
        File nested = archive('nested.jar', 1, ['a.txt'])
        File jar = new File(directory, 'outer.jar')
        new ZipOutputStream(new FileOutputStream(jar)).withCloseable { output ->
            for (String name : ['META-INF/', 'META-INF/jars/', 'META-INF/jars/library.jar']) {
                output.putNextEntry(new ZipEntry(name))
                if (name.endsWith('.jar')) output.write(nested.bytes)
                output.closeEntry()
            }
        }
        ReproducibleJar.normalize(jar)
        new ZipFile(jar).withCloseable { zip ->
            assertArrayEquals(nested.bytes, zip.getInputStream(zip.getEntry('META-INF/jars/library.jar')).readAllBytes())
            assertTrue(zip.getEntry('META-INF/').directory)
            assertEquals(0, zip.getEntry('META-INF/').size)
        }
    }

    @Test void rejectsDuplicateNamesWithoutChangingTheInput() {
        File jar = new File(directory, 'duplicates.jar')
        new ZipArchiveOutputStream(jar).withCloseable { output ->
            2.times {
                output.putArchiveEntry(new ZipArchiveEntry('duplicate.txt'))
                output.write('data'.bytes)
                output.closeArchiveEntry()
            }
        }
        byte[] original = jar.bytes
        assertThrows(IOException) { ReproducibleJar.normalize(jar) }
        assertArrayEquals(original, jar.bytes)
        assertFalse(new File(directory, 'duplicates.jar.normalized').exists())
    }

    @Test void doesNotDependOnTheDefaultTimeZone() {
        File first = archive('utc.jar', 1, ['a.txt'])
        File second = archive('seoul.jar', 1, ['a.txt'])
        TimeZone previous = TimeZone.default
        try {
            TimeZone.default = TimeZone.getTimeZone('UTC')
            ReproducibleJar.normalize(first)
            TimeZone.default = TimeZone.getTimeZone('Asia/Seoul')
            ReproducibleJar.normalize(second)
        } finally {
            TimeZone.default = previous
        }
        assertArrayEquals(first.bytes, second.bytes)
    }

    private File archive(String name, int level, List<String> entries) {
        File jar = new File(directory, name)
        new ZipOutputStream(new FileOutputStream(jar)).withCloseable { output ->
            output.setLevel(level)
            entries.each { entryName ->
                def entry = new ZipEntry(entryName)
                entry.time = level * 100000L
                output.putNextEntry(entry)
                output.write(payload(entryName).getBytes('UTF-8'))
                output.closeEntry()
            }
        }
        jar
    }

    private static String payload(String name) {
        (0..<256).collect { "${name}: ${it % 31} repeated payload ${it % 7}\n" }.join()
    }
}
