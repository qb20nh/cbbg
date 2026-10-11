package cbbg.gradle

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.util.zip.ZipFile
import static org.junit.jupiter.api.Assertions.*

class ReleaseEvidenceTest {
    @TempDir File directory

    @Test void packagesOriginalMetadataAndKeepsJarsUnchanged() {
        Map fixture = CandidateFixture.create(directory, true, true)
        CandidateManifest candidate = new CandidateManifest(fixture.file)
        Map reference = ReleaseEvidence.assemble(candidate, fixture.target.id)
        File archive = CandidateFiles.checked(fixture.bundle, reference)
        assertEquals(reference, ReleaseEvidence.reference(candidate, fixture.target.id))
        assertEquals(reference.sha256, candidate.releaseFiles()[archive.name])
        ['artifact', 'sources'].each { CandidateFiles.checked(fixture.bundle, fixture.record[it]) }
        new ZipFile(archive).withCloseable { zip ->
            assertEquals(['candidate.json', 'SHA256SUMS', 'provenance.jsonl',
                          fixture.record.sbom.path, fixture.record.mapping.path] as Set,
                    zip.entries().collect { it.name } as Set)
            assertArrayEquals(new File(fixture.bundle, 'provenance.jsonl').bytes,
                    zip.getInputStream(zip.getEntry('provenance.jsonl')).readAllBytes())
            String checksums = zip.getInputStream(zip.getEntry('SHA256SUMS')).getText('UTF-8')
            Set names = checksums.readLines().collect { it.substring(66) } as Set
            assertEquals([fixture.record.artifact.path, fixture.record.sources.path, 'provenance.jsonl'] as Set, names)
            assertFalse(names.contains('catalog.json'))
            assertFalse(zip.entries().any { it.name == 'catalog.json' })
        }
        assertThrows(Exception) { ReleaseEvidence.assemble(candidate, fixture.target.id) }
    }

    @Test void evidenceArchiveDoesNotDependOnTheDefaultTimeZone() {
        Map fixture = CandidateFixture.create(directory, true, true)
        CandidateManifest candidate = new CandidateManifest(fixture.file)
        TimeZone previous = TimeZone.default
        try {
            TimeZone.default = TimeZone.getTimeZone('UTC')
            Map first = ReleaseEvidence.assemble(candidate, fixture.target.id)
            File output = CandidateFiles.checked(fixture.bundle, first)
            byte[] original = output.bytes
            assertTrue(output.delete())
            TimeZone.default = TimeZone.getTimeZone('Asia/Seoul')
            Map second = ReleaseEvidence.assemble(candidate, fixture.target.id)
            assertArrayEquals(original, CandidateFiles.checked(fixture.bundle, second).bytes)
            assertEquals(first, second)
        } finally {
            TimeZone.default = previous
        }
    }

    @Test void rejectsMissingChangedAndExtraEvidence() {
        Map fixture = CandidateFixture.create(directory, true, true)
        CandidateManifest candidate = new CandidateManifest(fixture.file)
        assertThrows(Exception) { ReleaseEvidence.reference(candidate, fixture.target.id) }
        Map reference = ReleaseEvidence.assemble(candidate, fixture.target.id)
        File archive = CandidateFiles.checked(fixture.bundle, reference)
        new File(fixture.bundle, 'provenance.jsonl').text = '{"changed":true}\n'
        assertThrows(Exception) { ReleaseEvidence.reference(candidate, fixture.target.id) }
        CandidateFixture.archive(archive, ['unexpected.txt': 'extra'.bytes])
        assertThrows(Exception) { ReleaseEvidence.reference(candidate, fixture.target.id) }
    }

    @Test void olderCandidatesKeepTheirOriginalReleaseFiles() {
        Map fixture = CandidateFixture.create(directory)
        CandidateManifest candidate = new CandidateManifest(fixture.file)
        assertFalse(candidate.releaseFiles().keySet().any { it.endsWith('-evidence.zip') })
        assertThrows(Exception) { ReleaseEvidence.assemble(candidate, fixture.target.id) }
    }

    @Test void existingArchivesKeepTheirOriginalChecksumList() {
        Map fixture = CandidateFixture.create(directory, true, true)
        CandidateManifest candidate = new CandidateManifest(fixture.file)
        Map reference = ReleaseEvidence.assemble(candidate, fixture.target.id)
        File archive = CandidateFiles.checked(fixture.bundle, reference)
        Map<String, byte[]> entries = [:]
        new ZipFile(archive).withCloseable { zip ->
            zip.entries().each { entry -> entries[entry.name] = zip.getInputStream(entry).readAllBytes() }
        }
        entries.SHA256SUMS = new File(fixture.bundle, 'SHA256SUMS').bytes
        CandidateFixture.archive(archive, entries)
        assertEquals(CandidateFiles.sha256(archive), ReleaseEvidence.reference(candidate, fixture.target.id).sha256)
        entries.SHA256SUMS = 'incorrect checksum list\n'.bytes
        CandidateFixture.archive(archive, entries)
        assertThrows(Exception) { ReleaseEvidence.reference(candidate, fixture.target.id) }
    }

    @Test void sharedArtifactsHaveDistinctTargetEvidenceFiles() {
        Map fixture = CandidateFixture.create(directory, true, true)
        fixture.catalog.targets.add(fixture.target + [id: '26.3-quilt', loader: 'quilt', artifactOf: fixture.target.id])
        File catalog = new File(fixture.bundle, fixture.record.client_tests.catalog.path)
        catalog.text = JsonOutput.toJson(fixture.catalog)
        fixture.record.client_tests.catalog.sha256 = CandidateFiles.sha256(catalog)
        fixture.manifest.catalog_sha256 = CandidateFiles.canonicalHash(fixture.catalog)
        fixture.manifest.selected_targets.add('26.3-quilt')
        Map alias = CandidateFiles.parse(new StringReader(JsonOutput.toJson(fixture.record))) as Map
        alias.id = '26.3-quilt'
        fixture.manifest.targets.add(alias)
        fixture.file.text = JsonOutput.toJson(fixture.manifest)
        CandidateFixture.checksums(fixture.bundle)
        CandidateManifest candidate = new CandidateManifest(fixture.file)
        Map fabric = ReleaseEvidence.assemble(candidate, fixture.target.id)
        Map quilt = ReleaseEvidence.assemble(candidate, alias.id)
        assertNotEquals(fabric.path, quilt.path)
        Map files = candidate.releaseFiles()
        assertEquals(fabric.sha256, files[fabric.path])
        assertEquals(quilt.sha256, files[quilt.path])
    }
}
