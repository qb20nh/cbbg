package cbbg.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class PackagingPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.tasks.register('checkPackages', CheckPackages) {
            group = 'verification'
            description = 'Check production bytecode and embedded core sources.'
        }
    }
}
