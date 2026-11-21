package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.api.Project
import org.gradle.language.jvm.tasks.ProcessResources

/** Stable encoding for generated release files. Array order is preserved. */
class ReproducibleText {
    private static final String UTF8 = 'UTF-8'

    static void configureResources(Project project) {
        project.tasks.withType(ProcessResources).configureEach { filteringCharset = UTF8 }
        project.afterEvaluate {
            project.tasks.withType(ProcessResources).configureEach {
                doLast {
                    project.fileTree(destinationDir).matching {
                        include '**/*.json', '**/*.glsl', '**/*.fsh', '**/*.vsh', '**/*.properties'
                    }.files.each { normalize(it) }
                }
            }
        }
    }

    static String json(Object value) {
        JsonOutput.prettyPrint(JsonOutput.toJson(CandidateFiles.sorted(value))) + '\n'
    }

    static void writeJson(File file, Object value) {
        file.setText(json(value), UTF8)
    }

    static void normalize(File file) {
        String original = file.getText(UTF8)
        String normalized = original.replace('\r\n', '\n').replace('\r', '\n')
        if (original != normalized) {
            file.setText(normalized, UTF8)
        }
    }
}
