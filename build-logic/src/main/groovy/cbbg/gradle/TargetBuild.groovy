package cbbg.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/** The child Gradle build owns compilation inputs and incremental outputs. */
abstract class TargetBuild extends DefaultTask {
    @Internal abstract DirectoryProperty getRepositoryDirectory()
    @Internal abstract DirectoryProperty getJavaHome()
    @Input abstract Property<String> getTargetId()
    @Input abstract Property<String> getProfile()
    @Input abstract Property<String> getOperation()
    @Input abstract Property<Boolean> getOffline()
    @Input abstract ListProperty<String> getOptions()
    @Input abstract MapProperty<String, String> getBuildProperties()
    @Inject abstract ExecOperations getExecOperations()

    @TaskAction
    void runBuild() {
        if (operation.get() in ['runClient', 'updateFabricMinimums', 'verifyFabricCompatibility'] &&
                ('true'.equalsIgnoreCase(System.getenv('CI')) || System.getenv('GITHUB_ACTIONS') == 'true')) {
            throw new GradleException('Minecraft runtime tests are local-only. Run ' + operation.get() +
                    ' with -Ptarget=' + targetId.get() + ' from a local checkout with a test display; CI runs compilation and static checks.')
        }
        File root = repositoryDirectory.get().asFile
        File directory = new File(root, 'build-config/' + profile.get()).canonicalFile
        File profiles = new File(root, 'build-config').canonicalFile
        if (directory.parentFile != profiles || !new File(directory, 'build.gradle').isFile()) {
            throw new GradleException('Invalid build profile: ' + profile.get() + ' for ' + targetId.get() +
                    '. Expected build.gradle inside ' + profiles + '. Check buildProfile in targets.json and restore the profile files.')
        }
        boolean windows = System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('windows')
        List<String> args = wrapperCommand(root, directory, windows) + ['-p', directory.absolutePath,
                             operation.get(), '--no-daemon', '--no-watch-fs',
                             '--project-cache-dir', new File(root, '.gradle/dispatch/' + targetId.get()).absolutePath]
        if (profile.get() != 'fabric-upstream') args.add('-Ptarget=' + targetId.get())
        if (offline.get()) args.add('--offline')
        args.addAll(options.get())
        buildProperties.get().each { key, value -> args.add('-P' + key + '=' + value) }
        def result = execOperations.exec {
            workingDir root
            environment 'JAVA_HOME', javaHome.get().asFile.absolutePath
            commandLine args
            ignoreExitValue = true
        }
        if (result.exitValue != 0) {
            throw new GradleException("Target ${targetId.get()} failed (primary task ${operation.get()}, profile ${profile.get()}, exit value ${result.exitValue}). See the child Gradle failure above; build directory: ${directory}.")
        }
    }

    static List<String> wrapperCommand(File root, File directory, boolean windows) {
        String name = windows ? 'gradlew.bat' : 'gradlew'
        File wrapper = new File(directory, name)
        if (!wrapper.isFile()) wrapper = new File(root, name)
        if (!wrapper.isFile()) throw new GradleException('Missing Gradle wrapper: ' + wrapper)
        (windows ? ['cmd', '/d', '/c'] : []) + [wrapper.absolutePath]
    }
}
