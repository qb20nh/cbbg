package cbbg.gradle

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*

class GameTestMappingsTest {
    @TempDir File directory

    @Test
    void writesStableEscapedProperties() {
        File mappings = new File(directory, 'mappings.tiny')
        mappings.text = [
                'tiny\t2\t0\tintermediary\tnamed',
                'c\tnet/minecraft/class_1\tnet/minecraft/Example',
                '\tf\tI\tfield_2\tvalue: =\\name',
                '\tm\t()V\tmethod_3\tcontent'
        ].join('\n')
        File destination = new File(directory, 'names.properties')
        def project = ProjectBuilder.builder().withProjectDir(directory).build()
        def task = project.tasks.create('gameTestMappings', GameTestMappings)
        task.mappings.fileValue(mappings)
        task.output.fileValue(destination)
        task.generate()
        String first = destination.getText('UTF-8')
        assertFalse(first.readLines().any { it.startsWith('#') }, first)
        assertEquals(first.readLines().sort(), first.readLines())
        Properties properties = new Properties()
        destination.withReader('UTF-8') { properties.load(it) }
        assertEquals(GameTestMappings.names(mappings.readLines('UTF-8')), properties)
        task.generate()
        assertEquals(first, destination.getText('UTF-8'))
    }

    @Test
    void selectsRuntimeNamesFromThreeNamespaces() {
        def names = GameTestMappings.names([
                'tiny\t2\t0\tofficial\tintermediary\tnamed',
                'c\ta\tnet/minecraft/class_1\tnet/minecraft/Example',
                '\tf\tI\tb\tfield_2\tvalue',
                '\tm\t()Ljava/lang/String;\tc\tmethod_3\tcontent',
                '\tm\t(I)V\td\tmethod_4\twrite'
        ])
        assertEquals(['field|net.minecraft.class_1|value': 'field_2',
                      'method|net.minecraft.class_1|content': 'method_3'], names)
    }

    @Test
    void requiresBothNamespaces() {
        assertThrows(GradleException) { GameTestMappings.names(['tiny\t2\t0\tofficial\tnamed']) }
    }
}
