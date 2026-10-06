package cbbg.gradle

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

import static org.junit.jupiter.api.Assertions.*

class FabricSourcesTest {
    @TempDir File directory

    @Test void selectsTheSharedOrdinaryFixturesForGl3() {
        Map target = [id: '1.21.1-fabric', minecraft: '1.21.1', renderer: 'gl3',
                      buildProfile: 'fabric-modern', java: 21,
                      dependencies: [clientGametest: '2.0.0+99ff640a04']]
        Map layout = FabricSources.layout(target, target)
        String shared = 'adapters/fabric/shared/src/processedGametest/java/com/qb20nh/cbbg/gametest/'
        assertTrue(layout.legacyGametestApi)
        File metadata = new File(System.getProperty('cbbg.repository'),
                'renderers/gl3/src/processedGametest/resources/fabric.mod.json')
        List entrypoints = new JsonSlurper().parse(metadata).entrypoints['fabric-client-gametest']
        ['ReleaseSodiumConfigGameTest', 'ReleaseGenerationGameTest', 'ReleaseNotificationsGameTest'].each { name ->
            assertTrue(layout.processedGametest.java.any {
                FabricSources.contains(it, shared + name + '.java')
            })
            assertEquals(1, entrypoints.count('com.qb20nh.cbbg.gametest.' + name))
        }
        assertTrue(layout.processedGametest.java.any {
            FabricSources.contains(it, shared + 'ReleaseCommands.java')
        })
        ['UtilitiesBackend', 'ReleaseGenerationStatus', 'ReleaseNotificationUi',
         'ReleasePackagedFields'].each { name ->
            assertTrue(layout.processedGametest.java.any {
                FabricSources.contains(it, 'renderers/gl3/src/processedGametest/java/' +
                        'com/qb20nh/cbbg/gametest/' + name + '.java')
            })
        }
        ['ReleaseWorldPixelsGameTest', 'ReleaseTransparencyGameTest', 'ReleaseRenderScaleGameTest'].each { name ->
            assertTrue(layout.processedGametest.java.any {
                FabricSources.contains(it, shared + name + '.java')
            })
        }
        ['ReleaseWorldPixelsGameTest', 'ReleaseTransparencyGameTest', 'ReleaseRenderScaleGameTest'].each { name ->
            assertFalse(entrypoints.contains('com.qb20nh.cbbg.gametest.' + name))
        }
        ['ReleaseWorldCapture', 'ReleaseWorldReadback', 'ReleaseTransparencySettings', 'ReleaseImagePixels'].each { name ->
            assertFalse(layout.processedGametest.java.any {
                FabricSources.contains(it, shared + name + '.java')
            })
        }
        ['gametest', 'processedGametest'].each { name ->
            assertTrue(layout[name].java.any {
                FabricSources.contains(it, 'renderers/modern/src/gametest/java/' +
                        'com/qb20nh/cbbg/gametest/RenderScaleTestAccess.java')
            })
            assertTrue(layout[name].java.any {
                FabricSources.contains(it, 'renderers/gl3/src/gametest/java/' +
                        'com/qb20nh/cbbg/gametest/RenderScaleTargetFormat.java')
            })
            assertFalse(layout[name].java.any {
                FabricSources.contains(it, 'renderers/modern/src/gametest/java/' +
                        'com/qb20nh/cbbg/gametest/RenderScaleTargetFormat.java')
            })
        }
        ['ReleaseShutdownGameTest', 'ReleaseGeneratingShutdownGameTest'].each { name ->
            assertFalse(layout.processedGametest.java.any {
                FabricSources.contains(it, shared + name + '.java')
            })
        }
    }

    @Test void selectsTheModernRenderScaleFormatBridge() {
        [[id: '26.1-fabric', minecraft: '26.1', renderer: 'blaze-texture-format',
          buildProfile: 'fabric-modern', java: 25],
         [id: '26.2-fabric', minecraft: '26.2', renderer: 'modern',
          buildProfile: 'fabric-upstream', java: 25],
         [id: '26.3-fabric', minecraft: '26.3', renderer: 'renderpearl',
          buildProfile: 'fabric-modern', java: 25]].each { target ->
            Map layout = FabricSources.layout(target, target)
            assertEquals(1, layout.processedGametest.java.count {
                FabricSources.contains(it, 'renderers/modern/src/gametest/java/' +
                        'com/qb20nh/cbbg/gametest/RenderScaleTargetFormat.java')
            })
        }
    }

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
