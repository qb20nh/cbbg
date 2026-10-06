package cbbg.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import proguard.gradle.ProGuardTask

class PackagingPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.pluginManager.apply('cbbg.release-sbom')
        def optimization = project.extensions.create('releaseOptimization', ReleaseOptimization, project.objects, project)
        optimization.usageFile.convention(project.layout.buildDirectory.file('reports/proguard/usage.txt'))
        optimization.configurationFile.convention(project.layout.buildDirectory.file('reports/proguard/configuration.txt'))
        def minified = project.tasks.register('minifyReleaseJar', ProGuardTask) {
            group = 'build'
            description = 'Shrink, optimize, and obfuscate the release jar with ProGuard.'
            inputs.file(optimization.inputJar)
            inputs.file(optimization.rulesFile)
            inputs.files(optimization.libraryJars)
            inputs.files(optimization.jdkLibraries)
            outputs.file(optimization.optimizedJar)
            outputs.file(optimization.mappingFile)
            outputs.file(optimization.usageFile)
            outputs.file(optimization.configurationFile)
            doFirst {
                File output = optimization.optimizedJar.get().asFile
                File mapping = optimization.mappingFile.get().asFile
                File usage = optimization.usageFile.get().asFile
                File configurationDump = optimization.configurationFile.get().asFile
                output.parentFile.mkdirs()
                mapping.parentFile.mkdirs()
                usage.parentFile.mkdirs()
                configurationDump.parentFile.mkdirs()
                injars(optimization.inputJar.get().asFile)
                outjars(output)
                optimization.libraryJars.files.each { libraryjars(it) }
                if (optimization.jdkLibraries.files.isEmpty()) {
                    throw new org.gradle.api.GradleException('Release optimization requires target JDK libraries')
                }
                optimization.jdkLibraries.files.sort().each { library ->
                    if (library.name.endsWith('.jmod')) {
                        libraryjars(library, jarfilter: '!**.jar', filter: '!module-info.class')
                    } else {
                        libraryjars(library)
                    }
                }
                configuration(optimization.rulesFile.get().asFile)
                printmapping(mapping)
                printusage(usage)
                printconfiguration(configurationDump)
            }
            doLast { ReproducibleJar.normalize(optimization.optimizedJar.get().asFile) }
        }
        project.tasks.register('optimizeReleaseJar') {
            group = 'build'
            description = 'Build the optimized release jar in its runtime namespace.'
            dependsOn minified
            inputs.file(optimization.outputJar)
            doLast {
                if (!optimization.outputJar.get().asFile.isFile()) {
                    throw new org.gradle.api.GradleException('Optimized release jar is missing')
                }
            }
        }
        project.afterEvaluate {
            if (optimization.mappingFile.isPresent()) {
                def sources = project.tasks.named('sourcesJar', Jar)
                sources.configure { destinationDirectory = project.layout.buildDirectory.dir('intermediates/source-jar') }
                def releaseSources = project.tasks.register('releaseSourcesJar', Jar) {
                    group = 'build'
                    description = 'Package release sources with their exact ProGuard mapping.'
                    dependsOn sources, project.tasks.named('optimizeReleaseJar'), project.tasks.named('releaseSbom')
                    archiveFileName.set(sources.flatMap { it.archiveFileName })
                    destinationDirectory.set(project.layout.buildDirectory.dir('libs'))
                    from({ project.zipTree(sources.get().archiveFile.get().asFile) })
                    from(project.releaseSbomFile) {
                        into 'META-INF/cbbg'
                        rename { 'sbom.cdx.json' }
                    }
                    from(optimization.mappingFile) {
                        into 'META-INF/cbbg'
                        rename { 'proguard.map' }
                    }
                }
                project.tasks.named('assemble') { dependsOn releaseSources }
                project.configurations.named('sourcesElements') {
                    outgoing.artifacts.clear()
                    outgoing.artifact(releaseSources)
                }
            }
        }
        project.tasks.register('checkPackages', CheckPackages) {
            group = 'verification'
            description = 'Check production bytecode and embedded core sources.'
        }
    }
}
