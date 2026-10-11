package cbbg.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.util.zip.ZipFile

import static org.junit.jupiter.api.Assertions.*

class ReproducibleTextTest {
    @TempDir File directory

    @Test void jsonIgnoresMapInsertionOrderButKeepsArrayOrder() {
        Map first = [z: [b: 2, a: '한글'], a: [3, 1, 2]]
        Map second = [a: [3, 1, 2], z: [a: '한글', b: 2]]
        assertEquals(ReproducibleText.json(first), ReproducibleText.json(second))
        assertNotEquals(ReproducibleText.json(first), ReproducibleText.json(second + [a: [1, 2, 3]]))
        File output = new File(directory, 'metadata.json')
        ReproducibleText.writeJson(output, first)
        assertArrayEquals(ReproducibleText.json(first).getBytes('UTF-8'), output.bytes)
        assertTrue(output.getText('UTF-8').endsWith('\n'))
    }

    @Test void normalizesTextWithoutChangingOtherCharactersOrTrailingNewlines() {
        File output = new File(directory, 'mapping.txt')
        output.setText('한글\r\nsecond\rthird\n', 'UTF-8')
        ReproducibleText.normalize(output)
        assertEquals('한글\nsecond\nthird\n', output.getText('UTF-8'))
        byte[] original = output.bytes
        ReproducibleText.normalize(output)
        assertArrayEquals(original, output.bytes)
    }

    @Test void resourceFilteringUsesUtf8AndPackagesLfText() {
        new File(directory, 'settings.gradle').text = "rootProject.name = 'portable-resources'\n"
        new File(directory, 'build.gradle').text = '''
plugins { id 'java'; id 'cbbg.packaging' }
tasks.named('processResources') {
    filesMatching('shader.fsh') { filter { line -> line.replace('TOKEN', '한글') } }
}
'''
        File resources = new File(directory, 'src/main/resources')
        resources.mkdirs()
        new File(resources, 'shader.fsh').setText('TOKEN\r\nsecond\r\n', 'UTF-8')
        new File(resources, 'binary.bin').bytes = [0, 13, 10, 255] as byte[]
        def runner = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
        runner.withArguments('jar', '--offline', '--stacktrace').build()
        File jar = new File(directory, 'build/libs/portable-resources.jar')
        byte[] original = jar.bytes
        new ZipFile(jar).withCloseable { zip ->
            assertEquals('한글\nsecond\n', zip.getInputStream(zip.getEntry('shader.fsh')).getText('UTF-8'))
            assertArrayEquals([0, 13, 10, 255] as byte[], zip.getInputStream(zip.getEntry('binary.bin')).readAllBytes())
        }
        new File(resources, 'shader.fsh').setText('TOKEN\nsecond\n', 'UTF-8')
        runner.withArguments('jar', '--offline', '--stacktrace').build()
        assertArrayEquals(original, jar.bytes)
    }
}
