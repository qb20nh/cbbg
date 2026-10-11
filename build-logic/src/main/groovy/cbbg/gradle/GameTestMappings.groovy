package cbbg.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GameTestMappings extends DefaultTask {
    @InputFile abstract RegularFileProperty getMappings()
    @OutputFile abstract RegularFileProperty getOutput()

    static Map<String, String> names(List<String> lines) {
        def header = lines.first().split('\t', -1).toList()
        int runtime = header.indexOf('intermediary') - 3
        int named = header.indexOf('named') - 3
        if (header.take(3) != ['tiny', '2', '0'] || runtime < 0 || named < 0) {
            throw new GradleException('Game test mappings require Tiny v2 named and intermediary namespaces')
        }
        Map<String, String> result = [:]
        String owner = null
        lines.drop(1).each { line ->
            def parts = line.split('\t', -1)
            if (parts[0] == 'c') {
                owner = parts[1 + runtime].replace('/', '.')
            } else if (owner != null && parts.length > 3 && parts[0] == '') {
                if (parts[1] == 'f' || (parts[1] == 'm' && parts[2].startsWith('()'))) {
                    String kind = parts[1] == 'f' ? 'field' : 'method'
                    result["${kind}|${owner}|${parts[3 + named]}".toString()] = parts[3 + runtime]
                }
            }
        }
        result
    }

    @TaskAction
    void generate() {
        Properties properties = new Properties()
        properties.putAll(names(mappings.get().asFile.readLines('UTF-8')))
        File destination = output.get().asFile
        destination.parentFile.mkdirs()
        StringWriter writer = new StringWriter()
        properties.store(writer, null)
        destination.setText(writer.toString().readLines().findAll { !it.startsWith('#') }
                .sort().join('\n') + '\n', 'UTF-8')
    }
}
