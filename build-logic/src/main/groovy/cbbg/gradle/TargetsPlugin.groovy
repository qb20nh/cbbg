package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.GradleException
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

class TargetsPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.pluginManager.apply('base')
        project.pluginManager.apply('jvm-toolchains')
        def catalog = TargetCatalog.read(project.file('targets.json'))
        def single = project.providers.gradleProperty('target')
        def multiple = project.providers.gradleProperty('targets')
        if (single.present && multiple.present) {
            throw new GradleException('Use either -Ptarget or -Ptargets, not both')
        }
        String selection = single.orElse(multiple).getOrElse(catalog.defaults()*.id.join(','))
        def selected = catalog.select(selection)
        def owners = catalog.artifacts(selection)
        def serial = project.gradle.sharedServices.registerIfAbsent('cbbgBuildProcesses', BuildProcesses) {
            maxParallelUsages = 1
        }
        def toolchains = project.extensions.getByType(JavaToolchainService)
        Map<String, String> forwarded = [:]
        ['compat', 'backend', 'testJava', 'testRenderScale'].each { key ->
            def value = project.providers.gradleProperty(key)
            if (value.present) forwarded[key] = value.get()
        }
        List<String> options = []
        if (project.gradle.startParameter.rerunTasks) options.add('--rerun-tasks')
        if (!project.gradle.startParameter.buildCacheEnabled) options.add('--no-build-cache')
        ['build', 'check', 'ciCheck', 'qualityCheck', 'runClient', 'genSources', 'dev', 'compileJava',
         'checkPackages', 'optimizeReleaseJar', 'candidateBuildOutputs'].each { operation ->
            def parent = project.tasks.names.contains(operation)
                    ? project.tasks.named(operation) : project.tasks.register(operation)
            parent.configure {
                group = operation in ['check', 'ciCheck', 'qualityCheck'] ? 'verification' : 'build'
                description = "Run ${operation} for the selected targets."
            }
            def targets = operation == 'runClient' ? selected : owners
            targets.each { target ->
                def child = project.tasks.register("${operation}_${target.id}", TargetBuild) {
                    repositoryDirectory.set(project.layout.projectDirectory)
                    targetId.set(target.id as String)
                    profile.set((target.buildProfile ?: '') as String)
                    delegate.operation.set(operation)
                    offline.set(project.gradle.startParameter.offline)
                    delegate.options.set(options)
                    buildProperties.set(forwarded)
                    // Build JVM and game/bytecode Java requirements are independent.
                    int buildJava = (target.buildJava ?: 25) as int
                    javaHome.set(toolchains.launcherFor {
                        languageVersion = JavaLanguageVersion.of(buildJava)
                    }.map { it.metadata.installationPath })
                    usesService(serial)
                    doFirst {
                        if (operation == 'runClient' && selected.size() != 1) {
                            throw new GradleException('runClient requires exactly one target')
                        }
                    }
                }
                parent.configure { dependsOn(child) }
            }
        }
        project.tasks.register('checkCatalog') {
            group = 'verification'
            description = 'Validate the distribution catalog.'
            inputs.file(project.layout.projectDirectory.file('targets.json'))
            doLast { TargetCatalog.read(project.file('targets.json')) }
        }
        project.tasks.register('targetMatrix') {
            group = 'help'
            description = 'Print one build job per selected artifact.'
            doLast {
                if (project.providers.gradleProperty('requireImplemented').getOrElse('false').toBoolean()) {
                    catalog.select(selection, true)
                }
                String json = JsonOutput.toJson([include: catalog.matrix(selection)])
                def output = project.providers.gradleProperty('output').orNull
                if (output) {
                    File destination = project.file(output)
                    destination.parentFile.mkdirs()
                    destination.setText(json + '\n', 'UTF-8')
                } else {
                    println(json)
                }
            }
        }
        project.tasks.named('check') { dependsOn('checkCatalog') }
        project.tasks.register('bundleCandidate', BundleCandidate) {
            group = 'distribution'
            description = 'Bundle recorded build outputs without rebuilding them.'
            sourceRoot.set(project.layout.projectDirectory)
            buildOutputs.set(project.layout.file(project.providers.gradleProperty('buildOutputs').map { project.file(it) }))
            contract.set(project.layout.file(project.providers.gradleProperty('contract').map { project.file(it) }))
            runtimeLock.set(project.layout.file(project.providers.gradleProperty('runtimeLock').map { project.file(it) }))
            dependencyLock.set(project.layout.file(project.providers.gradleProperty('dependencyLock').map { project.file(it) }))
            releaseTag.set(project.providers.gradleProperty('release'))
            destination.set(project.layout.dir(project.providers.gradleProperty('output').map { project.file(it) }))
        }
    }
}
