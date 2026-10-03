package cbbg.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException

import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*

class FabricCompatibilitySearchTest {
    @TempDir File directory

    @Test void findsThePassingBoundary() {
        List visited = []
        String result = FabricCompatibilitySearch.minimum(
                ['0.19.2', '0.19.3', '0.19.4', '0.19.5'], '0.19.4') { version ->
            visited.add(version)
            version != '0.19.2'
        }
        assertEquals('0.19.3', result)
        assertEquals(['0.19.4', '0.19.3', '0.19.2'], visited)
    }

    @Test void returnsCurrentWhenAllEarlierVersionsFail() {
        List visited = []
        assertEquals('0.19.4', FabricCompatibilitySearch.minimum(
                ['0.18.0', '0.19.3', '0.19.4', '0.19.5'], '0.19.4') { version ->
            visited.add(version)
            version in ['0.19.4', '0.19.5']
        })
        assertEquals(['0.19.4', '0.19.3', '0.19.5'], visited)
    }

    @Test void searchesPublishedVersionsInNumericOrder() {
        List visited = []
        List versions = ['1.10.0+26.3', '0.19.9+26.3', '2.0.0+26.3', '1.2.0+26.3', '0.19.10+26.3']
        assertEquals('1.2.0+26.3', FabricCompatibilitySearch.minimum(versions, '2.0.0+26.3') { version ->
            visited.add(version)
            FabricCompatibilitySearch.numbers(version)[0] >= 1
        })
        assertEquals(['2.0.0+26.3', '1.10.0+26.3', '1.2.0+26.3', '0.19.9+26.3', '0.19.10+26.3'], visited)
    }

    @Test void doesNotFindAMinimumWhenCurrentFails() {
        assertThrows(GradleException) {
            FabricCompatibilitySearch.minimum(['0.19.3', '0.19.4'], '0.19.4') { false }
        }
    }

    @Test void reusesResultsOnlyWhileInputsAndEvidenceMatch() {
        File evidence = new File(directory, 'receipt.json')
        evidence.text = 'passed'
        int runs = 0
        Closure test = { runs++; [status: 'passed', files: [evidence]] }
        File cache = new File(directory, 'cache')
        assertFalse(FabricCompatibilitySearch.cached(cache, [jar: 'a'], test).cached)
        assertTrue(FabricCompatibilitySearch.cached(cache, [jar: 'a'], test).cached)
        evidence.text = 'changed'
        assertFalse(FabricCompatibilitySearch.cached(cache, [jar: 'a'], test).cached)
        assertFalse(FabricCompatibilitySearch.cached(cache, [jar: 'b'], test).cached)
        assertEquals(3, runs)
    }

    @Test void sharedArtifactRuntimeTargetsKeepSeparateSearchResults() {
        File evidence = new File(directory, 'receipt.json')
        evidence.text = 'passed'
        File cache = new File(directory, 'cache')
        int runs = 0
        Closure test = { runs++; [status: 'passed', files: [evidence]] }
        Map owner = [target: '26.1-fabric', artifactOwner: '26.1-fabric', jar: 'same']
        Map patch = [target: '26.1.2-fabric', artifactOwner: '26.1-fabric', jar: 'same']
        assertFalse(FabricCompatibilitySearch.cached(cache, owner, test).cached)
        assertFalse(FabricCompatibilitySearch.cached(cache, patch, test).cached)
        assertTrue(FabricCompatibilitySearch.cached(cache, patch, test).cached)
        assertEquals(2, runs)
    }

    @Test void optionalProfilesAndDependencyHashesKeepSeparateSearchResults() {
        File evidence = new File(directory, 'receipt.json')
        evidence.text = 'passed'
        File cache = new File(directory, 'cache')
        int runs = 0
        Closure test = { runs++; [status: 'passed', files: [evidence]] }
        Map base = [target: '26.1-fabric', jar: 'same', profile: 'none', optionalDependencies: [:]]
        Map iris = base + [profile: 'iris', optionalDependencies: [iris: [sha512: 'a']]]
        assertFalse(FabricCompatibilitySearch.cached(cache, base, test).cached)
        assertFalse(FabricCompatibilitySearch.cached(cache, iris, test).cached)
        assertTrue(FabricCompatibilitySearch.cached(cache, iris, test).cached)
        assertFalse(FabricCompatibilitySearch.cached(cache,
                iris + [optionalDependencies: [iris: [sha512: 'b']]], test).cached)
        assertEquals(3, runs)
    }

    @Test void sulkanProfilesResolveTheirRequiredOptionalDependencies() {
        File repository = new File(System.getProperty('cbbg.repository'))
        String script = new File(repository, 'build-config/fabric-compatibility.gradle').text
        int start = script.indexOf('Map<String, String> modNames =')
        String resolution = script.substring(start, script.indexOf('def sha512 =', start))
        Map catalog = new JsonSlurper().parse(new File(repository, 'targets.json')) as Map
        Map target = catalog.targets.find { it.id == '26.3-fabric' }
        List<String> profiles = target.compatibilityProfiles.keySet().findAll {
            it.tokenize('+').contains('sulkan')
        } as List
        assertTrue(profiles.contains('sulkan'))
        assertTrue(profiles.any { it.contains('+') })
        for (String profile : profiles) {
            Map resolved = new GroovyShell(new Binding([profile: profile])).evaluate(
                    'import org.gradle.api.GradleException\n' + resolution +
                            '\n[selected: selected, names: modNames]') as Map
            Set expected = profile.tokenize('+') as Set
            expected.add('sodium')
            if (expected.contains('renderscale')) expected.add('clothconfig')
            assertEquals(expected, resolved.selected)
            assertEquals('sulkan', resolved.names.sulkan)
            resolved.selected.each { assertNotNull(target.dependencies[resolved.names[it]]) }
        }
    }

    @Test void retriesBlockedRuns() {
        int runs = 0
        Closure test = { runs++; [status: 'blocked'] }
        File cache = new File(directory, 'cache')
        FabricCompatibilitySearch.cached(cache, [jar: 'a'], test)
        FabricCompatibilitySearch.cached(cache, [jar: 'a'], test)
        assertEquals(2, runs)
    }

    @Test void returnsOldestWhenEveryVersionPasses() {
        List visited = []
        assertEquals('0.18.1', FabricCompatibilitySearch.minimum(
                ['0.18.1', '0.18.2', '0.19.3', '0.19.4'], '0.19.4') {
            visited.add(it)
            true
        })
        assertTrue(visited.contains('0.18.2'))
    }

    @Test void usesLogarithmicallyManyTests() {
        List versions = (0..<128).collect { '1.0.' + it }
        List visited = []
        assertEquals('1.0.37', FabricCompatibilitySearch.minimum(versions, '1.0.127') {
            visited.add(it)
            FabricCompatibilitySearch.numbers(it)[2] >= 37
        })
        assertTrue(visited.size() <= 16)
        assertTrue(visited.containsAll(['1.0.36', '1.0.37', '1.0.38']))
    }

    @Test void stopsOnAnInconclusiveTest() {
        assertThrows(GradleException) {
            FabricCompatibilitySearch.minimum(['1.0.0', '1.0.1', '1.0.2'], '1.0.2') {
                if (it == '1.0.0') throw new GradleException('Test runtime unavailable')
                true
            }
        }
    }

    @Test void searchesUpWhenThePreviousMinimumFails() {
        List visited = []
        assertEquals('1.0.4', FabricCompatibilitySearch.minimum(
                (0..<8).collect { '1.0.' + it }, '1.0.2') {
            visited.add(it)
            FabricCompatibilitySearch.numbers(it)[2] >= 4
        })
        assertEquals(['1.0.2', '1.0.3', '1.0.4', '1.0.5'], visited)
    }

    @Test void doublesTheDistanceBeforeSearchingWithinTheRange() {
        List<Integer> visited = []
        assertEquals('1.0.20', FabricCompatibilitySearch.minimum(
                (0..<64).collect { '1.0.' + it }, '1.0.10') {
            int patch = FabricCompatibilitySearch.numbers(it)[2]
            visited.add(patch)
            patch >= 20
        })
        assertEquals([10, 11, 12, 14, 18, 26], visited.take(6))
        assertEquals([22, 20, 19, 21], visited.drop(6))
    }

    @Test void searchesDownFromThePreviousMinimumWithDoublingDistances() {
        List<Integer> visited = []
        assertEquals('1.0.20', FabricCompatibilitySearch.minimum(
                (0..<64).collect { '1.0.' + it }, '1.0.30') {
            int patch = FabricCompatibilitySearch.numbers(it)[2]
            visited.add(patch)
            patch >= 20
        })
        assertEquals([30, 29, 28, 26, 22, 14], visited.take(6))
        assertEquals([18, 20, 19, 21], visited.drop(6))
    }

    @Test void startsWithThePreviousApiVersionForTheNewMinecraftVersion() {
        assertEquals('0.160.7+26.3', FabricCompatibilitySearch.startingVersion(
                ['0.160.6+26.3', '0.160.7+26.3', '0.161.0+26.3'], '0.160.7+26.2'))
        assertEquals('0.160.6+26.3', FabricCompatibilitySearch.startingVersion(
                ['0.160.6+26.3', '0.161.0+26.3'], '0.160.7+26.2'))
        assertEquals('0.161.0+26.3', FabricCompatibilitySearch.startingVersion(
                ['0.161.0+26.3'], '0.160.7+26.2'))
    }

    @Test void retriesAnIncompleteCacheRecord() {
        File evidence = new File(directory, 'receipt')
        evidence.text = 'passed'
        File cache = new File(directory, 'cache')
        cache.mkdirs()
        new File(cache, CandidateFiles.canonicalHash([jar: 'a']) + '.json').text = '{'
        assertFalse(FabricCompatibilitySearch.cached(cache, [jar: 'a']) {
            [status: 'passed', files: [evidence]]
        }.cached)
    }

    @Test void findsTheUpperBoundaryWithDoublingAndBinarySearch() {
        List<Integer> visited = []
        Map result = FabricCompatibilitySearch.maximum((0..<64).collect { '1.0.' + it }, '1.0.10') {
            int patch = FabricCompatibilitySearch.numbers(it)[2]
            visited.add(patch)
            patch <= 20
        }
        assertEquals([maximum: '1.0.20', firstIncompatible: '1.0.21'], result)
        assertEquals([10, 11, 12, 14, 18, 26], visited.take(6))
        assertTrue(visited.containsAll([19, 20, 21]))
    }

    @Test void recordsNoKnownIncompatibilityWhenLatestPasses() {
        assertEquals([maximum: '2.0.0', firstIncompatible: null],
                FabricCompatibilitySearch.maximum(['1.9.0', '1.10.0', '2.0.0'], '1.9.0') { true })
    }

    @Test void limitsLatestPassingVersionsToTheirCurrentMajor() {
        assertEquals('1.0.0', FabricCompatibilitySearch.upperLimit('0.19.5', null))
        assertEquals('1.0.0', FabricCompatibilitySearch.upperLimit('0.161.0+26.3', null))
        assertEquals('3.0.0', FabricCompatibilitySearch.upperLimit('2.9.7', null))
    }

    @Test void usesTheObservedFailureBeforeTheNextMajor() {
        assertEquals('0.19.6', FabricCompatibilitySearch.upperLimit('0.19.5', '0.19.6'))
    }

    @Test void searchesDownWhenThePreviousMaximumFails() {
        assertEquals([maximum: '1.0.4', firstIncompatible: '1.0.5'],
                FabricCompatibilitySearch.maximum((0..<8).collect { '1.0.' + it }, '1.0.7') {
                    FabricCompatibilitySearch.numbers(it)[2] <= 4
                })
    }

    @Test void doesNotConvertAnInconclusiveUpperTestIntoALimit() {
        assertThrows(GradleException) {
            FabricCompatibilitySearch.maximum(['1.0.0', '1.0.1'], '1.0.0') {
                it == '1.0.1' ? null : true
            }
        }
    }

    @Test void intersectsSharedRuntimeBoundsWithoutExpandingAnyInterval() {
        def reports = [
                [minimumLoader: '0.19.3', minimumFabricApi: '0.145.1+26.1',
                 loaderUpperExclusive: '0.20.0', fabricApiUpperExclusive: '0.160.0+26.1'],
                [minimumLoader: '0.19.5', minimumFabricApi: '0.145.4+26.1.1',
                 loaderUpperExclusive: '0.19.8', fabricApiUpperExclusive: '0.161.0+26.1.1'],
                [minimumLoader: '0.19.4', minimumFabricApi: '0.155.3+26.1.2',
                 loaderUpperExclusive: '0.19.9', fabricApiUpperExclusive: '0.160.4+26.1.2']]
        assertEquals([minimumLoader: '0.19.5', minimumFabricApi: '0.155.3+26.1.2',
                      loaderUpperExclusive: '0.19.8', fabricApiUpperExclusive: '0.160.0+26.1'],
                FabricCompatibilitySearch.intersect(reports))
        assertEquals(['0.155.3+26.1.2', '0.156.0+26.1', '0.156.0+26.1.1'],
                FabricCompatibilitySearch.within(
                        ['0.145.1+26.1', '0.156.0+26.1.1', '0.155.3+26.1.2',
                         '0.160.0+26.1', '0.156.0+26.1'],
                        '0.155.3+26.1.2', '0.160.0+26.1'))
    }

    @Test void rejectsEmptySharedRuntimeIntervals() {
        assertThrows(GradleException) {
            FabricCompatibilitySearch.intersect([
                    [minimumLoader: '0.19.5', minimumFabricApi: '0.155.0+26.1',
                     loaderUpperExclusive: '0.19.5', fabricApiUpperExclusive: '0.160.0+26.1']])
        }
        assertThrows(GradleException) {
            FabricCompatibilitySearch.intersect([
                    [minimumLoader: '0.19.5', minimumFabricApi: '0.155.0+26.1',
                     loaderUpperExclusive: '0.20.0', fabricApiUpperExclusive: '0.150.0+26.1']])
        }
    }

    @Test void checksHistoricalDriverAtUpdateButAllowsItsMetadataRebuildForStrict() {
        File script = new File(directory, 'compatibility.gradle')
        File driver = new File(directory, 'processed-driver.jar')
        File config = new File(directory, 'initial-cbbg.json')
        File gametest = new File(directory, 'fabric-gametest-api.jar')
        [script: script, driver: driver, config: config, gametest: gametest].each { name, file ->
            file.text = name
        }
        Map inputs = [files: [(script.canonicalPath): CandidateFiles.sha256(script)],
                      driver: CandidateFiles.sha256(driver),
                      initialConfig: CandidateFiles.sha256(config),
                      gametestApiPin: '0.145.1+26.1',
                      gametestApiSha256: CandidateFiles.sha256(gametest)]
        FabricCompatibilitySearch.requireCurrentInputs(inputs, driver, config, gametest,
                '0.145.1+26.1')
        driver.text = 'changed driver'
        assertThrows(GradleException) {
            FabricCompatibilitySearch.requireCurrentInputs(inputs, driver, config, gametest,
                    '0.145.1+26.1')
        }
        FabricCompatibilitySearch.requireCurrentInputs(inputs, driver, config, gametest,
                '0.145.1+26.1', false)
        script.text = 'changed script'
        assertThrows(GradleException) {
            FabricCompatibilitySearch.requireCurrentInputs(inputs, driver, config, gametest,
                    '0.145.1+26.1', false)
        }
        script.text = 'script'
        config.text = 'changed config'
        assertThrows(GradleException) {
            FabricCompatibilitySearch.requireCurrentInputs(inputs, driver, config, gametest,
                    '0.145.1+26.1', false)
        }
        config.text = 'config'
        assertThrows(GradleException) {
            FabricCompatibilitySearch.requireCurrentInputs(inputs, driver, config, gametest,
                    '0.145.4+26.1.1', false)
        }
    }
    @Test void strictSharedVerificationRequiresTheCurrentIdentityFileSet() {
        File repository = new File(System.getProperty('cbbg.repository'))
        String script = new File(repository, 'build-config/fabric-compatibility.gradle').text
        int start = script.indexOf('Map searches = completedFamilySearches(false)')
        String validation = script.substring(start, script.indexOf('File searchedJar =', start))
        Map files = ['test.java': 'source-hash', 'test.json': 'resource-hash']
        Map bounds = [minimumLoader: '0.18.4', minimumFabricApi: '0.143.12+26.1',
                      loaderUpperExclusive: '1.0.0', fabricApiUpperExclusive: '1.0.0']
        Map searches = [reports: [[inputs: [files: files]]], bounds: bounds,
                        latestLoader: '0.19.5', latestFabricApi: '0.155.3+26.1.2']
        Binding binding = new Binding([
                completedFamilySearches: { boolean historical -> searches },
                fixed: [files: new LinkedHashMap(files)], declared: bounds, report: searches])
        GroovyShell shell = new GroovyShell(binding)
        String code = 'import org.gradle.api.GradleException\n' + validation
        shell.evaluate(code)
        for (Map changed : [files + ['new.json': 'new-hash'],
                            ['test.java': 'source-hash'],
                            files + ['test.java': 'changed-hash']]) {
            binding.setVariable('fixed', [files: changed])
            GradleException failure = assertThrows(GradleException) { shell.evaluate(code) }
            assertTrue(failure.message.contains('rerun every Fabric runtime search'))
        }
    }

    @Test void acceptsOnlyDependencyBoundsInTheRebuiltFabricArtifact() {
        File searched = new File(directory, 'searched.jar')
        File rebuilt = new File(directory, 'rebuilt.jar')
        Map bounds = [minimumLoader: '0.18.4', loaderUpperExclusive: '1.0.0',
                      minimumFabricApi: '0.143.12+26.1', fabricApiUpperExclusive: '1.0.0']
        Map oldMetadata = [id: 'cbbg', name: 'CBBG',
                           depends: [fabricloader: '>=0.19.5', 'fabric-api': '>=0.145.1+26.1',
                                     minecraft: '>=26.1 <26.2']]
        Map newMetadata = [id: 'cbbg', name: 'CBBG',
                           depends: [fabricloader: '>=0.18.4 <1.0.0',
                                     'fabric-api': '>=0.143.12+26.1 <1.0.0',
                                     minecraft: '>=26.1 <26.2']]
        Map<String, String> code = ['pkg/Client.class': 'same']
        writeArtifact(searched, oldMetadata, code)
        String searchedSha256 = CandidateFiles.sha256(searched)
        writeArtifact(rebuilt, newMetadata, code)
        FabricCompatibilitySearch.requireSameArtifactExceptBounds(
                searched, searchedSha256, rebuilt, bounds)

        writeArtifact(rebuilt, newMetadata, ['pkg/Client.class': 'changed'])
        assertThrows(GradleException) {
            FabricCompatibilitySearch.requireSameArtifactExceptBounds(
                    searched, searchedSha256, rebuilt, bounds)
        }
        writeArtifact(rebuilt, newMetadata, code + ['extra.txt': 'new'])
        assertThrows(GradleException) {
            FabricCompatibilitySearch.requireSameArtifactExceptBounds(
                    searched, searchedSha256, rebuilt, bounds)
        }
        writeArtifact(rebuilt, newMetadata, [:])
        assertThrows(GradleException) {
            FabricCompatibilitySearch.requireSameArtifactExceptBounds(
                    searched, searchedSha256, rebuilt, bounds)
        }
        writeArtifact(rebuilt, newMetadata + [name: 'Different'], code)
        assertThrows(GradleException) {
            FabricCompatibilitySearch.requireSameArtifactExceptBounds(
                    searched, searchedSha256, rebuilt, bounds)
        }
        writeArtifact(rebuilt, newMetadata + [depends: newMetadata.depends +
                [fabricloader: '>=0.19.5 <1.0.0']], code)
        assertThrows(GradleException) {
            FabricCompatibilitySearch.requireSameArtifactExceptBounds(
                    searched, searchedSha256, rebuilt, bounds)
        }
        writeArtifact(rebuilt, newMetadata, code)
        assertThrows(GradleException) {
            FabricCompatibilitySearch.requireSameArtifactExceptBounds(
                    searched, '0' * 64, rebuilt, bounds)
        }
        assertThrows(GradleException) {
            FabricCompatibilitySearch.requireSameArtifactExceptBounds(
                    new File(directory, 'missing.jar'), searchedSha256, rebuilt, bounds)
        }
    }

    private static void writeArtifact(File jar, Map metadata, Map<String, String> contents) {
        new ZipOutputStream(jar.newOutputStream()).withCloseable { output ->
            (['fabric.mod.json': JsonOutput.toJson(metadata)] + contents).each { name, value ->
                output.putNextEntry(new ZipEntry(name))
                output.write(value.getBytes(StandardCharsets.UTF_8))
                output.closeEntry()
            }
        }
    }

}
