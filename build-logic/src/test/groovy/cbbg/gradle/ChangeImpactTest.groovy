package cbbg.gradle

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*

class ChangeImpactTest {
    @TempDir File root

    private static Map target(String loader, String group, String owner = null) {
        Map target = [id: "1-${loader}".toString(), minecraft: '1', loader: loader, java: 25,
                      renderer: loader == 'forge' ? 'legacy' : 'modern',
                      backends: ['opengl'], implemented: false]
        if (owner) target.artifactOf = owner
        else {
            target.buildProfile = loader
            target.sourceGroups = [group]
        }
        target
    }

    private static Map catalogData(boolean includeForge = true) {
        List targets = [target('fabric', 'adapters/fabric'),
                        target('quilt', null, '1-fabric')]
        if (includeForge) targets << target('forge', 'adapters/forge')
        [schema: 1, ciTargets: ['1-fabric'], targets: targets]
    }

    @Test
    void selectsSharedRuntimesAndConservativeChecks() {
        TargetCatalog catalog = new TargetCatalog(catalogData())
        assertEquals(['1-fabric', '1-quilt'],
                ChangeImpact.select(catalog, ['adapters/fabric/src/A.java']).targets)
        assertEquals(['1-fabric', '1-quilt'],
                ChangeImpact.select(catalog, ['renderers/modern/A.java']).targets)
        assertEquals(['1-fabric', '1-quilt'],
                ChangeImpact.select(catalog, ['build-config/fabric/build.gradle']).targets)
        assertEquals(['1-fabric', '1-forge', '1-quilt'],
                ChangeImpact.select(catalog, ['adapters/fabric-other/A.java']).targets)
        assertTrue(ChangeImpact.select(catalog, ['core/src/A.java']).checks.contains('core-java-8-17-21-25'))
        assertEquals(['documentation', 'publication'],
                ChangeImpact.select(catalog, ['README.md', 'build-config/publishing/release.gradle']).checks)
        assertEquals([], ChangeImpact.select(catalog, []).targets)
        for (String path : ['', '../A', '/A', 'dir\\A']) {
            assertThrows(IllegalArgumentException) { ChangeImpact.select(catalog, [path]) }
        }
        Map plan = ChangeImpact.ci(catalog, ChangeImpact.select(catalog, ['core/src/A.java']))
        assertEquals(['1-fabric'], plan.matrix.include*.id)
        assertTrue(plan.build)
        assertTrue(plan.core)
        assertFalse(ChangeImpact.ci(catalog, ChangeImpact.select(catalog, ['README.md'])).build)
        assertEquals(['publication'], ChangeImpact.select(catalog,
                ['build-logic/src/main/groovy/cbbg/gradle/Publication.groovy']).checks)
    }

    @Test
    void undeclaredSourcesCannotExcludeTargets() {
        Map data = catalogData()
        data.targets.find { it.id == '1-forge' }.remove('sourceGroups')
        assertEquals(['1-fabric', '1-forge', '1-quilt'],
                ChangeImpact.select(new TargetCatalog(data), ['adapters/fabric/A.java']).targets)
    }

    @Test
    void selectsOnlyCiBuildsConsumingVersionSpecificSources() {
        TargetCatalog catalog = TargetCatalog.read(new File('../targets.json'))
        assertEquals(['26.3-fabric'], ChangeImpact.ci(catalog,
                ChangeImpact.select(catalog, ['renderers/renderpearl/src/main/java/com/qb20nh/cbbg/render/FloatAttachments.java'], true))
                .matrix.include*.id)
        assertEquals(['26.2-fabric'], ChangeImpact.ci(catalog,
                ChangeImpact.select(catalog, ['adapters/fabric/26.2/src/main/java/com/qb20nh/cbbg/mixin/ScreenshotMixin.java'], true))
                .matrix.include*.id)
        assertEquals(['26.1-fabric'], ChangeImpact.ci(catalog,
                ChangeImpact.select(catalog, ['adapters/minecraft/26.1/src/main/java/com/qb20nh/cbbg/mixin/ScreenshotMixin.java'], true))
                .matrix.include*.id)
    }

    @Test
    void includesFilteredSharedSourcesOnlyWhereConsumed() {
        TargetCatalog catalog = TargetCatalog.read(new File('../targets.json'))
        Map<String, List<String>> cases = [
                'renderers/renderpearl/src/gametest/java/com/qb20nh/cbbg/gametest/mixin/ScenarioProgressMixin.java':
                        ['1.21.11-fabric', '26.1-fabric', '26.2-fabric', '26.3-fabric'],
                'renderers/modern/src/main/java/com/qb20nh/cbbg/render/Rgba8Capture.java':
                        ['26.2-fabric', '26.3-fabric'],
                'adapters/fabric/shared/src/main/java/com/qb20nh/cbbg/render/stbn/STBNLoader.java':
                        ['1.21.1-fabric', '1.21.11-fabric', '26.1-fabric', '26.3-fabric'],
                'adapters/fabric/shared/src/gametest/java/com/qb20nh/cbbg/gametest/ClientTestAccess.java': [],
                'src/main/resources/assets/cbbg/icon.png': ['1.21.1-fabric', '1.21.11-fabric', '26.1-fabric', '26.2-fabric', '26.3-fabric'],
                'src/main/resources/assets/cbbg/shaders/core/cbbg_dither.fsh': ['1.21.11-fabric', '26.1-fabric', '26.2-fabric']
        ]
        cases.each { String path, List<String> ids ->
            assertEquals(ids, ChangeImpact.ci(catalog, ChangeImpact.select(catalog, [path], true)).matrix.include*.id, path)
        }
        assertEquals(['1.21.1-fabric', '1.21.11-fabric', '26.1-fabric', '26.3-fabric'], ChangeImpact.ci(catalog,
                ChangeImpact.select(catalog, ['renderers/renderpearl/src/main/java/com/qb20nh/cbbg/render/FloatAttachments.java']))
                .matrix.include*.id)
    }

    @Test
    void developmentToolingSkipsTargetBuildsWithoutNarrowingReleaseChecks() {
        TargetCatalog catalog = TargetCatalog.read(new File('../targets.json'))
        for (String path : ['scripts/fabric_dependency_lock.py', 'runtime-locks/26.2-fabric-mods.json',
                '.github/workflows/codeql.yml', 'build-logic/src/test/groovy/cbbg/gradle/ChangeImpactTest.groovy']) {
            Map report = ChangeImpact.select(catalog, [path], true)
            assertFalse(ChangeImpact.ci(catalog, report).build, path)
            assertFalse(ChangeImpact.ci(catalog, report).core, path)
            assertTrue(ChangeImpact.ci(catalog, ChangeImpact.select(catalog, [path])).build, path)
        }
        for (String path : ['.github/workflows/gradle.yml', 'gradle.properties', 'build-config/shared-code.gradle',
                'build-logic/src/main/groovy/cbbg/gradle/QualityPlugin.groovy', 'unknown/file',
                '.github/new-build-config.gradle', 'scripts/generated-shader.py']) {
            assertEquals(['1.21.1-fabric', '1.21.11-fabric', '26.1-fabric', '26.2-fabric', '26.3-fabric'],
                    ChangeImpact.ci(catalog, ChangeImpact.select(catalog, [path], true)).matrix.include*.id, path)
        }
    }

    @Test
    void developmentCatalogChangesSelectOnlyChangedArtifacts() {
        git('init')
        Map data = catalogData()
        data.ciTargets << '1-forge'
        write('targets.json', JsonOutput.toJson(data))
        git('add', '.')
        git('commit', '-m', 'base')
        String base = git('rev-parse', 'HEAD')
        data.targets.find { it.id == '1-fabric' }.dependencies = [loader: 'new']
        write('targets.json', JsonOutput.toJson(data))
        git('add', '.')
        git('commit', '-m', 'head')
        Map report = ChangeImpact.compare(root, base, 'HEAD', true)
        assertEquals(['1-fabric', '1-quilt'], report.targets)
        assertEquals(['1-fabric'], ChangeImpact.ci(new TargetCatalog(data), report).matrix.include*.id)
        assertFalse(ChangeImpact.ci(new TargetCatalog(data), report).core)
        assertEquals(['1-fabric', '1-forge', '1-quilt'], ChangeImpact.compare(root, base, 'HEAD').targets)
    }

    @Test
    void onlyDocumentationAndEmptyChangesCanSkipTooling() {
        TargetCatalog catalog = TargetCatalog.read(new File('../targets.json'))
        for (List<String> paths : [[], ['README.md', 'CONTRIBUTING.md', 'docs/multiversion-plan.md']]) {
            assertEquals(false, ChangeImpact.ci(catalog, ChangeImpact.select(catalog, paths, true)).tooling)
        }
        for (List<String> paths : [['README.md', 'targets.json'], ['unknown/file'],
                                  ['core/src/test/java/ConfigTest.java'], ['.github/workflows/dev.yml']]) {
            assertEquals(true, ChangeImpact.ci(catalog, ChangeImpact.select(catalog, paths, true)).tooling)
        }
    }

    @Test
    void coreUnitTestsNeedCoreJobsButSharedGameTestSupportNeedsTargetBuilds() {
        TargetCatalog catalog = TargetCatalog.read(new File('../targets.json'))
        for (String path : ['core/src/test/java/ConfigTest.java', 'core/rendering/src/test/resources/input.png']) {
            Map plan = ChangeImpact.ci(catalog, ChangeImpact.select(catalog, [path], true))
            assertFalse(plan.build, path)
            assertTrue(plan.core, path)
        }
        Map plan = ChangeImpact.ci(catalog,
                ChangeImpact.select(catalog, ['core/src/testSupport/java/com/qb20nh/cbbg/reference/DitherReference.java'], true))
        assertTrue(plan.build)
        assertTrue(plan.core)
    }

    @Test
    void developmentCatalogChangesIncludeNewCiTargetsButIgnoreOrdering() {
        git('init')
        Map data = catalogData()
        write('targets.json', JsonOutput.toJson(data))
        git('add', '.')
        git('commit', '-m', 'base')
        String base = git('rev-parse', 'HEAD')
        data.ciTargets << '1-forge'
        write('targets.json', JsonOutput.toJson(data))
        git('add', '.')
        git('commit', '-m', 'enable forge')
        Map report = ChangeImpact.compare(root, base, 'HEAD', true)
        assertEquals(['1-forge'], report.targets)
        base = git('rev-parse', 'HEAD')
        data.ciTargets.reverse(true)
        data.targets.reverse(true)
        write('targets.json', JsonOutput.toJson(data))
        git('add', '.')
        git('commit', '-m', 'reorder')
        assertEquals([], ChangeImpact.compare(root, base, 'HEAD', true).targets)
    }

    @Test
    void baselineBeforeCatalogCreationSelectsAllCurrentTargets() {
        git('init')
        write('README.md', 'Existing mod')
        git('add', '.')
        git('commit', '-m', 'base')
        String base = git('rev-parse', 'HEAD')
        write('targets.json', JsonOutput.toJson(catalogData()))
        git('add', '.')
        git('commit', '-m', 'head')

        Map report = ChangeImpact.compare(root, base, 'HEAD')
        assertEquals(['1-fabric', '1-forge', '1-quilt'], report.targets)
        assertTrue(report.checks.contains('core-java-8-17-21-25'))
        assertTrue(ChangeImpact.ci(new TargetCatalog(catalogData()), report).build)

        write('targets.json', '{invalid')
        git('add', '.')
        git('commit', '-m', 'invalid catalog')
        String invalid = git('rev-parse', 'HEAD')
        write('targets.json', JsonOutput.toJson(catalogData()))
        git('add', '.')
        git('commit', '-m', 'valid catalog')
        assertThrows(com.google.gson.stream.MalformedJsonException) {
            ChangeImpact.compare(root, invalid, 'HEAD')
        }
    }

    @Test
    void comparesBothCatalogsAndBothSidesOfRename() {
        git('init')
        write('targets.json', JsonOutput.toJson(catalogData()))
        write('adapters/forge/Old.java', 'class Old {}')
        git('add', '.')
        git('commit', '-m', 'base')
        String base = git('rev-parse', 'HEAD')
        write('targets.json', JsonOutput.toJson(catalogData(false)))
        new File(root, 'adapters/fabric').mkdirs()
        git('mv', 'adapters/forge/Old.java', 'adapters/fabric/New.java')
        git('add', '.')
        git('commit', '-m', 'head')
        Map report = ChangeImpact.compare(root, base, 'HEAD')
        assertEquals(['1-fabric', '1-forge', '1-quilt'], report.targets)
        assertTrue(report.before*.path.contains('adapters/forge/Old.java'))
        assertTrue(report.after*.path.contains('adapters/fabric/New.java'))
        assertEquals(40, report.head.length())
        assertThrows(IllegalArgumentException) { ChangeImpact.compare(root, '--bad', 'HEAD') }
    }

    @Test
    void hotfixRequiresReleasedAffectedTargetsAndCompleteSharedArtifactSelection() {
        git('init')
        Map baseline = catalogData()
        write('targets.json', JsonOutput.toJson(baseline))
        write('adapters/fabric/Old.java', 'class Old {}')
        git('add', '.')
        git('commit', '-m', 'base')
        String base = git('rev-parse', 'HEAD')
        write('adapters/fabric/Old.java', 'class New {}')
        git('add', '.')
        git('commit', '-m', 'head')
        File manifest = candidate('candidate.json', base, baseline, ['1-fabric', '1-quilt'])
        assertTrue(assertThrows(IllegalArgumentException) {
            ChangeImpact.hotfix(root, 'HEAD', [manifest], ['1-fabric'])
        }.message.contains('Same-artifact'))
        Map result = ChangeImpact.hotfix(root, 'HEAD', [manifest], ['1-fabric', '1-quilt'])
        assertEquals(['1-fabric', '1-quilt'], result.requiredTargets)
        assertEquals(base, result.baselines[0].base)
        assertThrows(IllegalArgumentException) {
            ChangeImpact.hotfix(root, 'HEAD', [manifest, manifest], ['1-fabric', '1-quilt'])
        }
        assertThrows(IllegalArgumentException) {
            ChangeImpact.hotfix(root, 'HEAD', [manifest], ['unknown'])
        }
    }

    @Test
    void hotfixRejectsRemovedReleasedTarget() {
        git('init')
        Map baseline = catalogData()
        write('targets.json', JsonOutput.toJson(baseline))
        write('adapters/forge/Old.java', 'class Old {}')
        git('add', '.')
        git('commit', '-m', 'base')
        String base = git('rev-parse', 'HEAD')
        File manifest = candidate('prior.json', base, baseline, ['1-forge'])
        write('targets.json', JsonOutput.toJson(catalogData(false)))
        git('rm', 'adapters/forge/Old.java')
        git('add', '.')
        git('commit', '-m', 'head')
        assertTrue(assertThrows(IllegalArgumentException) {
            ChangeImpact.hotfix(root, 'HEAD', [manifest], ['1-fabric'])
        }.message.contains('omitted'))
    }

    private File candidate(String name, String commit, Map catalog, List<String> ids) {
        File file = new File(root, name)
        file.text = JsonOutput.toJson([schema: 2, release: 'v1.0.0', commit: commit,
                catalog_sha256: CandidateFiles.canonicalHash(catalog),
                selected_targets: ids, targets: ids.collect { [id: it] }])
        file
    }

    private void write(String name, String content) {
        File file = new File(root, name)
        file.parentFile.mkdirs()
        file.text = content
    }

    private String git(String... args) {
        Process process = new ProcessBuilder(['git', '-c', 'user.name=Test',
                '-c', 'user.email=test@example.invalid', '-c', 'commit.gpgsign=false'] + args.toList())
                .directory(root).start()
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        ByteArrayOutputStream errors = new ByteArrayOutputStream()
        process.waitForProcessOutput(output, errors)
        assertEquals(0, process.exitValue(), errors.toString('UTF-8'))
        output.toString('UTF-8').trim()
    }
}
