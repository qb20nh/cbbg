package cbbg.gradle

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class FabricMetadataTest {
    @Test void aliasesUseTheOwnersMinecraftRequirementWithOrWithoutAnExplicitRange() {
        File repository = new File(System.getProperty('cbbg.repository'))
        String script = new File(repository, 'build-config/fabric-modern/build.gradle').text
        int start = script.indexOf('def properties = [version: project.version')
        String properties = script.substring(start, script.indexOf('inputs.properties(properties)', start))
        Map owner = [minecraft: '26.1', dependencies: [loader: '0.18.4']]
        Binding binding = new Binding([project: [version: '1.4.1'], artifactOwner: owner, remappedGame: false,
                                       target: [minecraft: '26.1.2', java: 25, renderer: 'blaze-texture-format']])
        GroovyShell shell = new GroovyShell(binding)
        String code = properties + '\nproperties'
        assertEquals('26.1', shell.evaluate(code).minecraft_version)
        owner.minecraftDependency = '>=26.1 <26.2'
        assertEquals('>=26.1 <26.2', shell.evaluate(code).minecraft_version)
    }
}
