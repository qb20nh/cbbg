package cbbg.gradle

import org.gradle.api.GradleException
import proguard.retrace.ReTrace
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class RetraceLog {
    static void translate(File manifestFile, String targetId, File crash, File output) {
        Map manifest = CandidateFiles.read(manifestFile)
        CandidateFiles.releaseIdentity(manifest.release as String, manifest.commit as String)
        if (!(manifest.schema instanceof Integer) || manifest.schema != 3 || !(manifest.targets instanceof List)) {
            throw new GradleException('Crash decoding requires a processed release manifest')
        }
        List records = manifest.targets.findAll { it instanceof Map && it.id == targetId }
        if (records.size() != 1 || records[0].processing != [tool: 'proguard', version: ProguardMapping.VERSION]) {
            throw new GradleException('Target or ProGuard version does not match this decoder')
        }
        File mapping = CandidateFiles.checked(manifestFile.parentFile, (Map) records[0].mapping)
        ProguardMapping.classes(mapping)
        StringWriter decoded = new StringWriter()
        crash.withReader('UTF-8') { reader ->
            new ReTrace(mapping).retrace(new LineNumberReader(reader), new PrintWriter(decoded))
        }
        Files.writeString(output.toPath(), decoded.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
}
