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

    @ParameterizedTest
    @ValueSource(strings = ['1.21.1-fabric', '1.21.11-fabric', '26.1-fabric', '26.2-fabric', '26.3-fabric'])
    void packagesGuiInteractionsForEveryArtifact(String id) {
        File root = new File(System.getProperty('cbbg.repository'))
        Map target = new JsonSlurper().parse(new File(root, 'targets.json')).targets.find { it.id == id }
        Map layout = FabricSources.layout(target, target)
        String packagePath = 'com/qb20nh/cbbg/gametest/'
        ['ReleaseSettingsGuiGameTest', 'ReleaseGuiInput', 'ReleaseViewport'].each { name ->
            List files = layout.processedGametest.java.collect { spec ->
                String relative = spec.path + '/' + packagePath + name + '.java'
                new File(root, relative).isFile() && FabricSources.contains(spec, relative) ? relative : null
            }.findAll { it != null }
            assertEquals(1, files.size(), id + ': ' + name)
        }
        Map contract = new JsonSlurper().parse(new File(root, 'runtime-locks/' + id + '-scenarios.json'))
        Map metadata = new JsonSlurper().parse(new File(root, contract.ordinaryMetadata as String))
        assertEquals(1, metadata.entrypoints['fabric-client-gametest'].count(
                'com.qb20nh.cbbg.gametest.ReleaseSettingsGuiGameTest'))
    }

    @ParameterizedTest
    @ValueSource(strings = ['1.21.1-fabric', '1.21.11-fabric', '26.1-fabric', '26.2-fabric', '26.3-fabric'])
    void gpuApiSourcesAreOwnedByTheStandaloneLibrary(String id) {
        File root = new File(System.getProperty('cbbg.repository'))
        Map target = new JsonSlurper().parse(new File(root, 'targets.json')).targets.find { it.id == id }
        Map layout = FabricSources.layout(target, target)
        String relative = 'libraries/fabric/src/' + target.renderer + '/java/com/qb20nh/cbbg/render/DitherPass.java'
        assertTrue(new File(root, relative).isFile(), id)
        assertFalse(layout.main.java.any { FabricSources.contains(it, relative) }, id)
        List<String> previous = ['renderers/' + target.renderer + '/src/main/java/com/qb20nh/cbbg/render/DitherPass.java',
                                 'adapters/fabric/' + target.minecraft + '/src/main/java/com/qb20nh/cbbg/render/DitherPass.java']
        assertFalse(previous.any { new File(root, it).isFile() }, id)
        assertTrue(new File(root, 'libraries/utilities/src/main/java/com/qb20nh/cbbg/api/Dithering.java').isFile())
        assertFalse(new File(root, 'core/src/main/java/com/qb20nh/cbbg/api/Dithering.java').exists())
    }

    @Test void selectsTheGuiGraphicsCanvasOnlyForItsMinecraftVersions() {
        File root = new File(System.getProperty('cbbg.repository'))
        Map catalog = new JsonSlurper().parse(new File(root, 'targets.json'))
        String canvas = 'adapters/minecraft/gui-graphics/src/main/java/' +
                'com/qb20nh/cbbg/config/gui/GuiGraphicsCanvas.java'
        ['1.21.1-fabric', '1.21.11-fabric', '26.1-fabric', '26.2-fabric', '26.3-fabric'].each { id ->
            Map target = catalog.targets.find { it.id == id }
            Map layout = FabricSources.layout(target, target)
            assertEquals(id in ['1.21.1-fabric', '1.21.11-fabric'],
                    layout.main.java.any { FabricSources.contains(it, canvas) })
        }
    }

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
        ['ReleaseSodiumConfigGameTest', 'ReleaseGenerationGameTest', 'ReleaseNotificationsGameTest',
         'ReleaseControlsGameTest', 'ReleaseModMenuGameTest', 'ReleaseIrisGameTest'].each { name ->
            assertTrue(layout.processedGametest.java.any {
                FabricSources.contains(it, shared + name + '.java')
            })
            assertEquals(1, entrypoints.count('com.qb20nh.cbbg.gametest.' + name))
        }
        assertTrue(layout.processedGametest.java.any {
            FabricSources.contains(it, shared + 'ReleaseCommands.java')
        })
        ['UtilitiesBackend', 'ReleaseGenerationStatus', 'ReleaseNotificationUi',
         'ReleasePackagedFields', 'ReleaseScreenshots', 'ReleaseRenderScale'].each { name ->
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
                FabricSources.contains(it, 'renderers/modern/src/gametest/java/' +
                        'com/qb20nh/cbbg/gametest/IrisFixture.java')
            })
            assertTrue(layout[name].java.any {
                FabricSources.contains(it, 'renderers/gl3/src/gametest/java/' +
                        'com/qb20nh/cbbg/gametest/IrisImagePixels.java')
            })
            assertFalse(layout[name].java.any {
                FabricSources.contains(it, 'renderers/modern/src/gametest/java/' +
                        'com/qb20nh/cbbg/gametest/IrisImagePixels.java')
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
        ['ReleaseEarlyStartupGameTest', 'ReleaseStartupPreLaunch', 'ReleaseStartupObservations',
         'ReleaseShutdownGameTest', 'ReleaseGeneratingShutdownGameTest', 'mixin/ReleaseShutdownMixin'].each { name ->
            assertEquals(1, layout.processedGametest.java.count {
                FabricSources.contains(it, shared + name + '.java')
            })
            assertFalse(entrypoints.contains('com.qb20nh.cbbg.gametest.' + name))
        }
        ['ReleaseAllocationGameTest', 'ReleaseShaderFailureGameTest', 'ReleaseDebugOverlayGameTest'].each { name ->
            assertFalse(layout.processedGametest.java.any {
                FabricSources.contains(it, shared + name + '.java')
            })
        }
        ['startup', 'shutdown'].each { kind ->
            String resource = 'cbbg.release-' + kind + '.mixins.json'
            assertFalse(layout.copies.processedGametest.any {
                FabricSources.contains(it, 'adapters/fabric/shared/src/processedGametest/resources/' + resource)
            })
            assertTrue(layout.processedGametest.resources.any {
                FabricSources.contains(it, 'renderers/gl3/src/processedGametest/resources/' + resource)
            })
            File nativeMetadata = new File(System.getProperty('cbbg.repository'),
                    'renderers/gl3/src/processedGametest/resources/' + resource)
            assertEquals('JAVA_21', new JsonSlurper().parse(nativeMetadata).compatibilityLevel)
        }
    }

    @Test void selectsMaximumNoiseCacheAsAnIsolatedGl3Fixture() {
        Map target = [id: '1.21.1-fabric', minecraft: '1.21.1', renderer: 'gl3',
                      buildProfile: 'fabric-modern', java: 21,
                      dependencies: [clientGametest: '2.0.0+99ff640a04']]
        Map layout = FabricSources.layout(target, target)
        String fixture = 'adapters/fabric/shared/src/processedGametest/java/' +
                'com/qb20nh/cbbg/gametest/ReleaseMaximumNoiseCacheGameTest.java'
        assertEquals(1, layout.processedGametest.java.count { FabricSources.contains(it, fixture) })
        String nativePixels = 'adapters/minecraft/1.21.1/src/processedGametest/java/' +
                'com/qb20nh/cbbg/gametest/ReleaseImagePixels.java'
        assertEquals(1, layout.processedGametest.java.count { FabricSources.contains(it, nativePixels) })
        assertFalse(layout.processedGametest.java.any {
            FabricSources.contains(it, 'adapters/fabric/shared/src/processedGametest/java/' +
                    'com/qb20nh/cbbg/gametest/ReleaseImagePixels.java')
        })
        File metadata = new File(System.getProperty('cbbg.repository'),
                'renderers/gl3/src/processedGametest/resources/fabric.mod.json')
        List entrypoints = new JsonSlurper().parse(metadata).entrypoints['fabric-client-gametest']
        assertFalse(entrypoints.contains('com.qb20nh.cbbg.gametest.ReleaseMaximumNoiseCacheGameTest'))
    }

    @Test void selectsGl3AllocationMixinsWithoutTheSharedJava25Descriptor() {
        Map target = [id: '1.21.1-fabric', minecraft: '1.21.1', renderer: 'gl3',
                      buildProfile: 'fabric-modern', java: 21,
                      dependencies: [clientGametest: '2.0.0+99ff640a04']]
        Map layout = FabricSources.layout(target, target)
        List resources = layout.processedGametest.resources + layout.copies.processedGametest
        String descriptor = 'cbbg.release-allocation.mixins.json'
        assertEquals(1, resources.count {
            FabricSources.contains(it, 'renderers/gl3/src/processedGametest/resources/' + descriptor)
        })
        assertFalse(resources.any {
            FabricSources.contains(it, 'adapters/fabric/shared/src/processedGametest/resources/' + descriptor)
        })
    }

    @ParameterizedTest
    @ValueSource(strings = ['1.21.11-fabric', '26.1-fabric'])
    void selectsVersionSpecificGpuHooks(String id) {
        File root = new File(System.getProperty('cbbg.repository'))
        Map target = new JsonSlurper().parse(new File(root, 'targets.json')).targets.find { it.id == id }
        Map layout = FabricSources.layout(target, target)
        boolean java21 = (target.java as int) == 21
        ['ReleaseNoiseMixin', 'ReleaseStartupRenderPassMixin', 'ReleaseAllocationFailureMixin'].each { name ->
            String relative = "com/qb20nh/cbbg/gametest/mixin/${name}.java"
            String shared = 'renderers/blaze-texture-format/src/processedGametest/java/' + relative
            String adapter = "adapters/minecraft/${target.minecraft}/src/processedGametest/java/" + relative
            assertEquals(!java21, layout.processedGametest.java.any { FabricSources.contains(it, shared) })
            assertEquals(java21, new File(root, adapter).isFile() &&
                    layout.processedGametest.java.any { FabricSources.contains(it, adapter) })
        }
    }

    @Test void retainsGl3StartupDrawHook() {
        File root = new File(System.getProperty('cbbg.repository'))
        Map target = new JsonSlurper().parse(new File(root, 'targets.json')).targets.find { it.id == '1.21.1-fabric' }
        Map layout = FabricSources.layout(target, target)
        String hook = 'renderers/gl3/src/processedGametest/java/com/qb20nh/cbbg/gametest/mixin/ReleaseStartupRenderPassMixin.java'
        assertTrue(new File(root, hook).isFile())
        assertTrue(layout.processedGametest.java.any { FabricSources.contains(it, hook) })
    }

    @Test void retainsOrdinaryCompatibilityFixturesOnlyForTextureFormatAndGl3() {
        File root = new File(System.getProperty('cbbg.repository'))
        Map catalog = new JsonSlurper().parse(new File(root, 'targets.json'))
        ['1.21.11-fabric', '26.1-fabric', '26.2-fabric', '26.3-fabric'].each { id ->
            Map target = catalog.targets.find { it.id == id }
            Map layout = FabricSources.layout(target, target)
            String shared = 'adapters/fabric/shared/src/processedGametest/java/com/qb20nh/cbbg/gametest/'
            ['ReleaseModMenuGameTest', 'ReleaseIrisGameTest'].each { name ->
                assertEquals(target.renderer == 'blaze-texture-format',
                        layout.processedGametest.java.any { FabricSources.contains(it, shared + name + '.java') })
                assertFalse(layout.processedGametest.java.any {
                    FabricSources.contains(it, 'renderers/blaze-texture-format/src/processedGametest/java/' +
                            'com/qb20nh/cbbg/gametest/' + name + '.java') &&
                            new File(root, 'renderers/blaze-texture-format/src/processedGametest/java/' +
                                    'com/qb20nh/cbbg/gametest/' + name + '.java').exists()
                })
            }
            assertTrue(layout.processedGametest.java.any {
                FabricSources.contains(it, 'renderers/modern/src/gametest/java/' +
                        'com/qb20nh/cbbg/gametest/IrisImagePixels.java')
            })
            assertFalse(layout.processedGametest.java.any {
                FabricSources.contains(it, 'renderers/gl3/src/gametest/java/' +
                        'com/qb20nh/cbbg/gametest/IrisImagePixels.java')
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
