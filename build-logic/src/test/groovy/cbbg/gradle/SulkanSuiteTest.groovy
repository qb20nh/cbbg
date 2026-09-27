package cbbg.gradle

import groovy.json.JsonSlurper
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class SulkanSuiteTest {
    @Test void sulkanUsesTheOrdinaryDriversOnly() {
        File repository = new File(System.getProperty('cbbg.repository'))
        String profile = new File(repository, 'build-config/fabric-modern/build.gradle').text
        def project = ProjectBuilder.builder().build()
        project.pluginManager.apply('java')
        project.sourceSets.create('gametest')
        project.sourceSets.create('processedGametest')
        String processed = profile.substring(profile.indexOf('    [MaximumNoiseCache:'),
                profile.indexOf("    tasks.register('prepareProcessedRuntime')"))
        String development = profile.substring(profile.indexOf('// Startup-state and slow maximum-size'),
                profile.indexOf("tasks.named('checkPackages', cbbg.gradle.CheckPackages)"))
        def binding = new Binding([tasks: project.tasks, layout: project.layout,
                                   sourceSets: project.sourceSets, target: [renderer: 'renderpearl']])
        new GroovyShell(binding).evaluate('import org.gradle.api.tasks.bundling.Jar\n'
                + 'import groovy.json.JsonSlurper\n' + processed + development)
        assertFalse(project.tasks.names.any { it.toLowerCase().contains('sulkan') })
        assertTrue(project.tasks.names.containsAll(['irisRestartDriverJar', 'processedIrisRestartDriverJar',
                                                   'earlyStartupDriverJar', 'processedEarlyStartupDriverJar']))
        for (String sourceSet : ['gametest', 'processedGametest']) {
            Map metadata = new JsonSlurper().parse(new File(repository,
                    "renderers/renderpearl/src/${sourceSet}/resources/fabric.mod.json")) as Map
            List tests = metadata.entrypoints['fabric-client-gametest'] as List
            String prefix = 'com.qb20nh.cbbg.gametest.' + (sourceSet == 'processedGametest' ? 'Release' : '')
            assertEquals(prefix + 'SulkanSetupGameTest', tests.first())
            assertEquals(1, tests.count(prefix + 'SulkanGameTest'))
        }
    }
}
