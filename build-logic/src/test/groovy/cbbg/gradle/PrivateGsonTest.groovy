package cbbg.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.util.zip.ZipFile

import static org.junit.jupiter.api.Assertions.*

class PrivateGsonTest {
    @TempDir File directory

    @Test void privateGsonUsesJava25RuntimeImageWithoutJmods() {
        File repository = new File(System.getProperty('cbbg.repository'))
        File rules = new File(directory, 'build-config')
        rules.mkdirs()
        for (String name : ['private-gson.pro', 'private-gson.map']) {
            new File(rules, name).bytes = new File(repository, 'build-config/' + name).bytes
        }
        File project = new File(directory, 'core')
        project.mkdirs()
        new File(project, 'settings.gradle').text = "rootProject.name = 'private-gson-fixture'\n"
        new File(project, 'build.gradle').text = '''
plugins { id 'java'; id 'cbbg.private-gson' }
repositories { mavenCentral() }
privateGsonJdkHome.set(layout.projectDirectory.dir('target-runtime'))
tasks.register('prepareRuntimeFixture') {
    doLast {
        def home = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }.get().metadata.installationPath.asFile
        def library = file('target-runtime/lib')
        library.mkdirs()
        for (String name : ['modules', 'jrt-fs.jar']) {
            java.nio.file.Files.createSymbolicLink(new File(library, name).toPath(),
                    new File(home, 'lib/' + name).toPath())
        }
    }
}
'''
        def runner = { String... tasks ->
            GradleRunner.create().withProjectDir(project).withPluginClasspath()
                    .withArguments(tasks.toList() + ['--stacktrace'])
        }
        runner('prepareRuntimeFixture').build()
        def first = runner('privateGsonJar').build()
        assertEquals(TaskOutcome.SUCCESS, first.task(':exportPrivateGsonJdkLibraries').outcome)
        assertEquals(TaskOutcome.SUCCESS, first.task(':privateGsonJar').outcome)
        File archive = new File(project, 'build/private-gson/gson-stream.jar')
        File runtime = new File(project, 'build/intermediates/proguard/private-gson-jdk-runtime.jar')
        new ZipFile(runtime).withCloseable { zip ->
            byte[] objectClass = zip.getInputStream(zip.getEntry('java/lang/Object.class')).bytes
            assertEquals(69, ((objectClass[6] & 0xff) << 8) | (objectClass[7] & 0xff))
            assertNotNull(zip.getEntry('java/sql/Date.class'))
        }
        new ZipFile(archive).withCloseable { zip ->
            assertTrue(zip.entries().toList().any { it.name.startsWith('com/qb20nh/cbbg/internal/gson/') })
            assertFalse(zip.entries().toList().any { it.name.startsWith('java/') || it.name.startsWith('javax/') })
        }
        byte[] original = archive.bytes
        def repeated = runner('privateGsonJar').build()
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(':exportPrivateGsonJdkLibraries').outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(':privateGsonJar').outcome)
        runner('clean', 'privateGsonJar').build()
        assertArrayEquals(original, archive.bytes)
    }
}
