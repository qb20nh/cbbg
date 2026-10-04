package cbbg.gradle

import groovy.json.JsonSlurper
import org.gradle.api.GradleException

import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Searches released versions; results describe tested boundaries, not every intervening release. */
class FabricCompatibilitySearch {
    static List<Integer> numbers(String version) {
        String base = version.split('\\+', 2)[0]
        if (!(base ==~ /\d+\.\d+\.\d+/)) {
            throw new GradleException('Expected a released version: ' + version)
        }
        base.tokenize('.').collect { Integer.parseInt(it) }
    }

    static List<String> ordered(Collection<String> versions) {
        versions.toSet().toList().sort { a, b ->
            List left = numbers(a)
            List right = numbers(b)
            for (int i = 0; i < 3; i++) {
                int result = left[i] <=> right[i]
                if (result != 0) return result
            }
            a <=> b
        }
    }

    static String minimum(Collection<String> available, String current, Closure<Boolean> passes) {
        boundary(grouped(available), current, passes)
    }

    static Map maximum(Collection<String> available, String current, Closure<Boolean> passes) {
        List<List<String>> groups = grouped(available)
        String maximum = boundary(groups.reverse(), current, passes)
        int index = groups.findIndexOf { it.contains(maximum) }
        [maximum: maximum, firstIncompatible: index + 1 < groups.size() ? groups[index + 1][0] : null]
    }

    static String upperLimit(String maximum, String firstIncompatible) {
        firstIncompatible ?: (numbers(maximum)[0] + 1) + '.0.0'
    }

    static boolean before(String left, String right) {
        List<Integer> leftNumbers = numbers(left)
        List<Integer> rightNumbers = numbers(right)
        for (int i = 0; i < 3; i++) {
            int result = leftNumbers[i] <=> rightNumbers[i]
            if (result != 0) return result < 0
        }
        false
    }

    static Map intersect(Collection<Map> reports) {
        if (!reports) throw new GradleException('Shared Fabric dependency search has no runtime reports')
        Map bounds = [minimumLoader: ordered(reports*.minimumLoader).last(),
                      minimumFabricApi: ordered(reports*.minimumFabricApi).last(),
                      loaderUpperExclusive: ordered(reports*.loaderUpperExclusive).first(),
                      fabricApiUpperExclusive: ordered(reports*.fabricApiUpperExclusive).first()]
        if (!before(bounds.minimumLoader, bounds.loaderUpperExclusive)) {
            throw new GradleException('No common Fabric Loader interval across runtime reports')
        }
        if (!before(bounds.minimumFabricApi, bounds.fabricApiUpperExclusive)) {
            throw new GradleException('No common Fabric API interval across runtime reports')
        }
        bounds
    }

    static List<String> within(Collection<String> available, String minimum, String upperExclusive) {
        ordered(available).findAll { !before(it, minimum) && before(it, upperExclusive) }
    }

    static boolean familyPasses(Collection<Map> targets, String loader, String api, Closure<Boolean> test) {
        for (Map target : targets) {
            Boolean passed = test.call(target, loader, api)
            if (passed == null) throw new GradleException('Dependency test was inconclusive for ' + target.id)
            if (!passed) return false
        }
        true
    }

    static void requireCurrentInputs(Map inputs, File driver, File initialConfig,
                                     File gametestApi, String gametestApiPin,
                                     boolean requireHistoricalDriver = true) {
        if (!(inputs.files instanceof Map) || inputs.files.isEmpty() ||
                inputs.files.any { path, hash ->
                    File source = new File((String) path)
                    !source.isFile() || CandidateFiles.sha256(source) != hash
                } || !driver.isFile() ||
                (requireHistoricalDriver && CandidateFiles.sha256(driver) != inputs.driver) ||
                !initialConfig.isFile() || CandidateFiles.sha256(initialConfig) != inputs.initialConfig ||
                inputs.gametestApiPin != gametestApiPin || !gametestApi.isFile() ||
                CandidateFiles.sha256(gametestApi) != inputs.gametestApiSha256) {
            throw new GradleException('Completed Fabric search inputs changed; rerun every runtime search')
        }
    }

    static void requireSameArtifactExceptBounds(File searched, String searchedSha256,
                                                File rebuilt, Map bounds) {
        if (!searched.isFile() || CandidateFiles.sha256(searched) != searchedSha256 ||
                !rebuilt.isFile()) {
            throw new GradleException('Missing or changed searched Fabric artifact')
        }
        new ZipFile(searched).withCloseable { previous ->
            new ZipFile(rebuilt).withCloseable { current ->
                Map<String, ZipEntry> oldEntries = indexedEntries(previous)
                Map<String, ZipEntry> newEntries = indexedEntries(current)
                if (oldEntries.keySet() != newEntries.keySet() ||
                        !oldEntries.containsKey('fabric.mod.json')) {
                    throw new GradleException('Fabric artifact entries changed after dependency search')
                }
                oldEntries.each { name, oldEntry ->
                    byte[] oldBytes = previous.getInputStream(oldEntry).bytes
                    byte[] newBytes = current.getInputStream(newEntries[name]).bytes
                    if (name == 'fabric.mod.json') {
                        Map oldMetadata = (Map) new JsonSlurper().parseText(
                                new String(oldBytes, StandardCharsets.UTF_8))
                        Map newMetadata = (Map) new JsonSlurper().parseText(
                                new String(newBytes, StandardCharsets.UTF_8))
                        if (!(oldMetadata.depends instanceof Map) ||
                                !(newMetadata.depends instanceof Map) ||
                                newMetadata.depends.fabricloader !=
                                        '>=' + bounds.minimumLoader + ' <' + bounds.loaderUpperExclusive ||
                                newMetadata.depends['fabric-api'] !=
                                        '>=' + bounds.minimumFabricApi + ' <' + bounds.fabricApiUpperExclusive) {
                            throw new GradleException('Rebuilt Fabric artifact has incorrect dependency bounds')
                        }
                        newMetadata.depends.fabricloader = oldMetadata.depends.fabricloader
                        newMetadata.depends['fabric-api'] = oldMetadata.depends['fabric-api']
                        if (oldMetadata != newMetadata) {
                            throw new GradleException('Fabric artifact metadata changed beyond dependency bounds')
                        }
                    } else if (!Arrays.equals(oldBytes, newBytes)) {
                        throw new GradleException('Fabric artifact entry changed after dependency search: ' + name)
                    }
                }
            }
        }
    }

    private static Map<String, ZipEntry> indexedEntries(ZipFile archive) {
        Map<String, ZipEntry> entries = [:]
        Collections.list(archive.entries()).each { ZipEntry entry ->
            if (entries.put(entry.name, entry) != null) {
                throw new GradleException('Fabric artifact has a duplicate entry: ' + entry.name)
            }
        }
        entries
    }

    private static List<List<String>> grouped(Collection<String> available) {
        List<List<String>> groups = []
        ordered(available).each { String version ->
            if (!groups || numbers(groups.last()[0]) != numbers(version)) groups.add([])
            groups.last().add(version)
        }
        groups
    }

    private static String boundary(List<List<String>> groups, String current, Closure<Boolean> passes) {
        int index = groups.findIndexOf { it.contains(current) }
        if (index < 0) throw new GradleException('Current version is absent from discovery: ' + current)
        Map<Integer, Boolean> tested = [:]
        Closure<Boolean> test = { int position ->
            if (!tested.containsKey(position)) {
                boolean groupPasses = true
                groups[position].each { String version ->
                    Boolean result = passes.call(version)
                    if (result == null) throw new GradleException('Dependency test was inconclusive: ' + version)
                    if (!result) groupPasses = false
                }
                tested[position] = groupPasses
            }
            tested[position]
        }
        // Search one compatibility transition in the supplied direction.
        // Semantic versioning alone does not establish that assumption.
        int failed = -1
        int passed = -1
        int step = 1
        if (test(index)) {
            passed = index
            while (passed > 0) {
                int next = Math.max(0, index - step)
                if (!test(next)) { failed = next; break }
                passed = next
                step *= 2
            }
        } else {
            failed = index
            while (failed < groups.size() - 1) {
                int next = Math.min(groups.size() - 1, index + step)
                if (test(next)) { passed = next; break }
                failed = next
                step *= 2
            }
            if (passed < 0) throw new GradleException('No passing dependency version found from: ' + current)
        }
        while (passed - failed > 1) {
            int middle = failed + (passed - failed).intdiv(2)
            if (test(middle)) passed = middle
            else failed = middle
        }
        if (passed + 1 < groups.size()) test(passed + 1)
        groups[passed].contains(current) ? current : groups[passed][0]
    }

    static String startingVersion(Collection<String> available, String previous) {
        List<String> versions = ordered(available)
        List<Integer> desired = numbers(previous)
        String selected = versions.find { numbers(it) == desired }
        if (selected != null) return selected
        List<String> earlier = versions.findAll {
            ordered([it, previous])[0] == it
        }
        earlier ? earlier.last() : versions.first()
    }

    static Map cached(File directory, Map inputs, Closure<Map> run) {
        directory.mkdirs()
        String key = CandidateFiles.canonicalHash(inputs)
        File record = new File(directory, key + '.json')
        if (record.isFile()) {
            Map previous = [:]
            try {
                Object parsed = CandidateFiles.read(record)
                if (parsed instanceof Map) previous = (Map) parsed
            } catch (IOException | GradleException ignored) {
                // An incomplete cache record requires a new run.
            }
            if (previous.inputs == inputs && previous.result instanceof Map &&
                    previous.result.status in ['passed', 'failed'] &&
                    previous.files instanceof Map && !previous.files.isEmpty() &&
                    previous.files.every { path, hash ->
                        File file = new File((String) path)
                        file.isFile() && CandidateFiles.sha256(file) == hash
                    }) {
                return previous.result + [cached: true]
            }
        }
        Map result = run.call()
        if (result.status in ['passed', 'failed']) {
            List<File> files = (List<File>) result.remove('files')
            if (!files) throw new GradleException('Dependency test produced no evidence')
            Map evidence = files.collectEntries { [(it.canonicalPath): CandidateFiles.sha256(it)] }
            record.text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(
                    [inputs: inputs, result: result, files: evidence])) + '\n'
        }
        result + [cached: false]
    }
}
