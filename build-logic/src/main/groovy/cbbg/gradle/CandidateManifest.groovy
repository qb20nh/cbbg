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
        if (!(data.schema instanceof Integer) || data.schema != 2) {
            throw new GradleException('Client validation requires candidate schema 2')
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
        specifications.each { id, specification ->
            if (specification.artifactOf) {
                ['artifact', 'sources'].each { kind ->
                    if (records[id][kind].sha256 != records[specification.artifactOf][kind].sha256) {
                        throw new GradleException('Shared ' + kind + ' differs from owner: ' + id)
                    }
                }
            }
        }
    }

    static List<Map> references(Map target) {
        def tests = target.client_tests
        if (!(tests instanceof Map) || !(tests.drivers instanceof Map) || tests.drivers.isEmpty()) {
            throw new GradleException('Missing client test drivers')
        }
        ['artifact', 'sources', 'source_inventory'].collect { target[it] as Map } +
                ['catalog', 'contract', 'ordinary_metadata', 'runtime_lock', 'dependency_lock'].collect { tests[it] as Map } +
                tests.drivers.values().collect { it as Map }
    }

    Map verifyPackages(File sourceRoot) {
        def reports = [:]
        records.each { id, record ->
            def target = specifications[id]
            String version = data.release.substring(1) + '+mc' + target.minecraft + '-' + target.loader
            PackageChecks.verifyCandidatePackage(file.parentFile, record, target, version, sourceRoot)
            reports[id] = [target: id, manifest_sha256: CandidateFiles.sha256(file),
                           source_commit: data.commit, release: data.release]
        }
        reports
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
        files
    }
}
