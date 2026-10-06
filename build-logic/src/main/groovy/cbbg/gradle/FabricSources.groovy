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
                      inputs: ['src/main/resources/fabric.mod.json']]
        List<Map> processed = result.processedGametest.java
        List<String> progress = classes('gametest/mixin/ScenarioProgressMixin',
                'gametest/IrisFixture', 'gametest/RenderScaleTestAccess')
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
            List<String> groups = (["renderers/${target.renderer}", "adapters/minecraft/${owner.minecraft}",
                    "adapters/fabric/${owner.minecraft}"] + (target.sourceGroups ?: [])).unique()
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
            result.main.java << tree('adapters/fabric/shared/src/main/java', textureFormat ? [] : classes(
                    'Cbbg', 'platform/LoaderPlatform', 'command/CbbgClientCommands', 'platform/Text',
                    'command/CommandPlatform', 'config/gui/CbbgConfigWidgets', 'config/gui/WidgetPlatform',
                    'compat/iris/IrisCompat', 'compat/renderscale/RenderScaleCompat',
                    'compat/modmenu/CbbgModMenuApi', 'compat/sodium/CbbgSodiumConfig',
                    'render/stbn/STBNGenerator', 'render/stbn/STBNLoader',
                    'render/stbn/StbnImagePixels', 'render/stbn/STBNCache', 'render/MainTargets'))
            result.gametest.java << tree('adapters/fabric/shared/src/gametest/java',
                    textureFormat ? [] : classes('gametest/CbbgConfigScreenGameTest'),
                    textureFormat ? classes('gametest/ClientTestAccess') : [])
            if (textureFormat) {
                result.main.java << tree('adapters/fabric/modern/src/main/java', classes(
                        'CbbgClient', 'CbbgEarlyInit', 'CbbgLanguageAdapter'))
                result.main.java << tree('renderers/modern/src/main/java', classes(
                        'render/GenerationNotifications', 'render/DitherController', 'config/gui/CbbgConfigScreen'))
                result.gametest.java << tree('renderers/modern/src/gametest/java', classes(
                        'gametest/ModMenuGameTest', 'gametest/IrisFixture', 'gametest/RenderScaleTestAccess',
                        'gametest/OptionalModsGameTest'))
                processed << tree("renderers/${owner.renderer}/src/processedGametest/java")
                processed << tree("adapters/fabric/${owner.minecraft}/src/processedGametest/java")
                result.processedGametest.resources << tree("renderers/${owner.renderer}/src/processedGametest/resources")
                result.copies.gametest << irisResources
            } else if (target.id == '26.3-fabric') {
                processed << tree('renderers/renderpearl/src/processedGametest/java')
                List<String> shared = classes('gametest/mixin/ScenarioProgressMixin', 'reference/DitherReference',
                        'gametest/FloatPrecisionGameTest', 'gametest/IrisFixture', 'gametest/RenderScaleTestAccess',
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
        if (owner.buildProfile == 'fabric-upstream' || owner.renderer == 'blaze-texture-format') {
            processed << tree('renderers/renderpearl/src/gametest/java', progress)
            processed << tree('renderers/modern/src/gametest/java', progress)
            processed << tree('core/src/testSupport/java', classes('reference/DitherReference'))
            processed << tree('adapters/fabric/shared/src/processedGametest/java')
            result.copies.processedGametest << tree('adapters/fabric/shared/src/processedGametest/resources')
        }
        result.copies.processedGametest << irisResources
        if (owner.renderer == 'renderpearl') {
            processed << tree('adapters/fabric/shared/src/processedGametest/java',
                    classes('gametest/ReleaseSodiumConfigGameTest', 'gametest/ReleaseUtilitiesGameTest'))
        }
        result
    }

    static void configure(Project project, File root, Map layout, String name) {
        def sourceSet = project.extensions.getByName('sourceSets').getByName(name)
        for (String kind : ['java', 'resources']) {
            def sources = sourceSet."$kind"
            sources.setSrcDirs([])
            layout[name][kind].eachWithIndex { Map spec, int index ->
                def tree = project.objects.sourceDirectorySet("${name}${kind}${index}", spec.path as String)
                tree.srcDir(new File(root, spec.path as String))
                if (spec.includes) tree.include(spec.includes)
                if (spec.excludes) tree.exclude(spec.excludes)
                sources.source(tree)
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
