package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.api.GradleException
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
    void utilitiesAreAttestedAndCopiedOnceIntoPublicAssetsAndChecksums() {
        Map fixture = SharedPublicationFixture.multiple(directory)
        CandidateFixture.utilities(fixture)
        List<List<String>> commands = []
        Map report = ReleaseChecks.provenance(fixture.file,
                new File(fixture.bundle, 'provenance.jsonl'), 'owner/repo', runFor(fixture, commands))
        ['utilities', 'utilities_sources'].each { kind ->
            assertEquals(1, report.subjects.count { it.sha256 == fixture.record[kind].sha256 })
        }
        Map files = ReleaseChecks.publicFiles(new CandidateManifest(fixture.file))
        File assets = new File(directory, 'public-utilities')
        assertEquals(files, ReleaseChecks.preparePublicAssets(fixture.file, assets))
        ['utilities', 'utilities_sources'].each { kind ->
            Map reference = fixture.record[kind]
            assertEquals(reference.sha256, files[reference.path])
            assertEquals(reference.sha256, CandidateFiles.sha256(new File(assets, reference.path)))
            assertTrue(new File(assets, 'SHA256SUMS').readLines().contains(reference.sha256 + '  ' + reference.path))
        }
        assertEquals(files.SHA256SUMS, CandidateFiles.sha256(new File(assets, 'SHA256SUMS')))
        String output = ReleaseChecks.githubValues(Publication.metadata(fixture.file, fixture.root, 'Release notes').records[0],
                fixture.target, new File(fixture.bundle, fixture.record.artifact.path),
                new File(fixture.bundle, fixture.record.sources.path))
        ['utilities', 'utilities_sources'].each { kind ->
            assertTrue(output.readLines().contains(kind + '=' + new File(fixture.bundle, fixture.record[kind].path).absolutePath))
        }
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
        String missing = assertThrows(IllegalArgumentException) {
            ReleaseChecks.verify(fixture.file, [:], new File(fixture.bundle, 'provenance.jsonl'),
                    fixture.root, 'owner/repo', run)
        }.message
        assertTrue(missing.contains(fixture.target.id), missing)
        assertTrue(missing.contains('-Presults.<target-id>=<index.json>'), missing)
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
    void finalizationFindsDraftWhenReleaseByTagApiReturnsNotFound() {
        Map fixture = CandidateFixture.create(directory)
        File notes = new File(directory, 'notes.md')
        notes.text = 'Release notes'
        File result = new File(directory, 'result-index.json')
        result.text = '{}'
        List<List<String>> commands = []
        Closure normal = runFor(fixture, commands)
        Closure draftApi = { List<String> command, File cwd ->
            if (command.take(2) == ['gh', 'api'] && command[2].contains('/releases/tags/')) {
                throw new GradleException('gh failed: gh: Not Found (HTTP 404)')
            }
            if (command.take(2) == ['gh', 'api'] && command[2].contains('/releases?')) {
                commands << command
                Map selected = CandidateFiles.parse(new StringReader(normal(
                        ['gh', 'api', 'repos/owner/repo/releases/tags/' + fixture.manifest.release], cwd))) as Map
                return JsonOutput.toJson([[tag_name: 'other-release'], selected])
            }
            normal(command, cwd)
        }
        File output = new File(directory, 'draft.json')
        Map report = ReleaseChecks.finalizeCandidate(fixture.file, [(fixture.target.id): result],
                fixture.root, 'owner/repo', fixture.manifest.release, notes, output, false, draftApi)
        assertFalse(report.published)
        assertEquals(2, commands.count { it[0..1] == ['gh', 'api'] && it[2].contains('/releases?') })
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
        assertFalse(metadata.containsKey('dry_run_only'))
        Publication.requireUploadAllowed(metadata)
        assertTrue(github.text.contains('target=' + fixture.target.id))
        assertTrue(github.text.readLines().contains('cf_game_versions='))
        assertFalse(commands.any { it.contains('PATCH') })
    }

    @Test
    void privateCandidateSupportsAReleaseWithOnlyPublicFiles() {
        Map fixture = CandidateFixture.create(directory, true, true)
        fixture.manifest.release = 'v1.4.0+mc26.3-fabric'
        fixture.file.text = JsonOutput.toJson(fixture.manifest)
        CandidateFixture.checksums(fixture.bundle)
        CandidateManifest candidate = new CandidateManifest(fixture.file)
        ReleaseEvidence.assemble(candidate, fixture.target.id)
        Map<String, String> publicFiles = ReleaseChecks.publicFiles(candidate)
        assertEquals(4, publicFiles.size())
        assertFalse(publicFiles.containsKey(fixture.record.mapping.path))
        assertFalse(publicFiles.containsKey(fixture.record.sbom.path))
        assertFalse(publicFiles.containsKey('candidate.json'))
        File publicAssets = new File(directory, 'public-assets')
        assertEquals(publicFiles, ReleaseChecks.preparePublicAssets(fixture.file, publicAssets))
        assertEquals(publicFiles.keySet(), publicAssets.listFiles()*.name as Set)
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.preparePublicAssets(fixture.file, publicAssets)
        }
        Map release = [id: 14, tag_name: fixture.manifest.release, draft: false,
                       prerelease: false, immutable: true, name: 'Release',
                       body: 'Release notes\n\n<!-- cbbg-candidate-run: 42 -->\n',
                       assets: publicFiles.collect { name, hash ->
                           [name: name, state: 'uploaded', id: 1, size: 1,
                            digest: 'sha256:' + hash, updated_at: 'now']
                       }]
        List<List<String>> commands = []
        Closure base = runFor(fixture, commands, true)
        Closure run = { List<String> command, File cwd ->
            if (command.take(2) == ['gh', 'api'] && command[2].endsWith('/actions/runs/42')) {
                return JsonOutput.toJson([head_sha: fixture.manifest.commit,
                                          event: 'workflow_dispatch', conclusion: 'success',
                                          path: '.github/workflows/release.yml'])
            }
            if (command.take(2) == ['gh', 'api'] && command[2].contains('/releases/tags/')) {
                return JsonOutput.toJson(release)
            }
            if (command.take(3) == ['gh', 'run', 'download']) {
                commands << command
                File target = new File(command[command.indexOf('--dir') + 1])
                fixture.bundle.listFiles().each { new File(target, it.name).bytes = it.bytes }
                return ''
            }
            if (command.take(3) == ['gh', 'release', 'download']) {
                commands << command
                File target = new File(command[command.indexOf('--dir') + 1])
                publicFiles.keySet().findAll { it != 'SHA256SUMS' }.each { name ->
                    new File(target, name).bytes = new File(fixture.bundle, name).bytes
                }
                new File(target, 'SHA256SUMS').text = publicFiles.keySet().findAll { it != 'SHA256SUMS' }
                        .sort().collect { publicFiles[it] + '  ' + it }.join('\n') + '\n'
                return ''
            }
            base(command, cwd)
        }
        File assets = new File(directory, 'download')
        Map metadata = ReleaseChecks.preparePublication(fixture.manifest.release, 'owner/repo',
                fixture.root, assets, new File(directory, 'publication.json'),
                new File(directory, 'github-output.txt'), 'modrinth', run, modrinthLabels())
        assertEquals([fixture.target.id], metadata.records[0].targets)
        assertTrue(commands.any { it.take(3) == ['gh', 'run', 'download'] })
        assertFalse(assets.listFiles().toList().size() == publicFiles.size())
        Map selected = ReleaseChecks.selectPublication(fixture.manifest.release, 'owner/repo',
                fixture.root, assets, new File(directory, 'publication.json'), fixture.target.id,
                new File(directory, 'selected.txt'), run, modrinthLabels(), 'modrinth')
        assertEquals([fixture.target.id], selected.targets)
        release.draft = true
        release.immutable = false
        File notes = new File(directory, 'notes.md')
        notes.text = 'Release notes\n'
        File result = new File(directory, 'results.json')
        result.text = '{}'
        Map finalization = ReleaseChecks.finalizeCandidate(fixture.file, [(fixture.target.id): result],
                fixture.root, 'owner/repo', fixture.manifest.release, notes,
                new File(directory, 'finalization.json'), false, run)
        assertEquals(publicFiles, finalization.files)
        assertTrue(finalization.notes.contains('<!-- cbbg-candidate-run: 42 -->'))
        release.draft = false
        release.immutable = true
        Closure wrongRun = { List<String> command, File cwd ->
            if (command.take(2) == ['gh', 'api'] && command[2].endsWith('/actions/runs/42')) {
                return JsonOutput.toJson([head_sha: '0' * 40, event: 'workflow_dispatch',
                                          conclusion: 'success', path: '.github/workflows/release.yml'])
            }
            run(command, cwd)
        }
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.preparePublication(fixture.manifest.release, 'owner/repo', fixture.root,
                    new File(directory, 'wrong-run-download'), new File(directory, 'wrong-run.json'),
                    new File(directory, 'wrong-run-output.txt'), 'modrinth', wrongRun, modrinthLabels())
        }
        Map wrongAsset = release.assets[0].clone() as Map
        release.assets[0] = wrongAsset + [digest: 'sha256:' + ('0' * 64)]
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.preparePublication(fixture.manifest.release, 'owner/repo', fixture.root,
                    new File(directory, 'wrong-public-download'), new File(directory, 'wrong-public.json'),
                    new File(directory, 'wrong-public-output.txt'), 'modrinth', run, modrinthLabels())
        }
    }

    private static Closure modrinthLabels() {
        { String url, Map headers ->
            if (url.endsWith('/tag/game_version')) return [[version: '26.3']]
            if (url.endsWith('/tag/loader')) return [[name: 'fabric']]
            throw new AssertionError('Unexpected lookup: ' + url)
        }
    }

    @Test
    void sharedReleasePreflightChecksAllRecordsAndSelectionRevalidatesRelease() {
        Map fixture = SharedPublicationFixture.multiple(directory)
        Closure fetch = { String url, Map headers ->
            if (url.endsWith('/tag/game_version')) return [[version: '26.3'], [version: '26.2']]
            if (url.endsWith('/tag/loader')) return [[name: 'fabric']]
            throw new AssertionError('Unexpected lookup: ' + url)
        }
        File assets = new File(directory, 'download')
        File metadataFile = new File(directory, 'publication.json')
        File github = new File(directory, 'github.txt')
        Closure run = runFor(fixture, [], true)
        Map metadata = ReleaseChecks.preparePublication(fixture.manifest.release, 'owner/repo',
                fixture.root, assets, metadataFile, github, 'modrinth', run, fetch)
        assertEquals(2, metadata.records.size())
        assertTrue(github.text.contains('matrix={"target":["26.3-fabric","26.2-fabric"]}'))
        assertFalse(github.text.readLines().any { it.startsWith('artifact=') })
        File selectedOutput = new File(directory, 'selected.txt')
        Map selected = ReleaseChecks.selectPublication(fixture.manifest.release, 'owner/repo',
                fixture.root, assets, metadataFile, '26.2-fabric', selectedOutput, run, fetch, 'modrinth')
        assertEquals(['26.2-fabric'], selected.targets)
        assertTrue(selectedOutput.text.contains('target=26.2-fabric'))
        metadata.records[0].modrinth.changelog = 'Changed unrelated record'
        metadataFile.text = JsonOutput.toJson(metadata)
        File invalidOutput = new File(directory, 'invalid-selected.txt')
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.selectPublication(fixture.manifest.release, 'owner/repo', fixture.root,
                    assets, metadataFile, '26.2-fabric', invalidOutput, run, fetch, 'modrinth')
        }
        assertFalse(invalidOutput.exists())
    }

    @Test
    void selectingDraftRetainsDryRunOnlyAndRejectsSourceAndFileChanges() {
        Map fixture = CandidateFixture.create(directory)
        File assets = new File(directory, 'download')
        File metadataFile = new File(directory, 'publication.json')
        Closure run = runFor(fixture, [])
        ReleaseChecks.preparePublication(fixture.manifest.release, 'owner/repo', fixture.root,
                assets, metadataFile, new File(directory, 'github.txt'), 'modrinth', run,
                modrinthLabels(), true)
        ReleaseChecks.selectPublication(fixture.manifest.release, 'owner/repo', fixture.root,
                assets, metadataFile, fixture.target.id, new File(directory, 'selected.txt'),
                run, modrinthLabels(), 'modrinth')
        assertTrue(CandidateFiles.read(metadataFile).dry_run_only)
        Closure changedSource = { List<String> command, File cwd ->
            command.take(3) == ['git', 'rev-parse', 'HEAD'] ? 'b' * 40 : run(command, cwd)
        }
        assertThrows(IllegalArgumentException) {
            ReleaseChecks.selectPublication(fixture.manifest.release, 'owner/repo', fixture.root,
                    assets, metadataFile, fixture.target.id, new File(directory, 'invalid-source.txt'),
                    changedSource, modrinthLabels(), 'modrinth')
        }
        new File(assets, fixture.record.artifact.path).append('changed')
        assertThrows(Exception) {
            ReleaseChecks.selectPublication(fixture.manifest.release, 'owner/repo', fixture.root,
                    assets, metadataFile, fixture.target.id, new File(directory, 'invalid-file.txt'),
                    run, modrinthLabels(), 'modrinth')
        }
        assertFalse(new File(directory, 'invalid-source.txt').exists())
        assertFalse(new File(directory, 'invalid-file.txt').exists())
    }

    @Test
    void draftDryRunPreflightRetainsPackageAndProvenanceChecksWithoutWrites() {
        Map fixture = CandidateFixture.create(directory)
        List<List<String>> commands = []
        File output = new File(directory, 'publication.json')
        Map metadata = ReleaseChecks.preparePublication(fixture.manifest.release, 'owner/repo',
                fixture.root, new File(directory, 'download'), output,
                new File(directory, 'github.txt'), 'modrinth', runFor(fixture, commands),
                modrinthLabels(), true)
        assertEquals(true, metadata.dry_run_only)
        assertEquals(true, CandidateFiles.read(output).dry_run_only)
        assertEquals([fixture.target.id], metadata.records[0].targets)
        assertTrue(commands.any { it.take(3) == ['gh', 'attestation', 'verify'] })
        assertFalse(commands.any { it.contains('PATCH') || it.contains('--method') })
        assertThrows(org.gradle.api.GradleException) { Publication.requireUploadAllowed(metadata) }
    }

    @Test
    void draftDryRunRejectsChangedIdentityAssetsAndMissingAssets() {
        Map fixture = CandidateFixture.create(directory)
        int attempt = 0
        for (String field : ['id', 'body', 'name', 'digest', 'updated_at', 'missing']) {
            int reads = 0
            Closure normal = runFor(fixture, [])
            Closure changed = { List<String> command, File cwd ->
                String response = normal(command, cwd)
                if (command.take(2) == ['gh', 'api'] && command[2].contains('/releases/tags/')) {
                    reads++
                    Map release = CandidateFiles.parse(new StringReader(response)) as Map
                    if (field == 'missing') release.assets.remove(0)
                    else if (reads > 1) {
                        if (field in ['digest', 'updated_at']) release.assets[0][field] = 'changed'
                        else release[field] = field == 'id' ? 99 : 'Changed'
                    }
                    return JsonOutput.toJson(release)
                }
                response
            }
            File output = new File(directory, 'changed-' + attempt + '.json')
            File github = new File(directory, 'github-' + attempt + '.txt')
            assertThrows(IllegalArgumentException) {
                ReleaseChecks.preparePublication(fixture.manifest.release, 'owner/repo', fixture.root,
                        new File(directory, 'download-' + attempt), output, github, 'modrinth',
                        changed, modrinthLabels(), true)
            }
            assertFalse(output.exists())
            assertFalse(github.exists())
            attempt++
        }
    }

    @Test
    void draftDryRunRejectsInvalidProvenanceAndMissingDownloadedAssets() {
        Map fixture = CandidateFixture.create(directory)
        Closure normal = runFor(fixture, [])
        int attempt = 0
        for (String failure : ['provenance', 'missing']) {
            Closure invalid = { List<String> command, File cwd ->
                if (failure == 'provenance' && command.take(3) == ['gh', 'attestation', 'verify']) {
                    return JsonOutput.toJson(attestation('0' * 64))
                }
                String response = normal(command, cwd)
                if (failure == 'missing' && command.take(3) == ['gh', 'release', 'download']) {
                    new File(command[command.indexOf('--dir') + 1], 'provenance.jsonl').delete()
                }
                response
            }
            File output = new File(directory, 'invalid-' + attempt + '.json')
            assertThrows(failure == 'missing' ? FileNotFoundException : IllegalArgumentException) {
                ReleaseChecks.preparePublication(fixture.manifest.release, 'owner/repo', fixture.root,
                        new File(directory, 'invalid-download-' + attempt), output,
                        new File(directory, 'github.txt'), 'modrinth', invalid, modrinthLabels(), true)
            }
            assertFalse(output.exists())
            attempt++
        }
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
                new File(fixture.root, fixture.record.artifact.path),
                new File(fixture.root, fixture.record.sources.path))
        assertTrue(output.readLines().contains('cf_game_versions=101,202,303,404'))
        assertTrue(output.readLines().contains('sources=' + new File(fixture.root, fixture.record.sources.path).absolutePath))
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
        ReleaseChecks.selectPublication(fixture.tag, 'owner/repo', fixture.root, assets, output,
                '26.3-fabric', new File(directory, 'legacy-selected.txt'),
                legacyRun(fixture, []), fetch, 'modrinth')
        assertTrue(new File(directory, 'legacy-selected.txt').text.contains('target=26.3-fabric'))
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
