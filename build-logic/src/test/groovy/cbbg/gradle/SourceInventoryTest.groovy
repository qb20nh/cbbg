package cbbg.gradle

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import static org.junit.jupiter.api.Assertions.*

class SourceInventoryTest {
    @TempDir File directory

    @Test void libraryInventoryIncludesItsGpuSourcesAndSharedUtilities() {
        File profile = new File(directory, 'libraries/fabric')
        profile.mkdirs()
        new File(profile, 'settings.gradle').text = "rootProject.name = 'library-inventory'\n"
        new File(profile, 'build.gradle').text = '''
plugins { id 'java' }
sourceSets.main.java.srcDirs = ['src/renderpearl/java']
ext.additionalInventorySources = [fileTree('../utilities/src/main/java')]
apply from: file('source-inventory.gradle')
'''
        new File(profile, 'source-inventory.gradle').text =
                new File(System.getProperty('cbbg.repository'), 'build-config/source-inventory.gradle').text
        Map paths = [
                'libraries/fabric/src/renderpearl/java/com/qb20nh/cbbg/render/DitherPass.java':
                        'com/qb20nh/cbbg/render/DitherPass.java',
                'libraries/utilities/src/main/java/com/qb20nh/cbbg/api/Dithering.java':
                        'com/qb20nh/cbbg/api/Dithering.java']
        paths.each { path, archive ->
            File source = new File(directory, path)
            source.parentFile.mkdirs()
            source.text = 'class ' + source.name.replace('.java', '') + ' {}\n'
        }
        GradleRunner.create().withProjectDir(profile).withArguments('sourceInventory', '--offline', '--stacktrace').build()
        Map inventory = new JsonSlurper().parse(new File(profile, 'build/source-inventory.json')) as Map
        assertEquals(paths.keySet(), inventory.sources*.source_path as Set)
        inventory.sources.each { item ->
            assertEquals(paths[item.source_path], item.archive_path)
            assertEquals(CandidateFiles.sha256(new File(directory, item.source_path)), item.sha256)
        }
    }
}
