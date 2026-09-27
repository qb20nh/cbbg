package cbbg.gradle

import org.gradle.api.GradleException
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
}
