package cbbg.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.util.zip.ZipFile

import static org.junit.jupiter.api.Assertions.*

class SharedCodeTest {
    @TempDir File directory

    @Test void onlyDeclaredModulesSupplyCompilationAndArchives() {
        write('settings.gradle', "rootProject.name = 'example'\ninclude 'core', 'rendering', 'legacy'\n")
        File repository = new File(System.getProperty('cbbg.repository'))
        write('shared-code.gradle', new File(repository, 'build-config/shared-code.gradle').text)
        write('build-config/private-gson.map',
                new File(repository, 'build-config/private-gson.map').text)
        for (String name : ['Apache-2.0.txt', 'Gson-2.2.4.txt']) {
            write('build-config/licenses/' + name,
                    new File(repository, 'build-config/licenses/' + name).text)
        }
        write('build.gradle', '''
plugins { id 'java'; id 'cbbg.packaging' }
java { withSourcesJar() }
subprojects { apply plugin: 'java' }
ext.sharedProjects = [project(':core'), project(':rendering')]
apply from: 'shared-code.gradle'
tasks.register('parityCandidateJar', Copy) {
    from(tasks.named('jar', Jar).flatMap { it.archiveFile }) {
        rename { 'example-candidate.jar' }
    }
    into layout.buildDirectory.dir('libs')
}
''')
        write('core/build.gradle', '''
tasks.register('privateGsonJar', Jar) {
    destinationDirectory = layout.buildDirectory.dir('private-gson')
    archiveFileName = 'gson-stream.jar'
    from('private-parser')
}
ext.privateGsonArchive = tasks.named('privateGsonJar', Jar).flatMap { it.archiveFile }
ext.privateGsonSourceArchive = files('gson-2.2.4-sources.jar')
ext.privateGsonMapping = files('mapping.txt')
''')
        write('core/private-parser/com/qb20nh/cbbg/internal/gson/stream/JsonReader.class',
                'private parser fixture')
        write('core/gson-2.2.4-sources.jar', 'source fixture')
        write('core/mapping.txt', 'mapping fixture')
        write('core/src/main/java/example/Common.java', 'package example; public class Common {}')
        write('rendering/src/main/java/example/Rendering.java', 'package example; public class Rendering {}')
        write('legacy/src/main/java/example/Legacy.java', 'package example; public class Legacy {}')
        write('src/main/java/example/Client.java',
                'package example; public class Client { Common common; Rendering rendering; }')
        runner('assemble', 'parityCandidateJar').build()
        for (String name : ['example.jar', 'example-candidate.jar', 'example-sources.jar']) {
            new ZipFile(new File(directory, 'build/libs/' + name)).withCloseable { zip ->
                String suffix = name.contains('sources') ? '.java' : '.class'
                assertNotNull(zip.getEntry('example/Client' + suffix))
                assertNotNull(zip.getEntry('example/Common' + suffix))
                assertNotNull(zip.getEntry('example/Rendering' + suffix))
                assertNull(zip.getEntry('example/Legacy' + suffix))
                if (suffix == '.class') {
                    def parser = zip.getEntry('com/qb20nh/cbbg/internal/gson/stream/JsonReader.class')
                    assertNotNull(parser)
                    assertEquals('private parser fixture',
                            zip.getInputStream(parser).getText('UTF-8'))
                    assertNull(zip.getEntry('com/google/gson/Gson.class'))
                    assertNotNull(zip.getEntry('META-INF/licenses/gson-2.2.4/Apache-2.0.txt'))
                    assertNotNull(zip.getEntry('META-INF/licenses/gson-2.2.4/Gson-2.2.4.txt'))
                } else {
                    assertNotNull(zip.getEntry('third-party/gson-2.2.4/gson-2.2.4-sources.jar'))
                    assertNotNull(zip.getEntry('third-party/gson-2.2.4/private-gson.map'))
                    assertNotNull(zip.getEntry('third-party/gson-2.2.4/mapping.txt'))
                }
            }
        }
        write('src/main/java/example/Client.java',
                'package example; public class Client { Legacy legacy; }')
        assertTrue(runner('compileJava').buildAndFail().output.contains('cannot find symbol'))
    }

    private GradleRunner runner(String... tasks) {
        GradleRunner.create().withProjectDir(directory)
                .withPluginClasspath()
                .withArguments(tasks.toList() + ['--offline', '--stacktrace'])
    }

    private void write(String path, String text) {
        File file = new File(directory, path)
        file.parentFile.mkdirs()
        file.text = text
    }
}
