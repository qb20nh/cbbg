package cbbg.gradle

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.util.zip.ZipFile
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

    @Test void utilityPairIsOptionalButRequiresBothCheckedFiles() {
        def fixture = CandidateFixture.create(directory)
        assertFalse(new CandidateManifest(fixture.file).records[fixture.target.id].containsKey('utilities'))
        CandidateFixture.utilities(fixture)
        def candidate = new CandidateManifest(fixture.file)
        assertEquals(fixture.record.utilities.sha256, candidate.releaseFiles()[fixture.record.utilities.path])
        assertEquals(fixture.record.utilities_sources.sha256, candidate.releaseFiles()[fixture.record.utilities_sources.path])
        Map source = fixture.record.remove('utilities_sources')
        fixture.file.text = JsonOutput.toJson(fixture.manifest)
        assertTrue(assertThrows(Exception) { new CandidateManifest(fixture.file) }.message.contains('together'))
        fixture.record.utilities_sources = source
        fixture.file.text = JsonOutput.toJson(fixture.manifest)
        new File(fixture.bundle, source.path).append('changed')
        assertThrows(Exception) { new CandidateManifest(fixture.file) }
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
                { it.targets[0].remove('mapping') },
                { it.targets[0].mapping.sha256 = '0' * 64 },
                { it.targets[0].processing.version = 'unknown' },
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
        assertEquals(['26.3-fabric', '26.3-quilt'] as Set,
                new CandidateManifest(fixture.file).verifyPackages(fixture.root).keySet())
        CandidateFixture.utilities(fixture)
        new CandidateManifest(fixture.file)
        ['utilities', 'utilities_sources'].each { kind ->
            Map previous = record[kind]
            File same = new File(fixture.bundle, 'alias-' + previous.path)
            same.bytes = new File(fixture.bundle, previous.path).bytes
            record[kind] = CandidateFiles.reference(fixture.bundle, same.name)
            fixture.file.text = JsonOutput.toJson(fixture.manifest)
            assertTrue(assertThrows(Exception) { new CandidateManifest(fixture.file) }.message.contains('Shared ' + kind))
            record[kind] = previous
        }
        File different = new File(fixture.bundle, 'different.jar')
        different.text = 'different'
        ['artifact', 'sources', 'mapping', 'source_inventory'].each { kind ->
            Map previous = record[kind]
            record[kind] = CandidateFiles.reference(fixture.bundle, different.name)
            fixture.file.text = JsonOutput.toJson(fixture.manifest)
            assertThrows(Exception) { new CandidateManifest(fixture.file) }
            record[kind] = previous
        }
    }

    @Test void currentUpstreamVersionIncludesLoader() {
        Map target = TargetCatalog.read(new File('../targets.json')).select('26.2-fabric')[0]
        assertEquals('1.4.2+mc26.2-fabric',
                CandidateManifest.packageVersion('v1.4.2+mc26.2-fabric', target))
        assertEquals('1.4.3-rc.1+mc26.2-fabric',
                CandidateManifest.packageVersion('v1.4.3-rc.1+mc26.2-fabric', target))
    }

    @Test void historicalUpstreamCatalogKeepsItsVersion() {
        assertEquals('1.4.0+mc26.2', CandidateManifest.packageVersion('v1.4.0',
                [minecraft: '26.2', loader: 'fabric', buildProfile: 'fabric-upstream']))
        assertEquals('1.4.0+mc26.3-fabric', CandidateManifest.packageVersion('v1.4.0',
                [minecraft: '26.3', loader: 'fabric', buildProfile: 'fabric-modern']))
    }

    @Test void everyCatalogTargetUsesItsArtifactOwnersMinecraftAndLoader() {
        TargetCatalog catalog = TargetCatalog.read(new File('../targets.json'))
        Map<String, Map> targets = catalog.select().collectEntries { [(it.id): it] }
        targets.values().each { target ->
            Map owner = targets[target.artifactOf ?: target.id]
            for (String modVersion : ['1.5.0', '1.5.0-rc.1']) {
                String expected = modVersion + '+mc' + owner.minecraft + '-' + owner.loader
                assertEquals(expected, CandidateManifest.packageVersion('v' + modVersion, owner), target.id)
            }
        }
    }

    @Test void standaloneLoadersIncludeTheirOwnSuffix() {
        for (String loader : ['fabric', 'quilt', 'forge', 'neoforge', 'legacy-fabric']) {
            Map target = [minecraft: '1.20.1', loader: loader, buildProfile: loader + '-modern']
            assertEquals('1.5.0+mc1.20.1-' + loader,
                    CandidateManifest.packageVersion('v1.5.0+mc1.20.1-' + loader, target))
        }
    }

    @Test void mappedCandidatesRejectMissingOrMismatchedEmbeddedMapping() {
        def fixture = CandidateFixture.create(directory)
        File sources = new File(fixture.bundle, fixture.record.sources.path)
        Map<String, byte[]> entries = [:]
        new ZipFile(sources).withCloseable { zip ->
            zip.entries().each { entries[it.name] = zip.getInputStream(it).bytes }
        }
        for (byte[] invalid : [null, 'different mapping'.bytes]) {
            entries.remove('META-INF/cbbg/proguard.map')
            if (invalid != null) entries['META-INF/cbbg/proguard.map'] = invalid
            CandidateFixture.archive(sources, entries)
            fixture.record.sources.sha256 = CandidateFiles.sha256(sources)
            fixture.file.text = JsonOutput.toJson(fixture.manifest)
            def error = assertThrows(Exception) { new CandidateManifest(fixture.file).verifyPackages(fixture.root) }
            assertTrue(error.message.contains('release mapping in source archive'))
        }
    }

    @Test void mappedCandidatesRejectMissingOrMismatchedEmbeddedSbom() {
        def fixture = CandidateFixture.create(directory, true, true)
        File sources = new File(fixture.bundle, fixture.record.sources.path)
        Map<String, byte[]> entries = [:]
        new ZipFile(sources).withCloseable { zip ->
            zip.entries().each { entries[it.name] = zip.getInputStream(it).bytes }
        }
        for (byte[] invalid : [null, '{}'.bytes]) {
            entries.remove('META-INF/cbbg/sbom.cdx.json')
            if (invalid != null) entries['META-INF/cbbg/sbom.cdx.json'] = invalid
            CandidateFixture.archive(sources, entries)
            fixture.record.sources.sha256 = CandidateFiles.sha256(sources)
            fixture.file.text = JsonOutput.toJson(fixture.manifest)
            def error = assertThrows(Exception) { new CandidateManifest(fixture.file).verifyPackages(fixture.root) }
            assertTrue(error.message.contains('release SBOM in source archive'))
        }
    }

    @Test void historicalCandidatesRemainReadableWithoutProcessingClaims() {
        def fixture = CandidateFixture.create(directory)
        File sources = new File(fixture.bundle, fixture.record.sources.path)
        Map<String, byte[]> entries = [:]
        new ZipFile(sources).withCloseable { zip ->
            zip.entries().each { if (it.name != 'META-INF/cbbg/proguard.map') entries[it.name] = zip.getInputStream(it).bytes }
        }
        CandidateFixture.archive(sources, entries)
        fixture.record.sources.sha256 = CandidateFiles.sha256(sources)
        fixture.manifest.schema = 2
        fixture.record.remove('sbom')
        fixture.record.remove('mapping')
        fixture.record.remove('processing')
        fixture.file.text = JsonOutput.toJson(fixture.manifest)
        new CandidateManifest(fixture.file).verifyPackages(fixture.root)
        fixture.record.processing = [tool: 'proguard', version: ProguardMapping.VERSION]
        fixture.file.text = JsonOutput.toJson(fixture.manifest)
        assertThrows(Exception) { new CandidateManifest(fixture.file) }
    }
}
