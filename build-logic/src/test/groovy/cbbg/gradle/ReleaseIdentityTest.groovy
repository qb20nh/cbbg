package cbbg.gradle

import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class ReleaseIdentityTest {
    @Test void productsHaveIndependentVersionsAndNotes() {
        ReleaseIdentity mod = ReleaseIdentity.parse('v1.5.0')
        ReleaseIdentity lib = ReleaseIdentity.parse('lib/v1.0.0')
        assertEquals('cbbg', mod.product)
        assertEquals('1.5.0', mod.version)
        assertEquals('cbbg 1.5.0', mod.releaseTitle)
        assertEquals('CHANGELOG.md', mod.changelogPath)
        assertEquals('lib', lib.product)
        assertEquals('1.0.0', lib.version)
        assertEquals('CBBG Lib 1.0.0', lib.releaseTitle)
        assertEquals('libraries/CHANGELOG.md', lib.changelogPath)
        assertFalse(lib.targeted)
        assertNull(lib.minecraft)
        assertNull(lib.loader)
        mod.requireProduct('cbbg')
        lib.requireProduct('lib')
        assertTrue(assertThrows(GradleException) { lib.requireProduct('cbbg') }.message.contains("belongs to product 'lib'"))
        assertTrue(assertThrows(GradleException) { mod.requireProduct('lib') }.message.contains("requires 'lib'"))
    }

    @Test void bothProductsKeepTheExistingPrereleaseAndTargetGrammar() {
        ['v', 'lib/v'].each { prefix ->
            ['fabric', 'quilt', 'forge', 'neoforge', 'legacy-fabric'].each { loader ->
                String tag = prefix + '1.4.1+mc1.20.1-' + loader
                ReleaseIdentity identity = ReleaseIdentity.parse(tag)
                assertEquals('1.20.1', identity.minecraft)
                assertEquals(loader, identity.loader)
                assertEquals('1.4.1', identity.version)
                assertTrue(identity.targeted)
                assertFalse(identity.prerelease)
                CandidateFiles.releaseIdentity(tag, 'a' * 40)
                CandidateFiles.releaseTargets(tag, [[minecraft: '1.20.1', loader: loader]])
                assertEquals('1.4.1', CandidateFiles.releaseVersion(tag))
                assertTrue(CandidateFiles.targetedRelease(tag))
            }
            ['rc.1', 'beta.preview.2', 'alpha-test.3'].each { suffix ->
                String tag = prefix + '1.0.0-' + suffix + '+mc26.3-snapshot-1-fabric'
                assertTrue(ReleaseIdentity.parse(tag).prerelease)
                assertTrue(CandidateFiles.prerelease(tag))
            }
        }
    }

    @Test void hyphenatedLoaderSuffixesRemainDistinctFromMinecraftPrereleases() {
        ['v', 'lib/v'].each { prefix ->
            ReleaseIdentity stable = ReleaseIdentity.parse(prefix + '1.0.0+mc1.20.1-legacy-fabric')
            assertEquals('1.20.1', stable.minecraft)
            assertEquals('legacy-fabric', stable.loader)
            ReleaseIdentity preview = ReleaseIdentity.parse(prefix + '1.0.0+mc1.20.1-rc.1-legacy-fabric')
            assertEquals('1.20.1-rc.1', preview.minecraft)
            assertEquals('legacy-fabric', preview.loader)
            assertEquals('fabric', ReleaseIdentity.parse(prefix + '1.0.0+mc1.20.1-rc.1-fabric').loader)
        }
        assertThrows(GradleException) { ReleaseIdentity.parse('v1.4.0-beta+mc26.3') }
        assertThrows(GradleException) { ReleaseIdentity.parse('lib/v1.0.0-beta+mc26.3') }
    }

    @Test void invalidProductsVersionsAndTargetsAreRejected() {
        [null, '', 'lib/1.0.0', 'cbbg/v1.0.0', 'mod/v1.0.0', 'Lib/v1.0.0', 'lib/lib/v1.0.0',
         'v01.4.0', 'lib/v01.0.0', 'v1.4.0-rc', 'lib/v1.0.0-rc.0', 'lib/v1.0.0-1.1',
         'lib/v1.0.0+mc26.3', 'lib/v1.0.0+mc26.3_fabric', 'lib/v1.0.0+mc26..3-fabric',
         'lib/v1.0.0+mc26.3-fabric+extra', 'lib/v1.0.0+mc26.3-fabric\n'].each { tag ->
            assertThrows(GradleException) { ReleaseIdentity.parse(tag) }
        }
        assertThrows(GradleException) { CandidateFiles.releaseIdentity('lib/v1.0.0', 'invalid') }
    }

    @Test void targetAliasesResolveToOneArtifactOwnerForEachProduct() {
        List<Map> shared = [[id: '26.1-fabric', minecraft: '26.1', loader: 'fabric'],
                            [id: '26.1.1-fabric', minecraft: '26.1.1', loader: 'fabric', artifactOf: '26.1-fabric'],
                            [id: '26.1.2-fabric', minecraft: '26.1.2', loader: 'fabric', artifactOf: '26.1-fabric']]
        ['v', 'lib/v'].each { prefix ->
            ReleaseIdentity.parse(prefix + '1.0.0+mc26.1-fabric').requireTargets(shared)
            assertThrows(GradleException) {
                ReleaseIdentity.parse(prefix + '1.0.0+mc26.1.2-fabric').requireTargets(shared)
            }
            assertThrows(GradleException) {
                ReleaseIdentity.parse(prefix + '1.0.0+mc26.1-fabric').requireTargets(shared.tail())
            }
            assertThrows(GradleException) {
                ReleaseIdentity.parse(prefix + '1.0.0+mc26.1-fabric').requireTargets(
                        shared + [[id: '26.1-quilt', minecraft: '26.1', loader: 'quilt']])
            }
            ReleaseIdentity.parse(prefix + '1.0.0').requireTargets(shared)
        }
    }
}
