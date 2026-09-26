package cbbg.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class TargetCatalogTest {
    private static final File CATALOG = new File('../targets.json')

    private static Map copyData() {
        (Map) new JsonSlurper().parseText(JsonOutput.toJson(TargetCatalog.read(CATALOG).data))
    }

    private static Map target(Map data, String id) {
        (Map) data.targets.find { it.id == id }
    }

    private static void rejects(Map data, String message) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException) {
            new TargetCatalog(data)
        }
        assertTrue(error.message.toLowerCase().contains(message.toLowerCase()), error.message)
    }

    @Test
    void actualCatalogSelectsRuntimeTargetsAndArtifactOwners() {
        TargetCatalog catalog = TargetCatalog.read(CATALOG)
        assertEquals(31, catalog.select().size())
        assertEquals(['26.3-fabric'], catalog.defaults()*.id)
        assertEquals(['1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge'],
                catalog.selectProfile('neoforge-modern')*.id)
        assertEquals(['26.3-fabric', '26.3-quilt'], catalog.select('26.3-quilt,26.3-fabric')*.id)
        assertEquals(['26.3-fabric'], catalog.artifacts('26.3-quilt,26.3-fabric')*.id)
        assertEquals([[
                id: '26.3-fabric', minecraft: '26.3', loader: 'fabric', java: 25,
                renderer: 'renderpearl', buildProfile: 'fabric-modern',
                runtimeTargets: ['26.3-fabric', '26.3-quilt']
        ]], catalog.matrix('26.3-quilt,26.3-fabric'))
        assertEquals(['26.3-quilt'], catalog.matrix('26.3-quilt')[0].runtimeTargets)
    }

    @Test
    void selectionsRejectUnknownDuplicatesAndPendingTargets() {
        TargetCatalog catalog = TargetCatalog.read(CATALOG)
        for (String selection : ['', '26.3-fabric,', '26.3-fabric,26.3-fabric']) {
            assertThrows(IllegalArgumentException) { catalog.select(selection) }
        }
        assertTrue(assertThrows(IllegalArgumentException) { catalog.select('unknown') }
                .message.contains('Unknown targets'))
        assertTrue(assertThrows(IllegalArgumentException) { catalog.matrix('26.3-fabric', true) }
                .message.contains('not implemented'))
        assertTrue(assertThrows(IllegalArgumentException) { catalog.defaults(true) }
                .message.contains('not implemented'))
        assertThrows(IllegalArgumentException) { catalog.selectProfile('missing') }
    }

    @Test
    void schemaAndTargetFieldsAreValidated() {
        Map data = copyData()
        data.schema = 2
        rejects(data, 'schema')
        data = copyData()
        data.targets << data.targets[0]
        rejects(data, 'Duplicate target')
        data = copyData()
        target(data, '26.3-fabric').backends << 'metal'
        rejects(data, 'backends')
        data = copyData()
        target(data, '1.20.1-fabric').projectionApi = null
        rejects(data, 'projection API')
        data = copyData()
        target(data, '1.19.2-fabric').sourceGroups = []
        rejects(data, 'source groups')
    }

    @Test
    void compatibilityAndSharedArtifactRulesAreValidated() {
        for (Object profiles : [[:], [none: ['opengl']],
                [none: ['opengl', 'vulkan'], iris: ['metal']],
                [none: ['opengl', 'vulkan'], iris: ['opengl', 'opengl']]]) {
            Map data = copyData()
            target(data, '26.3-fabric').compatibilityProfiles = profiles
            rejects(data, (profiles == [:] || profiles == [none: ['opengl']]) ?
                    'base fixture' : 'compatibility')
        }
        Map data = copyData()
        target(data, '1.20.1-quilt').java = 8
        rejects(data, 'shared artifact')
        data = copyData()
        target(data, '1.20.1-quilt').java = 21
        assertEquals(['1.20.1-fabric'], new TargetCatalog(data).artifacts('1.20.1-quilt')*.id)
        data = copyData()
        target(data, '1.20.1-quilt').artifactOf = '26.3-fabric'
        rejects(data, 'shared artifact')
    }

    @Test
    void ciTargetsAndPathsAreValidated() {
        for (Object defaults : [[], ['unknown'], ['26.3-fabric', '26.3-fabric'], [false], '26.3-fabric']) {
            Map data = copyData()
            data.ciTargets = defaults
            rejects(data, 'CI targets')
        }
        Map data = copyData()
        data.remove('ciTargets')
        assertTrue(assertThrows(IllegalArgumentException) { new TargetCatalog(data).defaults() }
                .message.contains('No CI targets'))
        for (String profile : ['../outside', '/outside', 'a/b', 'C:\\outside']) {
            data = copyData()
            target(data, '26.3-fabric').buildProfile = profile
            rejects(data, 'build profile')
        }
        for (String source : ['../../outside', '/outside', 'C:\\outside', 'a\\..\\outside']) {
            data = copyData()
            target(data, '1.19.2-fabric').sourceGroups = [source]
            rejects(data, 'source groups')
        }
    }
}
