package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32

import static org.junit.jupiter.api.Assertions.*

class LegacyPublicationTest {
    @TempDir File directory
    File sourceRoot
    File assets
    File artifact
    File sources
    File metadataFile
    Map sourceMetadata

    @BeforeEach
    void setUp() {
        sourceRoot = new File(directory, 'source')
        assets = new File(directory, 'assets')
        assets.mkdirs()
        new File(sourceRoot, 'src/main/resources').mkdirs()
        new File(sourceRoot, 'gradle.properties').text = '''mod_version=1.4.0
minecraft_version=26.2
archives_base_name=cbbg
modrinth_project_id=UBlXUQbC
curseforge_project_id=1408371
'''
        sourceMetadata = [id: 'cbbg', depends: [minecraft: '~${minecraft_version}',
                java: '>=25', fabricloader: '>=0.19.3', 'fabric-api': '*']]
        new File(sourceRoot, 'src/main/resources/fabric.mod.json').text = JsonOutput.toJson(sourceMetadata)
        artifact = new File(assets, 'cbbg-1.4.0+mc26.2.jar')
        sources = new File(assets, 'cbbg-1.4.0+mc26.2-sources.jar')
        metadataFile = new File(directory, 'publication.json')
        writeJars()
    }

    private static byte[] classHeader(int major) {
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt((int) 0xCAFEBABE)
                .putShort((short) 0).putShort((short) major).array()
    }

    private static void archive(File file, Map<String, byte[]> entries) {
        new ZipOutputStream(file.newOutputStream()).withCloseable { ZipOutputStream zip ->
            entries.each { String name, byte[] bytes ->
                zip.putNextEntry(new ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private void writeJars(Map override = [:], int major = 69, String mc = '26.2', int java = 25) {
        Map packaged = [id: 'cbbg', version: '1.4.0+mc' + mc, environment: 'client',
                depends: [minecraft: '~' + mc, java: '>=' + java,
                          fabricloader: '>=0.19.3', 'fabric-api': '*']]
        packaged.putAll(override)
        archive(artifact, ['fabric.mod.json': JsonOutput.toJson(packaged).bytes,
                           'example/Client.class': classHeader(major)])
        archive(sources, ['example/Client.java': 'class Client {}'.bytes])
    }

    private static void fails(String message, Closure operation) {
        GradleException error = assertThrows(GradleException) { operation.call() }
        assertTrue(error.message.contains(message), error.message)
    }

    private Map publication(String tag = 'v1.4.0+mc26.2', boolean prerelease = false) {
        LegacyPublication.metadata(sourceRoot, tag, 'Release notes', prerelease, assets, 'a' * 40)
    }

    @Test void malformedUtf8MetadataIsRejected() {
        archive(artifact, ['fabric.mod.json': [123, 34, 120, 34, 58, 34, 192, 175, 34, 125] as byte[]])
        fails('Invalid release archive') { publication() }
    }

    @Test
    void oldArtifactNamesAndPublishingLabelsArePreserved() {
        assertEquals('25', LegacyPublication.javaVersion(sourceRoot))
        Map value = publication()
        assertEquals(true, value.legacy)
        assertEquals('v1.4.0+mc26.2', value.release)
        assertEquals('a' * 40, value.source_commit)
        Map record = value.records[0]
        assertEquals(['26.2-fabric'], record.targets)
        assertEquals('cbbg-1.4.0+mc26.2.jar', record.artifact.path)
        assertEquals('cbbg-1.4.0+mc26.2-sources.jar', record.sources.path)
        assertEquals('1.4.0+mc26.2', record.modrinth.version_number)
        assertEquals('release', record.modrinth.version_type)
        assertEquals(['26.2', 'Java 25', 'Fabric', 'Environment:Client'], record.curseforge.version_labels)
        assertEquals('UBlXUQbC', record.modrinth.project_id)
        assertEquals('1408371', record.curseforge.project_id)
    }

    @Test
    void maintenanceLinesUseTheirDeclaredJavaBaseline() {
        for (Map line : [[mc: '1.21.1', java: 21], [mc: '1.20.1', java: 17]]) {
            new File(sourceRoot, 'gradle.properties').text = new File(sourceRoot, 'gradle.properties').text
                    .replaceFirst(/minecraft_version=[^\n]+/, 'minecraft_version=' + line.mc)
            sourceMetadata.depends.java = '>=' + line.java
            new File(sourceRoot, 'src/main/resources/fabric.mod.json').text = JsonOutput.toJson(sourceMetadata)
            artifact = new File(assets, 'cbbg-1.4.0+mc' + line.mc + '.jar')
            sources = new File(assets, 'cbbg-1.4.0+mc' + line.mc + '-sources.jar')
            writeJars([:], line.java + 44, line.mc, line.java)
            assertEquals(line.java.toString(), LegacyPublication.javaVersion(sourceRoot))
            assertEquals(['Java ' + line.java],
                    publication('v1.4.0+mc' + line.mc).records[0].curseforge.version_labels[1..1])
        }
    }

    @Test
    void prereleaseKeepsHistoricalTagAndBetaChannel() {
        File properties = new File(sourceRoot, 'gradle.properties')
        properties.text = properties.text.replace('mod_version=1.4.0', 'mod_version=1.4.0-rc.1')
        artifact.renameTo(new File(assets, 'cbbg-1.4.0-rc.1+mc26.2.jar'))
        sources.renameTo(new File(assets, 'cbbg-1.4.0-rc.1+mc26.2-sources.jar'))
        artifact = new File(assets, 'cbbg-1.4.0-rc.1+mc26.2.jar')
        sources = new File(assets, 'cbbg-1.4.0-rc.1+mc26.2-sources.jar')
        writeJars([version: '1.4.0-rc.1+mc26.2'])
        Map value = publication('v1.4.0-rc.1+mc26.2', true)
        assertEquals('beta', value.records[0].modrinth.version_type)
        assertEquals('beta', value.records[0].curseforge.release_type)
        assertEquals('1.4.0-rc.1+mc26.2', value.records[0].modrinth.version_number)
        fails('prerelease status') { publication('v1.4.0-rc.1+mc26.2', false) }
    }

    @Test
    void rejectsTagAndPropertyMismatches() {
        fails('mod_version') { publication('v1.4.1+mc26.2') }
        fails('minecraft_version') { publication('v1.4.0+mc26.3') }
        fails('historical release tag') { publication('v1.4.0') }
        File properties = new File(sourceRoot, 'gradle.properties')
        properties << 'curseforge_project_id=99\n'
        fails('curseforge_project_id') { publication() }
    }

    @Test
    void rejectsMissingJavaArtifactsAndInvalidPackagedMetadata() {
        sourceMetadata.depends.java = '*'
        new File(sourceRoot, 'src/main/resources/fabric.mod.json').text = JsonOutput.toJson(sourceMetadata)
        fails('Unsupported Java requirement') { LegacyPublication.javaVersion(sourceRoot) }
        sourceMetadata.depends.java = '>=25'
        new File(sourceRoot, 'src/main/resources/fabric.mod.json').text = JsonOutput.toJson(sourceMetadata)
        sources.delete()
        fails('Missing downloaded release artifact') { publication() }
        writeJars()
        writeJars([environment: '*'])
        fails('Packaged environment') { publication() }
        writeJars()
        writeJars([depends: [minecraft: '~26.2', java: '>=21', fabricloader: '>=0.19.3', 'fabric-api': '*']])
        fails('Packaged java requirement') { publication() }
        writeJars()
        writeJars([:], 70)
        fails('newer Java') { publication() }
    }

    @Test
    void corruptNonClassResourceIsRejected() {
        Map entries = ['fabric.mod.json': JsonOutput.toJson([
                id: 'cbbg', version: '1.4.0+mc26.2', environment: 'client',
                depends: [minecraft: '~26.2', java: '>=25', fabricloader: '>=0.19.3', 'fabric-api': '*']
        ]).bytes, 'example/Client.class': classHeader(69),
                'assets/cbbg/test.txt': 'release-resource-integrity-test'.bytes]
        new ZipOutputStream(artifact.newOutputStream()).withCloseable { ZipOutputStream zip ->
            entries.each { String name, byte[] bytes ->
                CRC32 crc = new CRC32()
                crc.update(bytes)
                ZipEntry entry = new ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = bytes.length
                entry.crc = crc.value
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        assertEquals('v1.4.0+mc26.2', publication().release)
        byte[] bytes = artifact.bytes
        byte[] old = 'release-resource-integrity-test'.bytes
        int offset = -1
        for (int i = 0; i <= bytes.length - old.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(bytes, i, i + old.length), old)) {
                offset = i
                break
            }
        }
        assertTrue(offset >= 0)
        bytes[offset] = (byte) 'X'
        artifact.bytes = bytes
        fails('Corrupt release JAR entry: assets/cbbg/test.txt') { publication() }
    }

    @Test
    void checkedRecordAllowsResolvedIdsButRejectsEditedLabelsAndFiles() {
        Map value = publication()
        value.records[0].curseforge.game_versions = ['1', '2', '3', '4']
        metadataFile.text = JsonOutput.toJson(value)
        List checked = LegacyPublication.checkedRecord(metadataFile, sourceRoot, assets)
        assertEquals(['1', '2', '3', '4'], checked[1].curseforge.game_versions)
        assertEquals(CandidateFiles.sha256(metadataFile), checked[2])
        value.records[0].curseforge.version_labels << 'Forge'
        metadataFile.text = JsonOutput.toJson(value)
        fails('CurseForge metadata differs') { LegacyPublication.checkedRecord(metadataFile, sourceRoot, assets) }
        value = publication()
        value.records[0].modrinth.loaders << 'quilt'
        metadataFile.text = JsonOutput.toJson(value)
        fails('checked legacy release') { LegacyPublication.checkedRecord(metadataFile, sourceRoot, assets) }
        value = publication()
        metadataFile.text = JsonOutput.toJson(value)
        artifact.bytes = 'changed'.bytes
        assertThrows(GradleException) { LegacyPublication.checkedRecord(metadataFile, sourceRoot, assets) }
    }
}
