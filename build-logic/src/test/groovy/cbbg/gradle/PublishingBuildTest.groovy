package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*

class PublishingBuildTest {
    @TempDir File directory

    @Test void sharedArtifactCanBeValidatedAndPlannedOnlyThroughItsOwner() {
        Map fixture = CandidateFixture.create(directory)
        Map quilt = fixture.target + [id: '26.3-quilt', loader: 'quilt', artifactOf: fixture.target.id]
        fixture.catalog.targets.add(quilt)
        new File(fixture.root, 'targets.json').text = JsonOutput.toJson(fixture.catalog)
        File catalog = new File(fixture.bundle, fixture.record.client_tests.catalog.path)
        catalog.text = JsonOutput.toJson(fixture.catalog)
        fixture.record.client_tests.catalog.sha256 = CandidateFiles.sha256(catalog)
        Map alias = CandidateFiles.parse(new StringReader(JsonOutput.toJson(fixture.record))) as Map
        alias.id = quilt.id
        fixture.manifest.selected_targets.add(quilt.id)
        fixture.manifest.targets.add(alias)
        fixture.manifest.catalog_sha256 = CandidateFiles.canonicalHash(fixture.catalog)
        fixture.file.text = JsonOutput.toJson(fixture.manifest)
        File metadata = new File(directory, 'publication.json')
        metadata.text = JsonOutput.toJson(Publication.metadata(fixture.file, fixture.root, 'Release notes'))

        File project = new File(fixture.root, 'build-config/publishing')
        project.mkdirs()
        new File(project, 'settings.gradle').text = '''pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
}
rootProject.name = 'publishing-test'
'''
        new File(project, 'build.gradle').text = new File('../build-config/publishing/build.gradle').text
        def runner = { String target, List<String> tasks ->
            GradleRunner.create().withProjectDir(project).withPluginClasspath().withArguments(tasks + [
                    '-Ptarget=' + target, '-PpublicationMetadata=' + metadata.absolutePath,
                    '-Pcandidate=' + fixture.file.absolutePath, '-PsourceRoot=' + fixture.root.absolutePath,
                    '--stacktrace'])
        }
        assertTrue(runner(fixture.target.id, ['verifyUploadInputs']).build().output
                .contains('Checked publication files for ' + fixture.target.id))
        assertTrue(runner(fixture.target.id, ['planModrinthUpload', '--dry-run']).build().output
                .contains(':planModrinthUpload SKIPPED'))
        assertTrue(runner(quilt.id, ['planModrinthUpload', '--dry-run']).buildAndFail().output
                .contains('Expected one publishing record'))
    }
}
