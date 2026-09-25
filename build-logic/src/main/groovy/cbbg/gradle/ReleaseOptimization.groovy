package cbbg.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.GradleException
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

    @Inject ReleaseOptimization(ObjectFactory objects) {
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
        jdkLibraries.from(toolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(javaVersion)
        }.map { launcher ->
            File home = launcher.metadata.installationPath.asFile
            File jmods = new File(home, 'jmods')
            if (jmods.isDirectory()) {
                return jmods.listFiles().findAll { it.name.endsWith('.jmod') }.sort()
            }
            File rtJar = new File(home, 'jre/lib/rt.jar')
            if (!rtJar.isFile()) rtJar = new File(home, 'lib/rt.jar')
            if (!rtJar.isFile()) throw new GradleException('Missing Java runtime libraries for ' + home)
            [rtJar]
        })
    }
}
