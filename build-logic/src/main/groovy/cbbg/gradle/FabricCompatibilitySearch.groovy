package cbbg.gradle

import org.gradle.api.GradleException

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
        List<String> versions = ordered(available)
        int index = versions.indexOf(current)
        if (index < 0) throw new GradleException('Current version is absent from discovery: ' + current)
        Map<String, Boolean> tested = [:]
        Closure<Boolean> test = { int position ->
            String version = versions[position]
            if (!tested.containsKey(version)) {
                Boolean result = passes.call(version)
                if (result == null) throw new GradleException('Dependency test was inconclusive: ' + version)
                tested[version] = result
            }
            tested[version]
        }
        // Search published releases, assuming compatibility starts at one version.
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
            while (failed < versions.size() - 1) {
                int next = Math.min(versions.size() - 1, index + step)
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
        if (passed + 1 < versions.size()) test(passed + 1)
        versions[passed]
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
