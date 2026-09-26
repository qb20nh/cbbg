package cbbg.gradle

import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project

/** Repository formatting is independent of Minecraft dependency resolution. */
class FormattingPlugin implements Plugin<Project> {
    static final String FORMATTER_VERSION = '1.36.1'

    void apply(Project project) {
        project.pluginManager.apply(SpotlessPlugin)
        project.extensions.configure(SpotlessExtension) { spotless ->
            spotless.java { format ->
                format.target project.fileTree(project.projectDir) {
                    include '**/src/**/*.java'
                    exclude '**/build/**', '**/.gradle/**'
                }
                format.googleJavaFormat(FORMATTER_VERSION)
            }
        }
    }
}
