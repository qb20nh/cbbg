package cbbg.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exports target runtime-image classes solely for ProGuard library analysis. */
abstract class ExportJdkLibraries extends DefaultTask {
    @Internal abstract DirectoryProperty getRuntimeHome()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getModulesFile()
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getFileSystemJar()
    @OutputFile abstract RegularFileProperty getOutputJar()

    @TaskAction void exportClasses() {
        File output = outputJar.get().asFile
        output.parentFile.mkdirs()
        File temporary = new File(output.parentFile, output.name + '.tmp')
        try {
            FileSystems.newFileSystem(URI.create('jrt:/'),
                    ['java.home': runtimeHome.get().asFile.absolutePath]).withCloseable { image ->
                def root = image.getPath('/modules')
                def classes = new ArrayList<>(Files.walk(root).withCloseable { paths ->
                    paths.filter { Files.isRegularFile(it) && it.toString().endsWith('.class') &&
                            it.fileName.toString() != 'module-info.class' }.toList()
                })
                if (classes.isEmpty()) throw new GradleException('Target runtime image contains no classes')
                classes.sort { path ->
                    def relative = root.relativize(path)
                    relative.subpath(1, relative.nameCount).toString()
                }
                Set<String> names = new HashSet<>()
                new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temporary))).withCloseable { jar ->
                    classes.each { path ->
                        def relative = root.relativize(path)
                        String name = relative.subpath(1, relative.nameCount).toString()
                        if (!names.add(name)) throw new GradleException('Duplicate runtime class: ' + name)
                        ZipEntry entry = new ZipEntry(name)
                        entry.time = 0L
                        jar.putNextEntry(entry)
                        Files.copy(path, jar)
                        jar.closeEntry()
                    }
                }
            }
            Files.move(temporary.toPath(), output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}
