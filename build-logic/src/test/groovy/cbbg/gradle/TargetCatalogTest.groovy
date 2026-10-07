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
        List<String> implemented = ['1.21.1-fabric', '1.21.11-fabric', '26.1-fabric', '26.1.1-fabric', '26.1.2-fabric', '26.2-fabric', '26.3-fabric']
        assertEquals(34, catalog.select().size())
        assertEquals(implemented, catalog.defaults()*.id)
        assertEquals(implemented, catalog.select().findAll { it.implemented }*.id)
        assertEquals(implemented, catalog.defaults(true)*.id)
        assertEquals(['1.21.1-fabric', '1.21.11-fabric', '26.1-fabric', '26.2-fabric', '26.3-fabric'], catalog.matrix(implemented.join(','), true)*.id)
        assertEquals(['26.3-fabric'], catalog.matrix('26.3-fabric', true)*.id)
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
        assertEquals(['26.1-fabric'], catalog.artifacts('26.1.2-fabric,26.1.1-fabric,26.1-fabric')*.id)
        assertEquals(['26.1-fabric', '26.1.1-fabric', '26.1.2-fabric'],
                catalog.matrix('26.1.2-fabric,26.1.1-fabric,26.1-fabric')[0].runtimeTargets)
        assertEquals(['26.1-fabric'], catalog.artifacts('26.1.2-quilt')*.id)
        assertEquals(['26.1-fabric', '26.1.1-fabric', '26.1.2-fabric'] as Set,
                catalog.releaseTargets(['26.1-fabric', '26.1.1-fabric', '26.1.2-fabric']).keySet())
        assertThrows(IllegalArgumentException) {
            catalog.releaseTargets(['26.1.1-fabric', '26.1.2-fabric'])
        }
    }

    @Test
    void olderFabricTargetDeclaresItsSeparateClientTestModule() {
        Map entry = target(copyData(), '1.21.1-fabric')
        assertEquals(21, entry.java)
        assertEquals('gl3', entry.renderer)
        assertTrue(entry.implemented)
        assertEquals('0.16.0', entry.dependencies.minimumLoader)
        assertEquals('0.101.2+1.21.1', entry.dependencies.minimumFabricApi)
        assertEquals('2.0.0+99ff640a04', entry.dependencies.clientGametest)
        assertEquals(['opengl'], entry.backends)
        Map stableIris = TargetCatalog.effectiveDependencies(entry, 'iris')
        assertEquals('zsoi0dso', stableIris.iris)
        assertEquals('u1OEbNKx', stableIris.sodium)
        assertEquals('SMxNOGZ6', TargetCatalog.effectiveDependencies(entry, 'sodium').sodium)
        assertTrue(entry.compatibilityProfiles.containsKey(
                'modmenu+sodium+iris+renderscale+chatpatches+immediatelyfast'))
    }

    @Test
    void selectionsRejectUnknownDuplicatesAndPendingTargets() {
        TargetCatalog catalog = TargetCatalog.read(CATALOG)
        for (String selection : ['', '26.3-fabric,', '26.3-fabric,26.3-fabric']) {
            assertThrows(IllegalArgumentException) { catalog.select(selection) }
        }
        assertTrue(assertThrows(IllegalArgumentException) { catalog.select('unknown') }
                .message.contains('Unknown targets'))
        assertTrue(assertThrows(IllegalArgumentException) { catalog.matrix('26.3-quilt', true) }
                .message.contains('not implemented'))
        Map pending = copyData()
        target(pending, '26.3-fabric').implemented = false
        assertTrue(assertThrows(IllegalArgumentException) { new TargetCatalog(pending).defaults(true) }
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
        data = copyData()
        target(data, '26.2-fabric').versionIncludesLoader = 'true'
        rejects(data, 'version format')
    }

    @Test void selectionErrorsListValidTargetsAndRequiredAliases() {
        TargetCatalog catalog = TargetCatalog.read(CATALOG)
        String unknown = assertThrows(IllegalArgumentException) { catalog.select('26.3') }.message
        assertTrue(unknown.contains('26.3-fabric'), unknown)
        assertTrue(unknown.contains('targets.json'), unknown)
        String pending = assertThrows(IllegalArgumentException) { catalog.select('26.3-quilt', true) }.message
        assertTrue(pending.contains('26.3-fabric'), pending)
        assertTrue(pending.contains('Implemented targets'), pending)
        String missing = assertThrows(IllegalArgumentException) {
            catalog.releaseTargets(['26.1-fabric'])
        }.message
        assertTrue(missing.contains('-Ptargets='), missing)
        assertTrue(missing.contains('26.1.1-fabric'), missing)
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
        data = copyData()
        target(data, '26.1-fabric').compatibleMinecraft = ['26.1.1']
        rejects(data, 'shared artifact')
        data = copyData()
        target(data, '26.1.1-fabric').java = 21
        rejects(data, 'shared artifact')
        data = copyData()
        target(data, '26.1.2-fabric').artifactOf = '26.1.1-fabric'
        rejects(data, 'shared artifact')
        data = copyData()
        target(data, '26.1-fabric').compatibleMinecraft = ['26.2']
        rejects(data, 'compatible Minecraft')
        data = copyData()
        target(data, '26.1-fabric').minecraftDependency = '>=26.1 <26.3'
        rejects(data, 'compatible Minecraft')
    }

    @Test
    void minecraftPatchRangeDoesNotDeclareSharedArtifacts() {
        Map data = copyData()
        Map fabric = target(data, '26.2-fabric')
        fabric.minecraftDependency = '~26.2'
        assertEquals(['26.2-fabric'], new TargetCatalog(data).artifacts('26.2-fabric')*.id)
        for (String dependency : ['~26.3', '*', '>=26.2']) {
            fabric.minecraftDependency = dependency
            rejects(data, 'Minecraft dependency')
        }
        data = copyData()
        target(data, '26.2-quilt').minecraftDependency = '~26.2'
        rejects(data, 'Minecraft dependency')
    }

    @Test
    void compatibilityDependencyOverridesAreValidatedAndSelected() {
        Map data = copyData()
        Map fabric = target(data, '26.2-fabric')
        assertEquals('xJZxADzI', TargetCatalog.effectiveDependencies(fabric, 'iris').sodium)
        assertEquals('2Yom1N68', TargetCatalog.effectiveDependencies(fabric, 'sulkan').sodium)
        assertEquals('2Yom1N68', TargetCatalog.effectiveDependencies(fabric,
                'modmenu+sodium+sulkan+renderscale+chatpatches+immediatelyfast').sodium)
        assertEquals('xJZxADzI', fabric.dependencies.sodium)
        for (Object overrides : [[:], [], [unknown: [sodium: 'pin']], [sulkan: [:]],
                [sulkan: []],
                [sulkan: [loader: 'pin']], [sulkan: [fabricApi: 'pin']],
                [sulkan: [minimumLoader: 'pin']], [sulkan: [fabricApiUpperExclusive: 'pin']],
                [sulkan: [missing: 'pin']], [sulkan: [sodium: '']],
                [sulkan: [sodium: '  ']], [sulkan: [sodium: 3]]]) {
            data = copyData()
            target(data, '26.2-fabric').compatibilityDependencyOverrides = overrides
            rejects(data, 'compatibility dependency overrides')
        }
    }

    @Test
    void glChangerProfilesHaveDependenciesForBothMinecraft121Targets() {
        Map data = copyData()
        for (String id : ['1.21.1-fabric', '1.21.11-fabric']) {
            Map fabric = target(data, id)
            String forceProfile = id == '1.21.1-fabric' ? 'forcegl2' : 'forcegl2+yacl'
            assertEquals(['opengl'], fabric.compatibilityProfiles[forceProfile])
            assertEquals(['opengl'], fabric.compatibilityProfiles.threatengl)
            assertEquals(id == '1.21.1-fabric' ? 'x831iJzz' : 'PK6vSUU6',
                    TargetCatalog.effectiveDependencies(fabric, forceProfile).forceGl2)
            assertEquals('nK30v84J', TargetCatalog.effectiveDependencies(fabric, 'threatengl').threatenGl)
        }
        new TargetCatalog(data)
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
