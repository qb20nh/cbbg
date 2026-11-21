package cbbg.gradle

import org.apache.tools.ant.types.selectors.SelectorUtils
import org.gradle.api.Project

/** Source selections shared by Fabric builds and CI change selection. */
class FabricSources {
    private static final String TEXTURE_FORMAT_RENDERER = 'blaze-texture-format'
    private static final String MC_1_21_11 = '1.21.11'
    private static final String IRIS_FIXTURE = 'gametest/IrisFixture'
    private static final String RENDER_SCALE_TEST_ACCESS = 'gametest/RenderScaleTestAccess'
    private static final String IRIS_IMAGE_PIXELS = 'gametest/IrisImagePixels'
    private static final String RENDER_SCALE_TARGET_FORMAT = 'gametest/RenderScaleTargetFormat'
    private static final String MAIN_TARGETS = 'render/MainTargets'
    private static final String FABRIC_UPSTREAM = 'fabric-upstream'
    private static final String MODERN_GAMETEST_SOURCES = 'renderers/modern/src/gametest/java'
    private static final String CORE_TEST_SUPPORT_SOURCES = 'core/src/testSupport/java'

    private static final String PACKAGE = 'com/qb20nh/cbbg/'

    private static Map tree(String path, List<String> includes = [], List<String> excludes = []) {
        [path: path, includes: includes, excludes: excludes]
    }

    private static List<String> classes(String... names) {
        names.collect { PACKAGE + it + '.java' }
    }

    static Map layout(Map target, Map owner) {
        if (!(owner.buildProfile in ['fabric-modern', FABRIC_UPSTREAM])) {
            return null
        }
        Map result = [main: [java: [], resources: []], test: [java: [], resources: []],
                      gametest: [java: [], resources: []], processedGametest: [java: [], resources: []],
                      copies: [main: [], gametest: [], processedGametest: []],
                      inputs: ['src/main/resources/fabric.mod.json'],
                      legacyGametestApi: owner.dependencies?.clientGametest?.startsWith('2.')]
        result.libraryInputs = [tree('libraries/utilities/src/main'), tree('libraries/fabric/src/main'),
                                tree("libraries/fabric/src/${owner.renderer}/java"),
                                tree("libraries/fabric/src/${owner.renderer}/resources")]
        if (owner.renderer == TEXTURE_FORMAT_RENDERER) {
            result.libraryInputs += [tree("libraries/fabric/src/${owner.minecraft}/java"),
                                     tree('libraries/fabric/src/gpu/resources')]
        } else if (owner.renderer == 'renderpearl') {
            result.test.java << tree('renderers/renderpearl/src/test/java')
            result.libraryInputs << tree('libraries/fabric/src/blaze-gpu-format/resources')
        }
        List<Map> processed = result.processedGametest.java
        List<String> progress = classes('gametest/mixin/ScenarioProgressMixin',
                IRIS_FIXTURE, IRIS_IMAGE_PIXELS,
                RENDER_SCALE_TEST_ACCESS, RENDER_SCALE_TARGET_FORMAT)
        Map irisResources = tree('renderers/modern/src/gametest/resources', ['cbbg-iris-fixture/**'])
        if (owner.buildProfile == FABRIC_UPSTREAM) {
            String adapter = 'adapters/fabric/' + owner.minecraft + '/src/'
            result.main.java << tree(adapter + 'main/java')
            result.main.resources << tree('src/main/resources')
            result.main.resources << tree(adapter + 'main/resources')
            result.test.java << tree(adapter + 'test/java')
            result.test.resources << tree('src/test/resources')
            result.gametest.java << tree(adapter + 'gametest/java')
            result.gametest.resources << tree('src/gametest/resources')
            result.main.java << tree('renderers/modern/src/main/java', classes(
                    'render/Rgba8Capture', 'render/DitherController',
                    'render/GenerationNotifications', 'render/NotificationPlatform'))
            List<String> compatibility = classes('platform/LoaderPlatform', 'compat/sulkan/SulkanCompat',
                    'platform/Text', MAIN_TARGETS, 'compat/sodium/CbbgSodiumConfig')
            result.main.java << tree('adapters/fabric/shared/src/main/java', compatibility)
            result.main.java << tree('adapters/fabric/modern/src/main/java', compatibility)
            processed << tree(adapter + 'processedGametest/java')
            result.processedGametest.resources << tree(adapter + 'processedGametest/resources')
        } else {
            boolean textureFormat = target.renderer == TEXTURE_FORMAT_RENDERER
            boolean gl3 = target.renderer == 'gl3'
            boolean sharedLifecycle = textureFormat || gl3
            List<String> groups = (["renderers/${target.renderer}", "adapters/minecraft/${owner.minecraft}",
                    "adapters/fabric/${owner.minecraft}"] + (target.sourceGroups ?: [])).unique()
            if (textureFormat) {
                groups << 'adapters/minecraft/blaze-texture-format'
            }
            if (owner.minecraft in ['1.21.1', MC_1_21_11]) {
                groups << 'adapters/minecraft/gui-graphics'
            }
            for (String group : groups) {
                result.main.java << tree(group + '/src/main/java')
                result.main.resources << tree(group + '/src/main/resources')
                result.gametest.java << tree(group + '/src/gametest/java')
                result.gametest.java << tree(group + '/src/parity/java')
                result.gametest.resources << tree(group + '/src/gametest/resources')
            }
            result.test.java << tree('adapters/fabric/modern/src/test/java')
            result.test.resources << tree('build-config/fabric-modern/src/test/resources')
            result.gametest.java << tree(CORE_TEST_SUPPORT_SOURCES)
            result.main.java << tree('adapters/fabric/shared/src/main/java', sharedLifecycle ? [] : classes(
                    'Cbbg', 'platform/LoaderPlatform', 'command/CbbgClientCommands', 'platform/Text',
                    'command/CommandPlatform', 'config/gui/CbbgConfigWidgets', 'config/gui/WidgetPlatform',
                    'compat/iris/IrisCompat', 'compat/renderscale/RenderScaleCompat',
                    'compat/modmenu/CbbgModMenuApi', 'compat/sodium/CbbgSodiumConfig',
                    'render/stbn/STBNGenerator', 'render/stbn/STBNLoader',
                    'render/stbn/StbnImagePixels', 'render/stbn/STBNCache', MAIN_TARGETS),
                    gl3 ? classes(MAIN_TARGETS, 'render/stbn/StbnImagePixels',
                            'config/gui/WidgetPlatform') : [])
            result.gametest.java << tree('adapters/fabric/shared/src/gametest/java',
                    sharedLifecycle ? [] : classes('gametest/CbbgConfigScreenGameTest'),
                    sharedLifecycle ? classes('gametest/ClientTestAccess') : [])
            if (sharedLifecycle) {
                result.main.java << tree('adapters/fabric/modern/src/main/java', classes(
                        'CbbgClient', 'CbbgEarlyInit', 'CbbgLanguageAdapter'),
                        gl3 ? classes('CbbgClient') : [])
                if (gl3) {
                    result.main.java << tree('adapters/minecraft/blaze-texture-format/src/main/java',
                            classes('config/gui/ClientScreenAccess'))
                    result.main.java << tree('adapters/minecraft/blaze-texture-format/src/main/java',
                            classes('mixin/package-info'))
                    result.main.java << tree('renderers/blaze-texture-format/src/main/java',
                            classes('compat/sulkan/ShaderCompat'))
                }
                result.main.java << tree('renderers/modern/src/main/java', classes(
                        'render/GenerationNotifications', 'render/DitherController',
                        'config/gui/CbbgConfigScreen', 'config/gui/ConfigCanvas'))
                result.gametest.java << tree(MODERN_GAMETEST_SOURCES, classes(
                        'gametest/ModMenuGameTest', 'gametest/OptionalModsGameTest',
                        RENDER_SCALE_TEST_ACCESS, IRIS_FIXTURE) +
                        (gl3 ? [] : classes(IRIS_IMAGE_PIXELS, RENDER_SCALE_TARGET_FORMAT)))
                processed << tree("renderers/${owner.renderer}/src/processedGametest/java", [],
                        ((owner.java as int) < 25 ? classes('gametest/mixin/ReleaseNoiseMixin') : []) +
                                (owner.minecraft == MC_1_21_11 ? classes('gametest/mixin/ReleaseStartupRenderPassMixin',
                                        'gametest/mixin/ReleaseAllocationFailureMixin',
                                        'gametest/mixin/ReleaseNoiseDeviceMixin') : []))
                processed << tree("adapters/fabric/${owner.minecraft}/src/processedGametest/java")
                processed << tree("adapters/minecraft/${owner.minecraft}/src/processedGametest/java")
                result.processedGametest.resources << tree("renderers/${owner.renderer}/src/processedGametest/resources")
                result.copies.gametest << irisResources
            } else if (target.id == '26.3-fabric') {
                processed << tree('renderers/renderpearl/src/processedGametest/java')
                List<String> shared = classes('gametest/mixin/ScenarioProgressMixin', 'reference/DitherReference',
                        'gametest/FloatPrecisionGameTest', IRIS_FIXTURE, IRIS_IMAGE_PIXELS,
                        RENDER_SCALE_TEST_ACCESS,
                        RENDER_SCALE_TARGET_FORMAT,
                        'gametest/EarlyStartupGameTest', 'gametest/mixin/EarlyPipelineCacheMixin',
                        'gametest/mixin/EarlyRenderPassMixin', 'gametest/WindowResizeGameTest')
                processed << tree('renderers/renderpearl/src/gametest/java', shared)
                processed << tree(MODERN_GAMETEST_SOURCES, shared)
                processed << tree(CORE_TEST_SUPPORT_SOURCES, shared)
                result.processedGametest.resources << tree('renderers/renderpearl/src/processedGametest/resources')
                result.copies.processedGametest << tree('renderers/renderpearl/src/gametest/resources',
                        ['cbbg-world-goldens/**', 'cbbg.early-startup-test.mixins.json'])
            }
            List<String> resources = ['assets/cbbg/icon.png', 'assets/cbbg/lang/*.json']
            result.copies.main << tree('src/main/resources', resources)
        }
        if (owner.buildProfile == FABRIC_UPSTREAM || owner.renderer in [TEXTURE_FORMAT_RENDERER, 'gl3']) {
            if (owner.renderer == 'gl3') {
                processed << tree(MODERN_GAMETEST_SOURCES,
                        classes(RENDER_SCALE_TEST_ACCESS, IRIS_FIXTURE))
                processed << tree('renderers/gl3/src/gametest/java',
                        classes(RENDER_SCALE_TARGET_FORMAT, IRIS_IMAGE_PIXELS))
            } else {
                processed << tree('renderers/renderpearl/src/gametest/java', progress)
                processed << tree(MODERN_GAMETEST_SOURCES, progress)
            }
            processed << tree(CORE_TEST_SUPPORT_SOURCES, classes('reference/DitherReference'))
            processed << tree('adapters/fabric/shared/src/processedGametest/java',
                    owner.renderer == 'gl3' ? classes('gametest/ReleaseCacheGameTest',
                            'gametest/ReleaseSettingsGuiGameTest', 'gametest/ReleaseUtilitiesGameTest',
                            'gametest/ReleaseLibraryResolutionGameTest',
                            'gametest/ReleaseSodiumConfigGameTest', 'gametest/ReleaseControlsGameTest',
                            'gametest/ReleaseModMenuGameTest', 'gametest/ReleaseIrisGameTest',
                            'gametest/ReleaseGenerationGameTest', 'gametest/ReleaseNotificationsGameTest',
                            'gametest/ReleaseEarlyStartupGameTest', 'gametest/ReleaseStartupPreLaunch',
                            'gametest/ReleaseStartupObservations', 'gametest/ReleaseShutdownGameTest',
                            'gametest/ReleaseGeneratingShutdownGameTest', 'gametest/mixin/ReleaseShutdownMixin',
                            'gametest/ReleaseWorldPixelsGameTest', 'gametest/ReleaseTransparencyGameTest',
                            'gametest/ReleaseRenderScaleGameTest', 'gametest/ReleaseCommands',
                            'gametest/ReleaseGraphics', 'gametest/ReleaseGameNames',
                            'gametest/ReleaseMapping', 'gametest/ReleaseNoiseWork',
                            'gametest/ReleaseMaximumNoiseCacheGameTest') : [],
                    (owner.renderer in ['gl3', TEXTURE_FORMAT_RENDERER] ? [] :
                            classes('gametest/ReleaseModMenuGameTest', 'gametest/ReleaseIrisGameTest')) +
                            (owner.minecraft == MC_1_21_11 ? classes('gametest/ReleaseGuiCharacters') : []))
            result.copies.processedGametest << tree('adapters/fabric/shared/src/processedGametest/resources', [],
                    owner.renderer == 'gl3' ? ['cbbg.release-allocation.mixins.json',
                                               'cbbg.release-startup.mixins.json',
                                               'cbbg.release-shutdown.mixins.json'] : [])
        }
        if (owner.buildProfile == FABRIC_UPSTREAM || owner.renderer == TEXTURE_FORMAT_RENDERER) {
            result.main.java << tree('renderers/blaze-shared/src/main/java')
            processed << tree('renderers/blaze-shared/src/processedGametest/java', [],
                    owner.minecraft == MC_1_21_11 ? classes('gametest/mixin/ReleaseNoiseWriteMixin') : [])
        }
        if (owner.buildProfile == FABRIC_UPSTREAM || owner.renderer == 'renderpearl') {
            result.main.java << tree('renderers/vulkan/src/main/java')
            result.libraryInputs << tree('libraries/fabric/src/vulkan/resources')
            result.main.java << tree('renderers/blaze-texture-format/src/main/java',
                    classes('render/WindowFramebuffer'))
        }
        result.copies.processedGametest << irisResources
        if (owner.renderer == 'renderpearl') {
            result.main.java << tree('adapters/minecraft/26.1/src/main/java',
                    classes('config/gui/ConfigScreenPlatform', 'render/HudPlatform'))
            processed << tree('adapters/fabric/shared/src/processedGametest/java',
                    classes('gametest/ReleaseSodiumConfigGameTest', 'gametest/ReleaseUtilitiesGameTest',
                            'gametest/ReleaseLibraryResolutionGameTest',
                            'gametest/ReleaseUtilityCalls', 'gametest/ReleaseImagePixels',
                            'gametest/ReleaseSettingsGuiGameTest', 'gametest/ReleaseGuiInput',
                            'gametest/ReleaseMapping', 'gametest/ReleaseGuiCharacters',
                            'gametest/ReleaseGraphics', 'gametest/ReleaseNoiseWork'))
        }
        result
    }

    static void configure(Project project, File root, Map layout, String name) {
        def sourceSet = project.extensions.getByName('sourceSets').getByName(name)
        for (String kind : ['java', 'resources']) {
            def sources = sourceSet."$kind"
            sources.setSrcDirs([])
            List selected = []
            layout[name][kind].eachWithIndex { Map spec, int index ->
                def tree = project.objects.sourceDirectorySet("${name}${kind}${index}", spec.path as String)
                tree.srcDir(new File(root, spec.path as String))
                if (spec.includes) {
                    tree.include(spec.includes)
                }
                if (spec.excludes) {
                    tree.exclude(spec.excludes)
                }
                selected.add(tree)
            }
            if (kind == 'java' && name in ['gametest', 'processedGametest'] && layout.legacyGametestApi) {
                def mapped = project.tasks.register("map${name.capitalize()}ApiImports", org.gradle.api.tasks.Sync) {
                    from(selected)
                    into(project.layout.buildDirectory.dir("generated/${name}-api-imports"))
                    filter { String line ->
                        line.replace('net.fabricmc.fabric.api.client.gametest.v1.context.',
                                'net.fabricmc.fabric.api.client.gametest.v1.')
                    }
                }
                sources.srcDir(mapped)
            } else {
                selected.each { sources.source(it) }
            }
        }
    }

    static void copyResources(Project project, File root, Map layout, String name, Object task) {
        for (Map spec : layout.copies[name]) {
            task.from(project.fileTree(new File(root, spec.path as String)) {
                if (spec.includes) {
                    include(spec.includes)
                }
                if (spec.excludes) {
                    exclude(spec.excludes)
                }
            })
        }
    }

    static List<Map> inputs(Map layout) {
        ['main', 'test', 'gametest', 'processedGametest'].collectMany { name ->
            layout[name].java + layout[name].resources + (layout.copies[name] ?: [])
        } + layout.inputs.collect { [path: it, file: true] } + (layout.libraryInputs ?: [])
    }

    static boolean contains(Map spec, String path) {
        if (spec.file) {
            return path == spec.path
        }
        if (!path.startsWith(spec.path + '/')) {
            return false
        }
        String relative = path.substring(spec.path.length() + 1)
        (!spec.includes || spec.includes.any { SelectorUtils.matchPath(it as String, relative, true) }) &&
                !spec.excludes.any { SelectorUtils.matchPath(it as String, relative, true) }
    }
}
