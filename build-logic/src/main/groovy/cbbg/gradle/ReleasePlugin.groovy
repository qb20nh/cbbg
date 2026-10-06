package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.process.ExecOperations
import javax.inject.Inject
import java.time.Duration

class ReleasePlugin implements Plugin<Project> {
    private final ExecOperations execOperations

    @Inject ReleasePlugin(ExecOperations execOperations) { this.execOperations = execOperations }

    void apply(Project project) {
        def required = { String name ->
            def value = project.providers.gradleProperty(name).orNull
            if (!value) throw new GradleException('Missing -P' + name + '. ' +
                    project.gradle.startParameter.taskNames.join(', ') + ' requires -P' + name + '=<value>. See the release commands in CONTRIBUTING.md.')
            value
        }
        def input = { String name -> project.file(required(name)).canonicalFile }
        def source = { project.file(project.providers.gradleProperty('sourceRoot').getOrElse(project.rootDir.absolutePath)).canonicalFile }
        def results = {
            project.properties.findAll { key, value -> key.startsWith('results.') }
                    .collectEntries { key, value -> [(key.substring('results.'.length())): project.file(value.toString()).canonicalPath] }
        }
        Closure run = { List<String> command, File directory ->
            def stdout = new ByteArrayOutputStream()
            def stderr = new ByteArrayOutputStream()
            def result = execOperations.exec {
                workingDir directory ?: project.rootDir
                commandLine command
                standardOutput = stdout
                errorOutput = stderr
                ignoreExitValue = true
            }
            if (result.exitValue != 0) throw new GradleException(command[0] + ' failed: ' + stderr.toString('UTF-8').trim())
            stdout.toString('UTF-8')
        }
        def local = {
            if ('true'.equalsIgnoreCase(System.getenv('CI'))) {
                throw new GradleException('Candidate runtime acceptance and finalization run locally. Download the candidate and run this task from a local checkout with the packaged test results; CI only builds the candidate.')
            }
        }
        def task = { String name, String description, Closure action ->
            project.tasks.register(name) {
                group = 'distribution'
                delegate.description = description
                timeout = Duration.ofMinutes(15)
                // Remote checks and publication are never restored from build outputs.
                outputs.upToDateWhen { false }
                doLast(action)
            }
        }
        task('releaseNotes', 'Select shared and target-specific changelog notes for a release.') {
            String tag = required('release')
            CandidateFiles.releaseIdentity(tag, '0' * 40)
            if (project.providers.gradleProperty('targets').isPresent() && project.providers.gradleProperty('target').isPresent()) {
                throw new GradleException('Use either -Ptarget or -Ptargets')
            }
            String selection = project.providers.gradleProperty('targets').orElse(project.providers.gradleProperty('target')).orNull
            if (!selection) throw new GradleException('Release notes require an explicit target selection. Use -Ptarget=<id> for one runtime or -Ptargets=<id,id> for a shared release; IDs are listed in targets.json.')
            List<Map> targets = TargetCatalog.read(new File(source(), 'targets.json')).select(selection)
            CandidateFiles.releaseTargets(tag, targets)
            String notes = ChangelogNotes.select(new File(source(), 'CHANGELOG.md'), CandidateFiles.releaseVersion(tag), targets)
            File output = input('output')
            output.parentFile.mkdirs()
            output.setText(notes, 'UTF-8')
        }
        task('retrace', 'Decode a crash with its release mapping.') {
            RetraceLog.translate(input('candidate'), required('target'), input('crash'), input('output'))
        }
        task('verifyProvenance', 'Check candidate build attestations.') {
            CandidateFiles.writeNew(input('output'), ReleaseChecks.provenance(
                    input('candidate'), input('bundle'), required('repo'), run))
        }
        task('packageReleaseEvidence', 'Package SBOM, mappings and GitHub build provenance.') {
            File manifest = input('candidate')
            ReleaseChecks.provenance(manifest, new File(manifest.parentFile, 'provenance.jsonl'), required('repo'), run)
            CandidateManifest candidate = new CandidateManifest(manifest)
            candidate.records.keySet().each { ReleaseEvidence.assemble(candidate, it) }
        }
        task('preparePublicReleaseAssets', 'Select public files from a verified candidate.') {
            ReleaseChecks.preparePublicAssets(input('candidate'), input('output'))
        }
        task('verifyCandidate', 'Check packaged candidate and complete local runtime results.') {
            local()
            File output = input('output')
            if (output.exists()) throw new GradleException("Validation output already exists: ${output}. Choose a new -Poutput=<file> for this verification.")
            CandidateFiles.writeNew(output, ReleaseChecks.verify(input('candidate'), results(), input('bundle'),
                    source(), required('repo'), run))
        }
        ['checkRelease', 'publishRelease'].each { name ->
            task(name, name == 'checkRelease' ? 'Check a GitHub draft against local acceptance results.' :
                    'Publish a verified GitHub draft after explicit approval.') {
                local()
                ReleaseChecks.finalizeCandidate(input('candidate'), results(), source(), required('repo'),
                        required('release'), input('notes'), input('output'), name == 'publishRelease', run)
            }
        }
        task('preparePublication', 'Check immutable release files and prepare service metadata.') {
            String tag = required('release')
            String dryRun = project.providers.gradleProperty('dryRun').getOrElse('false')
            if (!(dryRun in ['true', 'false'])) throw new GradleException('Expected -PdryRun=true or false')
            def arguments = [tag, required('repo'), source(), input('assets'), input('output'),
                             input('githubOutput'), project.providers.gradleProperty('services').getOrElse('both'), run]
            if (tag.contains('+mc') && !CandidateFiles.targetedRelease(tag)) {
                ReleaseChecks.prepareLegacyPublication(*arguments)
            } else {
                ReleaseChecks.preparePublication(*(arguments + [null, dryRun == 'true']))
            }
        }
        task('selectPublication', 'Recheck release files and select one artifact for upload.') {
            ReleaseChecks.selectPublication(required('release'), required('repo'), source(), input('assets'),
                    input('publicationMetadata'), required('target'), input('githubOutput'), run,
                    null, project.providers.gradleProperty('services').getOrElse('both'))
        }
        task('recordCurseForgeUpload', 'Save the CurseForge file ID with the submitted metadata.') {
            String identifier = required('fileId')
            if (!(identifier ==~ /[0-9]+/) || new BigInteger(identifier) <= 0) {
                throw new GradleException('CurseForge returned no valid file ID; check the project before retrying')
            }
            String sourcesIdentifier = project.providers.gradleProperty('sourcesFileId').orNull
            if (sourcesIdentifier != null &&
                    (!(sourcesIdentifier ==~ /[0-9]+/) || new BigInteger(sourcesIdentifier) <= 0)) {
                throw new GradleException('CurseForge returned no valid sources file ID; check the project before retrying')
            }
            String evidenceIdentifier = project.providers.gradleProperty('evidenceFileId').orNull
            if (evidenceIdentifier != null &&
                    (!(evidenceIdentifier ==~ /[0-9]+/) || new BigInteger(evidenceIdentifier) <= 0)) {
                throw new GradleException('CurseForge returned no valid evidence file ID; check the project before retrying')
            }
            File metadataFile = input('publicationMetadata')
            Map metadata = CandidateFiles.read(metadataFile)
            if (!(metadata.records instanceof List)) throw new GradleException('Expected publication records')
            String selected = project.providers.gradleProperty('target').orNull
            List<Map> matches = selected ? metadata.records.findAll { it.targets?.contains(selected) } : metadata.records
            if (matches.size() != 1) throw new GradleException('Select one publication record with -Ptarget')
            Map record = matches[0]
            Map result = [release: metadata.release, source_commit: metadata.source_commit,
                          targets: record.targets, artifact: record.artifact,
                          metadata_sha256: CandidateFiles.sha256(metadataFile), file_id: identifier,
                          url: 'https://www.curseforge.com/minecraft/mc-mods/cbbg/files/' + identifier]
            if (sourcesIdentifier != null) {
                if (!(record.sources instanceof Map)) throw new GradleException('Missing sources publication record')
                result.sources = record.sources
                result.sources_file_id = sourcesIdentifier
                result.sources_url = 'https://www.curseforge.com/minecraft/mc-mods/cbbg/files/' + sourcesIdentifier
            }
            if (evidenceIdentifier != null) {
                if (!(record.evidence instanceof Map)) throw new GradleException('Missing evidence publication record')
                result.evidence = record.evidence
                result.evidence_file_id = evidenceIdentifier
                result.evidence_url = 'https://www.curseforge.com/minecraft/mc-mods/cbbg/files/' + evidenceIdentifier
            }
            CandidateFiles.writeNew(input('output'), result)
            project.logger.lifecycle(result.url as String)
        }
        task('selectChecks', 'Select development checks from Git changes.') {
            String base = required('base')
            String head = project.providers.gradleProperty('head').getOrElse('HEAD')
            Map report
            if (base ==~ /0+/) {
                String commit = run(['git', 'rev-parse', '--verify', '--end-of-options', head + '^{commit}'], project.rootDir).trim()
                def catalog = new TargetCatalog(CandidateFiles.parse(new StringReader(
                        run(['git', 'show', commit + ':targets.json'], project.rootDir))) as Map)
                report = [base: null, head: commit] + ChangeImpact.select(catalog, ['<initial-push>'])
            } else {
                report = ChangeImpact.compare(project.rootDir, base, head)
            }
            def catalog = new TargetCatalog(CandidateFiles.parse(new StringReader(
                    run(['git', 'show', report.head + ':targets.json'], project.rootDir))) as Map)
            report.ci = ChangeImpact.ci(catalog, report)
            File output = input('output')
            output.parentFile.mkdirs()
            output.setText(JsonOutput.prettyPrint(JsonOutput.toJson(report)) + '\n', 'UTF-8')
            def githubOutput = project.providers.gradleProperty('githubOutput').orNull
            if (githubOutput) {
                project.file(githubOutput).withWriterAppend('UTF-8') { writer ->
                    report.ci.each { key, value -> writer.write(key + '=' + JsonOutput.toJson(value) + '\n') }
                }
            }
        }
        task('checkHotfix', 'Check selected targets against explicit prior release manifests.') {
            List<File> baselines = required('baselines').split(',', -1).collect { value ->
                if (!value) throw new GradleException('Empty baseline manifest path')
                project.file(value)
            }
            String selected = project.providers.gradleProperty('targets').orElse(project.providers.gradleProperty('target')).orNull
            if (!selected) throw new GradleException('Hotfix requires an explicit -Ptargets selection')
            Map report = ChangeImpact.hotfix(project.rootDir,
                    project.providers.gradleProperty('head').getOrElse('HEAD'), baselines, selected.split(',', -1).toList())
            CandidateFiles.writeNew(input('output'), report)
        }
    }
}
