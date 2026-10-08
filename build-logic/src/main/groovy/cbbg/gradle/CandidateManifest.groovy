package cbbg.gradle

import org.gradle.api.GradleException

class CandidateManifest {
    final File file
    final Map data
    final Map<String, Map> records
    final Map<String, Map> specifications

    CandidateManifest(File file) {
        this.file = file.canonicalFile
        data = CandidateFiles.read(this.file) as Map
        if (!(data.schema instanceof Integer) || !(data.schema in [2, 3])) {
            throw new GradleException('Client validation requires candidate schema 2 or 3')
        }
        CandidateFiles.releaseIdentity(data.release as String, data.commit as String)
        records = [:]
        if (!(data.targets instanceof List) || data.targets.isEmpty()) {
            throw new GradleException('Candidate needs target records')
        }
        data.targets.each { target ->
            if (!(target instanceof Map) || !(target.id instanceof String) || records.containsKey(target.id)) {
                throw new GradleException('Invalid or duplicate candidate target')
            }
            records[target.id] = target
            if (data.schema == 3) {
                if (!(target.mapping instanceof Map) || target.processing != [tool: 'proguard', version: ProguardMapping.VERSION]) {
                    throw new GradleException('Processed candidate requires a mapping and supported ProGuard version')
                }
            } else if (target.containsKey('mapping') || target.containsKey('processing')) {
                throw new GradleException('Processed candidates require schema 3')
            }
        }
        Map<String, Map> expected = null
        records.each { id, record ->
            def catalog = TargetCatalog.read(CandidateFiles.checked(this.file.parentFile, record.client_tests.catalog))
            def selected = catalog.releaseTargets(data.selected_targets as List)
            if (records.keySet() != selected.keySet() || data.catalog_sha256 != CandidateFiles.canonicalHash(catalog.data) ||
                    (expected != null && expected != selected)) {
                throw new GradleException('Candidate catalog or target selection differs')
            }
            expected = selected
            references(record).each { CandidateFiles.checked(this.file.parentFile, it) }
        }
        specifications = expected
        CandidateFiles.releaseTargets(data.release as String, specifications.values())
        specifications.each { id, specification ->
            if (specification.artifactOf) {
                (['artifact', 'sources', 'source_inventory'] + (data.schema == 3 ? ['mapping'] : []) +
                        (records[id].containsKey('sbom') || records[specification.artifactOf].containsKey('sbom') ? ['sbom'] : [])).each { kind ->
                    if (records[id][kind]?.sha256 != records[specification.artifactOf][kind]?.sha256) {
                        throw new GradleException('Shared ' + kind + ' differs from owner: ' + id)
                    }
                }
                ['utilities', 'utilities_sources'].each { kind ->
                    if (records[id][kind] != records[specification.artifactOf][kind]) {
                        throw new GradleException('Shared ' + kind + ' differs from owner: ' + id)
                    }
                }
            }
        }
    }

    static List<Map> references(Map target) {
        if (target.containsKey('utilities') != target.containsKey('utilities_sources')) {
            throw new GradleException('Candidate requires utilities and utilities_sources together')
        }
        def tests = target.client_tests
        if (!(tests instanceof Map) || !(tests.drivers instanceof Map) || tests.drivers.isEmpty()) {
            throw new GradleException('Missing client test drivers')
        }
        (['artifact', 'sources', 'source_inventory'] + (target.containsKey('mapping') ? ['mapping'] : []) +
                (target.containsKey('utilities') ? ['utilities', 'utilities_sources'] : []) +
                (target.containsKey('sbom') ? ['sbom'] : [])).collect { target[it] as Map } +
                ['catalog', 'contract', 'ordinary_metadata', 'runtime_lock', 'dependency_lock'].collect { tests[it] as Map } +
                tests.drivers.values().collect { it as Map } +
                (tests.containsKey('test_dependencies') ? [tests.test_dependencies as Map] : [])
    }

    Map verifyPackages(File sourceRoot) {
        def reports = [:]
        records.each { id, record ->
            def runtime = specifications[id]
            def target = specifications[runtime.artifactOf ?: id]
            String version = packageVersion(data.release as String, target)
            PackageChecks.verifyCandidatePackage(file.parentFile, record, target, version, sourceRoot)
            reports[id] = [target: id, manifest_sha256: CandidateFiles.sha256(file),
                           source_commit: data.commit, release: data.release]
        }
        reports
    }

    static String packageVersion(String release, Map target) {
        // Older upstream catalogs describe artifacts published without the loader suffix.
        boolean includeLoader = target.versionIncludesLoader == true || target.buildProfile != 'fabric-upstream'
        CandidateFiles.releaseVersion(release) + '+mc' + target.minecraft + (includeLoader ? '-' + target.loader : '')
    }

    Map<String, String> releaseFiles(boolean requireImplemented = true) {
        if (requireImplemented && specifications.values().any { !it.implemented }) {
            throw new GradleException('Selected target is not implemented')
        }
        Map<String, String> files = [(file.name): CandidateFiles.sha256(file)]
        records.values().each { record ->
            references(record).each { reference ->
                String name = reference.path
                if (!name || name.contains('/') || name.contains('\\') || name in ['.', '..', 'candidate.json', 'SHA256SUMS', 'provenance.jsonl']) {
                    throw new GradleException('Release candidate assets must have distinct flat names')
                }
                if (files.containsKey(name) && files[name] != reference.sha256) {
                    throw new GradleException('Conflicting candidate filename: ' + name)
                }
                files[name] = reference.sha256
            }
        }
        File checksums = new File(file.parentFile, 'SHA256SUMS')
        String expected = files.keySet().sort().collect { files[it] + '  ' + it }.join('\n') + '\n'
        if (!checksums.isFile() || checksums.getText('UTF-8') != expected) {
            throw new GradleException('Candidate checksum list differs from manifest')
        }
        files.SHA256SUMS = CandidateFiles.sha256(checksums)
        files['provenance.jsonl'] = CandidateFiles.sha256(new File(file.parentFile, 'provenance.jsonl'))
        records.each { id, record ->
            if (record.containsKey('sbom')) {
                Map evidence = ReleaseEvidence.reference(this, id)
                if (files.containsKey(evidence.path)) throw new GradleException('Duplicate release evidence filename')
                files[evidence.path] = evidence.sha256
            }
        }
        files
    }
}
