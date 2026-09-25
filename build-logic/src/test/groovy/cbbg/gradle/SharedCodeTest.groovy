package cbbg.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.util.zip.ZipFile

import static org.junit.jupiter.api.Assertions.*

class SharedCodeTest {
    @TempDir File directory

    @Test void onlyDeclaredModulesSupplyCompilationAndArchives() {
        write('settings.gradle', "rootProject.name = 'example'\ninclude 'common', 'rendering', 'legacy'\n")
        write('shared-code.gradle', new File('../build-config/shared-code.gradle').text)
        write('build.gradle', '''
plugins { id 'java'; id 'cbbg.packaging' }
java { withSourcesJar() }
subprojects { apply plugin: 'java' }
ext.sharedProjects = [project(':common'), project(':rendering')]
apply from: 'shared-code.gradle'
tasks.register('parityCandidateJar', Jar) {
    archiveClassifier = 'candidate'
    from sourceSets.main.output
}
''')
        write('common/src/main/java/example/Common.java', 'package example; public class Common {}')
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
