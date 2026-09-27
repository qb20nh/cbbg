package cbbg.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.gradle.api.Plugin
import org.gradle.api.Project

class ReleaseSbomPlugin implements Plugin<Project> {
    void apply(Project project) {
        def gsonInput = project.configurations.create('releasePrivateGsonInput') {
            canBeConsumed = false
            transitive = false
        }
        def sbom = project.tasks.register('releaseSbom', CyclonedxDirectTask) {
            group = 'build'
            description = 'Describe the third-party dependencies embedded in the release jar.'
            includeConfigs = ['releasePrivateGsonInput', 'include']
            includeBuildEnvironment = false
            includeBuildSystem = false
            includeBomSerialNumber = false
            includeLicenseText = false
            aggregateConfigurationName = 'releaseSbomUnusedAggregate'
            componentName = 'cbbg'
            xmlOutput.unset()
            doLast {
                File file = jsonOutput.get().asFile
                Map bom = new JsonSlurper().parse(file) as Map
                // Build time is recorded in provenance; keep dependency metadata reproducible.
                bom.metadata?.remove('timestamp')
                bom.components?.findAll { it.group == 'com.google.code.gson' && it.name == 'gson' }.each {
                    it.properties = (it.properties ?: []) + [[name: 'cbbg:distribution',
                            value: 'Only the streaming API is embedded; shrunk and relocated with ProGuard. ' +
                                    'Version and hashes identify the upstream input, not the transformed classes.']]
                }
                file.text = JsonOutput.prettyPrint(JsonOutput.toJson(bom)) + '\n'
            }
        }
        project.extensions.extraProperties.set('releaseSbomFile', sbom.flatMap { it.jsonOutput })
        project.afterEvaluate {
            def core = project.rootProject.findProject(':core')
            def original = core?.configurations?.findByName('privateGsonInput')
            if (original != null) {
                original.dependencies.each { gsonInput.dependencies.add(it.copy()) }
            }
            sbom.configure {
                componentGroup = project.group.toString()
                componentVersion = project.version.toString()
                def archiveName = project.extensions.findByName('base')?.archivesName?.get() ?: project.name
                jsonOutput = project.layout.buildDirectory.file("libs/${archiveName}-${project.version}.cdx.json")
            }
        }
    }
}
