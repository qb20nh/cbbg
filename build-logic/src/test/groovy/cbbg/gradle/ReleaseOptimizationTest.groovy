package cbbg.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import proguard.retrace.ReTrace

import java.util.zip.ZipFile

import static org.junit.jupiter.api.Assertions.*

class ReleaseOptimizationTest {
    @TempDir File directory

    @Test void shrinksObfuscatesAndPreservesRuntimeNamesAndResources() {
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
''')
        write('src/main/java/com/qb20nh/cbbg/CbbgClient.java', '''
package com.qb20nh.cbbg;
public class CbbgClient {
    public static void main(String[] args) throws Exception {
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
        runner('optimizeReleaseJar').build()

        File output = new File(directory, 'build/libs/example.jar')
        File mapping = new File(directory, 'build/mapping/example.map')
        assertTrue(output.isFile())
        assertTrue(mapping.text.contains('com.qb20nh.cbbg.internal.Useful -> '))
        assertFalse(mapping.text.contains('com.qb20nh.cbbg.internal.Dead -> '))
        assertFalse(mapping.text.contains('unused()'))
        assertTrue(mapping.text.contains('InlineFailure.fail()'))
        assertTrue((mapping.text =~ /(?m)^\s+\d+:\d+:.* -> /).find())
        new ZipFile(output).withCloseable { zip ->
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
        assertEquals(TaskOutcome.UP_TO_DATE,
                runner('optimizeReleaseJar').build().task(':optimizeReleaseJar').outcome)
        runner('clean', 'optimizeReleaseJar').build()
        assertArrayEquals(outputBytes, output.bytes)
        assertArrayEquals(mappingBytes, mapping.bytes)
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
