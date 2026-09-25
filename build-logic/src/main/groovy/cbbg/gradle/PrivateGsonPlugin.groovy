package cbbg.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import proguard.gradle.ProGuardTask

import javax.inject.Inject

class PrivateGsonPlugin implements Plugin<Project> {
    private final JavaToolchainService toolchains

    @Inject PrivateGsonPlugin(JavaToolchainService toolchains) { this.toolchains = toolchains }

    void apply(Project project) {
        def gson = project.configurations.create('privateGsonInput') { transitive = false }
        def gsonSources = project.configurations.create('privateGsonSources') { transitive = false }
        project.dependencies.add(gson.name, 'com.google.code.gson:gson:2.2.4')
        project.dependencies.add(gsonSources.name, 'com.google.code.gson:gson:2.2.4:sources@jar')

        def rules = new File(project.projectDir.parentFile, 'build-config/private-gson.pro')
        def mapping = new File(project.projectDir.parentFile, 'build-config/private-gson.map')
        def output = project.layout.buildDirectory.file('private-gson/gson-stream.jar')
        def fullMapping = project.layout.buildDirectory.file('private-gson/mapping.txt')
        def javaBase = toolchains.launcherFor { spec ->
            spec.languageVersion.set(JavaLanguageVersion.of(25))
        }.map { launcher -> new File(launcher.metadata.installationPath.asFile, 'jmods/java.base.jmod') }
        def javaSql = toolchains.launcherFor { spec ->
            spec.languageVersion.set(JavaLanguageVersion.of(25))
        }.map { launcher -> new File(launcher.metadata.installationPath.asFile, 'jmods/java.sql.jmod') }
        def task = project.tasks.register('privateGsonJar', ProGuardTask) {
            group = 'build'
            description = 'Shrink and relocate the Gson streaming API before compiling core.'
            inputs.files(gson)
            inputs.file(rules)
            inputs.file(mapping)
            inputs.file(javaBase)
            inputs.file(javaSql)
            outputs.file(output)
            outputs.file(fullMapping)
            doFirst {
                File archive = output.get().asFile
                archive.parentFile.mkdirs()
                injars(gson.singleFile)
                outjars(archive)
                libraryjars(javaBase.get(), jarfilter: '!**.jar', filter: '!module-info.class')
                libraryjars(javaSql.get(), jarfilter: '!**.jar', filter: '!module-info.class')
                configuration(rules)
                applymapping(mapping)
                printmapping(fullMapping.get().asFile)
            }
            doLast { ReproducibleJar.normalize(output.get().asFile) }
        }
        def classes = project.files(output).builtBy(task)
        project.dependencies.add('compileOnly', classes)
        project.dependencies.add('runtimeOnly', classes)
        project.dependencies.add('testImplementation', classes)
        project.extensions.extraProperties.set('privateGsonArchive', output)
        project.extensions.extraProperties.set('privateGsonMapping', fullMapping)
        project.extensions.extraProperties.set('privateGsonSourceArchive', gsonSources)
    }
}
