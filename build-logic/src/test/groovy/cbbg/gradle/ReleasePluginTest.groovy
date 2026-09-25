package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import static org.junit.jupiter.api.Assertions.*

class ReleasePluginTest {
    @TempDir File directory

    private GradleRunner runner(String... arguments) {
        new File(directory, 'settings.gradle').text = "rootProject.name = 'release-test'\n"
        new File(directory, 'build.gradle').text = "plugins { id 'cbbg.release' }\n"
        GradleRunner.create().withProjectDir(directory).withPluginClasspath()
                .withArguments(arguments.toList() + ['--stacktrace'])
    }

    @Test void acceptanceAndPublicationRequireLocalExecution() {
        Map environment = new HashMap(System.getenv())
        environment.CI = 'true'
        ['verifyCandidate', 'checkRelease', 'publishRelease'].each { name ->
            assertTrue(runner(name).withEnvironment(environment).buildAndFail().output
                    .contains('Candidate runtime acceptance and finalization run locally'))
        }
    }

    @Test void publicationRequiresExplicitInputsWithoutBuilding() {
        assertTrue(runner('preparePublication').buildAndFail().output.contains('Missing -Prelease'))
        assertTrue(runner('checkHotfix').buildAndFail().output.contains('Missing -Pbaselines'))
        def result = runner('preparePublication', '--dry-run').build()
        assertFalse(result.output.contains(':build '))
        assertFalse(result.output.contains(':compileJava '))
    }

    @Test void curseForgeReceiptBindsFileIdToSubmittedMetadata() {
        File metadata = new File(directory, 'publication.json')
        metadata.text = JsonOutput.toJson([release: 'v1.4.0', source_commit: 'a' * 40,
                records: [[targets: ['26.3-fabric'], artifact: [path: 'cbbg.jar', sha256: 'b' * 64]]]])
        String[] arguments = ['recordCurseForgeUpload', '-PpublicationMetadata=publication.json',
                              '-Poutput=receipt.json', '-PfileId=123']
        runner(*arguments).build()
        Map result = CandidateFiles.read(new File(directory, 'receipt.json'))
        assertEquals('123', result.file_id)
        assertEquals(CandidateFiles.sha256(metadata), result.metadata_sha256)
        assertEquals(['26.3-fabric'], result.targets)
        assertTrue(result.url.endsWith('/123'))
        runner(*arguments).buildAndFail()
        ['0', '-1', 'abc', '１２'].each { id ->
            assertTrue(runner('recordCurseForgeUpload', '-PfileId=' + id).buildAndFail().output
                    .contains('no valid file ID'))
        }
    }

    private String git(String... arguments) {
        Process command = new ProcessBuilder(['git', '-c', 'user.name=Test',
                '-c', 'user.email=test@example.invalid', '-c', 'commit.gpgsign=false'] + arguments.toList())
                .directory(directory).redirectErrorStream(true).start()
        String output = command.inputStream.getText('UTF-8')
        assertEquals(0, command.waitFor(), output)
        output.trim()
    }

    @Test void checkSelectionUsesRequestedRevisionAndInitialPushExpandsChecks() {
        runner('help').build()
        new File(directory, '.gitignore').text = '.gradle/\nbuild/\n'
        Map target = [id: '26.3-fabric', minecraft: '26.3', loader: 'fabric', java: 25,
                      renderer: 'renderpearl', backends: ['opengl', 'vulkan'],
                      implemented: false, buildProfile: 'fabric-modern']
        File catalog = new File(directory, 'targets.json')
        Map data = [schema: 1, ciTargets: ['26.3-fabric'], targets: [target]]
        catalog.text = JsonOutput.toJson(data)
        git('init')
        git('add', '.')
        git('commit', '-m', 'Initial catalog')
        String base = git('rev-parse', 'HEAD')
        Map another = target + [id: '26.2-fabric', minecraft: '26.2']
        data.targets.add(another)
        data.ciTargets = ['26.2-fabric']
        catalog.text = JsonOutput.toJson(data)
        git('add', 'targets.json')
        git('commit', '-m', 'Change development target')
        runner('selectChecks', '-Pbase=' + ('0' * 40), '-Phead=' + base,
                '-Poutput=build/report.json', '-PgithubOutput=build/github.txt').build()
        Map report = CandidateFiles.read(new File(directory, 'build/report.json'))
        assertEquals(base, report.head)
        assertEquals(['26.3-fabric'], report.ci.matrix.include*.id)
        assertTrue(report.ci.build)
        assertTrue(report.ci.core)
        assertTrue(new File(directory, 'build/github.txt').text.contains('core=true\n'))
        runner('selectChecks', '-Pbase=missing-commit', '-Poutput=build/missing.json').buildAndFail()
        assertFalse(new File(directory, 'build/missing.json').exists())

        String current = git('rev-parse', 'HEAD')
        File publishing = new File(directory, 'build-config/publishing/build.gradle')
        publishing.parentFile.mkdirs()
        publishing.text = '// Publication metadata\n'
        git('add', 'build-config')
        git('commit', '-m', 'Update publication metadata')
        runner('selectChecks', '-Pbase=' + current, '-Poutput=build/publication.json').build()
        Map publication = CandidateFiles.read(new File(directory, 'build/publication.json'))
        assertFalse(publication.ci.build)
        assertFalse(publication.ci.core)
        assertEquals(['publication'], publication.checks)
    }
}
