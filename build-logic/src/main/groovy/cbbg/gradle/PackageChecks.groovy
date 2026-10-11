package cbbg.gradle

import groovy.io.FileType
import org.gradle.api.GradleException

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class PackageChecks {
    static final String MINECRAFT_1211_MIXIN_EXTRAS_MINIMUM = '0.4.0'
    private static final List<String> TEST_PREFIXES = [
            'com/qb20nh/cbbg/parity/', 'com/qb20nh/cbbg/gametest/',
            'com/qb20nh/cbbg/smoke/', 'cbbg-iris-fixture/'
    ]
    private static final Set<String> TEST_RESOURCES = [
            'cbbg.parity.mixins.json', 'cbbg.parity.refmap.json',
            'cbbg.scenario-progress.mixins.json', 'cbbg.forge.smoke.mixins.json',
            'com/qb20nh/cbbg/mixin/ShaderFailureMixin.class',
            'com/qb20nh/cbbg/mixin/ShaderFailureMixin.java',
            'assets/cbbg/shaders/post/satin_parity.json'
    ] as Set

    static Map verifyArtifact(File artifact, File sources, int javaVersion, File coreSources) {
        verifyArtifact(artifact, sources, javaVersion, [coreSources])
    }

    static Map verifyArtifact(File artifact, File sources, int javaVersion, Collection<File> sourceRoots,
                              File mapping = null, File sbom = null) {
        if (javaVersion < 8) throw new GradleException('Invalid target Java baseline')
        Map<String, byte[]> expected = [:]
        for (File coreSources : sourceRoots) {
            if (coreSources.isDirectory()) {
                coreSources.eachFileRecurse(FileType.FILES) { File file ->
                    if (file.name.endsWith('.java') && !(file.name in ['package-info.java', 'module-info.java'])) {
                        expected[coreSources.toPath().relativize(file.toPath()).toString().replace('\\', '/')] = file.bytes
                    }
                }
            }
        }
        if (expected.isEmpty()) throw new GradleException('No core sources to verify')
        Set<String> coreNames = expected.keySet().collect { it.substring(0, it.length() - 5) } as Set
        Map<String, String> renamed = mapping == null ? [:] : ProguardMapping.classes(mapping)
        Set<String> coreOutputNames = renamed.findAll { original, output ->
            original.split('\\$', 2)[0] in coreNames
        }.values() as Set
        renamed.keySet().each { original ->
            if (TEST_PREFIXES.any { original.startsWith(it) } || original + '.class' in TEST_RESOURCES) {
                throw new GradleException('Test class in production mapping: ' + original)
            }
        }
        Set<String> classes = [] as Set
        int coreClasses = 0
        new ZipFile(artifact).withCloseable { ZipFile zip ->
            for (String name : productionEntries(zip)) {
                if (!name.endsWith('.class')) continue
                byte[] header = zip.getInputStream(zip.getEntry(name)).withCloseable { it.readNBytes(8) }
                if (header.length != 8) throw new GradleException('Truncated class header: ' + name)
                int magic = ((header[0] & 255) << 24) | ((header[1] & 255) << 16) |
                        ((header[2] & 255) << 8) | (header[3] & 255)
                int minor = ((header[4] & 255) << 8) | (header[5] & 255)
                int major = ((header[6] & 255) << 8) | (header[7] & 255)
                if (magic != (int) 0xCAFEBABE || major < 45 || minor == 65535) {
                    throw new GradleException('Invalid or preview class header: ' + name)
                }
                if (major > javaVersion + 44) throw new GradleException('Class exceeds target Java baseline: ' + name)
                classes.add(name)
                String base = name.substring(0, name.length() - 6).split('\\$', 2)[0]
                if (mapping == null ? coreNames.contains(base) : coreOutputNames.contains(name.substring(0, name.length() - 6))) {
                    if (major != 52) throw new GradleException('Core class is not Java 8: ' + name)
                    coreClasses++
                }
            }
            for (String name : (mapping == null ? coreNames : renamed.values())) {
                if (!classes.contains(name + '.class')) throw new GradleException('Missing core class: ' + name)
            }
        }
        new ZipFile(sources).withCloseable { ZipFile zip ->
            Set<String> names = productionEntries(zip) as Set
            if (names.any { it.endsWith('.class') }) throw new GradleException('Binary class in source archive')
            if (mapping != null && (!names.contains('META-INF/cbbg/proguard.map') ||
                    !Arrays.equals(read(zip, 'META-INF/cbbg/proguard.map'), mapping.bytes))) {
                throw new GradleException('Missing or changed release mapping in source archive')
            }
            if (sbom != null && (!names.contains('META-INF/cbbg/sbom.cdx.json') ||
                    !Arrays.equals(read(zip, 'META-INF/cbbg/sbom.cdx.json'), sbom.bytes))) {
                throw new GradleException('Missing or changed release SBOM in source archive')
            }
            for (Map.Entry<String, byte[]> source : expected.entrySet()) {
                if (!names.contains(source.key) || !Arrays.equals(read(zip, source.key), source.value)) {
                    throw new GradleException('Missing or changed core source: ' + source.key)
                }
            }
        }
        [classes: classes.size(), core_classes: coreClasses, core_sources: expected.size()]
    }

    static Map verifySourceInventory(File sources, Map inventory, File sourceRoot) {
        if (inventory == null || !(inventory.schema instanceof Integer) || inventory.schema != 1) {
            throw new GradleException('Invalid source inventory schema')
        }
        if (!(inventory.sources instanceof List) || inventory.sources.isEmpty()) {
            throw new GradleException('Empty source inventory')
        }
        File root = sourceRoot.canonicalFile
        Map<String, String> expected = [:]
        Set<String> sourcePaths = [] as Set
        for (Object item : inventory.sources) {
            if (!(item instanceof Map)) throw new GradleException('Invalid source inventory entry')
            Map entry = (Map) item
            String archivePath = javaPath(entry.archive_path)
            String sourcePath = javaPath(entry.source_path)
            String hash = entry.sha256
            if (!(hash ==~ /[0-9a-f]{64}/)) throw new GradleException('Invalid source hash: ' + archivePath)
            if (expected.containsKey(archivePath) || !sourcePaths.add(sourcePath)) {
                throw new GradleException('Duplicate source inventory entry: ' + archivePath)
            }
            File file = new File(root, sourcePath).canonicalFile
            if (!file.toPath().startsWith(root.toPath()) || !file.isFile()) {
                throw new GradleException('Missing or invalid source file: ' + sourcePath)
            }
            if (digest(file.bytes) != hash) throw new GradleException('Source file differs from inventory: ' + sourcePath)
            expected[archivePath] = hash
        }
        new ZipFile(sources).withCloseable { ZipFile zip ->
            List<String> names = zip.entries().collect { it.name }
            if (names.size() != names.toSet().size()) throw new GradleException('Duplicate source archive entry')
            Set<String> actual = names.findAll { it.endsWith('.java') } as Set
            if (actual != expected.keySet()) {
                throw new GradleException('Source archive differs from inventory; missing=' +
                        (expected.keySet() - actual).sort() + '; unexpected=' + (actual - expected.keySet()).sort())
            }
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                if (digest(read(zip, entry.key)) != entry.value) {
                    throw new GradleException('Packaged source differs from inventory: ' + entry.key)
                }
            }
        }
        [java_sources: expected.size()]
    }

    static Map verifyFabricMetadata(File artifact, Map target, String version, String product = 'cbbg',
                                    String libraryDependency = null) {
        if (target.loader != 'fabric') throw new GradleException('Expected a Fabric target')
        new ZipFile(artifact).withCloseable { ZipFile zip ->
            List<String> names = zip.entries().collect { it.name }
            if (names.size() != names.toSet().size()) throw new GradleException('Duplicate archive entry')
            Set<String> present = names as Set
            Map metadata = jsonResource(zip, present, 'fabric.mod.json')
            Map expected = [schemaVersion: 1, id: product == 'lib' ? 'cbbg_lib' : 'cbbg', version: version, environment: 'client']
            for (Map.Entry entry : expected.entrySet()) {
                if (metadata[entry.key]?.getClass() != entry.value.getClass() || metadata[entry.key] != entry.value) {
                    throw new GradleException('Wrong Fabric metadata: ' + entry.key)
                }
            }
            Map dependencies = [fabricloader: '>=' + (target.dependencies.minimumLoader ?: target.dependencies.loader) +
                    (target.dependencies.loaderUpperExclusive ? ' <' + target.dependencies.loaderUpperExclusive : ''),
                    minecraft: target.minecraftDependency ?: target.minecraft, java: '>=' + target.java,
                    'fabric-api': (target.dependencies.minimumFabricApi ? '>=' + target.dependencies.minimumFabricApi : '*') +
                            (target.dependencies.fabricApiUpperExclusive ? ' <' + target.dependencies.fabricApiUpperExclusive : '')]
            if (product == 'lib') dependencies.remove('fabric-api')
            if (libraryDependency != null) dependencies.cbbg_lib = libraryDependency
            if (product == 'cbbg' && target.minecraft == '1.21.1') {
                dependencies.mixinextras = '>=' + MINECRAFT_1211_MIXIN_EXTRAS_MINIMUM
            }
            if (metadata.depends != dependencies) {
                throw new GradleException("Dependencies differ from target ${target.minecraft}: " +
                        "${artifact.name}; expected ${dependencies}, found ${metadata.depends}")
            }
            if (product == 'lib') {
                if (metadata.entrypoints != null && (!(metadata.entrypoints instanceof Map) || !metadata.entrypoints.isEmpty())) {
                    throw new GradleException('CBBG Lib must not declare CBBG client entrypoints')
                }
                if (present.any { it.startsWith('com/qb20nh/cbbg/config/') || it == 'com/qb20nh/cbbg/CbbgClient.class' }) {
                    throw new GradleException('CBBG Lib must not contain CBBG client settings or initialization')
                }
                ['com/qb20nh/cbbg/api/Dithering.class', 'com/qb20nh/cbbg/api/DitherOptions.class',
                 'com/qb20nh/cbbg/api/NoiseVolume.class', 'com/qb20nh/cbbg/render/DitherPass.class'].each { name ->
                    if (!present.contains(name)) throw new GradleException('Missing CBBG Lib public API: ' + name)
                }
                String helper = target.minecraft == '26.2' ? 'DitherPipelines' :
                        target.renderer == 'gl3' ? 'CbbgShaders' : null
                if (helper != null) checkClass(present, 'com.qb20nh.cbbg.render.' + helper)
                List<String> resources = target.renderer == 'gl3' ?
                        ['assets/minecraft/shaders/core/cbbg_dither.fsh', 'assets/minecraft/shaders/core/cbbg_demo.fsh',
                         'assets/minecraft/shaders/include/cbbg_dither.glsl'] :
                        ['assets/cbbg/shaders/core/cbbg_dither.fsh', 'assets/cbbg/shaders/core/cbbg_demo.fsh',
                         'assets/cbbg/shaders/include/dither.glsl']
                if (target.renderer in ['blaze-gpu-format', 'renderpearl']) resources.remove('assets/cbbg/shaders/core/cbbg_demo.fsh')
                resources.each { name ->
                    if (!present.contains(name)) throw new GradleException('Missing CBBG Lib shader resource: ' + name)
                }
                boolean gl3 = target.renderer == 'gl3'
                if (gl3) {
                    if (metadata.mixins != ['cbbg_lib.gl3.mixins.json']) throw new GradleException('Unexpected CBBG Lib mixins')
                    Map config = jsonResource(zip, present, 'cbbg_lib.gl3.mixins.json')
                    if (config.required != true || config.compatibilityLevel != 'JAVA_' + target.java ||
                            config['package'] != 'com.qb20nh.cbbg.mixin' ||
                            config.client != ['GameRendererReloadShadersMixin'] ||
                            config.get('mixins', []) != [] || config.get('server', []) != []) {
                        throw new GradleException('Invalid CBBG Lib shader lifecycle mixin')
                    }
                    checkClass(present, 'com.qb20nh.cbbg.mixin.GameRendererReloadShadersMixin')
                } else if (metadata.mixins != null && metadata.mixins != []) {
                    throw new GradleException('Unexpected CBBG Lib mixins')
                }
                return [declared_classes: gl3 ? 1 : 0, mixin_configs: gl3 ? 1 : 0]
            }
            if (!(metadata.entrypoints instanceof Map) || !metadata.entrypoints.client) {
                throw new GradleException('Missing client entrypoint')
            }
            int declared = 0
            for (Map.Entry entry : ((Map) metadata.entrypoints).entrySet()) {
                if (!(entry.value instanceof List) || entry.value.isEmpty()) {
                    throw new GradleException('Invalid entrypoint list: ' + entry.key)
                }
                for (Object name : (List) entry.value) {
                    checkClass(present, name)
                    declared++
                }
            }
            Object mixins = metadata.mixins
            if (!(mixins instanceof List) || mixins.isEmpty() ||
                    mixins.any { !(it instanceof String) } || mixins.size() != mixins.toSet().size()) {
                throw new GradleException('Invalid mixin configuration list')
            }
            for (String name : (List<String>) mixins) {
                Map config = jsonResource(zip, present, name)
                if (config.required != true) throw new GradleException('Mixin configuration must be required: ' + name)
                if (config.compatibilityLevel != 'JAVA_' + target.java) {
                    throw new GradleException('Mixin Java version differs from target: ' + name)
                }
                if (!(config['package'] instanceof String) || !config['package']) {
                    throw new GradleException('Missing mixin package: ' + name)
                }
                int count = 0
                for (String section : ['client', 'mixins', 'server']) {
                    Object entries = config.get(section, [])
                    if (!(entries instanceof List)) throw new GradleException('Invalid mixin class list: ' + name)
                    for (Object entry : (List) entries) {
                        if (!(entry instanceof String)) throw new GradleException('Invalid mixin class: ' + name)
                        checkClass(present, config['package'] + '.' + entry)
                        count++
                    }
                }
                if (!count) throw new GradleException('Empty mixin configuration: ' + name)
                if (config.containsKey('plugin')) checkClass(present, config.plugin)
                if (config.containsKey('refmap')) jsonResource(zip, present, config.refmap)
                declared += count
            }
            [declared_classes: declared, mixin_configs: mixins.size()]
        }
    }

    static Map verifyCandidatePackage(File base, Map targetRecord, Map specification,
                                      String version, File sourceRoot, String product = 'cbbg') {
        if (!targetRecord.containsKey('source_inventory')) {
            throw new GradleException('Candidate is missing its source inventory')
        }
        File artifact = CandidateFiles.checked(base, (Map) targetRecord.artifact)
        File sources = CandidateFiles.checked(base, (Map) targetRecord.sources)
        File inventoryFile = CandidateFiles.checked(base, (Map) targetRecord.source_inventory)
        Object inventory = CandidateFiles.read(inventoryFile)
        if (!(inventory instanceof Map)) throw new GradleException('Invalid source inventory schema')
        Map sourceCheck = verifySourceInventory(sources, (Map) inventory, sourceRoot)
        Set<File> sharedRoots = [new File(sourceRoot, product == 'lib' ? 'libraries/utilities/src/main/java' : 'core/src/main/java')] as Set
        inventory.sources.each { entry ->
            def match = entry.source_path =~ /^(core\/(?:[^\/]+\/)?src\/main\/java)\//
            if (product == 'cbbg' && match.find()) sharedRoots.add(new File(sourceRoot, match.group(1)))
        }
        if (product == 'cbbg' && targetRecord.containsKey('library')) {
            verifyNestedLibrary(artifact, CandidateFiles.checked(base, targetRecord.library as Map))
        }
        [packaging: verifyArtifact(artifact, sources, (int) specification.java,
                sharedRoots, targetRecord.mapping == null ? null : CandidateFiles.checked(base, (Map) targetRecord.mapping),
                targetRecord.sbom == null ? null : CandidateFiles.checked(base, (Map) targetRecord.sbom)),
         metadata: verifyFabricMetadata(artifact, specification, version, product,
                 product == 'cbbg' && targetRecord.containsKey('library')
                         ? ReleaseIdentity.parse((targetRecord.library_release ?: 'lib/v1.0.0') as String).libraryRequirement
                         : null),
         sources: sourceCheck]
    }

    static void verifyNestedLibrary(File artifact, File library) {
        new ZipFile(artifact).withCloseable { ZipFile zip ->
            Set<String> outer = zip.entries().collect { it.name } as Set
            Map metadata = jsonResource(zip, outer, 'fabric.mod.json')
            String path = 'META-INF/jars/' + library.name
            if (metadata.jars != [[file: path]]) throw new GradleException('CBBG must declare its single nested library')
            ZipEntry entry = zip.getEntry(path)
            if (entry == null) throw new GradleException('Missing nested CBBG Lib: ' + path)
            byte[] bytes = zip.getInputStream(entry).withCloseable { it.bytes }
            if (!Arrays.equals(bytes, library.bytes)) throw new GradleException('Nested CBBG Lib differs from the standalone artifact')
            Set<String> nested = [] as Set
            new ZipInputStream(new ByteArrayInputStream(bytes)).withCloseable { stream ->
                ZipEntry item
                while ((item = stream.nextEntry) != null) {
                    if (!nested.add(item.name)) throw new GradleException('Duplicate entry in nested CBBG Lib: ' + item.name)
                }
            }
            Set<String> duplicateClasses = outer.intersect(nested).findAll { it.endsWith('.class') } as Set
            if (duplicateClasses) throw new GradleException('CBBG and CBBG Lib contain duplicate classes: ' + duplicateClasses.sort())
            if (outer.any { it.startsWith('com/qb20nh/cbbg/api/') ||
                    it.startsWith('com/qb20nh/cbbg/lib/internal/') ||
                    it == 'com/qb20nh/cbbg/render/DitherPass.class' ||
                    it.startsWith('assets/cbbg/shaders/') ||
                    it.startsWith('assets/minecraft/shaders/') }) {
                throw new GradleException('Library functionality is packaged outside the nested library')
            }
        }
    }

    private static List<String> productionEntries(ZipFile zip) {
        List<String> names = []
        Set<String> seen = [] as Set
        for (ZipEntry entry : zip.entries()) {
            String name = entry.name
            if (!seen.add(name)) throw new GradleException('Duplicate archive entry: ' + name)
            if (TEST_PREFIXES.any { name.startsWith(it) } || TEST_RESOURCES.contains(name)) {
                throw new GradleException('Test-only entry in production archive: ' + name)
            }
            byte[] bytes = zip.getInputStream(entry).withCloseable { it.bytes }
            CRC32 crc = new CRC32()
            crc.update(bytes)
            if (entry.crc != crc.value) throw new GradleException('Corrupt archive entry: ' + name)
            names.add(name)
        }
        names
    }

    private static String javaPath(Object value) {
        if (!(value instanceof String) || !value || value.contains('\\') ||
                value.startsWith('/') || value ==~ /^[A-Za-z]:.*/ ||
                value.split('/', -1).any { !it || it in ['.', '..'] }) {
            throw new GradleException('Invalid source path: ' + value)
        }
        if (!value.endsWith('.java')) throw new GradleException('Expected Java source: ' + value)
        value
    }

    private static Map jsonResource(ZipFile zip, Set<String> names, Object name) {
        if (!(name instanceof String) || !name || name.startsWith('/') ||
                name.contains('\\') || name.split('/', -1).any { !it || it in ['.', '..'] } ||
                !names.contains(name)) {
            throw new GradleException('Missing or invalid resource: ' + name)
        }
        Object parsed
        try {
            String contents = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(read(zip, name))).toString()
            parsed = CandidateFiles.parse(new StringReader(contents))
        } catch (Exception ignored) {
            throw new GradleException('Invalid JSON: ' + name)
        }
        if (!(parsed instanceof Map)) throw new GradleException('Expected JSON object: ' + name)
        (Map) parsed
    }

    private static void checkClass(Set<String> names, Object name) {
        if (!(name instanceof String) || !(name ==~ /[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*/)) {
            throw new GradleException('Invalid class name: ' + name)
        }
        if (!names.contains(name.replace('.', '/') + '.class')) {
            throw new GradleException('Missing declared class: ' + name)
        }
    }

    private static byte[] read(ZipFile zip, String name) {
        zip.getInputStream(zip.getEntry(name)).withCloseable { it.bytes }
    }

    private static String digest(byte[] bytes) {
        MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()
    }
}
