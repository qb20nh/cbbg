package cbbg.gradle

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ReproducibleJar {
    static void normalize(File jar) {
        File temporary = new File(jar.parentFile, jar.name + '.normalized')
        try {
            new ZipFile(jar).withCloseable { input ->
                new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temporary))).withCloseable { output ->
                    input.entries().toList().sort { it.name }.each { entry ->
                        ZipEntry copy = new ZipEntry(entry.name)
                        copy.time = 0L
                        output.putNextEntry(copy)
                        if (!entry.directory) {
                            input.getInputStream(entry).withCloseable { it.transferTo(output) }
                        }
                        output.closeEntry()
                    }
                }
            }
            Files.move(temporary.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}
