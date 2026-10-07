package cbbg.gradle

import org.apache.tools.ant.types.selectors.SelectorUtils
import org.gradle.api.Project

/** Source selections shared by Fabric builds and CI change selection. */
class FabricSources {
    private static final String PACKAGE = 'com/qb20nh/cbbg/'

    private static Map tree(String path, List<String> includes = [], List<String> excludes = []) {
        [path: path, includes: includes, excludes: excludes]
    }

    private static List<String> classes(String... names) {
        names.collect { PACKAGE + it + '.java' }
    }

    static Map layout(Map target, Map owner) {
        if (!(owner.buildProfile in ['fabric-modern', 'fabric-upstream'])) return null
        Map result = [main: [java: [], resources: []], test: [java: [], resources: []],
                      gametest: [java: [], resources: []], processedGametest: [java: [], resources: []],
                      copies: [main: [], gametest: [], processedGametest: []],
                      inputs: ['src/main/resources/fabric.mod.json'],
                      legacyGametestApi: owner.dependencies?.clientGametest?.startsWith('2.')]
        List<Map> processed = result.processedGametest.java
        List<String> progress = classes('gametest/mixin/ScenarioProgressMixin',
                'gametest/IrisFixture', 'gametest/IrisImagePixels',
                'gametest/RenderScaleTestAccess', 'gametest/RenderScaleTargetFormat')
        Map irisResources = tree('renderers/modern/src/gametest/resources', ['cbbg-iris-fixture/**'])
        if (owner.buildProfile == 'fabric-upstream') {
            String adapter = 'adapters/fabric/' + owner.minecraft + '/src/'
            result.main.java << tree(adapter + 'main/java')
            result.main.resources << tree('src/main/resources')
            result.test.java << tree(adapter + 'test/java')
            result.test.resources << tree('src/test/resources')
            result.gametest.java << tree(adapter + 'gametest/java')
            result.gametest.resources << tree('src/gametest/resources')
            result.main.java << tree('renderers/modern/src/main/java', classes(
                    'render/Rgba8Capture', 'render/DitherController',
                    'render/GenerationNotifications', 'render/NotificationPlatform'))
            List<String> compatibility = classes('platform/LoaderPlatform', 'compat/sulkan/SulkanCompat',
                    'platform/Text', 'render/MainTargets', 'compat/sodium/CbbgSodiumConfig')
            result.main.java << tree('adapters/fabric/shared/src/main/java', compatibility)
            result.main.java << tree('adapters/fabric/modern/src/main/java', compatibility)
            processed << tree(adapter + 'processedGametest/java')
            result.processedGametest.resources << tree(adapter + 'processedGametest/resources')
        } else {
            boolean textureFormat = target.renderer == 'blaze-texture-format'
            boolean gl3 = target.renderer == 'gl3'
            boolean sharedLifecycle = textureFormat || gl3
            List<String> groups = (["renderers/${target.renderer}", "adapters/minecraft/${owner.minecraft}",
                    "adapters/fabric/${owner.minecraft}"] + (target.sourceGroups ?: [])).unique()
            if (textureFormat) groups << 'adapters/minecraft/blaze-texture-format'
            if (owner.minecraft in ['1.21.1', '1.21.11']) groups << 'adapters/minecraft/gui-graphics'
            for (String group : groups) {
                result.main.java << tree(group + '/src/main/java')
                result.main.resources << tree(group + '/src/main/resources')
                result.gametest.java << tree(group + '/src/gametest/java')
                result.gametest.java << tree(group + '/src/parity/java')
                result.gametest.resources << tree(group + '/src/gametest/resources')
            }
            result.test.java << tree('adapters/fabric/modern/src/test/java')
            result.test.resources << tree('build-config/fabric-modern/src/test/resources')
            result.gametest.java << tree('core/src/testSupport/java')
            result.main.java << tree('adapters/fabric/shared/src/main/java', sharedLifecycle ? [] : classes(
                    'Cbbg', 'platform/LoaderPlatform', 'command/CbbgClientCommands', 'platform/Text',
                    'command/CommandPlatform', 'config/gui/CbbgConfigWidgets', 'config/gui/WidgetPlatform',
                    'compat/iris/IrisCompat', 'compat/renderscale/RenderScaleCompat',
                    'compat/modmenu/CbbgModMenuApi', 'compat/sodium/CbbgSodiumConfig',
                    'render/stbn/STBNGenerator', 'render/stbn/STBNLoader',
                    'render/stbn/StbnImagePixels', 'render/stbn/STBNCache', 'render/MainTargets'),
                    gl3 ? classes('render/MainTargets', 'render/stbn/StbnImagePixels',
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
                result.gametest.java << tree('renderers/modern/src/gametest/java', classes(
                        'gametest/ModMenuGameTest', 'gametest/OptionalModsGameTest',
                        'gametest/RenderScaleTestAccess', 'gametest/IrisFixture') +
                        (gl3 ? [] : classes('gametest/IrisImagePixels', 'gametest/RenderScaleTargetFormat')))
                processed << tree("renderers/${owner.renderer}/src/processedGametest/java", [],
                        ((owner.java as int) < 25 ? classes('gametest/mixin/ReleaseNoiseMixin') : []) +
                                (owner.minecraft == '1.21.11' ? classes('gametest/mixin/ReleaseStartupRenderPassMixin',
                                        'gametest/mixin/ReleaseAllocationFailureMixin') : []))
                processed << tree("adapters/fabric/${owner.minecraft}/src/processedGametest/java")
                processed << tree("adapters/minecraft/${owner.minecraft}/src/processedGametest/java")
                result.processedGametest.resources << tree("renderers/${owner.renderer}/src/processedGametest/resources")
                result.copies.gametest << irisResources
            } else if (target.id == '26.3-fabric') {
                processed << tree('renderers/renderpearl/src/processedGametest/java')
                List<String> shared = classes('gametest/mixin/ScenarioProgressMixin', 'reference/DitherReference',
                        'gametest/FloatPrecisionGameTest', 'gametest/IrisFixture', 'gametest/IrisImagePixels',
                        'gametest/RenderScaleTestAccess',
                        'gametest/RenderScaleTargetFormat',
                        'gametest/EarlyStartupGameTest', 'gametest/mixin/EarlyPipelineCacheMixin',
                        'gametest/mixin/EarlyRenderPassMixin', 'gametest/WindowResizeGameTest')
                processed << tree('renderers/renderpearl/src/gametest/java', shared)
                processed << tree('renderers/modern/src/gametest/java', shared)
                processed << tree('core/src/testSupport/java', shared)
                result.processedGametest.resources << tree('renderers/renderpearl/src/processedGametest/resources')
                result.copies.processedGametest << tree('renderers/renderpearl/src/gametest/resources',
                        ['cbbg-world-goldens/**', 'cbbg.early-startup-test.mixins.json'])
            }
            List<String> resources = ['assets/cbbg/icon.png', 'assets/cbbg/shaders/include/dither.glsl',
                                      'assets/cbbg/lang/*.json']
            if (textureFormat) resources << 'assets/cbbg/shaders/core/*.fsh'
            result.copies.main << tree('src/main/resources', resources)
        }
        if (owner.buildProfile == 'fabric-upstream' || owner.renderer in ['blaze-texture-format', 'gl3']) {
            if (owner.renderer == 'gl3') {
                processed << tree('renderers/modern/src/gametest/java',
                        classes('gametest/RenderScaleTestAccess', 'gametest/IrisFixture'))
                processed << tree('renderers/gl3/src/gametest/java',
                        classes('gametest/RenderScaleTargetFormat', 'gametest/IrisImagePixels'))
            } else {
                processed << tree('renderers/renderpearl/src/gametest/java', progress)
                processed << tree('renderers/modern/src/gametest/java', progress)
            }
            processed << tree('core/src/testSupport/java', classes('reference/DitherReference'))
            processed << tree('adapters/fabric/shared/src/processedGametest/java',
                    owner.renderer == 'gl3' ? classes('gametest/ReleaseCacheGameTest',
                            'gametest/ReleaseSettingsGuiGameTest', 'gametest/ReleaseUtilitiesGameTest',
                            'gametest/ReleaseSodiumConfigGameTest', 'gametest/ReleaseControlsGameTest',
                            'gametest/ReleaseModMenuGameTest', 'gametest/ReleaseIrisGameTest',
                            'gametest/ReleaseGenerationGameTest', 'gametest/ReleaseNotificationsGameTest',
                            'gametest/ReleaseEarlyStartupGameTest', 'gametest/ReleaseStartupPreLaunch',
                            'gametest/ReleaseStartupObservations', 'gametest/ReleaseShutdownGameTest',
                            'gametest/ReleaseGeneratingShutdownGameTest', 'gametest/mixin/ReleaseShutdownMixin',
                            'gametest/ReleaseWorldPixelsGameTest', 'gametest/ReleaseTransparencyGameTest',
                            'gametest/ReleaseRenderScaleGameTest', 'gametest/ReleaseCommands',
                            'gametest/ReleaseGraphics', 'gametest/ReleaseGameNames',
                            'gametest/ReleaseMapping', 'gametest/ReleaseMaximumNoiseCacheGameTest') : [],
                    (owner.renderer in ['gl3', 'blaze-texture-format'] ? [] :
                            classes('gametest/ReleaseModMenuGameTest', 'gametest/ReleaseIrisGameTest')) +
                            (owner.minecraft == '1.21.11' ? classes('gametest/ReleaseGuiCharacters') : []))
            result.copies.processedGametest << tree('adapters/fabric/shared/src/processedGametest/resources', [],
                    owner.renderer == 'gl3' ? ['cbbg.release-allocation.mixins.json',
                                               'cbbg.release-startup.mixins.json',
                                               'cbbg.release-shutdown.mixins.json'] : [])
        }
        result.copies.processedGametest << irisResources
        if (owner.renderer == 'renderpearl') {
            result.main.java << tree('adapters/minecraft/26.1/src/main/java',
                    classes('config/gui/ConfigScreenPlatform', 'render/HudPlatform'))
            processed << tree('adapters/fabric/shared/src/processedGametest/java',
                    classes('gametest/ReleaseSodiumConfigGameTest', 'gametest/ReleaseUtilitiesGameTest',
                            'gametest/ReleaseUtilityCalls', 'gametest/ReleaseImagePixels',
                            'gametest/ReleaseSettingsGuiGameTest', 'gametest/ReleaseGuiInput',
                            'gametest/ReleaseMapping', 'gametest/ReleaseGuiCharacters',
                            'gametest/ReleaseGraphics'))
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
                if (spec.includes) tree.include(spec.includes)
                if (spec.excludes) tree.exclude(spec.excludes)
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
                if (spec.includes) include(spec.includes)
                if (spec.excludes) exclude(spec.excludes)
            })
        }
    }

    static List<Map> inputs(Map layout) {
        ['main', 'test', 'gametest', 'processedGametest'].collectMany { name ->
            layout[name].java + layout[name].resources + (layout.copies[name] ?: [])
        } + layout.inputs.collect { [path: it, file: true] }
    }

    static boolean contains(Map spec, String path) {
        if (spec.file) return path == spec.path
        if (!path.startsWith(spec.path + '/')) return false
        String relative = path.substring(spec.path.length() + 1)
        (!spec.includes || spec.includes.any { SelectorUtils.matchPath(it as String, relative, true) }) &&
                !spec.excludes.any { SelectorUtils.matchPath(it as String, relative, true) }
    }
}
