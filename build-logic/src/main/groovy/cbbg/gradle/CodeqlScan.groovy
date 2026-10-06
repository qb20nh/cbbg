package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.ExecOperations
import javax.inject.Inject

abstract class CodeqlScan extends DefaultTask {
    @Internal abstract DirectoryProperty getRepositoryDirectory()
    @Internal abstract RegularFileProperty getCodeqlExecutable()
    @InputFile abstract RegularFileProperty getCatalogFile()
    @Input abstract Property<String> getLegacyTarget()
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()
    @Inject abstract ExecOperations getExecOperations()
    @Inject abstract JavaToolchainService getToolchains()

    static List<Map> targets(TargetCatalog catalog, String legacyTarget) {
        List<String> ids = catalog.defaults()*.id + catalog.select().findAll { it.implemented }*.id
        if (legacyTarget) ids.add(legacyTarget)
        String legacyOwner = legacyTarget ? catalog.artifacts(legacyTarget).first().id : null
        catalog.artifacts(ids.unique().join(',')).collect { target ->
            if (!(target.id ==~ /[A-Za-z0-9][A-Za-z0-9._+-]*/) || !target.buildProfile) {
                throw new IllegalArgumentException('Invalid CodeQL build target: ' + target.id)
            }
            [id: target.id, buildProfile: target.buildProfile, buildJava: target.buildJava ?: 25,
             category: target.id == legacyOwner ? '/language:java-kotlin' :
                     '/language:java-kotlin/target:' + target.id]
        }
    }

    @TaskAction
    void scan() {
        File root = repositoryDirectory.get().asFile.canonicalFile
        boolean windows = System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('windows')
        List<Map> selected = targets(TargetCatalog.read(catalogFile.get().asFile), legacyTarget.get())
        File output = outputDirectory.get().asFile
        project.delete(output)
        File reports = new File(output, 'sarif')
        reports.mkdirs()
        new File(output, 'plan.json').text = JsonOutput.prettyPrint(JsonOutput.toJson(selected)) + '\n'
        String executable = codeqlExecutable.get().asFile.absolutePath
        for (Map target : selected) {
            File profile = new File(root, 'build-config/' + target.buildProfile).canonicalFile
            if (profile.parentFile != new File(root, 'build-config').canonicalFile ||
                    !new File(profile, 'build.gradle').isFile()) {
                throw new GradleException('Invalid CodeQL build profile: ' + target.buildProfile)
            }
            List<String> build = TargetBuild.wrapperCommand(root, profile, windows) +
                    ['-p', profile.absolutePath, 'clean', 'compileJava', '--rerun-tasks', '--no-build-cache',
                     '--no-daemon', '--no-watch-fs', '--project-cache-dir',
                     new File(root, '.gradle/codeql/' + target.id).absolutePath]
            if (target.buildProfile != 'fabric-upstream') build.add('-Ptarget=' + target.id)
            if (project.gradle.startParameter.offline) build.add('--offline')
            File javaHome = toolchains.launcherFor { spec ->
                spec.languageVersion.set(JavaLanguageVersion.of(target.buildJava as int))
            }.get().metadata.installationPath.asFile
            File database = new File(output, 'databases/' + target.id)
            database.parentFile.mkdirs()
            File report = new File(reports, target.id + '.sarif')
            logger.lifecycle('CodeQL: {} ({})', target.id, target.buildProfile)
            def run = { List<String> args ->
                execOperations.exec { spec ->
                    spec.workingDir root
                    spec.environment 'JAVA_HOME', javaHome.absolutePath
                    spec.commandLine([executable] + args)
                }.assertNormalExitValue()
            }
            run(['database', 'init', database.absolutePath, '--language=java', '--build-mode=manual',
                 '--source-root=' + root.absolutePath])
            run(['database', 'trace-command', database.absolutePath, '--'] + build)
            run(['database', 'finalize', database.absolutePath, '--threads=2', '--ram=4096'])
            run(['database', 'analyze', database.absolutePath,
                 'codeql/java-queries:codeql-suites/java-code-scanning.qls',
                 '--format=sarif-latest', '--output=' + report.absolutePath,
                 '--sarif-category=' + target.category, '--threads=2', '--ram=4096'])
            Map result = CandidateFiles.read(report) as Map
            if (result.version != '2.1.0' || !(result.runs instanceof List) || result.runs.size() != 1 ||
                    result.runs.first().automationDetails?.id?.replaceFirst('/+$', '') != target.category) {
                throw new GradleException('Unexpected CodeQL report for ' + target.id)
            }
            project.delete(database)
        }
        logger.lifecycle('Analyzed {} distinct artifact builds', selected.size())
    }
}
