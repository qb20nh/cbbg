package cbbg.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.SourceTask
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations

import javax.inject.Inject

/** Uses the PMD CLI so CPD and PMD share a version and Gradle's process handling. */
abstract class CpdCheck extends SourceTask {
    @Classpath abstract ConfigurableFileCollection getToolClasspath()
    @Nested abstract Property<JavaLauncher> getJavaLauncher()
    @Input abstract Property<Integer> getMinimumTokens()
    @OutputFile abstract RegularFileProperty getReportFile()
    @Inject abstract ExecOperations getExecOperations()

    @TaskAction
    void checkDuplicates() {
        File list = new File(temporaryDir, 'sources.txt')
        list.parentFile.mkdirs()
        list.setText(source.files.collect { it.canonicalPath }.toSet().sort().join('\n') + '\n', 'UTF-8')
        File report = reportFile.get().asFile
        report.parentFile.mkdirs()
        execOperations.javaexec { spec ->
            spec.executable = javaLauncher.get().executablePath.asFile.absolutePath
            spec.classpath = toolClasspath
            spec.mainClass.set('net.sourceforge.pmd.cli.PmdCli')
            spec.args 'cpd', '--language', 'java', '--minimum-tokens', minimumTokens.get().toString(),
                    '--file-list', list.absolutePath, '--format', 'xml', '--report-file', report.absolutePath,
                    '--fail-on-error', '--fail-on-violation'
        }.assertNormalExitValue()
    }
}
