package cbbg.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

/** Selects target JDK libraries for ProGuard without packaging runtime classes. */
class JdkLibraries {
    static Provider<?> select(Project project, Provider<Directory> targetHome,
            String exportTaskName, String outputPath, List<String> moduleNames = []) {
        def runtimeJar = project.tasks.register(exportTaskName, ExportJdkLibraries) {
            runtimeHome.set(targetHome)
            modulesFile.set(targetHome.map { it.file('lib/modules') })
            fileSystemJar.set(targetHome.map { it.file('lib/jrt-fs.jar') })
            outputJar.set(project.layout.buildDirectory.file(outputPath))
        }
        targetHome.map { installation ->
            File home = installation.asFile
            File jmods = new File(home, 'jmods')
            if (jmods.isDirectory()) {
                def modules = moduleNames.isEmpty()
                        ? jmods.listFiles().findAll { it.name.endsWith('.jmod') }.sort()
                        : moduleNames.collect { new File(jmods, it + '.jmod') }
                if (!modules.isEmpty() && modules.every { it.isFile() }) return project.files(modules)
            }
            File rtJar = new File(home, 'jre/lib/rt.jar')
            if (!rtJar.isFile()) rtJar = new File(home, 'lib/rt.jar')
            if (rtJar.isFile()) return project.files(rtJar)
            if (new File(home, 'lib/modules').isFile() && new File(home, 'lib/jrt-fs.jar').isFile()) {
                return project.files(runtimeJar.flatMap { it.outputJar })
            }
            throw new GradleException('Missing Java runtime libraries for ' + home)
        }
    }
}
