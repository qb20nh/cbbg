package cbbg.gradle

import java.nio.charset.StandardCharsets

class ChangeImpact {
    static Map select(TargetCatalog catalog, List<String> paths, boolean development = false) {
        List<Map> targets = (List<Map>) catalog.data.targets
        Set<String> allIds = targets.collect { it.id as String } as Set
        Map<String, Map> byId = targets.collectEntries { [(it.id as String): it] }
        Map<String, String> owners = targets.collectEntries {
            [(it.id as String): (it.artifactOf ?: it.id) as String]
        }
        Map<String, Map> layouts = development ? owners.values().toSet().collectEntries { String id ->
            [(id): FabricSources.layout(byId[id], byId[id])]
        } : [:]
        Set<String> affected = [] as Set
        Set<String> checks = [] as Set
        List<Map> changes = []
        for (String path : paths.toSet().sort()) {
            if (!path || path.startsWith('/') || path.contains('\\') ||
                    path.split('/').contains('..')) {
                throw new IllegalArgumentException('Expected a repository-relative path: ' + path)
            }
            Set<String> selected = [] as Set
            String reason
            if (path.startsWith('docs/') || path in ['README.md', 'CHANGELOG.md'] ||
                    development && path in ['CONTRIBUTING.md', 'SECURITY.md', 'build-config/README.md']) {
                reason = 'documentation'
                checks.add('documentation')
            } else if (development && developmentTooling(path)) {
                reason = 'development tooling; checked in the planning job'
                checks.add('tooling')
            } else if (development && path ==~ /core\/(legacy\/|rendering\/)?src\/test\/.+/) {
                reason = 'core tests'
                checks.add('core-java-8-17-21-25')
            } else if (path.startsWith('build-config/publishing/') ||
                    path ==~ /build-logic\/src\/(main|test)\/groovy\/cbbg\/gradle\/(Publication|LegacyPublication|ReleaseChecks|ReleasePlugin)(Test)?\.groovy/ ||
                    path.startsWith('.github/scripts/') || path.startsWith('.github/tests/')) {
                reason = 'publication tooling'
                checks.add('publication')
            } else {
                checks.addAll(['catalog', 'compile', 'unit', 'runtime'])
                if (path.startsWith('core/') || path.startsWith('libraries/utilities/')) {
                    reason = 'shared core'
                    selected.addAll(allIds)
                    checks.add('core-java-8-17-21-25')
                } else {
                    boolean knownSource = false
                    for (Map target : targets) {
                        Map owner = byId[owners[target.id]]
                        Map layout = layouts[owner.id]
                        List<Map> inputs = layout ? FabricSources.inputs(layout) : []
                        if (inputs.any { it.file ? path == it.path : path.startsWith(it.path + '/') }) {
                            knownSource = true
                        }
                        if (inputs.any { FabricSources.contains(it, path) }) selected.add(target.id as String)
                        List<String> prefixes = layout ? [] :
                                ((owner.sourceGroups ?: []) + ["renderers/${owner.renderer}"])
                        if (owner.buildProfile) prefixes += "build-config/${owner.buildProfile}"
                        if (prefixes.any { path.startsWith(it + '/') }) selected.add(target.id as String)
                    }
                    if (selected || knownSource) {
                        selected.addAll(targets.findAll {
                            !layouts[owners[it.id]] && !byId[owners[it.id]].sourceGroups
                        }
                                .collect { it.id as String })
                        reason = development ? 'build source selections; includes targets with undeclared sources' :
                                'catalog dependencies; includes targets with undeclared sources'
                    } else {
                        selected.addAll(allIds)
                        reason = 'unknown dependency; all targets selected'
                        checks.add('core-java-8-17-21-25')
                    }
                }
                Set changedOwners = selected.collect { owners[it] } as Set
                selected.addAll(owners.findAll { id, owner -> owner in changedOwners }.keySet())
            }
            affected.addAll(selected)
            changes.add([path: path, reason: reason, targets: selected.sort()])
        }
        [changes: changes, targets: affected.sort(), checks: checks.sort()]
    }

    private static boolean developmentTooling(String path) {
        path.startsWith('scripts/tests/') || path.startsWith('runtime-locks/') ||
                path.startsWith('build-logic/src/test/') || path.startsWith('.github/scripts/') ||
                path.startsWith('.github/tests/') || path.startsWith('.github/ISSUE_TEMPLATE/') ||
                path in ['scripts/candidate_manifest.py', 'scripts/fabric_acceptance.py',
                         'scripts/fabric_dependency_lock.py', 'scripts/fabric_parity_runtime.py',
                         'scripts/fabric_run_evidence.py', 'scripts/fabric_runtime_lock.py',
                         'scripts/fabric_scenario_evidence.py', 'scripts/install_fabric_parity_runtime.py',
                         'scripts/parity_evidence.py', 'scripts/runtime_catalog.py',
                         'build-logic/src/main/groovy/cbbg/gradle/CodeqlScan.groovy',
                         '.github/FUNDING.yml', '.github/dependabot.yml', '.github/DEPENDABOT_BATCH.md'] ||
                path ==~ /\.github\/workflows\/(codeql|osv-scanner|scorecard|dev|release|publish|dependabot-major-batch)\.yml/
    }

    static Map ci(TargetCatalog catalog, Map report) {
        List<String> selected = catalog.defaults().collect { it.id as String }
                .findAll { it in report.targets }
        List<Map> rows = selected ? catalog.matrix(selected.join(',')) : []
        [matrix: [include: rows], build: !rows.isEmpty(),
         core: 'core-java-8-17-21-25' in report.checks,
         tooling: report.checks.any { it != 'documentation' }]
    }

    static Map compare(File root, String base, String head, boolean development = false) {
        String baseCommit = resolve(root, base)
        String headCommit = resolve(root, head)
        compareResolved(root, baseCommit, headCommit, development)
    }

    static Map hotfix(File root, String head, List<File> previousManifests, List<String> selection) {
        String headCommit = resolve(root, head)
        TargetCatalog current = catalogAt(root, headCommit)
        Set<String> known = current.select()*.id as Set
        if (!selection || selection.any { !(it instanceof String) || !it } ||
                selection.size() != selection.toSet().size()) {
            throw new IllegalArgumentException('Hotfix selection must contain unique target IDs')
        }
        Set<String> selected = selection as Set
        Set<String> unknown = selected - known
        if (unknown) throw new IllegalArgumentException('Unknown hotfix targets: ' + unknown.sort().join(', '))
        if (!previousManifests) throw new IllegalArgumentException('Previous candidate manifests are required')

        Set<String> released = [] as Set
        List<Map> baselines = []
        for (File file : previousManifests) {
            Object parsed = CandidateFiles.read(file)
            if (!(parsed instanceof Map) || !(parsed.schema in [2, 3]) ||
                    !(parsed.release instanceof String) || !(parsed.commit instanceof String)) {
                throw new IllegalArgumentException('Expected a schema 2 or 3 candidate manifest: ' + file)
            }
            Map manifest = (Map) parsed
            CandidateFiles.releaseIdentity(manifest.release, manifest.commit)
            if (!(manifest.selected_targets instanceof List) || !manifest.selected_targets ||
                    manifest.selected_targets.any { !(it instanceof String) || !it } ||
                    manifest.selected_targets.size() != manifest.selected_targets.toSet().size() ||
                    !(manifest.targets instanceof List) ||
                    manifest.targets.any { !(it instanceof Map) || !(it.id instanceof String) } ||
                    manifest.targets*.id.size() != manifest.targets*.id.toSet().size() ||
                    manifest.selected_targets.toSet() != manifest.targets*.id.toSet()) {
                throw new IllegalArgumentException('Candidate selected_targets differ from targets: ' + file)
            }
            String baseCommit = resolve(root, manifest.commit)
            TargetCatalog previous = catalogAt(root, baseCommit)
            Set<String> previousIds = previous.select()*.id as Set
            if (!(previousIds.containsAll(manifest.selected_targets))) {
                throw new IllegalArgumentException('Candidate target absent from baseline catalog: ' + file)
            }
            if (manifest.catalog_sha256 != CandidateFiles.canonicalHash(previous.data)) {
                throw new IllegalArgumentException('Candidate catalog hash differs from baseline: ' + file)
            }
            Set<String> duplicate = released.intersect(manifest.selected_targets as Set)
            if (duplicate) throw new IllegalArgumentException('Duplicate target baseline: ' + duplicate.sort().join(', '))
            released.addAll(manifest.selected_targets)
            Map report = compareResolved(root, baseCommit, headCommit)
            Set<String> affected = (report.targets as Set).intersect(manifest.selected_targets as Set)
            baselines.add([release: manifest.release, base: baseCommit,
                           releasedTargets: manifest.selected_targets, affectedTargets: affected.sort(),
                           report: report])
            requireSharedRuntimes(previous, selected, manifest.selected_targets as Set)
        }
        requireSharedRuntimes(current, selected, released)
        Set<String> required = baselines.collectMany { it.affectedTargets } as Set
        Set<String> omitted = required - selected
        if (omitted) throw new IllegalArgumentException('Affected released targets omitted: ' + omitted.sort().join(', '))
        [head: headCommit, selection: selection, requiredTargets: required.sort(), baselines: baselines]
    }

    private static void requireSharedRuntimes(TargetCatalog catalog, Set<String> selected, Set<String> released) {
        Map<String, Set<String>> groups = [:].withDefault { [] as Set }
        for (Map target : catalog.select()) {
            if (target.id in released) groups[(target.artifactOf ?: target.id) as String].add(target.id as String)
        }
        for (Set<String> group : groups.values()) {
            if (group.intersect(selected) && !selected.containsAll(group)) {
                throw new IllegalArgumentException('Same-artifact runtime selection omits: ' +
                        (group - selected).sort().join(', '))
            }
        }
    }

    private static Map compareResolved(File root, String base, String head, boolean development = false) {
        byte[] changed = git(root, ['diff', '--name-only', '--no-renames', '-z', base, head, '--'])
        List<String> paths = changed.length ? new String(changed, StandardCharsets.UTF_8)
                .split('\\u0000', -1).findAll { it } : []
        TargetCatalog current = catalogAt(root, head)
        boolean previousCatalogExists = git(root, ['ls-tree', '--name-only', base, '--', 'targets.json']).length > 0
        TargetCatalog previous = previousCatalogExists ? catalogAt(root, base) : null
        List<String> sourcePaths = development && previousCatalogExists ?
                paths.findAll { it != 'targets.json' } : paths
        Map before
        if (previousCatalogExists) {
            before = select(previous, sourcePaths, development)
        } else {
            before = select(current, ['targets.json'], development)
            before.changes[0].reason = 'baseline predates the target catalog; all current targets selected'
        }
        Map after = select(current, sourcePaths, development)
        if (development && previousCatalogExists && 'targets.json' in paths) {
            Set<String> changedIds = changedCatalogTargets(previous, current)
            for (Map pair : [[catalog: previous, report: before], [catalog: current, report: after]]) {
                List<Map> entries = pair.catalog.data.targets
                Set<String> changedOwners = entries.findAll { it.id in changedIds }
                        .collect { (it.artifactOf ?: it.id) as String } as Set
                List<String> affected = entries.findAll { (it.artifactOf ?: it.id) in changedOwners }*.id.sort()
                pair.report.targets = ((pair.report.targets + affected) as Set).sort()
                pair.report.checks = ((pair.report.checks + ['catalog']) as Set).sort()
                pair.report.changes << [path: 'targets.json', reason: 'changed catalog targets', targets: affected]
            }
        }
        [base: base, head: head, targets: ((before.targets + after.targets) as Set).sort(),
         checks: ((before.checks + after.checks) as Set).sort(),
         before: before.changes, after: after.changes]
    }

    private static Set<String> changedCatalogTargets(TargetCatalog before, TargetCatalog after) {
        Map previous = before.data.targets.collectEntries { [(it.id): it] }
        Map current = after.data.targets.collectEntries { [(it.id): it] }
        Set<String> ids = (previous.keySet() + current.keySet()) as Set
        if (before.data.findAll { !(it.key in ['targets', 'ciTargets']) } !=
                after.data.findAll { !(it.key in ['targets', 'ciTargets']) }) return ids
        Set<String> changed = ids.findAll { previous[it] != current[it] } as Set
        Set oldCi = before.defaults()*.id as Set
        Set newCi = after.defaults()*.id as Set
        changed + (oldCi - newCi) + (newCi - oldCi)
    }

    private static TargetCatalog catalogAt(File root, String commit) {
        Object parsed = CandidateFiles.parse(new InputStreamReader(
                new ByteArrayInputStream(git(root, ['show', commit + ':targets.json'])), StandardCharsets.UTF_8))
        if (!(parsed instanceof Map)) throw new IllegalArgumentException('Target catalog must be an object at ' + commit)
        new TargetCatalog((Map) parsed)
    }

    private static String resolve(File root, String revision) {
        if (!revision) throw new IllegalArgumentException('Missing Git revision')
        String commit = new String(git(root, ['rev-parse', '--verify', '--end-of-options',
                revision + '^{commit}']), StandardCharsets.UTF_8).trim()
        if (!(commit ==~ /[0-9a-f]{40}/)) throw new IllegalArgumentException('Invalid Git commit: ' + revision)
        commit
    }

    private static byte[] git(File root, List<String> args) {
        Process process = new ProcessBuilder(['git'] + args).directory(root).start()
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        ByteArrayOutputStream errors = new ByteArrayOutputStream()
        process.waitForProcessOutput(output, errors)
        if (process.exitValue() != 0) {
            throw new IllegalArgumentException('Git ' + args[0] + ' failed: ' +
                    errors.toString('UTF-8').trim())
        }
        output.toByteArray()
    }
}
