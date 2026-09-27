package cbbg.gradle

import org.gradle.api.GradleException
import proguard.obfuscate.MappingProcessor
import proguard.obfuscate.MappingReader

class ProguardMapping {
    static final String VERSION = '7.10.0'

    static Map<String, String> classes(File file) {
        Map<String, String> names = [:]
        new MappingReader(file).pump([
                processClassMapping: { String original, String renamed ->
                    if (!original || !renamed || names.containsKey(original.replace('.', '/')) ||
                            original.contains('/') || renamed.contains('/')) {
                        throw new GradleException('Invalid or duplicate class mapping')
                    }
                    names[original.replace('.', '/')] = renamed.replace('.', '/')
                    false
                }
        ] as MappingProcessor)
        if (names.isEmpty()) throw new GradleException('Mapping contains no classes')
        names
    }
}
