package cbbg.gradle

import groovy.json.JsonOutput
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
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
        ['runCandidateAcceptance', 'verifyCandidate', 'checkRelease', 'publishRelease'].each { name ->
            assertTrue(runner(name).withEnvironment(environment).buildAndFail().output
                    .contains('Candidate runtime acceptance and finalization run locally'))
        }
    }

    @Test void candidateAcceptanceRequiresInputsWithoutBuildingAndHasNoOverallTimeout() {
        assertTrue(runner('runCandidateAcceptance').buildAndFail().output.contains('Missing -Pcandidate'))
        def configured = runner('runCandidateAcceptance', '--dry-run')
        new File(directory, 'build.gradle').append("\nassert tasks.runCandidateAcceptance.timeout.orNull == null\n")
        String output = configured.build().output
        assertTrue(output.contains(':runCandidateAcceptance SKIPPED'))
        assertFalse(output.contains(':compileJava '))
    }

    @Test void candidateAcceptanceKeepsTheVirtualenvPythonPath() {
        File interpreter = new File(directory, 'interpreter')
        interpreter.text = '#!/bin/sh\nexit 0\n'
        interpreter.setExecutable(true)
        File bin = new File(directory, 'venv/bin')
        bin.mkdirs()
        Files.createSymbolicLink(new File(bin, 'python').toPath(), interpreter.toPath())
        File weston = new File(bin, 'weston')
        weston.text = interpreter.text
        weston.setExecutable(true)
        Map environment = new HashMap(System.getenv())
        environment.remove('CI')
        environment.PATH = bin.absolutePath + File.pathSeparator + environment.PATH
        def configured = runner('runCandidateAcceptance', '-Pcandidate=candidate.json', '-Poutput=acceptance',
                '-PacceptancePython=venv/bin/python', '-PacceptanceJava21=interpreter',
                '-PacceptanceJava25=interpreter').withEnvironment(environment)
        new File(directory, 'build.gradle').append('''
cbbg.gradle.FabricCandidateAcceptance.metaClass.static.execute = { Map options, Closure command, Closure probe ->
    assert options.python.absolutePath == new File(rootDir, 'venv/bin/python').absolutePath
    assert options.python.canonicalPath == new File(rootDir, 'interpreter').canonicalPath
    println 'Using the virtualenv executable'
}
''')
        assertTrue(configured.build().output.contains('Using the virtualenv executable'))
    }

    @Test void publicationRequiresExplicitInputsWithoutBuilding() {
        String output = runner('preparePublication').buildAndFail().output
        assertTrue(output.contains('Missing -Prelease'))
        assertTrue(output.contains('preparePublication requires -Prelease=<value>'), output)
        assertTrue(runner('checkHotfix').buildAndFail().output.contains('Missing -Pbaselines'))
        def result = runner('preparePublication', '--dry-run').build()
        assertFalse(result.output.contains(':build '))
        assertFalse(result.output.contains(':compileJava '))
        assertTrue(runner('selectPublication').buildAndFail().output.contains('Missing -Prelease'))
    }

    @Test void releaseNotesSupportsSingleAndSharedTargetSelections() {
        Map fabric = [id: '26.3-fabric', minecraft: '26.3', loader: 'fabric', java: 25,
                      renderer: 'renderpearl', backends: ['opengl', 'vulkan'], implemented: false]
        Map forge = fabric + [id: '26.3-neoforge', loader: 'neoforge']
        new File(directory, 'targets.json').text = JsonOutput.toJson([schema: 1, targets: [fabric, forge]])
        new File(directory, 'CHANGELOG.md').text = '''## [1.5.0] <!-- [1.5.0-mc26.3-fabric] [1.5.0-mc26.3-neoforge] -->
### Fixed
- Shared fix.
#### Fabric
- Fabric fix.
#### NeoForge
- NeoForge fix.
'''
        runner('releaseNotes', '-Prelease=v1.5.0', '-Ptarget=26.3-fabric', '-Poutput=notes.md').build()
        String single = new File(directory, 'notes.md').text
        assertTrue(single.contains('Fabric fix.'))
        assertFalse(single.contains('NeoForge fix.'))
        runner('releaseNotes', '-Prelease=v1.5.0+mc26.3-fabric', '-Ptarget=26.3-fabric', '-Poutput=notes.md').build()
        assertEquals(single, new File(directory, 'notes.md').text)
        assertTrue(runner('releaseNotes', '-Prelease=v1.5.0+mc26.3-fabric',
                '-Ptargets=26.3-fabric,26.3-neoforge', '-Poutput=notes.md').buildAndFail()
                .output.contains('selected artifact owner'))
        runner('releaseNotes', '-Prelease=v1.5.0', '-Ptargets=26.3-fabric,26.3-neoforge', '-Poutput=notes.md').build()
        assertTrue(new File(directory, 'notes.md').text.contains('NeoForge fix.'))
        assertTrue(runner('releaseNotes', '-Prelease=v1.5.0', '-Poutput=notes.md').buildAndFail()
                .output.contains('explicit target selection'))
        assertTrue(runner('releaseNotes', '-Prelease=v1.5.0', '-Ptarget=26.3-fabric',
                '-Ptargets=26.3-neoforge', '-Poutput=notes.md').buildAndFail().output.contains('Use either'))
    }

    @Test void curseForgeReceiptsSelectAnArtifactWithinASharedRelease() {
        new File(directory, 'publication.json').text = JsonOutput.toJson([release: 'v1.5.0',
                source_commit: 'a' * 40, records: [
                [targets: ['26.3-fabric', '26.3-quilt'], artifact: [path: 'fabric.jar', sha256: 'b' * 64]],
                [targets: ['26.2-fabric'], artifact: [path: 'older.jar', sha256: 'c' * 64]]]])
        runner('recordCurseForgeUpload', '-PpublicationMetadata=publication.json',
                '-Ptarget=26.3-quilt', '-Poutput=receipt.json', '-PfileId=123').build()
        assertEquals(['26.3-fabric', '26.3-quilt'], CandidateFiles.read(new File(directory, 'receipt.json')).targets)
        for (String selection : ['', '-Ptarget=missing']) {
            List args = ['recordCurseForgeUpload', '-PpublicationMetadata=publication.json',
                         '-Poutput=other-receipt.json', '-PfileId=123']
            if (selection) args.add(selection)
            assertTrue(runner(*(args as String[])).buildAndFail().output.contains('Select one publication record'))
        }
    }

    @Test void publicationDryRunRequiresStrictBooleanProperty() {
        ['TRUE', '1', 'yes', ''].each { value ->
            assertTrue(runner('preparePublication', '-Prelease=v1.4.0', '-PdryRun=' + value)
                    .buildAndFail().output.contains('Expected -PdryRun=true or false'))
        }
        ['true', 'false'].each { value ->
            assertTrue(runner('preparePublication', '-Prelease=v1.4.0', '-PdryRun=' + value)
                    .buildAndFail().output.contains('Missing -Prepo'))
        }
    }

    @Test void workflowUploadsUtilityPairAsOptionalCurseForgeChildFilesAndSavesReceipts() {
        String workflow = new File('../.github/workflows/publish.yml').text
        ['utilities', 'utilities_sources'].each { kind ->
            String step = workflow.split(/\n      - name: /).find { it.contains('id: curseforge_' + kind + '_upload\n') }
            assertNotNull(step)
            assertTrue(step.contains("!inputs.dry_run && inputs.services != 'modrinth' && steps.publication.outputs.${kind} != ''"))
            assertTrue(step.contains('parent_file_id: ${{ steps.curseforge_upload.outputs.id }}'))
            assertTrue(step.contains('file_path: ${{ steps.publication.outputs.' + kind + ' }}'))
        }
        assertTrue(workflow.contains('"-PutilitiesFileId=$CF_UTILITIES_FILE_ID"'))
        assertTrue(workflow.contains('"-PutilitiesSourcesFileId=$CF_UTILITIES_SOURCES_FILE_ID"'))
        assertTrue(workflow.contains('            build/curseforge-utilities-result.json\n'))
        assertTrue(workflow.contains('            build/curseforge-utilities-sources-result.json\n'))
    }

    @Test void curseForgeReceiptBindsFileIdToSubmittedMetadata() {
        File metadata = new File(directory, 'publication.json')
        metadata.text = JsonOutput.toJson([release: 'v1.4.0', source_commit: 'a' * 40,
                records: [[targets: ['26.3-fabric'], artifact: [path: 'cbbg.jar', sha256: 'b' * 64],
                           sources: [path: 'cbbg-sources.jar', sha256: 'c' * 64],
                           utilities: [path: 'cbbg-utilities.jar', sha256: 'e' * 64],
                           utilities_sources: [path: 'cbbg-utilities-sources.jar', sha256: 'f' * 64],
                           evidence: [path: 'cbbg-evidence.zip', sha256: 'd' * 64]]]])
        String[] arguments = ['recordCurseForgeUpload', '-PpublicationMetadata=publication.json',
                              '-Poutput=receipt.json', '-PfileId=123']
        runner(*arguments).build()
        Map result = CandidateFiles.read(new File(directory, 'receipt.json'))
        assertEquals('123', result.file_id)
        assertEquals(CandidateFiles.sha256(metadata), result.metadata_sha256)
        assertEquals(['26.3-fabric'], result.targets)
        assertTrue(result.url.endsWith('/123'))
        assertFalse(result.containsKey('sources_file_id'))
        runner(*arguments).buildAndFail()
        runner('recordCurseForgeUpload', '-PpublicationMetadata=publication.json',
                '-Poutput=sources-receipt.json', '-PfileId=123', '-PsourcesFileId=456').build()
        Map sourcesResult = CandidateFiles.read(new File(directory, 'sources-receipt.json'))
        assertEquals('123', sourcesResult.file_id)
        assertEquals('456', sourcesResult.sources_file_id)
        assertEquals([path: 'cbbg-sources.jar', sha256: 'c' * 64], sourcesResult.sources)
        assertTrue(sourcesResult.sources_url.endsWith('/456'))
        runner('recordCurseForgeUpload', '-PpublicationMetadata=publication.json',
                '-Poutput=evidence-receipt.json', '-PfileId=123', '-PsourcesFileId=456',
                '-PevidenceFileId=789').build()
        Map evidenceResult = CandidateFiles.read(new File(directory, 'evidence-receipt.json'))
        assertEquals('789', evidenceResult.evidence_file_id)
        assertEquals([path: 'cbbg-evidence.zip', sha256: 'd' * 64], evidenceResult.evidence)
        assertTrue(evidenceResult.evidence_url.endsWith('/789'))
        runner('recordCurseForgeUpload', '-PpublicationMetadata=publication.json',
                '-Poutput=utilities-receipt.json', '-PfileId=123', '-PutilitiesFileId=234',
                '-PutilitiesSourcesFileId=345').build()
        Map utilitiesResult = CandidateFiles.read(new File(directory, 'utilities-receipt.json'))
        assertEquals('234', utilitiesResult.utilities_file_id)
        assertEquals('345', utilitiesResult.utilities_sources_file_id)
        assertEquals([path: 'cbbg-utilities.jar', sha256: 'e' * 64], utilitiesResult.utilities)
        assertEquals([path: 'cbbg-utilities-sources.jar', sha256: 'f' * 64], utilitiesResult.utilities_sources)
        assertTrue(utilitiesResult.utilities_url.endsWith('/234'))
        assertTrue(utilitiesResult.utilities_sources_url.endsWith('/345'))
        assertTrue(runner('recordCurseForgeUpload', '-PpublicationMetadata=publication.json',
                '-Poutput=invalid-utilities.json', '-PfileId=123', '-PutilitiesFileId=0')
                .buildAndFail().output.contains('no valid utilities file ID'))
        assertTrue(runner('recordCurseForgeUpload', '-PpublicationMetadata=publication.json',
                '-Poutput=invalid-utilities-sources.json', '-PfileId=123', '-PutilitiesSourcesFileId=abc')
                .buildAndFail().output.contains('no valid utilities_sources file ID'))
        ['0', '-1', 'abc', '１２'].each { id ->
            assertTrue(runner('recordCurseForgeUpload', '-PfileId=' + id).buildAndFail().output
                    .contains('no valid file ID'))
            assertTrue(runner('recordCurseForgeUpload', '-PfileId=123', '-PsourcesFileId=' + id)
                    .buildAndFail().output.contains('no valid sources file ID'))
            assertTrue(runner('recordCurseForgeUpload', '-PfileId=123', '-PevidenceFileId=' + id)
                    .buildAndFail().output.contains('no valid evidence file ID'))
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
        assertTrue(new File(directory, 'build/github.txt').text.contains('tooling=true\n'))
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
        assertTrue(publication.ci.tooling)

        current = git('rev-parse', 'HEAD')
        new File(directory, 'README.md').text = 'Updated documentation\n'
        git('add', 'README.md')
        git('commit', '-m', 'Update documentation')
        runner('selectChecks', '-Pbase=' + current, '-Poutput=build/docs.json',
                '-PgithubOutput=build/docs-github.txt').build()
        assertFalse(CandidateFiles.read(new File(directory, 'build/docs.json')).ci.tooling)
        assertTrue(new File(directory, 'build/docs-github.txt').text.contains('tooling=false\n'))
    }
}
