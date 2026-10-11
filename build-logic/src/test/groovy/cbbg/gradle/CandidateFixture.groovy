package cbbg.gradle

import groovy.json.JsonOutput
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small synthetic archives for packaging and release-tool tests. */
class CandidateFixture {
    static Map create(File directory, boolean implemented = true, boolean withSbom = false,
                      String minecraft = '26.3', String minecraftDependency = null) {
        File root = new File(directory, 'source')
        File bundle = new File(directory, 'candidate')
        root.mkdirs()
        bundle.mkdirs()
        String base = 'cbbg-1.4.0+mc' + minecraft + '-fabric'
        Map target = [id: minecraft + '-fabric', minecraft: minecraft, loader: 'fabric', java: 25,
                renderer: 'renderpearl', backends: ['opengl', 'vulkan'], implemented: implemented,
                buildProfile: 'fabric-modern', sourceGroups: ['adapters/fabric/modern'],
                dependencies: [loader: '0.19.5'], compatibilityProfiles: [none: ['opengl', 'vulkan']]]
        if (minecraftDependency != null) target.minecraftDependency = minecraftDependency
        Map catalog = [schema: 1, ciTargets: [target.id], targets: [target],
                       projects: [modrinth: 'UBlXUQbC', curseforge: '1408371']]
        new File(root, 'gradle.properties').text = 'mod_version=1.4.0\narchives_base_name=cbbg\nmodrinth_project_id=UBlXUQbC\ncurseforge_project_id=1408371\n'
        new File(root, 'targets.json').text = JsonOutput.toJson(catalog)
        Map sourcePaths = ['example/Core.java': 'core/src/main/java/example/Core.java',
                           'example/Client.java': 'adapters/fabric/modern/src/main/java/example/Client.java',
                           'example/Mixin.java': 'adapters/fabric/modern/src/main/java/example/Mixin.java']
        Map<String, byte[]> sources = [:]
        def inventory = [schema: 1, sources: []]
        sourcePaths.each { entry, path ->
            File source = new File(root, path)
            source.parentFile.mkdirs()
            source.text = 'package example; class ' + source.name.replace('.java', '') + ' {}\n'
            sources[entry] = source.bytes
            inventory.sources.add([archive_path: entry, source_path: path, sha256: CandidateFiles.sha256(source)])
        }
        def metadata = [schemaVersion: 1, id: 'cbbg', version: '1.4.0+mc' + minecraft + '-fabric', environment: 'client',
                        entrypoints: [client: ['example.Client']], mixins: ['cbbg.mixins.json'],
                        depends: [fabricloader: '>=0.19.5', minecraft: minecraftDependency ?: minecraft,
                                  java: '>=25', 'fabric-api': '*']]
        def mixins = [required: true, 'package': 'example', compatibilityLevel: 'JAVA_25', client: ['Mixin']]
        Map artifact = ['fabric.mod.json': JsonOutput.toJson(metadata).getBytes('UTF-8'),
                        'cbbg.mixins.json': JsonOutput.toJson(mixins).getBytes('UTF-8')]
        sourcePaths.keySet().each { path ->
            int major = path == 'example/Core.java' ? 52 : 69
            artifact[path.replace('.java', '.class')] = [0xca, 0xfe, 0xba, 0xbe, 0, 0, 0, major] as byte[]
        }
        archive(new File(bundle, base + '.jar'), artifact)
        ['catalog.json': catalog, 'source-inventory.json': inventory,
         'contract.json': [schemaVersion: 1, target: target.id, ordinaryMetadata: 'ordinary-metadata.json', additionalRuns: []],
         'ordinary-metadata.json': [id: 'cbbg-renderer-test', entrypoints: ['fabric-client-gametest': ['example.Test']]],
         'runtime-lock.json': [:], 'dependency-lock.json': [:]].each { name, contents ->
            CandidateFiles.writeNew(new File(bundle, name), contents)
        }
        new File(bundle, 'ordinary-driver.jar').text = 'synthetic driver'
        new File(bundle, base + '-mapping.txt').text = sourcePaths.keySet().collect {
            String name = it.replace('.java', '').replace('/', '.')
            name + ' -> ' + name + ':'
        }.join('\n') + '\n'
        File sbom = new File(bundle, base + '-sbom.cdx.json')
        if (withSbom) CandidateFiles.writeNew(sbom, [bomFormat: 'CycloneDX', specVersion: '1.6', version: 1,
                                     components: [[type: 'library', group: 'com.google.code.gson',
                                                   name: 'gson', version: '2.8.9',
                                                   purl: 'pkg:maven/com.google.code.gson/gson@2.8.9']]])
        if (withSbom) sources['META-INF/cbbg/sbom.cdx.json'] = sbom.bytes
        sources['META-INF/cbbg/proguard.map'] = new File(bundle, base + '-mapping.txt').bytes
        archive(new File(bundle, base + '-sources.jar'), sources)
        def reference = { String name -> CandidateFiles.reference(bundle, name) }
        Map record = [id: target.id, artifact: reference(base + '.jar'),
                      mapping: reference(base + '-mapping.txt'),
                      processing: [tool: 'proguard', version: ProguardMapping.VERSION],
                      sources: reference(base + '-sources.jar'),
                      source_inventory: reference('source-inventory.json'), client_tests: [
                              catalog: reference('catalog.json'), contract: reference('contract.json'),
                              ordinary_metadata: reference('ordinary-metadata.json'),
                              runtime_lock: reference('runtime-lock.json'), dependency_lock: reference('dependency-lock.json'),
                              drivers: [ordinary: reference('ordinary-driver.jar')]]]
        if (withSbom) record.sbom = reference(sbom.name)
        Map manifest = [schema: 3, release: 'v1.4.0', commit: 'a' * 40,
                        catalog_sha256: CandidateFiles.canonicalHash(catalog), selected_targets: [target.id], targets: [record]]
        File manifestFile = new File(bundle, 'candidate.json')
        CandidateFiles.writeNew(manifestFile, manifest)
        checksums(bundle)
        new File(bundle, 'provenance.jsonl').text = '{}\n'
        [root: root, bundle: bundle, file: manifestFile, manifest: manifest, record: record, catalog: catalog, target: target]
    }

    static void utilities(Map fixture) {
        String name = 'cbbg-utilities-1.4.0'
        archive(new File(fixture.bundle, name + '.jar'),
                ['com/qb20nh/cbbg/api/NoiseVolume.class': [0xca, 0xfe, 0xba, 0xbe, 0, 0, 0, 52] as byte[]])
        archive(new File(fixture.bundle, name + '-sources.jar'),
                ['com/qb20nh/cbbg/api/NoiseVolume.java': 'class NoiseVolume {}'.bytes,
                 'META-INF/cbbg/proguard.map': 'com.qb20nh.cbbg.api.NoiseVolume -> com.qb20nh.cbbg.api.NoiseVolume:\n'.bytes])
        fixture.manifest.targets.each { record ->
            record.utilities = CandidateFiles.reference(fixture.bundle, name + '.jar')
            record.utilities_sources = CandidateFiles.reference(fixture.bundle, name + '-sources.jar')
        }
        fixture.file.text = JsonOutput.toJson(fixture.manifest)
        checksums(fixture.bundle)
    }

    static void checksums(File bundle) {
        new File(bundle, 'SHA256SUMS').text = bundle.listFiles().findAll { !(it.name in ['SHA256SUMS', 'provenance.jsonl']) }
                .sort { it.name }.collect { CandidateFiles.sha256(it) + '  ' + it.name }.join('\n') + '\n'
    }

    static void archive(File destination, Map<String, byte[]> entries) {
        new ZipOutputStream(new FileOutputStream(destination)).withCloseable { zip ->
            entries.each { name, bytes -> zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }
}
