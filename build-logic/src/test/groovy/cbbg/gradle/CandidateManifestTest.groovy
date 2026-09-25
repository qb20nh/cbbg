package cbbg.gradle

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import static org.junit.jupiter.api.Assertions.*

class CandidateManifestTest {
    @TempDir File directory

    @Test void validCandidateChecksPackagesAndReleaseFileInventory() {
        def fixture = CandidateFixture.create(directory)
        def candidate = new CandidateManifest(fixture.file)
        assertEquals(['26.3-fabric'] as Set, candidate.verifyPackages(fixture.root).keySet())
        assertEquals(fixture.bundle.listFiles()*.name as Set, candidate.releaseFiles().keySet())
    }

    @Test void pendingTargetsBuildButCannotFinalize() {
        def fixture = CandidateFixture.create(directory, false)
        def candidate = new CandidateManifest(fixture.file)
        candidate.verifyPackages(fixture.root)
        assertThrows(Exception) { candidate.releaseFiles() }
        assertFalse(candidate.releaseFiles(false).isEmpty())
    }

    @Test void changedInputAndMismatchedSelectionAreRejected() {
        def fixture = CandidateFixture.create(directory)
        def original = fixture.file.text
        fixture.manifest.selected_targets = ['26.3-quilt']
        fixture.file.text = JsonOutput.toJson(fixture.manifest)
        assertThrows(Exception) { new CandidateManifest(fixture.file) }
        fixture.file.text = original
        new File(fixture.bundle, 'ordinary-driver.jar').text = 'changed'
        assertThrows(Exception) { new CandidateManifest(fixture.file) }
    }

    @Test void checksumsMustContainExactlyTheManifestFiles() {
        def fixture = CandidateFixture.create(directory)
        new File(fixture.bundle, 'SHA256SUMS').append('b' * 64 + '  extra.jar\n')
        assertThrows(Exception) { new CandidateManifest(fixture.file).releaseFiles() }
    }

    @Test void malformedIdentitySelectionAndReferencesAreRejected() {
        def fixture = CandidateFixture.create(directory)
        String original = fixture.file.text
        List<Closure> edits = [
                { it.schema = true }, { it.release = 'v1.4.0-rc' }, { it.commit = 'HEAD' },
                { it.selected_targets = [] }, { it.selected_targets.add(it.selected_targets[0]) },
                { it.targets.add(it.targets[0]) }, { it.catalog_sha256 = '0' * 64 },
                { it.targets[0].remove('source_inventory') },
                { it.targets[0].artifact.path = '../outside.jar' },
                { it.targets[0].client_tests.drivers = [:] }]
        edits.each { edit ->
            Map manifest = CandidateFiles.parse(new StringReader(original))
            edit(manifest)
            fixture.file.text = JsonOutput.toJson(manifest)
            assertThrows(Exception) { new CandidateManifest(fixture.file) }
        }
    }

    @Test void sharedArtifactsRequireSelectedOwnerAndMatchingFiles() {
        def fixture = CandidateFixture.create(directory)
        Map quilt = fixture.target + [id: '26.3-quilt', loader: 'quilt', artifactOf: '26.3-fabric']
        fixture.catalog.targets.add(quilt)
        File catalog = new File(fixture.bundle, fixture.record.client_tests.catalog.path)
        catalog.text = JsonOutput.toJson(fixture.catalog)
        fixture.record.client_tests.catalog.sha256 = CandidateFiles.sha256(catalog)
        fixture.manifest.catalog_sha256 = CandidateFiles.canonicalHash(fixture.catalog)
        fixture.file.text = JsonOutput.toJson(fixture.manifest)
        assertThrows(Exception) { new CandidateManifest(fixture.file) }
        fixture.manifest.selected_targets.add('26.3-quilt')
        Map record = CandidateFiles.parse(new StringReader(JsonOutput.toJson(fixture.record)))
        record.id = '26.3-quilt'
        fixture.manifest.targets.add(record)
        fixture.file.text = JsonOutput.toJson(fixture.manifest)
        new CandidateManifest(fixture.file)
        File different = new File(fixture.bundle, 'different.jar')
        different.text = 'different'
        ['artifact', 'sources'].each { kind ->
            Map previous = record[kind]
            record[kind] = CandidateFiles.reference(fixture.bundle, different.name)
            fixture.file.text = JsonOutput.toJson(fixture.manifest)
            assertThrows(Exception) { new CandidateManifest(fixture.file) }
            record[kind] = previous
        }
    }
}
