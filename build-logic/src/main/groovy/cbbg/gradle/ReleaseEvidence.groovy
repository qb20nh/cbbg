package cbbg.gradle

import org.gradle.api.GradleException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Portable release metadata containing the original GitHub attestations. */
class ReleaseEvidence {
    private static Map<String, File> contents(CandidateManifest candidate, String target) {
        Map record = candidate.records[target]
        if (!(record?.sbom instanceof Map)) throw new GradleException('Release evidence requires an SBOM')
        Map<String, File> files = ['candidate.json': candidate.file,
                                  'provenance.jsonl': new File(candidate.file.parentFile, 'provenance.jsonl')]
        ['sbom', 'mapping'].each { kind ->
            File file = CandidateFiles.checked(candidate.file.parentFile, record[kind] as Map)
            if (files.containsKey(file.name)) throw new GradleException('Duplicate release evidence filename')
            files[file.name] = file
        }
        if (files.values().any { !it.isFile() }) throw new GradleException('Missing release evidence input')
        files
    }

    private static byte[] checksums(CandidateManifest candidate) {
        Map<String, String> files = ReleaseChecks.publicFiles(candidate)
        (files.keySet().findAll { it != 'SHA256SUMS' }.sort()
                .collect { files[it] + '  ' + it }.join('\n') + '\n').getBytes('UTF-8')
    }

    private static File archive(CandidateManifest candidate, String target) {
        new File(candidate.file.parentFile,
                (candidate.identity.product == 'lib' ? 'cbbg-lib-' : 'cbbg-') + candidate.identity.version + '-' + target + '-evidence.zip')
    }

    static Map assemble(CandidateManifest candidate, String target) {
        Map<String, File> files = contents(candidate, target)
        Map<String, String> hashes = files.collectEntries { name, file -> [(name): CandidateFiles.sha256(file)] }
        byte[] checksumList = checksums(candidate)
        File output = archive(candidate, target)
        if (output.exists()) throw new GradleException('Release evidence archive already exists')
        try {
            output.withOutputStream { stream ->
                ZipOutputStream zip = new ZipOutputStream(stream)
                try {
                    zip.setLevel(9)
                    (files.keySet() + ['SHA256SUMS']).sort().each { name ->
                        ZipEntry entry = new ZipEntry(name)
                        entry.time = 0
                        zip.putNextEntry(entry)
                        if (name == 'SHA256SUMS') zip.write(checksumList)
                        else files[name].withInputStream { input -> input.transferTo(zip) }
                        zip.closeEntry()
                    }
                } finally {
                    zip.close()
                }
            }
            if (files.any { name, file -> CandidateFiles.sha256(file) != hashes[name] } ||
                    !Arrays.equals(checksumList, checksums(candidate))) {
                throw new GradleException('Release evidence changed while packaging')
            }
            reference(candidate, target)
        } catch (Exception failure) {
            output.delete()
            throw failure
        }
    }

    static Map reference(CandidateManifest candidate, String target) {
        Map<String, File> files = contents(candidate, target)
        File output = archive(candidate, target)
        if (!output.isFile()) throw new GradleException('Missing release evidence archive')
        new ZipFile(output).withCloseable { zip ->
            List<String> names = zip.entries().collect { it.name }
            if (names.size() != files.size() + 1 || names.toSet() != (files.keySet() + ['SHA256SUMS']) as Set) {
                throw new GradleException('Release evidence archive has different entries')
            }
            files.each { name, file ->
                byte[] actual = zip.getInputStream(zip.getEntry(name)).withCloseable { it.readAllBytes() }
                if (!Arrays.equals(actual, file.bytes)) {
                    throw new GradleException('Release evidence archive differs: ' + name)
                }
            }
            byte[] actualChecksums = zip.getInputStream(zip.getEntry('SHA256SUMS')).withCloseable { it.readAllBytes() }
            // Already published archives contain the original candidate checksum list.
            if (!Arrays.equals(actualChecksums, checksums(candidate)) &&
                    !Arrays.equals(actualChecksums, new File(candidate.file.parentFile, 'SHA256SUMS').bytes)) {
                throw new GradleException('Release evidence archive differs: SHA256SUMS')
            }
        }
        [path: output.name, sha256: CandidateFiles.sha256(output)]
    }
}
