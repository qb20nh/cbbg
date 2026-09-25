package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.security.MessageDigest

import static org.junit.jupiter.api.Assertions.*

class PublicationTest {
    @TempDir File directory
    Map fixture
    File publication

    @BeforeEach
    void setUp() {
        fixture = CandidateFixture.create(directory)
        publication = new File(directory, 'publication.json')
    }

    private Map metadata() { Publication.metadata(fixture.file, fixture.root, 'Release notes') }

    private void writeMetadata(Map value) { publication.text = JsonOutput.toJson(value) }

    private static void fails(String message, Closure operation) {
        GradleException error = assertThrows(GradleException) { operation.call() }
        assertTrue(error.message.contains(message), error.message)
    }

    private static String sha512(File file) {
        MessageDigest.getInstance('SHA-512').digest(file.bytes).encodeHex().toString()
    }

    @Test
    void metadataUsesCheckedCandidateAndExactPublishingLabels() {
        Map result = metadata()
        assertEquals(1, result.schema)
        assertEquals('v1.4.0', result.release)
        assertEquals(CandidateFiles.sha256(fixture.file), result.manifest_sha256)
        assertEquals(1, result.records.size())
        Map record = result.records[0]
        assertEquals(['26.3-fabric'], record.targets)
        assertEquals(fixture.record.artifact, record.artifact)
        assertEquals('UBlXUQbC', record.modrinth.project_id)
        assertEquals('1.4.0+mc26.3-fabric', record.modrinth.version_number)
        assertEquals(['fabric-api'], record.modrinth.required_projects)
        assertEquals('1408371', record.curseforge.project_id)
        assertEquals(['26.3', 'Java 25', 'Fabric', 'Environment:Client'], record.curseforge.version_labels)
        assertEquals('fabric-api:requiredDependency', record.curseforge.relations)
        assertEquals('release', record.modrinth.version_type)
    }

    @Test
    void emptyNotesInvalidProjectsAndBadPackageStopMetadata() {
        fails('Release notes') { Publication.metadata(fixture.file, fixture.root, ' ') }
        File properties = new File(fixture.root, 'gradle.properties')
        properties << 'modrinth_project_id=other\n'
        fails('Invalid publishing project') { metadata() }
        properties.text = properties.text.replace('modrinth_project_id=other\n', '')
        new File(fixture.bundle, fixture.record.artifact.path).bytes = 'changed'.bytes
        assertThrows(GradleException) { metadata() }
    }

    @Test
    void checkedRecordRejectsEditsButAcceptsResolvedCurseForgeIds() {
        Map value = metadata()
        value.records[0].curseforge.game_versions = ['1', '2', '3', '4']
        writeMetadata(value)
        List checked = Publication.checkedRecord(fixture.file, publication, '26.3-fabric', fixture.root)
        assertEquals(['1', '2', '3', '4'], checked[1].curseforge.game_versions)
        assertEquals(CandidateFiles.sha256(publication), checked[2])
        value.records[0].curseforge.version_labels << 'Forge'
        writeMetadata(value)
        fails('CurseForge metadata differs') {
            Publication.checkedRecord(fixture.file, publication, '26.3-fabric', fixture.root)
        }
        value = metadata()
        value.records[0].modrinth.loaders << 'quilt'
        writeMetadata(value)
        fails('checked candidate') {
            Publication.checkedRecord(fixture.file, publication, '26.3-fabric', fixture.root)
        }
    }

    @Test
    void destinationLookupSelectsServicesAndRequiresEveryLabel() {
        Map value = metadata()
        Map original = CandidateFiles.parse(new StringReader(JsonOutput.toJson(value))) as Map
        List versions = [[id: 1, name: '26.3', gameVersionTypeID: 10],
                         [id: 2, name: 'Java 25', gameVersionTypeID: 11],
                         [id: 3, name: 'Fabric', gameVersionTypeID: 12],
                         [id: 4, name: 'Client', gameVersionTypeID: 13]]
        List types = [[id: 10, name: 'Minecraft 26.3'], [id: 13, name: 'Environment']]
        List<String> urls = []
        Closure fetch = { String url, Map headers ->
            urls.add(url)
            if (url.endsWith('/game_version')) return [[version: '26.3']]
            if (url.endsWith('/loader')) return [[name: 'fabric']]
            if (url.endsWith('/versions')) return versions
            if (url.endsWith('/version-types')) return types
            throw new AssertionError(url)
        }
        Map resolved = Publication.resolve(value, 'both', fetch, 'fixture')
        assertEquals(['1', '2', '3', '4'], resolved.records[0].curseforge.game_versions)
        assertEquals(original, value)
        assertEquals(4, urls.size())
        urls.clear()
        assertFalse(Publication.resolve(value, 'modrinth', fetch, null).records[0].curseforge
                .containsKey('game_versions'))
        assertEquals(2, urls.size())
        urls.clear()
        assertEquals(['1', '2', '3', '4'],
                Publication.resolve(value, 'curseforge', fetch, 'fixture').records[0].curseforge.game_versions)
        assertEquals(2, urls.size())
        versions.remove(3)
        fails('refusing partial metadata') { Publication.resolve(value, 'curseforge', fetch, 'fixture') }
        fails('Invalid publishing service') { Publication.resolve(value, 'other', fetch, 'fixture') }
    }

    @Test
    void minecraftLabelIsDisambiguatedByVersionType() {
        List versions = [[id: 1, name: '26.3', gameVersionTypeID: 10],
                         [id: 2, name: '26.3', gameVersionTypeID: 20]]
        List types = [[id: 10, slug: 'minecraft-release'], [id: 20, slug: 'modloader']]
        assertEquals(['1'], Publication.curseforgeVersionIds('26.3', ['26.3'], versions, types))
        versions << [id: 3, name: '26.3', gameVersionTypeID: 10]
        fails('resolved to 2 entries') {
            Publication.curseforgeVersionIds('26.3', ['26.3'], versions, types)
        }
    }

    @Test
    void minecraftVersionGroupsWorkAcrossHistoricalReleaseLines() {
        for (String mc : ['1.7.10', '1.12.2', '1.20.1', '1.21.1', '26.2']) {
            for (Map group : [[id: 10, name: 'Minecraft ' + mc],
                              [id: 10, name: mc, slug: 'minecraft-' + mc.replace('.', '-')]]) {
                List types = [group, [id: 20, name: 'Forge', slug: 'forge']]
                List versions = [[id: 100, name: mc, gameVersionTypeID: 10],
                                 [id: 101, name: mc, gameVersionTypeID: 1],
                                 [id: 102, name: mc, gameVersionTypeID: 20]]
                assertEquals(['100'], Publication.curseforgeVersionIds(mc, [mc], versions, types))
                versions << [id: 103, name: mc, gameVersionTypeID: 10]
                fails('resolved to 2 entries') {
                    Publication.curseforgeVersionIds(mc, [mc], versions, types)
                }
            }
        }
    }

    @Test
    void curseForgeLookupUsesHeaderAndFailsClosedOnAuthFailure() {
        Map value = metadata()
        List<String> urls = []
        Closure fetch = { String url, Map headers ->
            urls.add(url)
            assertEquals('fixture-token', headers['X-Api-Token'])
            throw new GradleException('HTTP 401')
        }
        fails('HTTP 401') { Publication.resolve(value, 'curseforge', fetch, 'fixture-token') }
        assertEquals(1, urls.size())
        fails('CF_API_TOKEN') { Publication.resolve(value, 'curseforge', fetch, null) }
        assertEquals(1, urls.size())
    }

    private Map existing(Map record) {
        Map upload = record.modrinth
        [id: 'Version1', project_id: upload.project_id, name: upload.version_name,
         version_number: upload.version_number, version_type: upload.version_type,
         status: 'listed', changelog: upload.changelog,
         loaders: ['fabric'], game_versions: ['26.3'],
         dependencies: [[project_id: 'P7dR8mSH', dependency_type: 'required']],
         files: ['artifact', 'sources'].collect { String kind ->
             File file = new File(fixture.bundle, record[kind].path)
             [filename: file.name, hashes: [sha512: sha512(file)],
              primary: kind == 'artifact', file_type: kind == 'sources' ? 'sources-jar' : null]
         }]
    }

    private Closure fetch(List versions, List<String> urls) {
        { String url ->
            urls.add(url)
            if (url.endsWith('/version')) return versions
            if (url.endsWith('/fabric-api')) return [id: 'P7dR8mSH']
            return [id: 'UBlXUQbC', slug: 'cbbg']
        }
    }

    @Test
    void retryReusesExactRemoteVersionAndPlansNewUpload() {
        Map value = metadata()
        writeMetadata(value)
        List<String> urls = []
        Map remote = existing(value.records[0])
        Map result = Publication.plan(fixture.file, publication, '26.3-fabric', fixture.root,
                fetch([remote], urls))
        assertEquals('reuse', result.action)
        assertEquals('Version1', result.version_id)
        assertEquals('https://modrinth.com/mod/cbbg/version/Version1', result.url)
        assertEquals(value.records[0].artifact, result.artifact)
        assertEquals(3, urls.size())
        result = Publication.plan(fixture.file, publication, '26.3-fabric', fixture.root, fetch([], []))
        assertEquals('upload', result.action)
        assertFalse(result.containsKey('version_id'))
    }

    @Test
    void retryRejectsRemoteConflictsAndLocalEditsBeforeRequests() {
        Map value = metadata()
        writeMetadata(value)
        Map remote = existing(value.records[0])
        for (String field : ['name', 'version_type', 'status', 'changelog', 'project_id']) {
            Map changed = new LinkedHashMap(remote)
            changed[field] = 'other'
            fails('different metadata') {
                Publication.plan(fixture.file, publication, '26.3-fabric', fixture.root, fetch([changed], []))
            }
        }
        Map changed = new LinkedHashMap(remote)
        changed.files = remote.files.collect { new LinkedHashMap(it) }
        changed.files[0].hashes = [sha512: '0' * 128]
        fails('file differs') {
            Publication.plan(fixture.file, publication, '26.3-fabric', fixture.root, fetch([changed], []))
        }
        changed = new LinkedHashMap(remote)
        changed.dependencies = []
        fails('different dependencies') {
            Publication.plan(fixture.file, publication, '26.3-fabric', fixture.root, fetch([changed], []))
        }
        fails('Multiple Modrinth versions') {
            Publication.plan(fixture.file, publication, '26.3-fabric', fixture.root, fetch([remote, remote], []))
        }
        for (List files : [remote.files[0..0], remote.files + [remote.files[0]]]) {
            changed = new LinkedHashMap(remote)
            changed.files = files
            fails('different files') {
                Publication.plan(fixture.file, publication, '26.3-fabric', fixture.root, fetch([changed], []))
            }
        }
        for (Map alteration : [[filename: 'other.jar'], [primary: false], [file_type: 'sources-jar']]) {
            changed = new LinkedHashMap(remote)
            changed.files = remote.files.collect { new LinkedHashMap(it) }
            changed.files[0].putAll(alteration)
            assertThrows(GradleException) {
                Publication.plan(fixture.file, publication, '26.3-fabric', fixture.root, fetch([changed], []))
            }
        }
        value.records[0].modrinth.loaders << 'quilt'
        writeMetadata(value)
        List<String> urls = []
        fails('checked candidate') {
            Publication.plan(fixture.file, publication, '26.3-fabric', fixture.root, fetch([], urls))
        }
        assertEquals([], urls)
    }

    @Test
    void retryNeverTreatsLocalChangesOrReadFailureAsUploadPermission() {
        Map value = metadata()
        writeMetadata(value)
        File artifact = new File(fixture.bundle, value.records[0].artifact.path)
        artifact.bytes = 'changed'.bytes
        assertThrows(GradleException) {
            Publication.plan(fixture.file, publication, '26.3-fabric', fixture.root, fetch([], []))
        }
        // Regenerate the signed references and package for an independent remote-read check.
        fixture = CandidateFixture.create(new File(directory, 'second'))
        value = metadata()
        writeMetadata(value)
        IOException failure = new IOException('request failed')
        IOException observed = assertThrows(IOException) {
            Publication.plan(fixture.file, publication, '26.3-fabric', fixture.root,
                    { String url -> throw failure })
        }
        assertSame(failure, observed)
    }

    @Test
    void legacyRetryPlanUsesAssetsWithoutCandidateManifest() {
        File root = new File(directory, 'legacy-source')
        File assets = new File(directory, 'legacy-assets')
        new File(root, 'src/main/resources').mkdirs()
        assets.mkdirs()
        new File(root, 'gradle.properties').text = '''mod_version=1.4.0
minecraft_version=26.2
archives_base_name=cbbg
modrinth_project_id=UBlXUQbC
curseforge_project_id=1408371
'''
        Map source = [id: 'cbbg', depends: [java: '>=25', fabricloader: '>=0.19.3',
                minecraft: '~${minecraft_version}', 'fabric-api': '*']]
        new File(root, 'src/main/resources/fabric.mod.json').text = JsonOutput.toJson(source)
        Map packaged = [id: 'cbbg', version: '1.4.0+mc26.2', environment: 'client',
                depends: [java: '>=25', fabricloader: '>=0.19.3', minecraft: '~26.2', 'fabric-api': '*']]
        CandidateFixture.archive(new File(assets, 'cbbg-1.4.0+mc26.2.jar'),
                ['fabric.mod.json': JsonOutput.toJson(packaged).bytes,
                 'example/Client.class': [0xca, 0xfe, 0xba, 0xbe, 0, 0, 0, 69] as byte[]])
        CandidateFixture.archive(new File(assets, 'cbbg-1.4.0+mc26.2-sources.jar'),
                ['example/Client.java': 'class Client {}'.bytes])
        Map legacy = LegacyPublication.metadata(root, 'v1.4.0+mc26.2', 'Release notes', false, assets)
        File legacyMetadata = new File(directory, 'legacy-publication.json')
        legacyMetadata.text = JsonOutput.toJson(legacy)
        Map result = Publication.plan(null, legacyMetadata, '26.2-fabric', root,
                fetch([], []), assets)
        assertEquals('upload', result.action)
        assertEquals('1.4.0+mc26.2', result.version_number)
        assertFalse(result.containsKey('manifest_sha256'))
        fails('selected target') {
            Publication.plan(null, legacyMetadata, 'wrong', root, fetch([], []), assets)
        }
    }
}
