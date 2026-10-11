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
            xmlOutput.unset()
            doLast {
                File file = jsonOutput.get().asFile
                Map bom = new JsonSlurper().parse(file) as Map
                // Build time is recorded in provenance; keep dependency metadata reproducible.
                bom.metadata?.remove('timestamp')
                // Checkout remotes may use SSH, HTTPS, or a .git suffix.
                bom.metadata.component.externalReferences =
                        (bom.metadata.component.externalReferences ?: []).findAll { it.type != 'vcs' } +
                        [[type: 'vcs', url: 'https://github.com/qb20nh/cbbg']]
                bom.components?.findAll { it.group == 'com.google.code.gson' && it.name == 'gson' }.each {
                    it.properties = (it.properties ?: []) + [[name: 'cbbg:distribution',
                            value: 'Only the streaming API is embedded; shrunk and relocated with ProGuard. ' +
                                    'Version and hashes identify the upstream input, not the transformed classes.']]
                }
                if (project.extensions.extraProperties.has('libraryArchive')) {
                    File library = project.libraryArchive
                    if (!library.isFile()) throw new org.gradle.api.GradleException('Missing nested library for release SBOM')
                    String hash = java.security.MessageDigest.getInstance('SHA-256').digest(library.bytes).encodeHex().toString()
                    String reference = 'cbbg-lib:' + hash
                    List components = bom.components ?: []
                    components.add([type: 'library', 'bom-ref': reference, group: 'com.qb20nh', name: 'cbbg-lib',
                                    version: project.libraryPackageVersion,
                                    hashes: [[alg: 'SHA-256', content: hash]]])
                    bom.components = components
                    List dependencies = bom.dependencies ?: []
                    Map root = dependencies.find { it.ref == bom.metadata.component['bom-ref'] }
                    if (root == null) {
                        root = [ref: bom.metadata.component['bom-ref'], dependsOn: []]
                        dependencies.add(root)
                    }
                    root.dependsOn = (root.dependsOn ?: []) + reference
                    bom.dependencies = dependencies
                }
                bom.components = (bom.components ?: []).sort { JsonOutput.toJson(CandidateFiles.sorted(it)) }
                bom.dependencies = (bom.dependencies ?: []).each {
                    if (it.dependsOn != null) it.dependsOn = it.dependsOn.sort()
                }.sort { it.ref }
                ReproducibleText.writeJson(file, bom)
            }
        }
        project.extensions.extraProperties.set('releaseSbomFile', sbom.flatMap { it.jsonOutput })
        project.afterEvaluate {
            if (project.extensions.extraProperties.has('libraryArchive')) {
                sbom.configure {
                    inputs.file(project.libraryArchive).withPropertyName('nestedLibrary')
                    inputs.property('nestedLibraryVersion', project.libraryPackageVersion)
                }
            }
            def core = project.rootProject.findProject(':core')
            def original = core?.configurations?.findByName('privateGsonInput')
            if (original != null) {
                original.dependencies.each { gsonInput.dependencies.add(it.copy()) }
            }
            sbom.configure {
                componentGroup = project.group.toString()
                componentVersion = project.version.toString()
                def archiveName = project.extensions.findByName('base')?.archivesName?.get() ?: project.name
                componentName = archiveName
                includeConfigs.set(['releasePrivateGsonInput', 'include', 'utilitiesRaw'].findAll {
                    project.configurations.findByName(it) != null
                })
                jsonOutput = project.layout.buildDirectory.file("libs/${archiveName}-${project.version}.cdx.json")
            }
        }
    }
}
