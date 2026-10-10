package cbbg.gradle

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import com.jcraft.jzlib.Deflater
import com.jcraft.jzlib.DeflaterOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream

import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.ZipFile

class ReproducibleJar {
    static void normalize(File jar) {
        File temporary = new File(jar.parentFile, jar.name + '.normalized')
        try {
            new ZipFile(jar).withCloseable { input ->
                def entries = input.entries().toList().sort { it.name }
                if (entries*.name.toSet().size() != entries.size()) {
                    throw new IOException('Duplicate JAR entries: ' + jar)
                }
                new ZipArchiveOutputStream(temporary).withCloseable { output ->
                    output.setEncoding('UTF-8')
                    output.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER)
                    entries.each { entry ->
                        byte[] bytes = input.getInputStream(entry).withCloseable { it.readAllBytes() }
                        def compressed = new ByteArrayOutputStream()
                        def deflater = new Deflater(9, true)
                        try {
                            new DeflaterOutputStream(compressed, deflater).withCloseable { it.write(bytes) }
                        } finally {
                            deflater.end()
                        }
                        def crc = new CRC32()
                        crc.update(bytes)
                        def copy = new ZipArchiveEntry(entry.name)
                        copy.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
                        copy.setUnixMode(entry.directory ? 040755 : 0100644)
                        copy.method = ZipArchiveEntry.DEFLATED
                        copy.size = bytes.length
                        copy.compressedSize = compressed.size()
                        copy.crc = crc.value
                        output.addRawArchiveEntry(copy, new ByteArrayInputStream(compressed.toByteArray()))
                    }
                }
            }
            Files.move(temporary.toPath(), jar.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}
