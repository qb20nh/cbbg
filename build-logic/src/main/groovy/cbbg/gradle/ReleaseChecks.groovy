package cbbg.gradle

import groovy.json.JsonOutput
import java.nio.file.Files

class ReleaseChecks {
    private static final String PREDICATE = 'https://slsa.dev/provenance/v1'
    private static final String TARGET = '26.3-fabric'

    static Map provenance(File manifest, File bundle, String repo, Closure run) {
        repository(repo)
        CandidateManifest candidate = new CandidateManifest(manifest)
        if (!bundle.isFile()) throw new IllegalArgumentException('Missing attestation bundle')
        String manifestHash = CandidateFiles.sha256(candidate.file)
        String bundleHash = CandidateFiles.sha256(bundle)
        Map<String, File> subjects = [(manifestHash): candidate.file]
        Map<File, String> files = [(candidate.file): manifestHash]
        candidate.records.values().each { record ->
            ['artifact', 'sources'].each { kind ->
                File path = CandidateFiles.checked(candidate.file.parentFile, record[kind] as Map)
                String hash = record[kind].sha256
                files[path] = hash
                if (!subjects.containsKey(hash)) subjects[hash] = path
            }
        }
        List reports = []
        subjects.each { String hash, File path ->
            stable(path, hash, bundle, bundleHash)
            List<String> command = ['gh', 'attestation', 'verify', path.absolutePath,
                    '--bundle', bundle.absolutePath, '--repo', repo,
                    '--signer-workflow', repo + '/.github/workflows/release.yml',
                    '--signer-digest', candidate.data.commit,
                    '--source-digest', candidate.data.commit,
                    '--source-ref', 'refs/tags/' + candidate.data.release,
                    '--deny-self-hosted-runners', '--predicate-type', PREDICATE,
                    '--format', 'json']
            Object verified = CandidateFiles.parse(new StringReader(run(command, candidate.file.parentFile) as String))
            if (!(verified instanceof List) || !verified || verified.any { !(it instanceof Map) ||
                    !(it.verificationResult instanceof Map) }) {
                throw new IllegalArgumentException('Missing verified attestations: ' + path.name)
            }
            verified.each { item ->
                Map statement = item.verificationResult.statement as Map
                if (!(statement instanceof Map) || statement.predicateType != PREDICATE ||
                        !(statement.subject instanceof List) ||
                        !statement.subject.any { it instanceof Map && it.digest instanceof Map &&
                                it.digest.sha256 == hash }) {
                    throw new IllegalArgumentException('Verified attestation does not identify candidate file: ' + path.name)
                }
            }
            stable(path, hash, bundle, bundleHash)
            reports << [sha256: hash, attestations: verified]
        }
        if (files.any { path, hash -> CandidateFiles.sha256(path) != hash }) {
            throw new IllegalArgumentException('Candidate files changed during verification')
        }
        [manifest_sha256: manifestHash, bundle_sha256: bundleHash, repository: repo,
         source_commit: candidate.data.commit, release: candidate.data.release,
         subjects: reports, releaseAcceptance: false]
    }

    static Map verify(File manifest, Map results, File bundle, File sourceRoot, String repo, Closure run) {
        CandidateManifest candidate = new CandidateManifest(manifest)
        List<String> selected = candidate.data.selected_targets as List<String>
        if (!(results instanceof Map) || results.keySet() != selected.toSet()) {
            throw new IllegalArgumentException('Result indexes must cover exactly the selected targets')
        }
        List<File> indexes = selected.collect { id -> new File(results[id].toString()).canonicalFile }
        if (indexes.toSet().size() != indexes.size()) {
            throw new IllegalArgumentException('Each runtime target needs its own result index')
        }
        String hash = CandidateFiles.sha256(candidate.file)
        Map expected = [manifest_sha256: hash, source_commit: candidate.data.commit,
                        release: candidate.data.release]
        Map packages = candidate.verifyPackages(sourceRoot)
        Map targets = [:]
        selected.each { id ->
            Map packageReport = packages[id] as Map
            identity(packageReport, expected, id)
            String result = run(['python3', new File(sourceRoot, 'scripts/fabric_acceptance.py').absolutePath,
                    '--candidate', candidate.file.absolutePath, '--target', id,
                    '--results', indexes[selected.indexOf(id)].absolutePath], sourceRoot) as String
            Object parsed = CandidateFiles.parse(new StringReader(result))
            if (!(parsed instanceof Map)) throw new IllegalArgumentException('Invalid runtime validation result')
            Map runtime = (Map) parsed
            identity(runtime, expected, id)
            targets[id] = [package: packageReport, runtime: runtime]
        }
        Map attestation = provenance(candidate.file, bundle, repo, run)
        identity(attestation, expected)
        if (attestation.repository != repo) throw new IllegalArgumentException('Provenance repository differs')
        if (CandidateFiles.sha256(candidate.file) != hash) {
            throw new IllegalArgumentException('Candidate manifest changed during validation')
        }
        expected + [targets: targets, provenance: attestation, releaseAcceptance: false]
    }

    static Map finalizeCandidate(File manifest, Map results, File sourceRoot, String repo, String tag,
                                 File notes, File output, boolean publish, Closure run) {
        repository(repo)
        if (manifest.name != 'candidate.json') throw new IllegalArgumentException('Expected candidate.json')
        outputGuard(manifest, output)
        CandidateManifest candidate = new CandidateManifest(manifest)
        Map<String, String> files = candidate.releaseFiles()
        if (candidate.data.release != tag) throw new IllegalArgumentException('Requested release differs from candidate')
        cleanSource(sourceRoot, candidate.data.commit as String, run)
        String body = notes.getText('UTF-8')
        if (!body.trim()) throw new IllegalArgumentException('Release notes are required')
        Map validation = verify(candidate.file, results,
                new File(candidate.file.parentFile, 'provenance.jsonl'), sourceRoot, repo, run)
        String endpoint = 'repos/' + repo
        String tagEndpoint = endpoint + '/commits/' + segment('refs/tags/' + tag)
        String releaseEndpoint = endpoint + '/releases/tags/' + tag
        if (api(tagEndpoint, sourceRoot, run).sha != candidate.data.commit) {
            throw new IllegalArgumentException('Release tag differs from candidate commit')
        }
        if (api(endpoint + '/immutable-releases', sourceRoot, run).enabled != true) {
            throw new IllegalArgumentException('Enable immutable releases before finalization')
        }
        Map snapshot = draftSnapshot(api(releaseEndpoint, sourceRoot, run), tag, files)
        File download = Files.createTempDirectory('cbbg-finalize-').toFile()
        try {
            run(['gh', 'release', 'download', tag, '--repo', repo, '--dir',
                 download.absolutePath, '--pattern', '*'], sourceRoot)
            if (localFiles(download) != files) {
                throw new IllegalArgumentException('Downloaded draft files differ from candidate')
            }
        } finally {
            removeTree(download)
        }
        if (draftSnapshot(api(releaseEndpoint, sourceRoot, run), tag, files) != snapshot) {
            throw new IllegalArgumentException('Draft changed during finalization checks')
        }
        checkFiles(candidate.file.parentFile, files)
        if (api(tagEndpoint, sourceRoot, run).sha != candidate.data.commit) {
            throw new IllegalArgumentException('Release tag changed during finalization checks')
        }
        Map report = [release: tag, repository: repo, source_commit: candidate.data.commit,
                      manifest_sha256: files['candidate.json'], files: files, draft: snapshot,
                      validation: validation, notes: body, published: false]
        CandidateFiles.writeNew(output, report)
        if (publish) {
            if (api(endpoint + '/immutable-releases', sourceRoot, run).enabled != true ||
                    api(tagEndpoint, sourceRoot, run).sha != candidate.data.commit) {
                throw new IllegalArgumentException('Release settings or tag changed before publication')
            }
            report.published = null
            overwrite(output, report)
            File request = File.createTempFile('cbbg-release-request-', '.json')
            try {
                request.setText(JsonOutput.toJson([draft: false, body: body]), 'UTF-8')
                try {
                    run(['gh', 'api', '--method', 'PATCH', endpoint + '/releases/' + snapshot.id,
                         '--input', request.absolutePath], sourceRoot)
                } catch (Exception error) {
                    throw new IllegalStateException('Publication outcome needs manual inspection; do not automatically retry', error)
                }
            } finally {
                request.delete()
            }
            Map published = api(releaseEndpoint, sourceRoot, run)
            if (published.id != snapshot.id || published.draft != false ||
                    published.immutable != true || published.body != body ||
                    api(tagEndpoint, sourceRoot, run).sha != candidate.data.commit ||
                    draftSnapshot(published + [draft: true], tag, files) != snapshot + [body: body]) {
                throw new IllegalStateException('Publication outcome needs manual inspection; do not automatically retry')
            }
            report.published = true
            report.url = published.html_url
            overwrite(output, report)
        }
        report
    }

    static Map preparePublication(String tag, String repo, File sourceRoot, File assets, File output,
                                  File githubOutput, String services, Closure run, Closure fetch = null) {
        repository(repo)
        CandidateFiles.releaseIdentity(tag, '0' * 40)
        File source = sourceRoot.canonicalFile
        File assetDir = assets.canonicalFile
        File metadataFile = output.canonicalFile
        if (assetDir.exists() || metadataFile.exists() ||
                metadataFile.toPath().startsWith(assetDir.toPath()) ||
                assetDir.toPath().startsWith(source.toPath())) {
            throw new IllegalArgumentException('Use new output and asset paths outside source checkout')
        }
        String commit = sourceCommit(source, run)
        CandidateFiles.releaseIdentity(tag, commit)
        cleanSource(source, commit, run)
        String localTag = run(['git', 'rev-parse', '--verify',
                'refs/tags/' + tag + '^{commit}'], source).trim()
        if (localTag != commit) throw new IllegalArgumentException('Source checkout differs from selected tag')
        String endpoint = 'repos/' + repo
        String tagEndpoint = endpoint + '/commits/' + segment('refs/tags/' + tag)
        String releaseEndpoint = endpoint + '/releases/tags/' + tag
        if (api(tagEndpoint, source, run).sha != commit) {
            throw new IllegalArgumentException('Live release tag differs from source checkout')
        }
        Map published = api(releaseEndpoint, source, run)
        Map snapshot = publishedSnapshot(published, tag)
        assetDir.mkdirs()
        run(['gh', 'release', 'download', tag, '--repo', repo, '--dir',
             assetDir.absolutePath, '--pattern', '*'], source)
        File manifest = new File(assetDir, 'candidate.json')
        CandidateManifest candidate = new CandidateManifest(manifest)
        Map<String, String> files = candidate.releaseFiles()
        if (candidate.data.release != tag || candidate.data.commit != commit ||
                candidate.data.selected_targets != [TARGET]) {
            throw new IllegalArgumentException('Published candidate differs from selected target or source')
        }
        publishedSnapshot(published, tag, files)
        if (localFiles(assetDir) != files || assetDir.listFiles().size() != files.size()) {
            throw new IllegalArgumentException('Downloaded release assets differ from candidate')
        }
        provenance(manifest, new File(assetDir, 'provenance.jsonl'), repo, run)
        Map metadata = Publication.resolve(Publication.metadata(manifest, source, snapshot.body as String),
                                            services, fetch)
        if (api(tagEndpoint, source, run).sha != commit ||
                publishedSnapshot(api(releaseEndpoint, source, run), tag, files) != snapshot) {
            throw new IllegalArgumentException('Published release changed during preflight')
        }
        checkFiles(assetDir, files)
        CandidateFiles.writeNew(metadataFile, metadata)
        Map record = (Map) metadata.records[0]
        File artifact = CandidateFiles.checked(assetDir, candidate.records[TARGET].artifact as Map)
        githubOutput.append(githubValues(record, candidate.specifications[TARGET], artifact), 'UTF-8')
        metadata
    }

    static Map prepareLegacyPublication(String tag, String repo, File sourceRoot, File assets,
                                        File output, File githubOutput, String services,
                                        Closure run, Closure fetch = null) {
        repository(repo)
        File source = sourceRoot.canonicalFile
        File assetDir = assets.canonicalFile
        File metadataFile = output.canonicalFile
        if (assetDir.exists() || metadataFile.exists() ||
                metadataFile.toPath().startsWith(assetDir.toPath()) ||
                assetDir.toPath().startsWith(source.toPath())) {
            throw new IllegalArgumentException('Use new output and asset paths outside source checkout')
        }
        Properties properties = new Properties()
        new File(source, 'gradle.properties').withInputStream { properties.load(it) }
        String version = properties.getProperty('mod_version')
        String minecraft = properties.getProperty('minecraft_version')
        String archive = properties.getProperty('archives_base_name')
        if (!version || !minecraft || !archive || !(minecraft ==~ /[0-9A-Za-z][0-9A-Za-z.-]*/) ||
                !(archive ==~ /[0-9A-Za-z][0-9A-Za-z._-]*/)) {
            throw new IllegalArgumentException('Incomplete legacy release properties')
        }
        String commit = sourceCommit(source, run)
        if (!(version ==~ /[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?/) ||
                tag != 'v' + version + '+mc' + minecraft) {
            throw new IllegalArgumentException('Legacy release tag differs from source properties')
        }
        cleanSource(source, commit, run)
        if (run(['git', 'rev-parse', '--verify', 'refs/tags/' + tag + '^{commit}'], source).trim() != commit) {
            throw new IllegalArgumentException('Source checkout differs from selected tag')
        }
        String endpoint = 'repos/' + repo
        String tagEndpoint = endpoint + '/commits/' + segment('refs/tags/' + tag)
        String releaseEndpoint = endpoint + '/releases/tags/' + tag
        if (api(tagEndpoint, source, run).sha != commit) {
            throw new IllegalArgumentException('Live release tag differs from source checkout')
        }
        boolean prerelease = version.contains('-')
        Map published = api(releaseEndpoint, source, run)
        Map snapshot = publishedSnapshot(published, tag, null, prerelease)
        String base = archive + '-' + version + '+mc' + minecraft
        String artifactName = base + '.jar'
        String sourcesName = base + '-sources.jar'
        Set<String> expectedNames = [artifactName, sourcesName] as Set
        if (!snapshot.assets.keySet().containsAll(expectedNames)) {
            throw new IllegalArgumentException('Published legacy assets differ from expected jars')
        }
        assetDir.mkdirs()
        run(['gh', 'release', 'download', tag, '--repo', repo, '--dir',
             assetDir.absolutePath, '--pattern', artifactName, '--pattern', sourcesName], source)
        Map<String, String> downloaded = localFiles(assetDir)
        if (downloaded.keySet() != expectedNames ||
                assetDir.listFiles().size() != downloaded.size() ||
                expectedNames.any { name -> snapshot.assets[name] != null &&
                        snapshot.assets[name] != 'sha256:' + downloaded[name] }) {
            throw new IllegalArgumentException('Downloaded legacy assets differ from release')
        }
        Map metadata = Publication.resolve(
                LegacyPublication.metadata(source, tag, snapshot.body as String, prerelease, assetDir, commit),
                services, fetch)
        if (api(tagEndpoint, source, run).sha != commit ||
                publishedSnapshot(api(releaseEndpoint, source, run), tag, null, prerelease) != snapshot ||
                localFiles(assetDir) != downloaded) {
            throw new IllegalArgumentException('Published legacy release changed during preflight')
        }
        CandidateFiles.writeNew(metadataFile, metadata)
        Map record = (Map) metadata.records[0]
        githubOutput.append(githubValues(record, [java: LegacyPublication.javaVersion(source)],
                new File(assetDir, artifactName)), 'UTF-8')
        metadata
    }

    private static void identity(Map report, Map expected, String target = null) {
        expected.each { key, value ->
            if (report[key] != value) throw new IllegalArgumentException('Validation result differs from candidate: ' + key)
        }
        if (target != null && report.target != target) {
            throw new IllegalArgumentException('Validation result differs from selected target: ' + target)
        }
    }

    private static void stable(File file, String hash, File bundle, String bundleHash) {
        if (CandidateFiles.sha256(file) != hash || CandidateFiles.sha256(bundle) != bundleHash) {
            throw new IllegalArgumentException('Candidate or attestation bundle changed during verification')
        }
    }

    private static void repository(String repo) {
        if (!(repo ==~ /[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+/)) {
            throw new IllegalArgumentException('Expected repository owner/name')
        }
    }

    private static String sourceCommit(File root, Closure run) {
        String commit = run(['git', 'rev-parse', 'HEAD'], root).trim()
        if (!(commit ==~ /[0-9a-f]{40}/)) throw new IllegalArgumentException('Invalid source commit')
        commit
    }

    private static void cleanSource(File root, String commit, Closure run) {
        if (sourceCommit(root, run) != commit ||
                run(['git', 'status', '--porcelain', '--untracked-files=all',
                     '--', '.', ':(top,exclude)docs/**'], root).trim()) {
            throw new IllegalArgumentException('Use a clean source checkout at the candidate commit')
        }
    }

    private static Map api(String endpoint, File cwd, Closure run) {
        Object parsed = CandidateFiles.parse(new StringReader(run(['gh', 'api', endpoint], cwd) as String))
        if (!(parsed instanceof Map)) throw new IllegalArgumentException('Invalid GitHub API response')
        parsed as Map
    }

    private static String segment(String value) {
        java.net.URLEncoder.encode(value, 'UTF-8')
    }

    private static Map draftSnapshot(Map release, String tag, Map files) {
        if (release.tag_name != tag || release.draft != true ||
                release.prerelease != tag.contains('-') ||
                !(release.id instanceof Integer || release.id instanceof Long ||
                        release.id instanceof BigInteger) || release.id <= 0 ||
                !(release.assets instanceof List)) {
            throw new IllegalArgumentException('Expected selected draft and release channel')
        }
        Map assets = [:]
        release.assets.each { asset ->
            if (!(asset instanceof Map) || !(asset.name instanceof String) ||
                    assets.containsKey(asset.name) || asset.state != 'uploaded') {
                throw new IllegalArgumentException('Duplicate or incomplete draft asset')
            }
            assets[asset.name] = asset.subMap(['id', 'size', 'digest', 'updated_at'])
        }
        if (assets.keySet() != files.keySet()) {
            throw new IllegalArgumentException('Draft asset names differ from candidate')
        }
        [id: release.id, tag: tag, assets: assets, name: release.name,
         body: release.body, prerelease: release.prerelease]
    }

    private static Map publishedSnapshot(Map release, String tag, Map files = null,
                                         Boolean expectedPrerelease = null) {
        if (release.tag_name != tag || release.draft != false ||
                release.prerelease != (expectedPrerelease == null ? tag.contains('-') : expectedPrerelease) ||
                release.immutable != true ||
                !(release.body instanceof String) || !release.body.trim() ||
                !(release.assets instanceof List)) {
            throw new IllegalArgumentException('Expected immutable published release in selected channel')
        }
        Map assets = [:]
        release.assets.each { asset ->
            String name = asset.name
            if (!(name instanceof String) || !name || name in ['.', '..'] ||
                    name.contains('/') || name.contains('\\') ||
                    assets.containsKey(name) || asset.state != 'uploaded') {
                throw new IllegalArgumentException('Duplicate, unsafe, or incomplete release asset')
            }
            assets[name] = asset.digest
        }
        if (files != null && (assets.keySet() != files.keySet() ||
                files.any { name, hash -> !(assets[name] in [null, 'sha256:' + hash]) })) {
            throw new IllegalArgumentException('Published assets differ from candidate')
        }
        [id: release.id, body: release.body, assets: assets]
    }

    private static Map<String, String> localFiles(File directory) {
        Map<String, String> files = [:]
        directory.listFiles().each { file ->
            if (file.isFile() && !Files.isSymbolicLink(file.toPath())) files[file.name] = CandidateFiles.sha256(file)
        }
        files
    }

    private static void checkFiles(File directory, Map files) {
        if (files.any { name, hash -> CandidateFiles.sha256(new File(directory, name)) != hash }) {
            throw new IllegalArgumentException('Local candidate changed during checks')
        }
    }

    private static void outputGuard(File manifest, File output) {
        if (output.exists() || output.canonicalFile.toPath().startsWith(manifest.parentFile.canonicalFile.toPath())) {
            throw new IllegalArgumentException('Use a new output file outside candidate directory')
        }
    }

    private static void overwrite(File output, Map report) {
        output.setText(JsonOutput.prettyPrint(JsonOutput.toJson(report)) + '\n', 'UTF-8')
    }

    private static void removeTree(File root) {
        Files.walk(root.toPath()).withCloseable { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private static String githubValues(Map record, Map specification, File artifact) {
        Map curseforge = record.curseforge
        Map values = [target: record.targets[0], java: specification.java.toString(),
                      artifact: artifact.absolutePath, cf_project_id: curseforge.project_id,
                      cf_game_versions: curseforge.version_labels.join(','),
                      cf_display_name: curseforge.display_name,
                      cf_release_type: curseforge.release_type,
                      cf_relations: curseforge.relations, cf_changelog: curseforge.changelog]
        values.collect { key, value ->
            String text = value.toString()
            if (text.contains('\n') || text.contains('\r')) {
                String marker = 'EOF_' + UUID.randomUUID().toString().replace('-', '')
                return key + '<<' + marker + '\n' + text + '\n' + marker
            }
            key + '=' + text
        }.join('\n') + '\n'
    }
}
