package cbbg.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

import static org.junit.jupiter.api.Assertions.*

class FabricSourcesTest {
    @TempDir File directory

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    void compilesSharedTestsAgainstBothApiPackages(boolean legacy) {
        new File(directory, 'settings.gradle').text = "rootProject.name = 'test-imports'\n"
        File shared = new File(directory, 'shared/Example.java')
        shared.parentFile.mkdirs()
        String original = '''package example;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
class Example { ClientGameTestContext context; }
'''
        shared.text = original
        File api = new File(directory, 'src/main/java/ClientGameTestContext.java')
        api.parentFile.mkdirs()
        String apiPackage = 'net.fabricmc.fabric.api.client.gametest.v1' + (legacy ? '' : '.context')
        api.text = "package ${apiPackage}; public class ClientGameTestContext {}"
        new File(directory, 'build.gradle').text = """
plugins { id 'java'; id 'cbbg.packaging' }
sourceSets.create('gametest') {
    compileClasspath += sourceSets.main.output
}
cbbg.gradle.FabricSources.configure(project, projectDir,
        [legacyGametestApi: ${legacy},
         gametest: [java: [[path: 'shared', includes: [], excludes: []]], resources: []]], 'gametest')
"""
        def result = GradleRunner.create().withProjectDir(directory).withPluginClasspath()
                .withArguments('compileGametestJava', '--stacktrace').build()
        assertTrue(new File(directory, 'build/classes/java/gametest/example/Example.class').isFile())
        assertEquals(original, shared.text)
        assertEquals(legacy, result.output.contains(':mapGametestApiImports'))
        if (legacy) {
            assertTrue(new File(directory, 'build/generated/gametest-api-imports/Example.java').text
                    .contains('import ' + apiPackage + '.ClientGameTestContext;'))
        }
    }
}
