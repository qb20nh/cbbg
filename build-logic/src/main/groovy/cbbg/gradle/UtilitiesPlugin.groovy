package cbbg.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.JavaVersion
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import proguard.gradle.ProGuardTask

/** Java 8 utilities with raw development and optimized release artifacts. */
class UtilitiesPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.pluginManager.apply('java-library')
        project.pluginManager.apply('maven-publish')
        project.pluginManager.apply('cbbg.release-sbom')
        project.group = 'com.qb20nh'
        project.version = project.providers.gradleProperty('library_version').getOrElse('1.0.0')
        String name = "cbbg-utilities-${project.version}"
        File license = new File(project.projectDir, '../../LICENSE')
        project.java {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
            withSourcesJar()
        }
        project.tasks.withType(org.gradle.api.tasks.compile.JavaCompile).configureEach { options.release = 8 }
        def raw = project.tasks.named('jar') {
            archiveFileName = "${name}-raw.jar"
            from(license)
        }
        project.tasks.register('rawUtilitiesJar') { dependsOn raw }
        def output = project.layout.buildDirectory.file("libs/${name}.jar")
        def mapping = project.layout.buildDirectory.file("mapping/${name}.map")
        def toolchains = project.extensions.getByType(JavaToolchainService)
        def java8 = toolchains.launcherFor { languageVersion = JavaLanguageVersion.of(8) }
        def jdkLibraries = JdkLibraries.select(project, java8.map { it.metadata.installationPath },
                'exportUtilitiesJdkLibraries', 'intermediates/utilities/jdk-runtime.jar')
        def optimized = project.tasks.register('optimizeUtilitiesJar', ProGuardTask) {
            group = 'build'
            description = 'Build the optimized standalone Java 8 noise and CPU dithering library.'
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
        project.tasks.named('sourcesJar') { archiveFileName = "${name}-raw-sources.jar" }
        def sources = project.tasks.register('utilitiesSourcesJar', Jar) {
            dependsOn optimized, project.tasks.named('releaseSbom')
            archiveFileName = "${name}-sources.jar"
            from(project.sourceSets.main.allSource)
            from(mapping) { into 'META-INF/cbbg'; rename { 'proguard.map' } }
            from(project.releaseSbomFile) { into 'META-INF/cbbg'; rename { 'sbom.cdx.json' } }
            from(license)
        }
        project.tasks.register('releaseSourcesJar') { dependsOn sources }
        project.tasks.register('optimizedReleaseJar') { dependsOn optimized }
        project.tasks.named('assemble') { dependsOn optimized }
        project.configurations.create('releaseElements') {
            canBeConsumed = true
            canBeResolved = false
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage, Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, project.objects.named(Category, Category.LIBRARY))
                attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, project.objects.named(LibraryElements, LibraryElements.JAR))
                attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling, Bundling.EXTERNAL))
                attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 8)
            }
            outgoing.capability("com.qb20nh:cbbg-utilities-release:${project.version}")
            outgoing.artifact(output) { builtBy optimized }
        }
        project.publishing.publications.create('utilities', MavenPublication) {
            artifactId = 'cbbg-utilities'
            artifact(output) { builtBy optimized }
            artifact(sources)
        }
    }
}
