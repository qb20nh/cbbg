package cbbg.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import proguard.retrace.ReTrace

import java.util.zip.ZipFile
import java.nio.file.Files
import java.nio.file.FileSystems

import static org.junit.jupiter.api.Assertions.*

class ReleaseOptimizationTest {
    @TempDir File directory

    @Test void shrinksObfuscatesAndPreservesRuntimeNamesAndResources() {
        exerciseOptimization(false)
    }

    @Test void exportsTargetRuntimeImageWithoutJmodsAndReusesItsLibraryJar() {
        exerciseOptimization(true, 25)
    }

    @Test void exportsDifferentTargetVersionRatherThanHostRuntime() {
        exerciseOptimization(true, Runtime.version().feature() == 17 ? 21 : 17)
    }

    private void exerciseOptimization(boolean runtimeImage, int targetVersion = 25) {
        File targetHome = new File(System.getProperty('java.home'))
        write('settings.gradle', "rootProject.name = 'example'\n")
        write('build.gradle', '''
plugins { id 'java'; id 'cbbg.packaging' }
tasks.named('jar', Jar) { destinationDirectory = layout.buildDirectory.dir('intermediates/raw-jar') }
releaseOptimization {
    targetJdk(javaToolchains, 25)
    inputJar.set(tasks.named('jar', Jar).flatMap { it.archiveFile })
    outputJar.set(layout.buildDirectory.file('libs/example.jar'))
    mappingFile.set(layout.buildDirectory.file('mapping/example.map'))
    rulesFile.set(file('release.pro'))
    libraryJars.from(sourceSets.main.compileClasspath)
}
tasks.named('optimizeReleaseJar') { dependsOn tasks.named('jar') }
tasks.register('recordSelectedJdk') {
    doLast {
        file('selected-jdk.txt').text = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(25)
        }.get().metadata.installationPath.asFile.absolutePath
    }
}
''')
        if (runtimeImage) {
            File build = new File(directory, 'build.gradle')
            build.text = build.text.replace('targetJdk(javaToolchains, 25)',
                    "targetJdk(providers.provider { layout.projectDirectory.dir('target-runtime') })")
            build.append('''
tasks.register('prepareRuntimeFixture') {
    doLast {
        def target = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(TARGET_VERSION)
        }.get().metadata.installationPath.asFile
        def fixture = layout.projectDirectory.dir('target-runtime/lib').asFile
        fixture.mkdirs()
        for (String name : ['modules', 'jrt-fs.jar']) {
            java.nio.file.Files.createSymbolicLink(new File(fixture, name).toPath(),
                    new File(target, 'lib/' + name).toPath())
        }
        file('target-home.txt').text = target.absolutePath
    }
}
'''.replace('TARGET_VERSION', targetVersion.toString()))
        }
        write('src/main/java/com/qb20nh/cbbg/CbbgClient.java', '''
package com.qb20nh.cbbg;
public class CbbgClient {
    public static void main(String[] args) throws Exception {
        java.sql.DriverManager.getDrivers();
        System.out.println(com.qb20nh.cbbg.internal.Useful.live() + ":"
            + com.qb20nh.cbbg.config.CbbgConfig.value());
    }
}
''')
        write('src/main/java/com/qb20nh/cbbg/config/CbbgConfig.java', '''
package com.qb20nh.cbbg.config;
public class CbbgConfig {
    public static String value() { return "ok"; }
}
''')
        write('src/main/java/com/qb20nh/cbbg/internal/Useful.java', '''
package com.qb20nh.cbbg.internal;
public class Useful {
    public static synchronized int live() {
        if (Boolean.getBoolean("fixturecrash")) return InlineFailure.fail();
        return 7;
    }
    public static int unused() { return 9; }
}
''')
        write('src/main/java/com/qb20nh/cbbg/internal/InlineFailure.java', '''
package com.qb20nh.cbbg.internal;
public final class InlineFailure {
    static int fail() { throw new IllegalStateException("fixture crash"); }
}
''')
        write('src/main/java/com/qb20nh/cbbg/internal/Dead.java',
                'package com.qb20nh.cbbg.internal; public class Dead {}')
        write('src/main/java/com/qb20nh/cbbg/mixin/SampleMixin.java', '''
package com.qb20nh.cbbg.mixin;
public class SampleMixin { public void shadow() {} }
''')
        write('src/main/resources/fabric.mod.json',
                '{"entrypoints":{"client":["com.qb20nh.cbbg.CbbgClient"]}}')
        write('src/main/resources/example.mixins.json',
                '{"package":"com.qb20nh.cbbg.mixin","client":["SampleMixin"]}')
        write('src/main/resources/assets/cbbg/example.txt', 'untouched resource')
        write('release.pro', new File(System.getProperty('cbbg.repository'),
                'build-config/proguard-release.pro').text)

        def runner = { String... tasks ->
            GradleRunner.create().withProjectDir(directory).withPluginClasspath()
                    .withArguments(tasks.toList() + ['--offline', '--stacktrace'])
        }
        if (runtimeImage) {
            runner('prepareRuntimeFixture').build()
            targetHome = new File(new File(directory, 'target-home.txt').text)
        }
        def firstBuild = runner('recordSelectedJdk', 'optimizeReleaseJar').build()
        File runtimeLibrary = new File(directory, 'build/intermediates/proguard/jdk-runtime.jar')
        if (runtimeImage) {
            assertEquals(TaskOutcome.SUCCESS, firstBuild.task(':exportReleaseJdkLibraries').outcome)
            FileSystems.newFileSystem(URI.create('jrt:/'), ['java.home': targetHome.absolutePath]).withCloseable { image ->
                byte[] objectClass = Files.readAllBytes(image.getPath('/modules/java.base/java/lang/Object.class'))
                int classMajor = ((objectClass[6] & 0xff) << 8) | (objectClass[7] & 0xff)
                assertEquals(targetVersion + 44, classMajor)
                if (targetVersion != Runtime.version().feature()) {
                    assertNotEquals(Runtime.version().feature() + 44, classMajor)
                }
                new ZipFile(runtimeLibrary).withCloseable { zip ->
                    assertArrayEquals(objectClass, zip.getInputStream(zip.getEntry('java/lang/Object.class')).bytes)
                    assertNotNull(zip.getEntry('java/sql/DriverManager.class'))
                    def names = zip.entries().toList().collect { it.name }
                    assertEquals(names.sort(false), names)
                    assertFalse(names.any { it.endsWith('module-info.class') || it.startsWith('modules/') })
                    def targetClasses = Files.walk(image.getPath('/modules')).withCloseable { paths ->
                        paths.filter { Files.isRegularFile(it) && it.toString().endsWith('.class') &&
                                it.fileName.toString() != 'module-info.class' }.map { path ->
                            def relative = image.getPath('/modules').relativize(path)
                            relative.subpath(1, relative.nameCount).toString()
                        }.toList()
                    }
                    assertEquals(targetClasses.toSet(), names.toSet())
                    assertTrue(zip.entries().toList().every { it.time == 0L })
                }
            }
        } else {
            File selectedHome = new File(new File(directory, 'selected-jdk.txt').text)
            File jmods = new File(selectedHome, 'jmods')
            boolean nativeLibraries = (jmods.isDirectory() && jmods.listFiles().any { it.name.endsWith('.jmod') }) ||
                    new File(selectedHome, 'jre/lib/rt.jar').isFile() || new File(selectedHome, 'lib/rt.jar').isFile()
            if (nativeLibraries) {
                assertNull(firstBuild.task(':exportReleaseJdkLibraries'))
                assertFalse(runtimeLibrary.exists())
            } else {
                assertEquals(TaskOutcome.SUCCESS, firstBuild.task(':exportReleaseJdkLibraries').outcome)
                assertTrue(runtimeLibrary.isFile())
            }
        }

        File output = new File(directory, 'build/libs/example.jar')
        File mapping = new File(directory, 'build/mapping/example.map')
        assertTrue(output.isFile())
        assertTrue(mapping.text.contains('com.qb20nh.cbbg.internal.Useful -> '))
        assertFalse(mapping.text.contains('com.qb20nh.cbbg.internal.Dead -> '))
        assertFalse(mapping.text.contains('unused()'))
        assertTrue(mapping.text.contains('InlineFailure.fail()'))
        assertTrue((mapping.text =~ /(?m)^\s+\d+:\d+:.* -> /).find())
        new ZipFile(output).withCloseable { zip ->
            assertFalse(zip.entries().toList().any { it.name.startsWith('java/') || it.name.startsWith('javax/') })
            assertNotNull(zip.getEntry('com/qb20nh/cbbg/CbbgClient.class'))
            assertNotNull(zip.getEntry('com/qb20nh/cbbg/mixin/SampleMixin.class'))
            assertNull(zip.getEntry('com/qb20nh/cbbg/internal/Dead.class'))
            assertNull(zip.getEntry('com/qb20nh/cbbg/internal/InlineFailure.class'))
            for (String name : ['fabric.mod.json', 'example.mixins.json', 'assets/cbbg/example.txt']) {
                assertEquals(new File(directory, 'src/main/resources/' + name).text,
                        zip.getInputStream(zip.getEntry(name)).getText('UTF-8'))
            }
        }
        def run = new ProcessBuilder(new File(System.getProperty('java.home'), 'bin/java').path,
                '-cp', output.path, 'com.qb20nh.cbbg.CbbgClient').directory(directory).start()
        assertEquals(0, run.waitFor())
        assertEquals('7:ok', run.inputStream.text.trim())
        def crash = new ProcessBuilder(new File(System.getProperty('java.home'), 'bin/java').path,
                '-Dfixturecrash=true', '-cp', output.path, 'com.qb20nh.cbbg.CbbgClient')
                .directory(directory).start()
        assertNotEquals(0, crash.waitFor())
        StringWriter decoded = new StringWriter()
        new ReTrace(mapping).retrace(new LineNumberReader(new StringReader(crash.errorStream.text)),
                new PrintWriter(decoded))
        assertTrue(decoded.toString().contains('com.qb20nh.cbbg.internal.InlineFailure.fail(InlineFailure.java:'),
                decoded.toString())
        assertTrue(decoded.toString().contains('com.qb20nh.cbbg.internal.Useful.live(Useful.java:'),
                decoded.toString())

        byte[] outputBytes = output.bytes
        byte[] mappingBytes = mapping.bytes
        byte[] runtimeBytes = runtimeImage ? runtimeLibrary.bytes : null
        def unchanged = runner('optimizeReleaseJar').build()
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(':optimizeReleaseJar').outcome)
        if (runtimeImage) assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(':exportReleaseJdkLibraries').outcome)
        runner('clean', 'optimizeReleaseJar').build()
        assertArrayEquals(outputBytes, output.bytes)
        assertArrayEquals(mappingBytes, mapping.bytes)
        if (runtimeImage) assertArrayEquals(runtimeBytes, runtimeLibrary.bytes)
        write('release.pro', new File(directory, 'release.pro').text + '\n# changed input\n')
        assertEquals(TaskOutcome.SUCCESS,
                runner('optimizeReleaseJar').build().task(':optimizeReleaseJar').outcome)
    }

    private void write(String path, String text) {
        File file = new File(directory, path)
        file.parentFile.mkdirs()
        file.text = text
    }
}
