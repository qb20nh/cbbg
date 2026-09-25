package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.api.GradleException

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

class Publication {
    private static final String MODRINTH_API = 'https://api.modrinth.com/v2'
    private static final String CURSEFORGE_API = 'https://minecraft.curseforge.com/api/game'

    static Map metadata(File candidate, File sourceRoot, String notes) {
        if (!(notes instanceof String) || !notes.trim()) throw new GradleException('Release notes are empty')
        String before = CandidateFiles.sha256(candidate)
        CandidateManifest manifest = new CandidateManifest(candidate)
        if (!(manifest.data.selected_targets instanceof List) || manifest.data.selected_targets.isEmpty()) {
            throw new GradleException('Candidate requires an explicit target selection')
        }
        Map projects = projectIds(new File(sourceRoot, 'gradle.properties'))
        manifest.verifyPackages(sourceRoot)
        List<Map> records = []
        for (String id : manifest.data.selected_targets) {
            Map target = manifest.records[id]
            Map specification = manifest.specifications[id]
            if (specification.loader != 'fabric') {
                throw new GradleException('Publishing metadata is not configured for ' + id)
            }
            String minecraft = specification.minecraft
            String version = manifest.data.release.substring(1) + '+mc' + minecraft + '-fabric'
            String channel = manifest.data.release.contains('-') ? 'beta' : 'release'
            records.add([
                    targets: [id], artifact: target.artifact, sources: target.sources,
                    modrinth: [project_id: projects.modrinth, version_number: version,
                               version_name: 'cbbg ' + version, version_type: channel,
                               game_versions: [minecraft], loaders: [specification.loader],
                               required_projects: ['fabric-api'], changelog: notes],
                    curseforge: [project_id: projects.curseforge, display_name: 'cbbg ' + version,
                                 release_type: channel,
                                 version_labels: [minecraft, 'Java ' + specification.java,
                                                  'Fabric', 'Environment:Client'],
                                 relations: 'fabric-api:requiredDependency',
                                 changelog: notes, changelog_type: 'markdown']
            ])
        }
        if (CandidateFiles.sha256(candidate) != before) {
            throw new GradleException('Candidate changed during metadata generation')
        }
        [schema: 1, release: manifest.data.release, source_commit: manifest.data.commit,
         manifest_sha256: before, records: records]
    }

    static List checkedRecord(File candidate, File metadataFile, String target, File sourceRoot) {
        String before = CandidateFiles.sha256(metadataFile)
        Object value = CandidateFiles.read(metadataFile)
        if (!(value instanceof Map) || !(value.records instanceof List)) {
            throw new GradleException('Invalid publishing metadata')
        }
        Map published = (Map) value
        List<Map> matches = published.records.findAll { it instanceof Map && it.targets == [target] }
        if (matches.size() != 1) throw new GradleException('Expected one publishing record for the selected target')
        Map record = matches[0]
        Map expected = metadata(candidate, sourceRoot, record.modrinth?.changelog)
        List<Map> expectedRecords = expected.records.findAll { it.targets == [target] }
        if (expectedRecords.size() != 1 ||
                ['schema', 'release', 'source_commit', 'manifest_sha256'].any { published[it] != expected[it] } ||
                ['targets', 'artifact', 'sources', 'modrinth'].any { record[it] != expectedRecords[0][it] }) {
            throw new GradleException('Publishing metadata differs from the checked candidate')
        }
        Map curseforge = record.curseforge instanceof Map ? new LinkedHashMap(record.curseforge) : [:]
        curseforge.remove('game_versions')
        if (curseforge != expectedRecords[0].curseforge) {
            throw new GradleException('CurseForge metadata differs from the checked candidate')
        }
        if (CandidateFiles.sha256(metadataFile) != before) {
            throw new GradleException('Publishing metadata changed during validation')
        }
        [expected, record, before]
    }

    static Map resolve(Map metadata, String services = 'both', Closure fetch = null,
                       String cfToken = System.getenv('CF_API_TOKEN')) {
        if (!(services in ['both', 'modrinth', 'curseforge'])) {
            throw new GradleException('Invalid publishing service selection')
        }
        Map resolved = (Map) CandidateFiles.parse(new StringReader(JsonOutput.toJson(metadata)))
        List modrinthVersions = []
        List modrinthLoaders = []
        List curseforgeVersions = []
        List curseforgeTypes = []
        if (services in ['both', 'modrinth']) {
            modrinthVersions = request(fetch, MODRINTH_API + '/tag/game_version', [:]) as List
            modrinthLoaders = request(fetch, MODRINTH_API + '/tag/loader', [:]) as List
        }
        if (services in ['both', 'curseforge']) {
            if (!cfToken) throw new GradleException('CF_API_TOKEN is required for CurseForge lookup')
            Map headers = ['X-Api-Token': cfToken]
            curseforgeVersions = request(fetch, CURSEFORGE_API + '/versions', headers) as List
            curseforgeTypes = request(fetch, CURSEFORGE_API + '/version-types', headers) as List
        }
        for (Map record : (List<Map>) resolved.records) {
            Map modrinth = record.modrinth
            if (services in ['both', 'modrinth']) {
                if (!modrinthVersions.collect { it.version }.containsAll(modrinth.game_versions)) {
                    throw new GradleException('Modrinth does not recognize the selected Minecraft versions')
                }
                if (!modrinthLoaders.collect { it.name }.containsAll(modrinth.loaders)) {
                    throw new GradleException('Modrinth does not recognize the selected loaders')
                }
            }
            if (services in ['both', 'curseforge']) {
                record.curseforge.game_versions = curseforgeVersionIds(
                        modrinth.game_versions[0], record.curseforge.version_labels,
                        curseforgeVersions, curseforgeTypes)
            }
        }
        resolved
    }

    static List<String> curseforgeVersionIds(String mc, List labels, List versions, List types) {
        List<String> ids = []
        for (String label : labels) {
            int colon = label.indexOf(':')
            String typeName = colon >= 0 ? label.substring(0, colon) : null
            String name = colon >= 0 ? label.substring(colon + 1) : label
            List<Map> matches = versions.findAll { it.name == name || it.slug == name }
            if (typeName != null) {
                Set typeIds = types.findAll { it.name == typeName || it.slug == typeName }.collect { it.id } as Set
                matches = matches.findAll { typeIds.contains(it.gameVersionTypeID) }
            } else if (name == mc) {
                Set typeIds = types.findAll { Map type ->
                    (type.slug instanceof String && type.slug.startsWith('minecraft-')) ||
                            (type.name instanceof String && type.name ==~ /^Minecraft(\s.*)?$/)
                }.collect { it.id } as Set
                matches = matches.findAll { typeIds.contains(it.gameVersionTypeID) }
            }
            if (matches.size() != 1) {
                throw new GradleException("CurseForge label '${label}' resolved to ${matches.size()} entries; refusing partial metadata")
            }
            ids.add(matches[0].id.toString())
        }
        ids
    }

    static Map plan(File candidate, File metadataFile, String target, File sourceRoot,
                    Closure fetch = null, File legacyAssets = null) {
        if (candidate == null && legacyAssets == null) {
            throw new GradleException('Modrinth plan requires a candidate or legacy assets')
        }
        List checked = candidate != null
                ? checkedRecord(candidate, metadataFile, target, sourceRoot)
                : LegacyPublication.checkedRecord(metadataFile, sourceRoot, legacyAssets)
        Map expected = (Map) checked[0]
        Map record = (Map) checked[1]
        String metadataHash = (String) checked[2]
        if (record.targets != [target]) throw new GradleException('Publishing target differs from selected target')
        File assets = candidate != null ? candidate.parentFile : legacyAssets
        Map upload = record.modrinth
        Map project = request(fetch, MODRINTH_API + '/project/' + segment(upload.project_id), [:]) as Map
        if (project.id != upload.project_id || !(project.slug instanceof String) ||
                !(project.slug ==~ /[\w-]+/)) {
            throw new GradleException('Unexpected Modrinth project')
        }
        Object versionResponse = request(fetch, MODRINTH_API + '/project/' + segment(project.id) + '/version', [:])
        if (!(versionResponse instanceof List) ||
                versionResponse.any { !(it instanceof Map) || !(it.version_number instanceof String) }) {
            throw new GradleException('Invalid Modrinth version list')
        }
        List matches = versionResponse.findAll { it.version_number == upload.version_number }
        if (matches.size() > 1) throw new GradleException('Multiple Modrinth versions use the requested version number')
        Map result = [schema: 1, service: 'modrinth', target: target,
                      metadata_sha256: metadataHash,
                      project_id: project.id, version_number: upload.version_number,
                      artifact: record.artifact, sources: record.sources, action: 'upload']
        if (candidate != null) result.manifest_sha256 = expected.manifest_sha256
        if (!matches.isEmpty()) {
            Map existing = matches[0]
            Map fields = [project_id: project.id, name: upload.version_name,
                    version_number: upload.version_number, version_type: upload.version_type,
                    changelog: upload.changelog, status: 'listed']
            if (fields.any { key, value -> existing[key] != value }) {
                throw new GradleException('Existing Modrinth version has different metadata')
            }
            for (String key : ['loaders', 'game_versions']) {
                if (!(existing[key] instanceof List) || existing[key].toSorted() != upload[key].toSorted()) {
                    throw new GradleException('Existing Modrinth version has different ' + key)
                }
            }
            List<List> dependencies = []
            for (String id : upload.required_projects) {
                Map dependency = request(fetch, MODRINTH_API + '/project/' + segment(id), [:]) as Map
                if (!(dependency.id instanceof String) || !(dependency.id ==~ /[A-Za-z0-9]+/)) {
                    throw new GradleException('Invalid Modrinth dependency project')
                }
                dependencies.add([dependency.id, null, null, 'required'])
            }
            if (!(existing.dependencies instanceof List) || existing.dependencies.any { !(it instanceof Map) }) {
                throw new GradleException('Invalid Modrinth dependencies')
            }
            List<List> actual = existing.dependencies.collect {
                [it.project_id, it.version_id, it.file_name, it.dependency_type]
            }
            if (actual.size() != dependencies.size() || actual.toSet() != dependencies.toSet()) {
                throw new GradleException('Existing Modrinth version has different dependencies')
            }
            if (!(existing.files instanceof List) || existing.files.size() != 2) {
                throw new GradleException('Existing Modrinth version has different files')
            }
            for (String kind : ['artifact', 'sources']) {
                File file = CandidateFiles.checked(assets, (Map) record[kind])
                List fileMatches = existing.files.findAll { it instanceof Map && it.filename == file.name }
                if (fileMatches.size() != 1) {
                    throw new GradleException('Existing Modrinth version has different filenames')
                }
                Map item = fileMatches[0]
                String sha512 = digest(file, 'SHA-512')
                if (item.hashes?.sha512 != sha512 || item.primary != (kind == 'artifact') ||
                        item.file_type != (kind == 'sources' ? 'sources-jar' : null)) {
                    throw new GradleException('Existing Modrinth file differs from candidate: ' + kind)
                }
            }
            if (!(existing.id instanceof String) || !(existing.id ==~ /[A-Za-z0-9]+/)) {
                throw new GradleException('Invalid Modrinth version ID')
            }
            result.action = 'reuse'
            result.version_id = existing.id
            result.url = 'https://modrinth.com/mod/' + project.slug + '/version/' + existing.id
        }
        ['artifact', 'sources'].each { CandidateFiles.checked(assets, (Map) record[it]) }
        if ((candidate != null && CandidateFiles.sha256(candidate) != expected.manifest_sha256) ||
                CandidateFiles.sha256(metadataFile) != metadataHash) {
            throw new GradleException('Candidate or publishing metadata changed during the Modrinth check')
        }
        result
    }

    private static Map projectIds(File properties) {
        List<String> lines = properties.readLines('UTF-8')
        Map result = [:]
        [[key: 'modrinth_project_id', pattern: /[A-Za-z0-9]+/, name: 'modrinth'],
         [key: 'curseforge_project_id', pattern: /[1-9][0-9]*/, name: 'curseforge']].each { item ->
            List<String> matches = lines.findAll { it.startsWith(item.key + '=') }
                    .collect { it.substring(item.key.length() + 1) }
            if (matches.size() != 1 || !(matches[0] ==~ item.pattern)) {
                throw new GradleException('Invalid publishing project: ' + item.key)
            }
            result[item.name] = matches[0]
        }
        result
    }

    private static Object request(Closure fetch, String url, Map<String, String> headers) {
        if (fetch != null) {
            return fetch.maximumNumberOfParameters > 1 ? fetch.call(url, headers) : fetch.call(url)
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET()
        headers.each { key, value -> builder.header(key, value) }
        HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new GradleException('Publishing lookup failed: HTTP ' + response.statusCode() + ' ' + url)
        }
        CandidateFiles.parse(new StringReader(response.body()))
    }

    private static String segment(String value) {
        URLEncoder.encode(value, 'UTF-8').replace('+', '%20')
    }

    private static String digest(File file, String algorithm) {
        MessageDigest hash = MessageDigest.getInstance(algorithm)
        file.withInputStream { stream ->
            byte[] buffer = new byte[65536]
            int size
            while ((size = stream.read(buffer)) != -1) hash.update(buffer, 0, size)
        }
        hash.digest().encodeHex().toString()
    }
}
