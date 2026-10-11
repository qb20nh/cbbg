package cbbg.gradle

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import javax.tools.ToolProvider
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile

import static org.junit.jupiter.api.Assertions.*

class DriverDependenciesTest {
    @TempDir File directory

    @Test void tracksTransitiveHelpersButNotOtherSuites() {
        File driver = fixture('return 1;', 'return 2;')
        Map before = DriverDependencies.inventory(driver)
        assertTrue(before.files.keySet().containsAll(['Test.class', 'Helper.class', 'Leaf.class', 'fabric.mod.json']))
        assertFalse(before.files.containsKey('Other.class'))
        Map unrelated = DriverDependencies.inventory(fixture('return 1;', 'return 3;'))
        assertEquals(before.files, unrelated.files)
        assertNotEquals(before.driver_sha256, unrelated.driver_sha256)
        Map changed = DriverDependencies.inventory(fixture('return 4;', 'return 3;'))
        assertNotEquals(before.files, changed.files)
        assertNotEquals(before.files['Leaf.class'], changed.files['Leaf.class'])
    }

    @Test void reflectionRequiresEveryPackagedHelper() {
        Map dependencies = DriverDependencies.inventory(fixture('return 1;', 'return 2;', true))
        assertTrue(dependencies.files.containsKey('Reflective.class'))
        assertTrue(dependencies.files.containsKey('Other.class'))
    }

    @Test void aRemovedReferencedHelperRequiresTheFullArchive() {
        File original = fixture('return 1;', 'return 2;')
        File missing = new File(directory, 'missing.jar')
        new ZipFile(original).withCloseable { input ->
            new ZipOutputStream(new FileOutputStream(missing)).withCloseable { output ->
                input.entries().each { entry ->
                    if (entry.name != 'Leaf.class') {
                        output.putNextEntry(new ZipEntry(entry.name))
                        output.write(input.getInputStream(entry).withCloseable { it.bytes })
                        output.closeEntry()
                    }
                }
            }
        }
        assertTrue(DriverDependencies.inventory(missing).full_archive)
    }

    private File fixture(String leaf, String other, boolean reflection = false) {
        Map sources = [Test: 'public class Test { public void run() throws Exception { Helper.run(); ' +
                           (reflection ? 'Class.forName("Reflective");' : '') + ' } }',
                       Helper: 'class Helper { static int run() { return Leaf.run(); } }',
                       Leaf: 'class Leaf { static int run() { ' + leaf + ' } }',
                       Reflective: 'class Reflective {}', Other: 'class Other { int run() { ' + other + ' } }']
        List<String> arguments = ['-g:none', '-d', directory.absolutePath]
        sources.each { name, text ->
            File source = new File(directory, name + '.java')
            source.text = text
            arguments.add(source.absolutePath)
        }
        assertEquals(0, ToolProvider.systemJavaCompiler.run(null, null, null, arguments as String[]))
        File jar = new File(directory, 'driver.jar')
        new ZipOutputStream(new FileOutputStream(jar)).withCloseable { output ->
            sources.keySet().each { name ->
                output.putNextEntry(new ZipEntry(name + '.class'))
                output.write(new File(directory, name + '.class').bytes)
                output.closeEntry()
            }
            output.putNextEntry(new ZipEntry('fabric.mod.json'))
            output.write(JsonOutput.toJson([entrypoints: ['fabric-client-gametest': ['Test']]]).getBytes('UTF-8'))
            output.closeEntry()
        }
        jar
    }
}
