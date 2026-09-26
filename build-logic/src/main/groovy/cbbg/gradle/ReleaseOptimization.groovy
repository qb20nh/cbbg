package cbbg.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
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
        jdkLibraries.from(JdkLibraries.select(project, targetHome,
                'exportReleaseJdkLibraries', 'intermediates/proguard/jdk-runtime.jar'))
    }
}
