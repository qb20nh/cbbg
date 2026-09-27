package cbbg.gradle

import com.diffplug.gradle.spotless.SpotlessPlugin
import net.ltgt.gradle.errorprone.ErrorPronePlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.process.CommandLineArgumentProvider

class QualityPlugin implements Plugin<Project> {
    static final String PMD_VERSION = '7.28.0'
    static final String ERROR_PRONE_VERSION = '2.50.0'
    static final String NULLAWAY_VERSION = '0.14.2'
    static final String JSPECIFY_VERSION = '1.0.0'
    static final String MIXIN_METHOD_ANNOTATIONS = [
            'org.spongepowered.asm.mixin.Shadow',
            'org.spongepowered.asm.mixin.injection.Inject',
            'org.spongepowered.asm.mixin.injection.Redirect',
            'org.spongepowered.asm.mixin.injection.ModifyArg',
            'org.spongepowered.asm.mixin.injection.ModifyVariable',
            'com.llamalad7.mixinextras.injector.ModifyExpressionValue',
            'com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod'
    ].join(',')
    static final List<String> RULES = [
            'BrokenNullCheck', 'ClassCastExceptionWithToArray', 'CheckSkipResult',
            'DontUseFloatTypeForLoopIndices', 'EqualsNull', 'MisplacedNullCheck',
            'ReturnFromFinallyBlock', 'UnconditionalIfStatement'
    ].collect { "category/java/errorprone.xml/${it}" } + [
            'UnusedLocalVariable'
    ].collect { "category/java/bestpractices.xml/${it}" } + [
            'category/java/codestyle.xml/UnnecessaryImport'
    ]

    void apply(Project project) {
        project.pluginManager.withPlugin('java') {
            configureJava(project)
        }
    }

    private static void configureJava(Project project) {
        project.pluginManager.apply('pmd')
        project.pluginManager.apply(ErrorPronePlugin)
        def javaExtension = project.extensions.getByType(JavaPluginExtension)
        javaExtension.toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        def sources = project.extensions.getByType(SourceSetContainer)
        def quality = project.tasks.register('qualityCheck') {
            group = 'verification'
            description = 'Check PMD, duplication, Error Prone and nullness.'
        }
        project.plugins.withType(SpotlessPlugin) {
            quality.configure { dependsOn project.tasks.named('spotlessCheck') }
        }
        project.tasks.named('check') { dependsOn quality }
        project.tasks.matching { it.name == 'ciCheck' }.configureEach { dependsOn quality }
        project.dependencies.add('errorprone', "com.google.errorprone:error_prone_core:${ERROR_PRONE_VERSION}")
        project.dependencies.add('errorprone', "com.uber.nullaway:nullaway:${NULLAWAY_VERSION}")
        project.dependencies.add('errorprone', project.files(modelClasspath()))
        def cpd = project.configurations.create('cpdTool') {
            canBeConsumed = false
            canBeResolved = true
        }
        project.dependencies.add(cpd.name, "net.sourceforge.pmd:pmd-cli:${PMD_VERSION}")
        project.dependencies.add(cpd.name, "net.sourceforge.pmd:pmd-java:${PMD_VERSION}")
        project.extensions.configure(PmdExtension) { pmd ->
            pmd.toolVersion = PMD_VERSION
            pmd.ruleSets = RULES
            pmd.ignoreFailures = false
        }
        project.tasks.withType(Pmd).configureEach {
            reports {
                xml.required = true
                html.required = true
            }
        }
        sources.configureEach { sourceSet ->
            project.dependencies.add(sourceSet.compileOnlyConfigurationName,
                    "org.jspecify:jspecify:${JSPECIFY_VERSION}")
            def duplicateCheck = project.tasks.register(sourceSet.getTaskName('cpd', null), CpdCheck) {
                source project.provider {
                    def files = sourceSet.allJava
                    if (sourceSet.name == 'main' && project.extensions.extraProperties.has('sharedProjects')) {
                        files = project.files(files, project.sharedProjects.collect { it.sourceSets.main.allJava })
                    }
                    files
                }
                toolClasspath.from(cpd)
                javaLauncher.set(project.javaToolchains.launcherFor {
                    languageVersion = JavaLanguageVersion.of(25)
                })
                minimumTokens.set(100)
                reportFile.set(project.layout.buildDirectory.file("reports/cpd/${sourceSet.name}.xml"))
            }
            quality.configure {
                dependsOn duplicateCheck, project.tasks.named(sourceSet.compileJavaTaskName),
                        project.tasks.named(sourceSet.getTaskName('pmd', null))
            }
        }
        project.tasks.withType(JavaCompile).configureEach { compile ->
            javaCompiler.set(project.javaToolchains.compilerFor {
                languageVersion = JavaLanguageVersion.of(25)
            })
            options.compilerArgs.add('-Xlint:-options')
            options.compilerArgumentProviders.add({ ->
                // JSpecify's MODULE annotation target is absent from Java 8's API.
                compile.options.release.orNull == 8 ? [] : ['-Werror']
            } as CommandLineArgumentProvider)
            options.errorprone {
                error('NullAway', 'RequireExplicitNullMarking')
                // Mixin supplies these methods and requires their signatures.
                option('UnusedMethod:ExemptingMethodAnnotations', MIXIN_METHOD_ANNOTATIONS)
                option('Unused:methodAnnotationsExemptingParameters', MIXIN_METHOD_ANNOTATIONS)
                option('NullAway:OnlyNullMarked', true)
                option('NullAway:JSpecifyMode', true)
                option('NullAway:JSpecifyJDKModels', true)
                option('NullAway:ExcludedFieldAnnotations',
                        'org.spongepowered.asm.mixin.Shadow,org.junit.jupiter.api.io.TempDir')
            }
        }
    }

    private static Set<File> modelClasspath() {
        ['cbbg/gradle/MinecraftLibraryModels.class',
         'META-INF/services/com.uber.nullaway.LibraryModels'].collect { path ->
            URL resource = QualityPlugin.class.classLoader.getResource(path)
            if (resource.protocol == 'jar') {
                return new File(resource.openConnection().jarFileURL.toURI())
            }
            File entry = new File(resource.toURI())
            path.split('/').each { entry = entry.parentFile }
            entry
        }.toSet()
    }
}
