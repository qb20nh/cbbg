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
        options.add(project.gradle.startParameter.buildCacheEnabled ? '--build-cache' : '--no-build-cache')
        List<String> requested = project.gradle.startParameter.taskNames
        int checkIndex = requested.findIndexOf { it in ['ciCheck', ':ciCheck'] }
        int devIndex = requested.findIndexOf { it in ['dev', ':dev'] }
        boolean checkedDev = checkIndex >= 0 && devIndex == checkIndex + 1 &&
                !project.gradle.startParameter.continueOnFailure &&
                project.gradle.startParameter.excludedTaskNames.empty
        ['build', 'check', 'ciCheck', 'qualityCheck', 'runClient', 'genSources', 'dev', 'compileJava',
         'checkPackages', 'optimizeReleaseJar', 'candidateBuildOutputs'].each { operation ->
            def parent = project.tasks.names.contains(operation)
                    ? project.tasks.named(operation) : project.tasks.register(operation)
            parent.configure {
                group = operation in ['check', 'ciCheck', 'qualityCheck'] ? 'verification' : 'build'
                description = "Run ${operation} for the selected targets."
            }
            if (operation == 'dev' && checkedDev) {
                parent.configure { dependsOn(project.tasks.named('ciCheck')) }
                return
            }
            def targets = operation == 'runClient' ? selected : owners
            targets.each { target ->
                def child = project.tasks.register("${operation}_${target.id}", TargetBuild) {
                    repositoryDirectory.set(project.layout.projectDirectory)
                    targetId.set(target.id as String)
                    profile.set((target.buildProfile ?: '') as String)
                    delegate.operation.set(operation)
                    if (operation == 'ciCheck' && checkedDev) additionalOperations.set(['dev'])
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
                            throw new GradleException('runClient requires exactly one target; selected: ' +
                                    selected.collect { it.id }.join(', ') + '. Launch with ./gradlew runClient -Ptarget=<id>; IDs are listed in targets.json.')
                        }
                    }
                }
                parent.configure { dependsOn(child) }
            }
        }
        def minimums = project.tasks.register('determineFabricMinimums') {
            group = 'verification'
            description = 'Locally find dependency lower/upper bounds, update metadata and verify the release jar.'
        }
        Map<String, String> localInputs = [:]
        ['compatibilityPython', 'compatibilityRuntime', 'compatibilityJava', 'compatibilityManageDisplay',
         'compatibilityDisplayDirectory', 'compatibilityWaylandDisplay', 'compatibilityEglVendorFile'].each { key ->
            def value = project.providers.gradleProperty(key)
            if (value.present) localInputs[key] = value.get()
        }
        Map owner = owners.size() == 1 ? owners.first() : null
        List<Map> family = []
        if (selected.size() == 1 && selected.first().loader == 'fabric' && owner != null) {
            if (owner.renderer in ['renderpearl', 'blaze-gpu-format']) {
                family = [selected.first()]
            } else if (owner.renderer == 'blaze-texture-format' && owner.compatibleMinecraft) {
                String ids = ([owner.minecraft] + owner.compatibleMinecraft).collect { it + '-fabric' }.join(',')
                family = catalog.select(ids)
            }
        }
        if (family.isEmpty()) {
            minimums.configure {
                doLast { throw new GradleException('determineFabricMinimums requires one supported Fabric target. Use -Ptarget=<fabric-id> from targets.json; this dependency search runs locally.') }
            }
        } else {
            List<Map> phases = []
            if (family.size() > 1) {
                phases.add([name: 'local_search_' + owner.id,
                        target: owner, operation: 'verifyFabricCompatibility', strict: false])
                phases.add([name: 'local_updateFabricMinimums', target: owner,
                        operation: 'updateFabricMinimums', strict: false])
                family.each { target -> phases.add([name: 'local_strict_' + target.id,
                        target: target, operation: 'verifyFabricCompatibility', strict: true]) }
            } else {
                phases.add([name: 'local_updateFabricMinimums', target: family.first(),
                        operation: 'updateFabricMinimums', strict: false])
                phases.add([name: 'local_verifyFabricCompatibility', target: family.first(),
                        operation: 'verifyFabricCompatibility', strict: true])
            }
            def previousPhase = null
            phases.each { plan ->
                def phase = project.tasks.register(plan.name as String, TargetBuild) {
                    repositoryDirectory.set(project.layout.projectDirectory)
                    targetId.set(plan.target.id as String)
                    profile.set((plan.target.buildProfile ?: '') as String)
                    operation.set(plan.operation as String)
                    offline.set(project.gradle.startParameter.offline)
                    delegate.options.set(options)
                    buildProperties.set(localInputs + [verifyDeclaredMinimums: plan.strict.toString()])
                    javaHome.set(toolchains.launcherFor {
                        languageVersion = JavaLanguageVersion.of((plan.target.buildJava ?: 25) as int)
                    }.map { it.metadata.installationPath })
                    usesService(serial)
                }
                if (previousPhase != null) {
                    def dependency = previousPhase
                    phase.configure { dependsOn(dependency) }
                }
                previousPhase = phase
            }
            minimums.configure { dependsOn(previousPhase) }
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
        project.tasks.register('codeqlScan', CodeqlScan) {
            group = 'verification'
            description = 'Analyze each supported artifact in a separate CodeQL database.'
            repositoryDirectory.set(project.layout.projectDirectory)
            catalogFile.set(project.layout.projectDirectory.file('targets.json'))
            codeqlExecutable.set(project.layout.file(project.providers.gradleProperty('codeqlExecutable')
                    .map { project.file(it) }))
            legacyTarget.set(project.providers.gradleProperty('codeqlLegacyTarget').orElse(''))
            // The historical upstream profile's clean task deletes repository-level build/.
            outputDirectory.set(project.layout.projectDirectory.dir('.gradle/codeql-results'))
            outputs.upToDateWhen { false }
        }
        project.tasks.named('check') { dependsOn('checkCatalog') }
        project.tasks.register('bundleCandidate', BundleCandidate) {
            group = 'distribution'
            description = 'Bundle recorded build outputs without rebuilding them.'
            sourceRoot.set(project.layout.projectDirectory)
            selectedTargets.set(project.providers.gradleProperty('targets').map { it.split(',', -1).toList() }.orElse([]))
            buildOutputs.set(project.layout.file(project.providers.gradleProperty('buildOutputs').map { project.file(it) }))
            contract.set(project.layout.file(project.providers.gradleProperty('contract').map { project.file(it) }))
            runtimeLock.set(project.layout.file(project.providers.gradleProperty('runtimeLock').map { project.file(it) }))
            dependencyLock.set(project.layout.file(project.providers.gradleProperty('dependencyLock').map { project.file(it) }))
            releaseTag.set(project.providers.gradleProperty('release'))
            destination.set(project.layout.dir(project.providers.gradleProperty('output').map { project.file(it) }))
        }
    }
}
