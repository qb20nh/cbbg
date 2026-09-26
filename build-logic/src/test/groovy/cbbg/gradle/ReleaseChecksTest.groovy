package cbbg.gradle

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*

class ReleaseChecksTest {
    @TempDir File directory

    private static List attestation(String hash) {
        [[verificationResult: [statement: [predicateType: 'https://slsa.dev/provenance/v1',
                subject: [[digest: [sha256: hash]]]]]]]
    }

    private static Closure runFor(Map fixture, List<List<String>> commands, boolean published = false) {
        Map<String, String> files = new CandidateManifest(fixture.file).releaseFiles()
        Map release = [id: 4_294_967_297L, tag_name: fixture.manifest.release, draft: !published,
                       prerelease: false, immutable: published, name: 'Release',
                       body: 'Release notes', html_url: 'https://example.invalid/release',
                       assets: files.collect { name, hash ->
                           [name: name, state: 'uploaded', id: 1, size: 1,
                            digest: 'sha256:' + hash, updated_at: 'now']
                       }]
        return { List<String> command, File cwd ->
            commands << command
            if (command[0] == 'python3') {
                return JsonOutput.toJson([target: command[command.indexOf('--target') + 1],
                        manifest_sha256: CandidateFiles.sha256(fixture.file),
                        source_commit: fixture.manifest.commit, release: fixture.manifest.release,
                        runs: [[raw: 'preserved']], releaseAcceptance: false])
            }
            if (command.take(3) == ['gh', 'attestation', 'verify']) {
                String hash = CandidateFiles.sha256(new File(command[3]))
                return JsonOutput.toJson(attestation(hash))
            }
            if (command.take(3) == ['gh', 'release', 'download']) {
                File target = new File(command[command.indexOf('--dir') + 1])
                fixture.bundle.listFiles().each { file ->
                    new File(target, file.name).bytes = file.bytes
                }
                return ''
            }
            if (command.take(2) == ['gh', 'api']) {
                String endpoint = command[2]
                if (endpoint.endsWith('/immutable-releases')) return JsonOutput.toJson([enabled: true])
                if (endpoint.contains('/commits/')) return JsonOutput.toJson([sha: fixture.manifest.commit])
                if (endpoint.contains('/releases/tags/')) return JsonOutput.toJson(release)
            }
            if (command.take(3) == ['git', 'rev-parse', 'HEAD'] ||
                    command.take(3) == ['git', 'rev-parse', '--verify']) {
                return fixture.manifest.commit + '\n'
            }
            if (command.take(2) == ['git', 'status']) return ''
            throw new AssertionError('Unexpected command: ' + command)
        }
    }

    @Test
    void provenanceUsesPinnedAttestationFlagsAndRejectsWrongSubject() {
        Map fixture = CandidateFixture.create(directory)
        List<List<String>> commands = []
        Closure run = runFor(fixture, commands)
        Map report = ReleaseChecks.provenance(fixture.file,
                new File(fixture.bundle, 'provenance.jsonl'), 'owner/repo', run)
        assertEquals(4, report.subjects.size())
        assertTrue(report.subjects.any { it.sha256 == fixture.record.mapping.sha256 })
        assertFalse(report.releaseAcceptance)
        List<String> flags = commands.find { it.take(3) == ['gh', 'attestation', 'verify'] }
        assertEquals(fixture.manifest.commit, flags[flags.indexOf('--signer-digest') + 1])
        assertEquals('refs/tags/v1.4.0', flags[flags.indexOf('--source-ref') + 1])
        assertTrue(flags.contains('--deny-self-hosted-runners'))
        Closure bad = { List<String> command, File cwd ->
            if (command.take(3) == ['gh', 'attestation', 'verify']) {
                return JsonOutput.toJson(attestation('0' * 64))
            }
            run(command, cwd)
        }
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.provenance(fixture.file,
                    new File(fixture.bundle, 'provenance.jsonl'), 'owner/repo', bad)
        }
    }

    @Test
    void provenanceRejectsChangesDuringAttestationVerification() {
        Map fixture = CandidateFixture.create(directory)
        File bundle = new File(fixture.bundle, 'provenance.jsonl')
        Closure fake = runFor(fixture, [])
        Closure changing = { List<String> command, File cwd ->
            if (command.take(3) == ['gh', 'attestation', 'verify']) bundle.append('changed\n')
            fake(command, cwd)
        }
        assertTrue(assertThrows(IllegalArgumentException) {
            ReleaseChecks.provenance(fixture.file, bundle, 'owner/repo', changing)
        }.message.contains('changed'))
    }

    @Test
    void verifyRetainsRuntimeReportAndRequiresExactResultIndexes() {
        Map fixture = CandidateFixture.create(directory)
        List<List<String>> commands = []
        Closure run = runFor(fixture, commands)
        File index = new File(directory, 'result-index.json')
        index.text = '{}'
        Map report = ReleaseChecks.verify(fixture.file, [(fixture.target.id): index],
                new File(fixture.bundle, 'provenance.jsonl'), fixture.root, 'owner/repo', run)
        assertEquals('preserved', report.targets[fixture.target.id].runtime.runs[0].raw)
        assertFalse(report.releaseAcceptance)
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.verify(fixture.file, [:], new File(fixture.bundle, 'provenance.jsonl'),
                    fixture.root, 'owner/repo', run)
        }
        Closure wrongTarget = { List<String> command, File cwd ->
            if (command[0] == 'python3') {
                return JsonOutput.toJson([target: 'wrong', manifest_sha256: CandidateFiles.sha256(fixture.file),
                        source_commit: fixture.manifest.commit, release: fixture.manifest.release])
            }
            run(command, cwd)
        }
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.verify(fixture.file, [(fixture.target.id): index],
                    new File(fixture.bundle, 'provenance.jsonl'), fixture.root, 'owner/repo', wrongTarget)
        }
    }

    @Test
    void finalizeChecksDraftAndWritesOnlyLocalUnpublishedReport() {
        Map fixture = CandidateFixture.create(directory)
        File notes = new File(directory, 'notes.md')
        notes.text = 'Release notes'
        File result = new File(directory, 'result-index.json')
        result.text = '{}'
        File output = new File(directory, 'finalization.json')
        List<List<String>> commands = []
        Map report = ReleaseChecks.finalizeCandidate(fixture.file, [(fixture.target.id): result],
                fixture.root, 'owner/repo', fixture.manifest.release, notes, output, false,
                runFor(fixture, commands))
        assertFalse(report.published)
        assertTrue(output.isFile())
        assertFalse(commands.any { it.contains('PATCH') })
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.finalizeCandidate(fixture.file, [(fixture.target.id): result],
                    fixture.root, 'owner/repo', fixture.manifest.release, notes, output, false,
                    runFor(fixture, []))
        }
    }

    @Test
    void finalizeRejectsMovedTagAndChangedDownloadedAssetBeforeReport() {
        Map fixture = CandidateFixture.create(directory)
        File notes = new File(directory, 'notes.md')
        notes.text = 'Release notes'
        File result = new File(directory, 'result-index.json')
        result.text = '{}'
        Closure fake = runFor(fixture, [])
        File wrongTagOutput = new File(directory, 'wrong-tag.json')
        Closure movedTag = { List<String> command, File cwd ->
            if (command.take(2) == ['gh', 'api'] && command[2].contains('/commits/')) {
                return JsonOutput.toJson([sha: 'b' * 40])
            }
            fake(command, cwd)
        }
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.finalizeCandidate(fixture.file, [(fixture.target.id): result],
                    fixture.root, 'owner/repo', fixture.manifest.release, notes,
                    wrongTagOutput, false, movedTag)
        }
        assertFalse(wrongTagOutput.exists())
        File changedOutput = new File(directory, 'changed-download.json')
        Closure changedDownload = { List<String> command, File cwd ->
            String value = fake(command, cwd)
            if (command.take(3) == ['gh', 'release', 'download']) {
                new File(command[command.indexOf('--dir') + 1], 'candidate.json').append('changed')
            }
            value
        }
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.finalizeCandidate(fixture.file, [(fixture.target.id): result],
                    fixture.root, 'owner/repo', fixture.manifest.release, notes,
                    changedOutput, false, changedDownload)
        }
        assertFalse(changedOutput.exists())
    }

    @Test
    void failedPublicationRequestLeavesManualUncertaintyRecord() {
        Map fixture = CandidateFixture.create(directory)
        File notes = new File(directory, 'notes.md')
        notes.text = 'Release notes'
        File result = new File(directory, 'result-index.json')
        result.text = '{}'
        File output = new File(directory, 'uncertain.json')
        Closure fake = runFor(fixture, [])
        Closure failing = { List<String> command, File cwd ->
            if (command.contains('PATCH')) throw new IllegalStateException('simulated failure')
            fake(command, cwd)
        }
        assertTrue(assertThrows(IllegalStateException) {
            ReleaseChecks.finalizeCandidate(fixture.file, [(fixture.target.id): result],
                    fixture.root, 'owner/repo', fixture.manifest.release, notes, output, true, failing)
        }.message.contains('manual inspection'))
        assertTrue(output.isFile())
        assertNull(CandidateFiles.read(output).published)
    }

    @Test
    void confirmedPublicationUsesSameDraftAssetsAndRecordsUrl() {
        Map fixture = CandidateFixture.create(directory)
        File notes = new File(directory, 'notes.md')
        notes.text = 'Release notes'
        File result = new File(directory, 'result-index.json')
        result.text = '{}'
        File output = new File(directory, 'published.json')
        List<List<String>> commands = []
        Closure draft = runFor(fixture, commands)
        Closure published = runFor(fixture, commands, true)
        boolean patched = false
        Closure fake = { List<String> command, File cwd ->
            if (command.contains('PATCH')) {
                commands << command
                patched = true
                return '{}'
            }
            (patched ? published : draft)(command, cwd)
        }
        Map report = ReleaseChecks.finalizeCandidate(fixture.file, [(fixture.target.id): result],
                fixture.root, 'owner/repo', fixture.manifest.release, notes, output, true, fake)
        assertTrue(patched)
        assertTrue(report.published)
        assertEquals('https://example.invalid/release', report.url)
        assertEquals(true, CandidateFiles.read(output).published)
        assertEquals(1, commands.count { it.contains('PATCH') })
    }

    @Test
    void finalizationRejectsChangingDraftAndImmutableSetting() {
        Map fixture = CandidateFixture.create(directory)
        File notes = new File(directory, 'notes.md')
        notes.text = 'Release notes'
        File result = new File(directory, 'result-index.json')
        result.text = '{}'
        Closure normal = runFor(fixture, [])
        int releaseReads = 0
        Closure changed = { List<String> command, File cwd ->
            String response = normal(command, cwd)
            if (command.take(2) == ['gh', 'api'] && command[2].contains('/releases/tags/')) {
                releaseReads++
                if (releaseReads > 1) {
                    Map value = CandidateFiles.parse(new StringReader(response)) as Map
                    value.body = 'Changed notes'
                    return JsonOutput.toJson(value)
                }
            }
            response
        }
        File output = new File(directory, 'changed-draft.json')
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.finalizeCandidate(fixture.file, [(fixture.target.id): result],
                    fixture.root, 'owner/repo', fixture.manifest.release, notes, output, false, changed)
        }
        assertFalse(output.exists())
        Closure setting = { List<String> command, File cwd ->
            if (command.take(2) == ['gh', 'api'] && command[2].endsWith('/immutable-releases')) {
                return JsonOutput.toJson([enabled: false])
            }
            normal(command, cwd)
        }
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.finalizeCandidate(fixture.file, [(fixture.target.id): result],
                    fixture.root, 'owner/repo', fixture.manifest.release, notes, output, false, setting)
        }
        assertFalse(output.exists())
    }

    @Test
    void publicationPreflightChecksImmutableReleaseAndWritesMetadata() {
        Map fixture = CandidateFixture.create(directory)
        File assets = new File(directory, 'download')
        File output = new File(directory, 'publication.json')
        File github = new File(directory, 'github-output.txt')
        List<List<String>> commands = []
        Closure fetch = { String url, Map headers ->
            if (url.endsWith('/tag/game_version')) return [[version: '26.3']]
            if (url.endsWith('/tag/loader')) return [[name: 'fabric']]
            throw new AssertionError('Unexpected lookup: ' + url)
        }
        Map metadata = ReleaseChecks.preparePublication(fixture.manifest.release, 'owner/repo',
                fixture.root, assets, output, github, 'modrinth',
                runFor(fixture, commands, true), fetch)
        assertEquals([fixture.target.id], metadata.records[0].targets)
        assertTrue(output.isFile())
        assertTrue(github.text.contains('target=' + fixture.target.id))
        assertTrue(github.text.readLines().contains('cf_game_versions='))
        assertFalse(commands.any { it.contains('PATCH') })
    }

    @Test
    void curseforgeUploadOutputUsesResolvedVersionIds() {
        Map fixture = CandidateFixture.create(directory)
        Map metadata = Publication.metadata(fixture.file, fixture.root, 'Release notes')
        Closure fetch = { String url, Map headers ->
            if (url.endsWith('/versions')) {
                return [[id: 101, name: '26.3', gameVersionTypeID: 10],
                        [id: 202, name: 'Java 25', gameVersionTypeID: 11],
                        [id: 303, name: 'Fabric', gameVersionTypeID: 12],
                        [id: 404, name: 'Client', gameVersionTypeID: 13]]
            }
            if (url.endsWith('/version-types')) {
                return [[id: 10, name: 'Minecraft 26.3'], [id: 13, name: 'Environment']]
            }
            throw new AssertionError('Unexpected lookup: ' + url)
        }
        Map resolved = Publication.resolve(metadata, 'curseforge', fetch, 'fixture')
        String output = ReleaseChecks.githubValues(resolved.records[0], fixture.target,
                new File(fixture.root, fixture.record.artifact.path))
        assertTrue(output.readLines().contains('cf_game_versions=101,202,303,404'))
    }

    @Test
    void publicationPreflightRejectsDraftBeforeDownloading() {
        Map fixture = CandidateFixture.create(directory)
        File assets = new File(directory, 'download')
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.preparePublication(fixture.manifest.release, 'owner/repo', fixture.root,
                    assets, new File(directory, 'publication.json'),
                    new File(directory, 'github-output.txt'), 'modrinth', runFor(fixture, []))
        }
        assertFalse(assets.exists())
    }

    @Test
    void publicationPreflightRejectsFailedProvenanceAndChangingAssets() {
        Map fixture = CandidateFixture.create(directory)
        File output = new File(directory, 'publication.json')
        File github = new File(directory, 'github-output.txt')
        Closure normal = runFor(fixture, [], true)
        Closure invalidProvenance = { List<String> command, File cwd ->
            if (command.take(3) == ['gh', 'attestation', 'verify']) {
                return JsonOutput.toJson(attestation('0' * 64))
            }
            normal(command, cwd)
        }
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.preparePublication(fixture.manifest.release, 'owner/repo', fixture.root,
                    new File(directory, 'bad-provenance'), output, github, 'modrinth',
                    invalidProvenance, null)
        }
        assertFalse(output.exists())
        Closure fetch = { String url, Map headers ->
            File artifact = new File(directory, 'changed-assets/' + fixture.record.artifact.path)
            if (artifact.isFile()) artifact.append('changed')
            if (url.endsWith('/tag/game_version')) return [[version: '26.3']]
            if (url.endsWith('/tag/loader')) return [[name: 'fabric']]
            throw new AssertionError('Unexpected lookup: ' + url)
        }
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.preparePublication(fixture.manifest.release, 'owner/repo', fixture.root,
                    new File(directory, 'changed-assets'), output, github, 'modrinth',
                    normal, fetch)
        }
        assertFalse(output.exists())
        assertFalse(github.exists())
    }

    private Map legacyFixture(String version = '1.4.0') {
        Map fixture = CandidateFixture.create(directory)
        fixture.root.toPath().resolve('gradle.properties').toFile().text =
                "mod_version=${version}\narchives_base_name=cbbg\nminecraft_version=26.3\n" +
                "modrinth_project_id=UBlXUQbC\ncurseforge_project_id=1408371\n"
        Map sourceMetadata = [id: 'cbbg', depends: [java: '>=25', fabricloader: '>=0.19.5',
                minecraft: '~26.3', 'fabric-api': '*']]
        File sourceFile = new File(fixture.root, 'src/main/resources/fabric.mod.json')
        sourceFile.parentFile.mkdirs()
        sourceFile.text = JsonOutput.toJson(sourceMetadata)
        File originals = new File(directory, 'old-release-assets')
        originals.mkdirs()
        String base = 'cbbg-' + version + '+mc26.3'
        Map packaged = sourceMetadata + [version: version + '+mc26.3', environment: 'client']
        CandidateFixture.archive(new File(originals, base + '.jar'),
                ['fabric.mod.json': JsonOutput.toJson(packaged).getBytes('UTF-8'),
                 'example/Client.class': [0xca, 0xfe, 0xba, 0xbe, 0, 0, 0, 69] as byte[]])
        CandidateFixture.archive(new File(originals, base + '-sources.jar'),
                ['example/Client.java': 'class Client {}'.getBytes('UTF-8')])
        new File(originals, 'SHA256SUMS').text = 'historical checksum list\n'
        new File(originals, 'release-notes.txt').text = 'Other historical asset\n'
        [root: fixture.root, originals: originals, tag: 'v' + version + '+mc26.3',
         commit: fixture.manifest.commit, prerelease: version.contains('-')]
    }

    private static Closure legacyRun(Map fixture, List<List<String>> commands,
                                     boolean wrongDigest = false) {
        Map release = [id: 4_294_967_297L, tag_name: fixture.tag, draft: false,
                       prerelease: fixture.prerelease, immutable: true, body: 'Old release notes',
                       assets: fixture.originals.listFiles().collect { file ->
                           [name: file.name, state: 'uploaded',
                            digest: 'sha256:' + (wrongDigest ? '0' * 64 : CandidateFiles.sha256(file))]
                       }]
        return { List<String> command, File cwd ->
            commands << command
            if (command.take(3) == ['git', 'rev-parse', 'HEAD'] ||
                    command.take(3) == ['git', 'rev-parse', '--verify']) return fixture.commit + '\n'
            if (command.take(2) == ['git', 'status']) return ''
            if (command.take(2) == ['gh', 'api']) {
                if (command[2].contains('/commits/')) return JsonOutput.toJson([sha: fixture.commit])
                if (command[2].contains('/releases/tags/')) return JsonOutput.toJson(release)
            }
            if (command.take(3) == ['gh', 'release', 'download']) {
                File destination = new File(command[command.indexOf('--dir') + 1])
                fixture.originals.listFiles().findAll { it.name in command }.each { file ->
                    new File(destination, file.name).bytes = file.bytes
                }
                return ''
            }
            throw new AssertionError('Unexpected command: ' + command)
        }
    }

    @Test
    void legacyPreflightDownloadsOnlyJarsAndRetainsHistoricalPrereleaseSyntax() {
        Map fixture = legacyFixture('1.4.0-beta')
        List<List<String>> commands = []
        Closure fetch = { String url, Map headers ->
            if (url.endsWith('/tag/game_version')) return [[version: '26.3']]
            if (url.endsWith('/tag/loader')) return [[name: 'fabric']]
            throw new AssertionError('Unexpected lookup: ' + url)
        }
        File assets = new File(directory, 'legacy-download')
        File output = new File(directory, 'legacy-publication.json')
        File github = new File(directory, 'legacy-github.txt')
        Map metadata = ReleaseChecks.prepareLegacyPublication(fixture.tag, 'owner/repo',
                fixture.root, assets, output, github, 'modrinth',
                legacyRun(fixture, commands), fetch)
        assertTrue(metadata.legacy)
        assertEquals([fixture.tag], [metadata.release])
        assertEquals(['26.3-fabric'], metadata.records[0].targets)
        assertTrue(output.isFile())
        assertTrue(github.text.contains('target=26.3-fabric'))
        assertEquals(2, assets.listFiles().length)
        assertTrue(assets.listFiles().every { it.name.endsWith('.jar') })
        assertFalse(commands.any { it.contains('PATCH') })
    }

    @Test
    void legacyPreflightRejectsWrongDigestAndTagBeforeMetadataWrite() {
        Map fixture = legacyFixture()
        Closure fetch = { String url, Map headers -> [[version: '26.3'], [name: 'fabric']] }
        File output = new File(directory, 'legacy-publication.json')
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.prepareLegacyPublication(fixture.tag, 'owner/repo', fixture.root,
                    new File(directory, 'bad-digest-download'), output,
                    new File(directory, 'github.txt'), 'modrinth',
                    legacyRun(fixture, [], true), fetch)
        }
        assertFalse(output.exists())
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.prepareLegacyPublication('v1.4.0+mc26.2', 'owner/repo', fixture.root,
                    new File(directory, 'wrong-tag-download'), output,
                    new File(directory, 'github.txt'), 'modrinth',
                    legacyRun(fixture, []), fetch)
        }
        assertFalse(output.exists())
    }
}
