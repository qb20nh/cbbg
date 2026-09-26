package cbbg.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

import javax.inject.Inject

class ReleaseOptimization {
    final RegularFileProperty inputJar
    final RegularFileProperty outputJar
    final RegularFileProperty mappingFile
    final RegularFileProperty rulesFile
    final RegularFileProperty usageFile
    final RegularFileProperty configurationFile
    final ConfigurableFileCollection libraryJars
    final ConfigurableFileCollection jdkLibraries
    private final Project project

    @Inject ReleaseOptimization(ObjectFactory objects, Project project) {
        this.project = project
        inputJar = objects.fileProperty()
        outputJar = objects.fileProperty()
        mappingFile = objects.fileProperty()
        rulesFile = objects.fileProperty()
        usageFile = objects.fileProperty()
        configurationFile = objects.fileProperty()
        libraryJars = objects.fileCollection()
        jdkLibraries = objects.fileCollection()
    }

    void targetJdk(JavaToolchainService toolchains, int javaVersion) {
        targetJdk(toolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(javaVersion)
        }.map { it.metadata.installationPath })
    }

    void targetJdk(Provider<Directory> targetHome) {
        Project owner = project
        def runtimeJar = owner.tasks.register('exportReleaseJdkLibraries', ExportJdkLibraries) {
            runtimeHome.set(targetHome)
            modulesFile.set(targetHome.map { it.file('lib/modules') })
            fileSystemJar.set(targetHome.map { it.file('lib/jrt-fs.jar') })
            outputJar.set(owner.layout.buildDirectory.file('intermediates/proguard/jdk-runtime.jar'))
        }
        jdkLibraries.from(targetHome.map { installation ->
            File home = installation.asFile
            File jmods = new File(home, 'jmods')
            if (jmods.isDirectory()) {
                def modules = jmods.listFiles().findAll { it.name.endsWith('.jmod') }.sort()
                if (!modules.isEmpty()) return owner.files(modules)
            }
            File rtJar = new File(home, 'jre/lib/rt.jar')
            if (!rtJar.isFile()) rtJar = new File(home, 'lib/rt.jar')
            if (rtJar.isFile()) return owner.files(rtJar)
            if (new File(home, 'lib/modules').isFile() && new File(home, 'lib/jrt-fs.jar').isFile()) {
                return owner.files(runtimeJar.flatMap { it.outputJar })
            }
            throw new GradleException('Missing Java runtime libraries for ' + home)
        })
    }
}
