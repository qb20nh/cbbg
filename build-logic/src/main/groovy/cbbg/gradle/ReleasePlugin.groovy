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
            if (!value) throw new GradleException('Missing -P' + name)
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
                throw new GradleException('Candidate runtime acceptance and finalization run locally')
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
        task('retrace', 'Decode a crash with its release mapping.') {
            RetraceLog.translate(input('candidate'), required('target'), input('crash'), input('output'))
        }
        task('verifyProvenance', 'Check candidate build attestations.') {
            CandidateFiles.writeNew(input('output'), ReleaseChecks.provenance(
                    input('candidate'), input('bundle'), required('repo'), run))
        }
        task('verifyCandidate', 'Check packaged candidate and complete local runtime results.') {
            local()
            File output = input('output')
            if (output.exists()) throw new GradleException('Validation output already exists')
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
            def arguments = [tag, required('repo'), source(), input('assets'), input('output'),
                             input('githubOutput'), project.providers.gradleProperty('services').getOrElse('both'), run]
            if (tag.contains('+mc')) {
                ReleaseChecks.prepareLegacyPublication(*arguments)
            } else {
                ReleaseChecks.preparePublication(*arguments)
            }
        }
        task('recordCurseForgeUpload', 'Save the CurseForge file ID with the submitted metadata.') {
            String identifier = required('fileId')
            if (!(identifier ==~ /[0-9]+/) || new BigInteger(identifier) <= 0) {
                throw new GradleException('CurseForge returned no valid file ID; check the project before retrying')
            }
            File metadataFile = input('publicationMetadata')
            Map metadata = CandidateFiles.read(metadataFile)
            if (!(metadata.records instanceof List) || metadata.records.size() != 1) {
                throw new GradleException('Expected one publication record')
            }
            Map record = metadata.records[0]
            Map result = [release: metadata.release, source_commit: metadata.source_commit,
                          targets: record.targets, artifact: record.artifact,
                          metadata_sha256: CandidateFiles.sha256(metadataFile), file_id: identifier,
                          url: 'https://www.curseforge.com/minecraft/mc-mods/cbbg/files/' + identifier]
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
