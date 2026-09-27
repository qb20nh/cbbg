package cbbg.gradle

import org.gradle.api.GradleException

import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class LegacyPublication {
    static String javaVersion(File sourceRoot) {
        Map source = sourceMetadata(sourceRoot)
        Object requirement = source.depends instanceof Map ? source.depends.java : null
        if (!(requirement instanceof String) || !(requirement ==~ />=\s*\d+/)) {
            throw new GradleException('Unsupported Java requirement: ' + requirement)
        }
        (requirement =~ /\d+/)[0]
    }

    static Map metadata(File sourceRoot, String tag, String body, boolean prerelease,
                        File assets, String commit = null) {
        if (!(body instanceof String) || !body.trim()) throw new GradleException('Release notes are empty')
        def match = tag =~ /^v([0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?)\+mc([0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)$/
        if (!match.matches()) throw new GradleException('Invalid historical release tag: ' + tag)
        String taggedVersion = match[0][1]
        String taggedMinecraft = match[0][3]
        if (taggedVersion.contains('-') != prerelease) {
            throw new GradleException('GitHub prerelease status does not match the release tag')
        }
        Map properties = properties(sourceRoot)
        String modVersion = property(properties, 'mod_version', /[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?/)
        String minecraft = property(properties, 'minecraft_version', /[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*/)
        String baseName = property(properties, 'archives_base_name', /[A-Za-z0-9][A-Za-z0-9._-]*/)
        String modrinth = property(properties, 'modrinth_project_id', /[A-Za-z0-9]+/)
        String curseforge = property(properties, 'curseforge_project_id', /[1-9][0-9]*/)
        if (taggedVersion != modVersion) {
            throw new GradleException('Release tag does not match gradle.properties mod_version')
        }
        if (taggedMinecraft != minecraft) {
            throw new GradleException('Release tag Minecraft version does not match gradle.properties minecraft_version')
        }
        String java = javaVersion(sourceRoot)
        String artifactVersion = modVersion + '+mc' + minecraft
        String artifactName = baseName + '-' + artifactVersion + '.jar'
        String sourcesName = baseName + '-' + artifactVersion + '-sources.jar'
        File artifact = new File(assets, artifactName)
        File sources = new File(assets, sourcesName)
        if (!artifact.isFile()) throw new GradleException('Missing downloaded release artifact: ' + artifactName)
        if (!sources.isFile()) throw new GradleException('Missing downloaded release artifact: ' + sourcesName)
        try {
            verifyArtifacts(artifact, sources, sourceMetadata(sourceRoot), artifactVersion, minecraft, Integer.parseInt(java))
        } catch (IOException error) {
            throw new GradleException('Invalid release archive: ' + error.message, error)
        }
        Map record = [targets: [minecraft + '-fabric'],
                      artifact: CandidateFiles.reference(assets, artifactName),
                      sources: CandidateFiles.reference(assets, sourcesName),
                      modrinth: [project_id: modrinth, version_number: artifactVersion,
                                 version_name: 'cbbg ' + artifactVersion,
                                 version_type: prerelease ? 'beta' : 'release',
                                 game_versions: [minecraft], loaders: ['fabric'],
                                 required_projects: ['fabric-api'], changelog: body],
                      curseforge: [project_id: curseforge, display_name: 'cbbg ' + artifactVersion,
                                   release_type: prerelease ? 'beta' : 'release',
                                   version_labels: [minecraft, 'Java ' + java, 'Fabric', 'Environment:Client'],
                                   relations: 'fabric-api:requiredDependency',
                                   changelog: body, changelog_type: 'markdown']]
        Map result = [schema: 1, legacy: true, release: tag, records: [record]]
        if (commit != null) {
            if (!(commit ==~ /[0-9a-f]{40}/)) throw new GradleException('Invalid source commit')
            result.source_commit = commit
        }
        result
    }

    static List checkedRecord(File metadataFile, File sourceRoot, File assets) {
        String before = CandidateFiles.sha256(metadataFile)
        Object value = CandidateFiles.read(metadataFile)
        if (!(value instanceof Map) || value.legacy != true ||
                !(value.records instanceof List) || value.records.size() != 1) {
            throw new GradleException('Invalid legacy publishing metadata')
        }
        Map published = (Map) value
        Map record = published.records[0]
        String tag = published.release
        boolean prerelease = tag instanceof String && tag =~ /^v[^+]*-/
        Map expected = metadata(sourceRoot, tag, record.modrinth?.changelog,
                prerelease, assets, published.source_commit)
        Map expectedRecord = expected.records[0]
        if (['schema', 'legacy', 'release', 'source_commit'].any { published[it] != expected[it] } ||
                ['targets', 'artifact', 'sources', 'modrinth'].any { record[it] != expectedRecord[it] }) {
            throw new GradleException('Publishing metadata differs from the checked legacy release')
        }
        Map curseforge = record.curseforge instanceof Map ? new LinkedHashMap(record.curseforge) : [:]
        curseforge.remove('game_versions')
        if (curseforge != expectedRecord.curseforge) {
            throw new GradleException('CurseForge metadata differs from the checked legacy release')
        }
        ['artifact', 'sources'].each { CandidateFiles.checked(assets, (Map) record[it]) }
        if (CandidateFiles.sha256(metadataFile) != before) {
            throw new GradleException('Publishing metadata changed during validation')
        }
        [expected, record, before]
    }

    private static Map sourceMetadata(File sourceRoot) {
        Object value = CandidateFiles.read(new File(sourceRoot, 'src/main/resources/fabric.mod.json'))
        if (!(value instanceof Map)) throw new GradleException('Invalid source Fabric metadata')
        (Map) value
    }

    private static Map properties(File sourceRoot) {
        File file = new File(sourceRoot, 'gradle.properties')
        if (!file.isFile()) throw new GradleException('Missing gradle.properties')
        Map<String, List<String>> values = [:].withDefault { [] }
        file.readLines('UTF-8').each { String line ->
            int equals = line.indexOf('=')
            if (equals > 0) values[line.substring(0, equals)].add(line.substring(equals + 1).replace('\r', ''))
        }
        values
    }

    private static String property(Map values, String key, String pattern) {
        List<String> found = values[key] ?: []
        if (found.size() != 1 || !(found[0] ==~ pattern)) {
            throw new GradleException('Invalid gradle.properties value: ' + key)
        }
        found[0]
    }

    private static void verifyArtifacts(File artifact, File sources, Map source,
                                        String artifactVersion, String minecraft, int java) {
        new ZipFile(artifact).withCloseable { ZipFile zip ->
            Set<String> names = checkArchive(zip)
            if (!names.contains('fabric.mod.json')) throw new GradleException('Missing packaged fabric.mod.json')
            byte[] metadataBytes = zip.getInputStream(zip.getEntry('fabric.mod.json')).withCloseable { it.bytes }
            String metadataText = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(metadataBytes)).toString()
            Object parsed = CandidateFiles.parse(new StringReader(metadataText))
            if (!(parsed instanceof Map)) throw new GradleException('Invalid packaged Fabric metadata')
            Map packaged = (Map) parsed
            [id: source.id, version: artifactVersion, environment: 'client'].each { key, value ->
                if (packaged[key] != value) throw new GradleException('Packaged ' + key + ' does not match release')
            }
            if (!(packaged.depends instanceof Map) || packaged.depends.minecraft != '~' + minecraft) {
                throw new GradleException('Packaged Minecraft requirement does not match release')
            }
            for (String key : ['java', 'fabricloader', 'fabric-api']) {
                if (!packaged.depends.containsKey(key) || packaged.depends[key] != source.depends[key]) {
                    throw new GradleException('Packaged ' + key + ' requirement does not match release source')
                }
            }
            for (String name : names) {
                if (!name.endsWith('.class') || name.startsWith('META-INF/versions/')) continue
                byte[] header = zip.getInputStream(zip.getEntry(name)).withCloseable { it.readNBytes(8) }
                if (header.length < 8) throw new GradleException('Truncated class header: ' + name)
                int major = ((header[6] & 255) << 8) | (header[7] & 255)
                if (major > java + 44) {
                    throw new GradleException(name + ' requires a newer Java than the declared minimum')
                }
            }
        }
        new ZipFile(sources).withCloseable { checkArchive(it) }
    }

    private static Set<String> checkArchive(ZipFile zip) {
        Set<String> names = [] as Set
        for (ZipEntry entry : zip.entries()) {
            if (!names.add(entry.name)) throw new GradleException('Duplicate release JAR entry: ' + entry.name)
            CRC32 crc = new CRC32()
            crc.update(zip.getInputStream(entry).withCloseable { it.bytes })
            if (crc.value != entry.crc) throw new GradleException('Corrupt release JAR entry: ' + entry.name)
        }
        names
    }
}
