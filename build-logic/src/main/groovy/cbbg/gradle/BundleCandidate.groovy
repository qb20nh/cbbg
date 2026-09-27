package cbbg.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import javax.inject.Inject
import java.nio.file.Files

abstract class BundleCandidate extends DefaultTask {
    @Internal abstract DirectoryProperty getSourceRoot()
    @Optional @InputFile abstract RegularFileProperty getBuildOutputs()
    @Optional @InputFile abstract RegularFileProperty getContract()
    @Optional @InputFile abstract RegularFileProperty getRuntimeLock()
    @Optional @InputFile abstract RegularFileProperty getDependencyLock()
    @Input abstract ListProperty<String> getSelectedTargets()
    @Input abstract Property<String> getReleaseTag()
    @OutputDirectory abstract DirectoryProperty getDestination()
    @Inject abstract ExecOperations getExecOperations()

    BundleCandidate() {
        selectedTargets.convention([])
        outputs.upToDateWhen { false }
    }

    private String git(List<String> args) {
        def output = new ByteArrayOutputStream()
        execOperations.exec {
            workingDir sourceRoot.get().asFile
            commandLine(['git'] + args)
            standardOutput = output
        }.assertNormalExitValue()
        output.toString('UTF-8').trim()
    }

    private List sourceState() {
        [git(['rev-parse', 'HEAD']), !git(['status', '--porcelain', '--untracked-files=all',
                                         '--', '.', ':(top,exclude)docs/**']).isEmpty()]
    }

    @TaskAction void bundle() {
        File root = sourceRoot.get().asFile.canonicalFile
        File output = destination.get().asFile.canonicalFile
        if (output.exists()) throw new GradleException('Candidate destination already exists')
        def state = sourceState()
        String commit = state[0]
        String release = releaseTag.get()
        CandidateFiles.releaseIdentity(release, commit)
        if (state[1]) throw new GradleException('Commit source changes before bundling a candidate')
        def catalog = TargetCatalog.read(new File(root, 'targets.json'))
        List<String> ids = selectedTargets.get()
        boolean multipleInputs = !ids.isEmpty()
        if (multipleInputs && [buildOutputs, contract, runtimeLock, dependencyLock].any { it.present }) {
            throw new GradleException('Explicit targets cannot be combined with single-target input overrides')
        }
        if (!multipleInputs) ids = [CandidateFiles.read(buildOutputs.get().asFile).target as String]
        Map<String, Map> selected = catalog.releaseTargets(ids)
        Map<String, Map> files = [:]
        Set<String> inputPaths = [] as Set
        def add = { String name, File file ->
            if (!name || name.contains('/') || name.contains('\\') || name in ['.', '..', 'candidate.json', 'SHA256SUMS', 'provenance.jsonl'] ||
                    files.containsKey(name) || !file.isFile() || !inputPaths.add(file.canonicalPath)) {
                throw new GradleException('Missing input, aliased path or duplicate candidate filename: ' + name)
            }
            String sha = CandidateFiles.sha256(file)
            files[name] = [file: file, sha256: sha]
            [path: name, sha256: sha]
        }
        Map catalogReference = add('catalog.json', new File(root, 'targets.json'))
        Map<String, Map> records = [:]
        selected.values().sort { it.artifactOf ? 1 : 0 }.each { Map target ->
        Map owner = target.artifactOf ? selected[target.artifactOf] : target
        File outputsFile = multipleInputs ? CandidateFiles.relativeFile(root, 'build/targets/' + owner.id + '/candidate-build-outputs.json') : buildOutputs.get().asFile
        File contractFile = multipleInputs ? CandidateFiles.relativeFile(root, 'runtime-locks/' + target.id + '-scenarios.json') : contract.get().asFile
        File runtimeFile = multipleInputs ? CandidateFiles.relativeFile(root, 'runtime-locks/' + target.id + '-linux-x86_64.json') : runtimeLock.get().asFile
        File dependencyFile = multipleInputs ? CandidateFiles.relativeFile(root, 'runtime-locks/' + target.id + '-mods.json') : dependencyLock.get().asFile
        Map built = CandidateFiles.read(outputsFile)
        if (!(built.schema instanceof Integer) || built.schema != 2 || built.source_commit != commit || built.source_dirty != false) {
            throw new GradleException('Build outputs do not identify the current clean source')
        }
        if (built.target != owner.id) throw new GradleException('Build outputs identify a different target')
        String version = CandidateManifest.packageVersion(release, owner)
        if (built.version != version) throw new GradleException('Build version differs from requested release')
        Map scenarios = CandidateFiles.read(contractFile)
        if (scenarios.schemaVersion != 1 || scenarios.target != target.id) {
            throw new GradleException('Scenario contract target or version differs')
        }
        File metadata = CandidateFiles.relativeFile(root, scenarios.ordinaryMetadata)
        List suites = ['ordinary'] + scenarios.additionalRuns.collect { it.suite }
        if (suites.any { !(it instanceof String) || !it } || suites.toSet().size() != suites.size() ||
                !(built.drivers instanceof Map) || !built.drivers.keySet().containsAll(suites)) {
            throw new GradleException('Build outputs omit required test drivers or contract has duplicate suites')
        }
        if (built.processing != [tool: 'proguard', version: ProguardMapping.VERSION] || !(built.mapping instanceof Map)) {
            throw new GradleException('Build outputs require ProGuard processing and mapping')
        }
        Map record = [id: target.id, processing: built.processing]
        ['artifact', 'sources', 'source_inventory', 'mapping', 'sbom'].each { kind ->
            if (target.artifactOf) {
                record[kind] = records[owner.id][kind]
                return
            }
            String name = kind == 'source_inventory' && multipleInputs ? target.id + '-' + built[kind].filename : built[kind].filename
            record[kind] = add(name, CandidateFiles.checked(root, built[kind]))
        }
        String prefix = multipleInputs ? target.id + '-' : ''
        Map metadataReference = records.values().find {
            files[it.client_tests.ordinary_metadata.path].file.canonicalFile == metadata.canonicalFile
        }?.client_tests?.ordinary_metadata
        record.client_tests = [catalog: catalogReference,
                               contract: add(prefix + 'contract.json', contractFile),
                               ordinary_metadata: metadataReference ?: add(prefix + 'ordinary-metadata.json', metadata),
                               runtime_lock: add(prefix + 'runtime-lock.json', runtimeFile),
                               dependency_lock: add(prefix + 'dependency-lock.json', dependencyFile),
                               drivers: suites.sort().collectEntries { suite ->
                                   [(suite): target.artifactOf && records[owner.id].client_tests.drivers.containsKey(suite) ?
                                           records[owner.id].client_tests.drivers[suite] :
                                           add(prefix + built.drivers[suite].filename, CandidateFiles.checked(root, built.drivers[suite]))]
                               }]
        records[target.id] = record
        }
        if (!output.mkdirs()) throw new GradleException('Could not create candidate directory')
        try {
            files.each { name, reference ->
                File copied = new File(output, name)
                Files.copy(reference.file.toPath(), copied.toPath())
                if (CandidateFiles.sha256(copied) != reference.sha256) {
                    throw new GradleException('Candidate input changed while copying: ' + name)
                }
            }
            File manifest = new File(output, 'candidate.json')
            CandidateFiles.writeNew(manifest, [schema: 3, release: release, commit: commit,
                    catalog_sha256: CandidateFiles.canonicalHash(catalog.data), targets: records.values().toList(), selected_targets: ids])
            new CandidateManifest(manifest).verifyPackages(root)
            if (sourceState() != state) throw new GradleException('Source changed while bundling candidate')
            def sums = output.listFiles().sort { it.name }.collect { CandidateFiles.sha256(it) + '  ' + it.name }
            Files.writeString(new File(output, 'SHA256SUMS').toPath(), sums.join('\n') + '\n')
        } catch (Exception error) {
            output.deleteDir()
            throw error
        }
    }
}
