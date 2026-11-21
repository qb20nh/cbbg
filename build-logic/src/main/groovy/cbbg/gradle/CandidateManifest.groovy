package cbbg.gradle

import org.gradle.api.GradleException

class CandidateManifest {
    private static final String LIBRARY_SOURCES = 'library_sources'
    private static final String LIBRARY = 'library'
    private static final String LIBRARY_PROVENANCE = 'library_provenance'
    private static final String LIBRARY_RELEASE = 'library_release'
    private static final String UTILITIES = 'utilities'
    private static final String UTILITIES_SOURCES = 'utilities_sources'
    private static final String MAPPING = 'mapping'
    private static final String PROVENANCE_FILE = 'provenance.jsonl'

    final File file
    final Map data
    final Map<String, Map> records
    final Map<String, Map> specifications
    final ReleaseIdentity identity

    CandidateManifest(File file) {
        this.file = file.canonicalFile
        data = CandidateFiles.read(this.file) as Map
        if (!(data.schema instanceof Integer) || !(data.schema in [2, 3])) {
            throw new GradleException('Client validation requires candidate schema 2 or 3')
        }
        CandidateFiles.releaseIdentity(data.release as String, data.commit as String)
        identity = ReleaseIdentity.parse(data.release as String)
        // Existing CBBG manifests predate explicit product metadata.
        identity.requireProduct(data.containsKey('product') ? data.product as String : 'cbbg')
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
            } else if (target.containsKey(MAPPING) || target.containsKey('processing')) {
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
            Map contract = CandidateFiles.read(CandidateFiles.checked(this.file.parentFile, record.client_tests.contract)) as Map
            if ((contract.product ?: 'cbbg') != identity.product) {
                throw new GradleException("Candidate product '${identity.product}' requires its own acceptance contract: ${record.client_tests.contract.path}; found '${contract.product ?: 'cbbg'}'.")
            }
        }
        specifications = expected
        CandidateFiles.releaseTargets(data.release as String, specifications.values())
        specifications.each { id, specification ->
            if (specification.artifactOf) {
                verifySharedArtifacts(id, records[id], records[specification.artifactOf], data.schema == 3)
            }
        }
    }

    private static void verifySharedArtifacts(String id, Map record, Map owner, boolean processed) {
        List<String> artifacts = ['artifact', 'sources', 'source_inventory']
        if (processed) {
            artifacts.add(MAPPING)
        }
        if (record.containsKey('sbom') || owner.containsKey('sbom')) {
            artifacts.add('sbom')
        }
        artifacts.each { kind ->
            if (record[kind]?.sha256 != owner[kind]?.sha256) {
                throw new GradleException('Shared ' + kind + ' differs from owner: ' + id)
            }
        }
        [UTILITIES, UTILITIES_SOURCES, LIBRARY, LIBRARY_SOURCES, LIBRARY_RELEASE,
         LIBRARY_PROVENANCE, 'library_source_commit'].each { kind ->
            if (record[kind] != owner[kind]) {
                throw new GradleException('Shared ' + kind + ' differs from owner: ' + id)
            }
        }
    }

    static List<Map> references(Map target) {
        if (target.containsKey(UTILITIES) != target.containsKey(UTILITIES_SOURCES)) {
            throw new GradleException('Candidate requires utilities and utilities_sources together')
        }
        if (target.containsKey(LIBRARY) != target.containsKey(LIBRARY_SOURCES)) {
            throw new GradleException('Candidate requires library and library_sources together')
        }
        if (target.containsKey(LIBRARY_PROVENANCE) &&
                (!target.containsKey(LIBRARY) || !(target.library_source_commit ==~ /[0-9a-f]{40}/))) {
            throw new GradleException('Library provenance requires its dependency artifact and source commit')
        }
        if (target.containsKey(LIBRARY_RELEASE)) {
            ReleaseIdentity.parse(target.library_release as String).requireProduct('lib')
        }
        def tests = target.client_tests
        if (!(tests instanceof Map) || !(tests.drivers instanceof Map) || tests.drivers.isEmpty()) {
            throw new GradleException('Missing client test drivers')
        }
        (['artifact', 'sources', 'source_inventory'] + (target.containsKey(MAPPING) ? [MAPPING] : []) +
                (target.containsKey(UTILITIES) ? [UTILITIES, UTILITIES_SOURCES] : []) +
                (target.containsKey(LIBRARY) ? [LIBRARY, LIBRARY_SOURCES] : []) +
                (target.containsKey(LIBRARY_PROVENANCE) ? [LIBRARY_PROVENANCE] : []) +
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
            if (record.containsKey(UTILITIES) && (identity.product == 'lib' || record.containsKey(LIBRARY_RELEASE))) {
                verifyUtilityNames(record, identity.product == 'lib' ? identity.version :
                        ReleaseIdentity.parse(record.library_release as String).version)
            }
            PackageChecks.verifyCandidatePackage(file.parentFile, record, target, version, sourceRoot, identity.product)
            reports[id] = [target: id, manifest_sha256: CandidateFiles.sha256(file),
                           source_commit: data.commit, release: data.release]
        }
        reports
    }

    private static void verifyUtilityNames(Map record, String version) {
        [(UTILITIES): 'cbbg-utilities-' + version + '.jar',
         (UTILITIES_SOURCES): 'cbbg-utilities-' + version + '-sources.jar'].each { kind, expected ->
            if (record[kind].path != expected) {
                throw new GradleException('Utility filename differs from its product version: ' + record[kind].path +
                        '; expected ' + expected + '. Build utilities with the library version for library dependencies.')
            }
        }
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
                if (!name || name.contains('/') || name.contains('\\') || name in ['.', '..', 'candidate.json', 'SHA256SUMS', PROVENANCE_FILE]) {
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
        files[PROVENANCE_FILE] = CandidateFiles.sha256(new File(file.parentFile, PROVENANCE_FILE))
        records.each { id, record ->
            if (record.containsKey('sbom')) {
                Map evidence = ReleaseEvidence.reference(this, id)
                if (files.containsKey(evidence.path)) {
                    throw new GradleException('Duplicate release evidence filename')
                }
                files[evidence.path] = evidence.sha256
            }
        }
        files
    }
}
