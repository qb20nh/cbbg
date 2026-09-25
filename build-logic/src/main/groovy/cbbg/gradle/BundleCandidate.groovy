package cbbg.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import javax.inject.Inject
import java.nio.file.Files

abstract class BundleCandidate extends DefaultTask {
    @Internal abstract DirectoryProperty getSourceRoot()
    @InputFile abstract RegularFileProperty getBuildOutputs()
    @InputFile abstract RegularFileProperty getContract()
    @InputFile abstract RegularFileProperty getRuntimeLock()
    @InputFile abstract RegularFileProperty getDependencyLock()
    @Input abstract Property<String> getReleaseTag()
    @OutputDirectory abstract DirectoryProperty getDestination()
    @Inject abstract ExecOperations getExecOperations()

    BundleCandidate() {
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
        Map built = CandidateFiles.read(buildOutputs.get().asFile)
        if (!(built.schema instanceof Integer) || built.schema != 2 || built.source_commit != commit || built.source_dirty != false) {
            throw new GradleException('Build outputs do not identify the current clean source')
        }
        def catalog = TargetCatalog.read(new File(root, 'targets.json'))
        def selected = catalog.select(built.target as String)
        if (selected.size() != 1 || selected[0].loader != 'fabric' || selected[0].buildProfile != 'fabric-modern') {
            throw new GradleException('Candidate bundling is not configured for this target')
        }
        Map target = selected[0]
        catalog.releaseTargets([target.id])
        String version = release.substring(1) + '+mc' + target.minecraft + '-fabric'
        if (built.version != version) throw new GradleException('Build version differs from requested release')
        Map scenarios = CandidateFiles.read(contract.get().asFile)
        if (scenarios.schemaVersion != 1 || scenarios.target != target.id) {
            throw new GradleException('Scenario contract target or version differs')
        }
        File metadata = CandidateFiles.relativeFile(root, scenarios.ordinaryMetadata)
        List suites = ['ordinary'] + scenarios.additionalRuns.collect { it.suite }
        if (suites.any { !(it instanceof String) || !it } || suites.toSet().size() != suites.size() ||
                !(built.drivers instanceof Map) || !built.drivers.keySet().containsAll(suites)) {
            throw new GradleException('Build outputs omit required test drivers or contract has duplicate suites')
        }
        Map<String, Map> files = [:]
        def add = { String name, File file ->
            if (!name || name.contains('/') || name.contains('\\') || name in ['.', '..', 'candidate.json', 'SHA256SUMS', 'provenance.jsonl'] ||
                    files.containsKey(name) || !file.isFile()) {
                throw new GradleException('Missing input or duplicate candidate filename: ' + name)
            }
            String sha = CandidateFiles.sha256(file)
            files[name] = [file: file, sha256: sha]
            [path: name, sha256: sha]
        }
        if (built.processing != [tool: 'proguard', version: ProguardMapping.VERSION] || !(built.mapping instanceof Map)) {
            throw new GradleException('Build outputs require ProGuard processing and mapping')
        }
        Map record = [id: target.id, processing: built.processing]
        ['artifact', 'sources', 'source_inventory', 'mapping'].each { kind ->
            record[kind] = add(built[kind].filename, CandidateFiles.checked(root, built[kind]))
        }
        record.client_tests = [catalog: add('catalog.json', new File(root, 'targets.json')),
                               contract: add('contract.json', contract.get().asFile),
                               ordinary_metadata: add('ordinary-metadata.json', metadata),
                               runtime_lock: add('runtime-lock.json', runtimeLock.get().asFile),
                               dependency_lock: add('dependency-lock.json', dependencyLock.get().asFile),
                               drivers: suites.sort().collectEntries { suite ->
                                   [(suite): add(built.drivers[suite].filename, CandidateFiles.checked(root, built.drivers[suite]))]
                               }]
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
                    catalog_sha256: CandidateFiles.canonicalHash(catalog.data), targets: [record], selected_targets: [target.id]])
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
