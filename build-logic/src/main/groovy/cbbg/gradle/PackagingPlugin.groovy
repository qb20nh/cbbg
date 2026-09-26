package cbbg.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import proguard.gradle.ProGuardTask

class PackagingPlugin implements Plugin<Project> {
    void apply(Project project) {
        def optimization = project.extensions.create('releaseOptimization', ReleaseOptimization, project.objects, project)
        optimization.usageFile.convention(project.layout.buildDirectory.file('reports/proguard/usage.txt'))
        optimization.configurationFile.convention(project.layout.buildDirectory.file('reports/proguard/configuration.txt'))
        project.tasks.register('optimizeReleaseJar', ProGuardTask) {
            group = 'build'
            description = 'Shrink, optimize, and obfuscate the release jar with ProGuard.'
            inputs.file(optimization.inputJar)
            inputs.file(optimization.rulesFile)
            inputs.files(optimization.libraryJars)
            inputs.files(optimization.jdkLibraries)
            outputs.file(optimization.outputJar)
            outputs.file(optimization.mappingFile)
            outputs.file(optimization.usageFile)
            outputs.file(optimization.configurationFile)
            doFirst {
                File output = optimization.outputJar.get().asFile
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
            doLast { ReproducibleJar.normalize(optimization.outputJar.get().asFile) }
        }
        project.tasks.register('checkPackages', CheckPackages) {
            group = 'verification'
            description = 'Check production bytecode and embedded core sources.'
        }
    }
}
