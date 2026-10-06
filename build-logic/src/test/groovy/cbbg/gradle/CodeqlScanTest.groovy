package cbbg.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*
import static org.junit.jupiter.api.Assumptions.assumeFalse

class CodeqlScanTest {
    @TempDir File directory

    private static Map catalog() {
        new JsonSlurper().parse(new File(System.getProperty('cbbg.repository'), 'targets.json')) as Map
    }

    @Test
    void coversArtifactOwnersAndPreservesHistoricalCategory() {
        List<Map> rows = CodeqlScan.targets(new TargetCatalog(catalog()), '26.2-fabric')
        assertEquals(['26.1-fabric', '26.2-fabric', '26.3-fabric'] as Set, rows*.id as Set)
        assertEquals(3, rows.size())
        assertEquals('/language:java-kotlin', rows.find { it.id == '26.2-fabric' }.category)
        assertEquals('/language:java-kotlin/target:26.1-fabric', rows.find { it.id == '26.1-fabric' }.category)
        assertEquals(3, rows*.category.toSet().size())
        Map data = catalog()
        data.ciTargets.remove('26.2-fabric')
        data.targets.find { it.id == '26.2-fabric' }.implemented = false
        assertFalse(CodeqlScan.targets(new TargetCatalog(data), '')*.id.contains('26.2-fabric'))
        assertEquals(rows, CodeqlScan.targets(new TargetCatalog(data), '26.2-fabric'))
    }

    @Test
    void includesNewImplementedOwnersEvenOutsideCiSelection() {
        Map data = catalog()
        data.targets.find { it.id == '1.21.11-fabric' }.implemented = true
        assertTrue(CodeqlScan.targets(new TargetCatalog(data), '26.2-fabric')*.id.contains('1.21.11-fabric'))
        assertThrows(IllegalArgumentException) {
            CodeqlScan.targets(new TargetCatalog(data), 'unknown')
        }
    }

    private File fixture(String failure = '') {
        assumeFalse(System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('windows'))
        File root = new File(directory, "repo with 'quotes")
        root.mkdirs()
        new File(root, 'settings.gradle').text = "rootProject.name = 'codeql-test'\n"
        new File(root, 'build.gradle').text = "plugins { id 'cbbg.targets' }\n"
        new File(root, 'targets.json').text = JsonOutput.toJson(catalog())
        for (String profile : ['fabric-modern', 'fabric-upstream']) {
            File build = new File(root, 'build-config/' + profile + '/build.gradle')
            build.parentFile.mkdirs()
            build.text = ''
        }
        File wrapper = new File(root, 'gradlew')
        wrapper.text = '''#!/bin/sh
set -eu
case " $* " in *" clean "*) rm -rf build;; *) exit 9;; esac
printf '%s\\n' "$@" > last-build-args
'''
        assertTrue(wrapper.setExecutable(true))
        File cli = new File(root, 'fake-codeql')
        cli.text = '''#!/usr/bin/env python3
import json, os, pathlib, subprocess, sys
root = pathlib.Path(__file__).parent
args = sys.argv[1:]
with (root / 'calls.jsonl').open('a') as log:
    log.write(json.dumps({'args': args, 'java': os.environ.get('JAVA_HOME')}) + '\\n')
if args[1] == 'init':
    database = pathlib.Path(args[2])
    database.mkdir(parents=True)
elif args[1] == 'trace-command':
    subprocess.run(args[4:], cwd=root, check=True)
elif args[1] == 'analyze':
    if pathlib.Path(args[2]).name == 'FAILURE':
        sys.exit(7)
    output = pathlib.Path(next(a.split('=', 1)[1] for a in args if a.startswith('--output=')))
    category = next(a.split('=', 1)[1] for a in args if a.startswith('--sarif-category='))
    if 'WRONG_CATEGORY':
        category = '/wrong'
    output.write_text(json.dumps({'version': '2.1.0', 'runs': [{
        'automationDetails': {'id': category + '/'},
        'tool': {'driver': {'name': 'CodeQL', 'rules': [{'id': 'java/example'}]}},
        'results': [{'ruleId': 'java/example', 'message': {'text': 'fixture finding'},
                     'partialFingerprints': {'primaryLocationLineHash': 'fixture'}}]
    }]}))
'''.replace('FAILURE', failure == 'command' ? '26.3-fabric' : '')
                .replace('WRONG_CATEGORY', failure == 'category' ? 'yes' : '')
        assertTrue(cli.setExecutable(true))
        root
    }

    private GradleRunner runner(File root) {
        GradleRunner.create().withProjectDir(root).withPluginClasspath().withArguments(
                'codeqlScan', '-PcodeqlExecutable=' + new File(root, 'fake-codeql').absolutePath,
                '-PcodeqlLegacyTarget=26.2-fabric', '--offline', '--stacktrace')
    }

    @Test
    void tracesCleanBuildsInSeparateDatabasesAndRetainsEveryReport() {
        File root = fixture()
        runner(root).build()
        File output = new File(root, '.gradle/codeql-results')
        assertEquals(['26.1-fabric.sarif', '26.2-fabric.sarif', '26.2-fabric-target.sarif', '26.3-fabric.sarif'] as Set,
                new File(output, 'sarif').list() as Set)
        Map primary = new JsonSlurper().parse(new File(output, 'sarif/26.2-fabric.sarif')) as Map
        Map target = new JsonSlurper().parse(new File(output, 'sarif/26.2-fabric-target.sarif')) as Map
        assertEquals('/language:java-kotlin/', primary.runs.first().automationDetails.id)
        assertEquals('/language:java-kotlin/target:26.2-fabric/', target.runs.first().automationDetails.id)
        primary.runs.first().automationDetails.id = target.runs.first().automationDetails.id
        assertEquals(primary, target)
        List<Map> calls = new File(root, 'calls.jsonl').readLines().collect { new JsonSlurper().parseText(it) as Map }
        List<Map> builds = calls.findAll { it.args[1] == 'trace-command' }
        assertEquals(3, builds*.args.collect { it[2] }.toSet().size())
        assertEquals(3, calls.count { it.args[1] == 'analyze' })
        builds.each { call ->
            assertTrue(new File(call.java as String).isDirectory())
            List<String> command = call.args.drop(4)
            assertTrue(command.containsAll(['clean', 'compileJava', '--rerun-tasks', '--no-build-cache']))
            if (call.args[2].endsWith('26.2-fabric')) assertFalse(command.any { it.startsWith('-Ptarget=') })
            else assertTrue(command.any { it.startsWith('-Ptarget=') })
            assertFalse(new File(call.args[2] as String).exists())
        }
        runner(root).build()
        assertEquals(24, new File(root, 'calls.jsonl').readLines().size())
    }

    @Test
    void failsWhenAnyAnalysisFails() {
        File root = fixture('command')
        String output = runner(root).buildAndFail().output
        assertTrue(output.contains('non-zero exit value 7'), output)
        assertFalse(new File(root, '.gradle/codeql-results/sarif/26.3-fabric.sarif').exists())
    }

    @Test
    void rejectsReportsFromAnotherCategory() {
        assertTrue(runner(fixture('category')).buildAndFail().output.contains('Unexpected CodeQL report'))
    }
}
