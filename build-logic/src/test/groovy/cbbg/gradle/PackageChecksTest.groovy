package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import static org.junit.jupiter.api.Assertions.*

class PackageChecksTest {
    @TempDir File root

    private static byte[] header(int major, int minor = 0) {
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt((int) 0xCAFEBABE).putShort((short) minor)
                .putShort((short) major).array()
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

    private static byte[] json(Object value) { JsonOutput.toJson(value).getBytes('UTF-8') }
    private static String hash(byte[] bytes) { MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString() }
    private static Map reference(File file) { [path: file.name, sha256: hash(file.bytes)] }

    private static void fails(String message, Closure operation) {
        GradleException error = assertThrows(GradleException) { operation.call() }
        assertTrue(error.message.contains(message), error.message)
    }

    private Map specimen() {
        File core = new File(root, 'core/src/main/java/example/Core.java')
        core.parentFile.mkdirs()
        core.bytes = 'package example; class Core {}'.getBytes('UTF-8')
        File artifact = new File(root, 'candidate.jar')
        File sources = new File(root, 'sources.jar')
        Map target = [loader: 'fabric', minecraft: '26.3', java: 25,
                      dependencies: [loader: '0.19.5']]
        Map metadata = [schemaVersion: 1, id: 'cbbg', environment: 'client', version: '1.4.0',
                depends: [fabricloader: '>=0.19.5', minecraft: '26.3', java: '>=25', 'fabric-api': '*'],
                entrypoints: [client: ['example.Client']], mixins: ['cbbg.mixins.json']]
        Map mixin = [required: true, compatibilityLevel: 'JAVA_25', 'package': 'example.mixin',
                client: ['RenderMixin'], refmap: 'cbbg.refmap.json']
        Map<String, byte[]> binary = [
                'fabric.mod.json': json(metadata), 'cbbg.mixins.json': json(mixin),
                'cbbg.refmap.json': json([mappings: [:]]),
                'example/Client.class': header(69),
                'example/mixin/RenderMixin.class': header(69),
                'example/Core.class': header(52)]
        Map<String, byte[]> sourceEntries = ['example/Core.java': core.bytes]
        archive(artifact, binary)
        archive(sources, sourceEntries)
        Map inventory = [schema: 1, sources: [[archive_path: 'example/Core.java',
                source_path: 'core/src/main/java/example/Core.java', sha256: hash(core.bytes)]]]
        File inventoryFile = new File(root, 'inventory.json')
        inventoryFile.bytes = json(inventory)
        [core: core, artifact: artifact, sources: sources, target: target, metadata: metadata,
         mixin: mixin, binary: binary, sourceEntries: sourceEntries,
         inventory: inventory, inventoryFile: inventoryFile]
    }

    @Test
    void validCandidateChecksArtifactMetadataAndSourceInventory() {
        Map s = specimen()
        Map record = [artifact: reference(s.artifact), sources: reference(s.sources),
                      source_inventory: reference(s.inventoryFile)]
        Map result = PackageChecks.verifyCandidatePackage(root, record, s.target, '1.4.0', root)
        assertEquals([classes: 3, core_classes: 1, core_sources: 1], result.packaging)
        assertEquals([declared_classes: 2, mixin_configs: 1], result.metadata)
        assertEquals([java_sources: 1], result.sources)
    }

    @Test void selectedSharedModulesKeepTheJava8Requirement() {
        Map s = specimen()
        File helper = new File(root, 'core/rendering/src/main/java/example/Helper.java')
        helper.parentFile.mkdirs()
        helper.bytes = 'package example; class Helper {}'.bytes
        s.sourceEntries['example/Helper.java'] = helper.bytes
        archive(s.sources, s.sourceEntries)
        s.inventory.sources.add([archive_path: 'example/Helper.java',
                source_path: 'core/rendering/src/main/java/example/Helper.java', sha256: hash(helper.bytes)])
        s.inventoryFile.bytes = json(s.inventory)
        for (int major : [52, 69]) {
            s.binary['example/Helper.class'] = header(major)
            archive(s.artifact, s.binary)
            Map record = [artifact: reference(s.artifact), sources: reference(s.sources),
                          source_inventory: reference(s.inventoryFile)]
            if (major == 52) {
                assertEquals(2, PackageChecks.verifyCandidatePackage(root, record, s.target, '1.4.0', root)
                        .packaging.core_classes)
            } else {
                fails('Core class is not Java 8') {
                    PackageChecks.verifyCandidatePackage(root, record, s.target, '1.4.0', root)
                }
            }
        }
    }

    @Test void processedCoreClassesUseTheirOriginalJavaBaseline() {
        Map s = specimen()
        File mapping = new File(root, 'mapping.txt')
        mapping.text = 'example.Core -> example.a:\nexample.Client -> example.Client:\nexample.mixin.RenderMixin -> example.mixin.RenderMixin:\n'
        byte[] original = s.binary.remove('example/Core.class')
        s.binary['example/a.class'] = original
        archive(s.artifact, s.binary)
        assertEquals(1, PackageChecks.verifyArtifact(s.artifact, s.sources, 25,
                [new File(root, 'core/src/main/java')], mapping).core_classes)
        s.binary['example/a.class'] = header(69)
        archive(s.artifact, s.binary)
        fails('Core class is not Java 8') {
            PackageChecks.verifyArtifact(s.artifact, s.sources, 25,
                    [new File(root, 'core/src/main/java')], mapping)
        }
    }

    @Test
    void bytecodeAndCoreSourceFailuresAreRejected() {
        Map s = specimen()
        File coreRoot = new File(root, 'core/src/main/java')
        s.binary['example/Client.class'] = header(70)
        archive(s.artifact, s.binary)
        fails('baseline') { PackageChecks.verifyArtifact(s.artifact, s.sources, 25, coreRoot) }
        s.binary['example/Client.class'] = header(69)
        s.binary['example/Core.class'] = header(61)
        archive(s.artifact, s.binary)
        fails('Core class is not Java 8') { PackageChecks.verifyArtifact(s.artifact, s.sources, 25, coreRoot) }
        s.binary.remove('example/Core.class')
        archive(s.artifact, s.binary)
        fails('Missing core class') { PackageChecks.verifyArtifact(s.artifact, s.sources, 25, coreRoot) }
        s.binary['example/Core.class'] = header(52)
        archive(s.artifact, s.binary)
        s.sourceEntries['example/Core.java'] = 'stale'.bytes
        archive(s.sources, s.sourceEntries)
        fails('core source') { PackageChecks.verifyArtifact(s.artifact, s.sources, 25, coreRoot) }
    }

    @Test
    void productionArchiveRejectsTestDriversAndPreviewClasses() {
        Map s = specimen()
        File coreRoot = new File(root, 'core/src/main/java')
        s.binary['com/qb20nh/cbbg/smoke/Driver.class'] = header(52)
        archive(s.artifact, s.binary)
        fails('Test-only') { PackageChecks.verifyArtifact(s.artifact, s.sources, 25, coreRoot) }
        s.binary.remove('com/qb20nh/cbbg/smoke/Driver.class')
        s.binary['example/Client.class'] = header(69, 65535)
        archive(s.artifact, s.binary)
        fails('preview') { PackageChecks.verifyArtifact(s.artifact, s.sources, 25, coreRoot) }
    }

    @Test
    void metadataRequiresEntrypointsMixinsAndRefmaps() {
        Map s = specimen()
        s.binary.remove('example/Client.class')
        archive(s.artifact, s.binary)
        fails('Missing declared class') { PackageChecks.verifyFabricMetadata(s.artifact, s.target, '1.4.0') }
        s.binary['example/Client.class'] = header(69)
        s.binary.remove('cbbg.refmap.json')
        archive(s.artifact, s.binary)
        fails('resource') { PackageChecks.verifyFabricMetadata(s.artifact, s.target, '1.4.0') }
        s.binary['cbbg.refmap.json'] = json([mappings: [:]])
        s.mixin.client = ['MissingMixin']
        s.binary['cbbg.mixins.json'] = json(s.mixin)
        archive(s.artifact, s.binary)
        fails('Missing declared class') { PackageChecks.verifyFabricMetadata(s.artifact, s.target, '1.4.0') }
        s.mixin.client = ['RenderMixin']
        s.binary['cbbg.mixins.json'] = json(s.mixin)
        s.metadata.version = 'wrong'
        s.binary['fabric.mod.json'] = json(s.metadata)
        archive(s.artifact, s.binary)
        fails('Wrong Fabric metadata') { PackageChecks.verifyFabricMetadata(s.artifact, s.target, '1.4.0') }
    }

    @Test
    void inventoryRejectsTamperingExtraSourcesAndTraversal() {
        Map s = specimen()
        assertEquals([java_sources: 1], PackageChecks.verifySourceInventory(s.sources, s.inventory, root))
        s.core.bytes = 'changed'.bytes
        fails('differs from inventory') { PackageChecks.verifySourceInventory(s.sources, s.inventory, root) }
        s.core.bytes = 'package example; class Core {}'.bytes
        s.sourceEntries['other/Extra.java'] = 'class Extra {}'.bytes
        archive(s.sources, s.sourceEntries)
        fails('Source archive differs') { PackageChecks.verifySourceInventory(s.sources, s.inventory, root) }
        s.sourceEntries.remove('other/Extra.java')
        archive(s.sources, s.sourceEntries)
        for (String path : ['../Core.java', '/Core.java', './Core.java', 'a//Core.java', 'a\\Core.java']) {
            s.inventory.sources[0].source_path = path
            fails('Invalid source path') { PackageChecks.verifySourceInventory(s.sources, s.inventory, root) }
        }
    }

    @Test
    void candidateReferencesRejectChangedFilesAndTraversal() {
        Map s = specimen()
        Map record = [artifact: reference(s.artifact), sources: reference(s.sources),
                      source_inventory: reference(s.inventoryFile)]
        record.source_inventory.sha256 = '0' * 64
        fails('Changed or invalid candidate file') {
            PackageChecks.verifyCandidatePackage(root, record, s.target, '1.4.0', root)
        }
        record.source_inventory = [path: '../inventory.json', sha256: hash(s.inventoryFile.bytes)]
        fails('escaping candidate file') {
            PackageChecks.verifyCandidatePackage(root, record, s.target, '1.4.0', root)
        }
    }

    @Test void malformedMetadataAndDependenciesAreRejected() {
        List<Closure> edits = [
                { it.schemaVersion = true }, { it.id = 'other' }, { it.environment = '*' },
                { it.depends.minecraft = '26.2' }, { it.depends.java = '>=21' },
                { it.depends.fabricloader = '*' }, { it.depends.remove('fabric-api') },
                { it.depends.extra = '*' }, { it.entrypoints = [:] },
                { it.entrypoints.client = [] }, { it.entrypoints.client = 'example.Client' },
                { it.entrypoints.client = [[value: 'example.Client']] },
                { it.entrypoints.client = ['example/Client'] },
                { it.mixins = [] }, { it.mixins = ['cbbg.mixins.json', 'cbbg.mixins.json'] },
                { it.mixins = [false] }]
        edits.each { edit ->
            Map s = specimen()
            edit(s.metadata)
            s.binary['fabric.mod.json'] = json(s.metadata)
            archive(s.artifact, s.binary)
            assertThrows(GradleException) { PackageChecks.verifyFabricMetadata(s.artifact, s.target, '1.4.0') }
        }
        ['[]', '{"id":"cbbg","id":"other"}', '{', '{"x":NaN}'].each { contents ->
            Map s = specimen()
            s.binary['fabric.mod.json'] = contents.bytes
            archive(s.artifact, s.binary)
            assertThrows(GradleException) { PackageChecks.verifyFabricMetadata(s.artifact, s.target, '1.4.0') }
        }
        Map s = specimen()
        fails('Fabric target') { PackageChecks.verifyFabricMetadata(s.artifact, s.target + [loader: 'quilt'], '1.4.0') }
    }

    @Test void malformedMixinConfigurationIsRejected() {
        List<Closure> edits = [
                { it.required = false }, { it.compatibilityLevel = 'JAVA_21' },
                { it.remove('package') }, { it.client = 'RenderMixin' },
                { it.client = [false] }, { it.client = [] }, { it.plugin = 'example.Missing' },
                { it.refmap = '../cbbg.refmap.json' }]
        edits.each { edit ->
            Map s = specimen()
            edit(s.mixin)
            s.binary['cbbg.mixins.json'] = json(s.mixin)
            archive(s.artifact, s.binary)
            assertThrows(GradleException) { PackageChecks.verifyFabricMetadata(s.artifact, s.target, '1.4.0') }
        }
    }

    @Test void invalidInventoriesAndMissingSourcesAreRejected() {
        List<Closure> edits = [
                { it.schema = true }, { it.sources = [] }, { it.sources = [false] },
                { it.sources[0].sha256 = 'BAD' },
                { it.sources.add(new LinkedHashMap(it.sources[0])) },
                { it.sources[0].source_path = 'missing.java' },
                { it.sources[0].archive_path = 'missing.java' }]
        edits.each { edit ->
            Map s = specimen()
            edit(s.inventory)
            assertThrows(GradleException) { PackageChecks.verifySourceInventory(s.sources, s.inventory, root) }
        }
    }

    @Test void excludedResourcesAndBinarySourcesAreRejected() {
        ['cbbg.parity.refmap.json', 'cbbg.parity.mixins.json',
         'com/qb20nh/cbbg/mixin/ShaderFailureMixin.java',
         'assets/cbbg/shaders/post/satin_parity.json', 'cbbg-iris-fixture/example'].each { name ->
            Map s = specimen()
            s.sourceEntries[name] = '{}'.bytes
            archive(s.sources, s.sourceEntries)
            fails('Test-only') { PackageChecks.verifyArtifact(s.artifact, s.sources, 25, new File(root, 'core/src/main/java')) }
        }
        Map s = specimen()
        s.sourceEntries['example/Binary.class'] = header(52)
        archive(s.sources, s.sourceEntries)
        fails('Binary class') { PackageChecks.verifyArtifact(s.artifact, s.sources, 25, new File(root, 'core/src/main/java')) }
    }

    @Test void archiveCorruptionAndDuplicateEntriesAreRejected() {
        for (String kind : ['artifact', 'sources']) {
            Map s = specimen()
            Map entries = new LinkedHashMap(kind == 'artifact' ? s.binary : s.sourceEntries)
            entries['first.txt'] = 'example'.bytes
            entries['other.txt'] = 'example'.bytes
            archive(s[kind], entries)
            byte[] bytes = s[kind].bytes
            // Replace both ZIP filenames while leaving their recorded lengths unchanged.
            byte[] before = 'other.txt'.bytes
            byte[] after = 'first.txt'.bytes
            for (int i = 0; i <= bytes.length - before.length; i++) {
                if (Arrays.equals(Arrays.copyOfRange(bytes, i, i + before.length), before)) {
                    System.arraycopy(after, 0, bytes, i, after.length)
                }
            }
            s[kind].bytes = bytes
            fails('Duplicate archive') { PackageChecks.verifyArtifact(s.artifact, s.sources, 25, new File(root, 'core/src/main/java')) }

            archive(s[kind], entries)
            bytes = s[kind].bytes
            // Change a central-directory CRC, including for a non-class resource.
            for (int i = 0; i < bytes.length - 46; i++) {
                if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 1 && bytes[i + 3] == 2) {
                    bytes[i + 16] = (byte) (bytes[i + 16] ^ 1)
                    break
                }
            }
            s[kind].bytes = bytes
            fails('Corrupt archive') { PackageChecks.verifyArtifact(s.artifact, s.sources, 25, new File(root, 'core/src/main/java')) }
        }
    }
}
