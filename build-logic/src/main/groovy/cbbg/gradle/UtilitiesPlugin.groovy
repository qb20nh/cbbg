package cbbg.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import proguard.gradle.ProGuardTask

/** Standalone Java 8 API, built from the same sources embedded in the mod. */
class UtilitiesPlugin implements Plugin<Project> {
    void apply(Project project) {
        def core = project
        def rendering = project.project("${project.path == ':' ? '' : project.path}:rendering")
        File root = core.projectDir.parentFile
        def properties = new Properties()
        new File(root, 'gradle.properties').withInputStream { properties.load(it) }
        String version = project.providers.gradleProperty('mod_version').getOrElse(properties.mod_version as String)
        String name = "cbbg-utilities-${version}"
        File shader = new File(root, 'src/main/resources/assets/cbbg/shaders/include/dither.glsl')
        File license = new File(root, 'LICENSE')
        def raw = project.tasks.register('rawUtilitiesJar', Jar) {
            archiveFileName = "${name}-raw.jar"
            destinationDirectory = project.layout.buildDirectory.dir('intermediates/utilities')
            from(core.sourceSets.main.output) { include 'com/qb20nh/cbbg/math/MiniFFT*.class' }
            from(rendering.sourceSets.main.output) {
                include 'com/qb20nh/cbbg/api/**', 'com/qb20nh/cbbg/math/BlueNoise*.class'
            }
            from(shader) { into 'com/qb20nh/cbbg/api/shaders' }
            from(license)
        }
        def output = project.layout.buildDirectory.file("libs/${name}.jar")
        def mapping = project.layout.buildDirectory.file("mapping/${name}.map")
        def toolchains = project.extensions.getByType(JavaToolchainService)
        def java8 = toolchains.launcherFor { languageVersion = JavaLanguageVersion.of(8) }
        def jdkLibraries = JdkLibraries.select(project, java8.map { it.metadata.installationPath },
                'exportUtilitiesJdkLibraries', 'intermediates/utilities/jdk-runtime.jar')
        def optimized = project.tasks.register('optimizeUtilitiesJar', ProGuardTask) {
            group = 'build'
            description = 'Build the standalone Java 8 noise and CPU dithering library.'
            dependsOn raw
            inputs.file(raw.flatMap { it.archiveFile })
            inputs.files(jdkLibraries)
            inputs.files(project.configurations.compileClasspath)
            outputs.file(output)
            outputs.file(mapping)
            doFirst {
                output.get().asFile.parentFile.mkdirs()
                mapping.get().asFile.parentFile.mkdirs()
                injars(raw.get().archiveFile.get().asFile)
                outjars(output.get().asFile)
                jdkLibraries.get().files.each { library ->
                    if (library.name.endsWith('.jmod')) {
                        libraryjars(library, jarfilter: '!**.jar', filter: '!module-info.class')
                    } else {
                        libraryjars(library)
                    }
                }
                project.configurations.compileClasspath.files
                        .findAll { it.name.startsWith('jspecify-') }.each { libraryjars(it) }
                keep 'class com.qb20nh.cbbg.api.** { public protected *; }'
                keepattributes '*'
                printmapping mapping.get().asFile
            }
            doLast { ReproducibleJar.normalize(output.get().asFile) }
        }
        project.tasks.register('utilitiesSourcesJar', Jar) {
            group = 'build'
            dependsOn optimized
            archiveFileName = "${name}-sources.jar"
            destinationDirectory = project.layout.buildDirectory.dir('libs')
            from(core.sourceSets.main.allSource) { include 'com/qb20nh/cbbg/math/MiniFFT.java' }
            from(rendering.sourceSets.main.allSource) {
                include 'com/qb20nh/cbbg/api/**', 'com/qb20nh/cbbg/math/BlueNoise.java'
            }
            from(shader) { into 'com/qb20nh/cbbg/api/shaders' }
            from(mapping) { into 'META-INF/cbbg'; rename { 'proguard.map' } }
            from(license)
        }
    }
}
