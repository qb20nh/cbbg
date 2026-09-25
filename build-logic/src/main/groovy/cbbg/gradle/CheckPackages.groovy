package cbbg.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

abstract class CheckPackages extends DefaultTask {
    @InputFile abstract RegularFileProperty getArtifact()
    @InputFile abstract RegularFileProperty getSources()
    @Optional @InputFile abstract RegularFileProperty getMapping()
    @InputDirectory abstract DirectoryProperty getCoreSources()
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getSharedSources()
    @Input abstract Property<Integer> getJavaVersion()

    @TaskAction void verify() {
        PackageChecks.verifyArtifact(artifact.get().asFile, sources.get().asFile,
                javaVersion.get(), ([coreSources.get().asFile] + sharedSources.files).unique(),
                mapping.orNull?.asFile)
    }
}
