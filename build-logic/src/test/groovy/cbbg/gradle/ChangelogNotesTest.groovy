package cbbg.gradle

import org.gradle.api.GradleException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import static org.junit.jupiter.api.Assertions.*

class ChangelogNotesTest {
    @TempDir File directory
    Map target = [minecraft: '26.3', loader: 'fabric']

    private File changelog(String content) {
        File file = new File(directory, 'CHANGELOG.md')
        file.setText(content, 'UTF-8')
        file
    }

    @Test void selectsTheTargetIdentifierAndExcludesOtherReleases() {
        File file = changelog('''# Changelog
## [Unreleased]
## [1.4.1 for Minecraft 26.3 NeoForge] - 2026-09-27 <!-- [1.4.1-mc26.3-neoforge] -->
Other loader.
## [1.4.1 for Minecraft 26.3 Fabric] - 2026-09-27 <!-- [1.4.1-mc26.3-fabric] -->

### Added

- Minecraft 26.3 support.

## [1.4.0] - 2026-09-22
Previous release.
''')
        assertEquals('### Added\n\n- Minecraft 26.3 support.\n', ChangelogNotes.select(file, '1.4.1', target))
    }

    @Test void theVisibleTitleCanChangeWithoutChangingSelection() {
        assertEquals('Notes.\n', ChangelogNotes.select(changelog(
                '## [A readable title] - 2026-09-27 <!-- [1.4.1-mc26.3-fabric] -->\nNotes.\n'), '1.4.1', target))
    }

    @Test void patchAliasesUseTheArtifactOwnersChangelogEntry() {
        File file = changelog('## [Release] <!-- [1.4.1-mc26.1-fabric] -->\n#### Minecraft 26.1.2 — Fabric\n- Patch fix.\n')
        List<Map> targets = [[id: '26.1-fabric', minecraft: '26.1', loader: 'fabric'],
                             [id: '26.1.2-fabric', minecraft: '26.1.2', loader: 'fabric', artifactOf: '26.1-fabric']]
        assertEquals('#### Minecraft 26.1.2 — Fabric\n- Patch fix.\n',
                ChangelogNotes.select(file, '1.4.1', targets))
    }

    @Test void supportsHistoricalVersionHeadings() {
        assertEquals('Historical notes.\n', ChangelogNotes.select(changelog(
                '## [1.4.0] - 2026-09-22\nHistorical notes.\n'), '1.4.0', target))
    }

    @Test void crossVersionQuiltAliasesUseTheSelectedArtifactOwnersEntry() {
        File file = changelog('## [Release] <!-- [1.4.1-mc26.1-fabric] -->\n#### Minecraft 26.1.2 — Quilt\n- Quilt fix.\n')
        List<Map> targets = [[id: '26.1-fabric', minecraft: '26.1', loader: 'fabric'],
                             [id: '26.1.2-quilt', minecraft: '26.1.2', loader: 'quilt', artifactOf: '26.1-fabric']]
        assertEquals('#### Minecraft 26.1.2 — Quilt\n- Quilt fix.\n',
                ChangelogNotes.select(file, '1.4.1', targets))
    }

    @Test void sameVersionQuiltAliasesKeepTheirOwnEntryIdentifier() {
        File file = changelog('## [Release] <!-- [1.4.1-mc26.3-fabric] [1.4.1-mc26.3-quilt] -->\n- Shared fix.\n')
        List<Map> targets = [[id: '26.3-fabric', minecraft: '26.3', loader: 'fabric'],
                             [id: '26.3-quilt', minecraft: '26.3', loader: 'quilt', artifactOf: '26.3-fabric']]
        assertEquals('- Shared fix.\n', ChangelogNotes.select(file, '1.4.1', targets))
        assertThrows(GradleException) {
            ChangelogNotes.select(changelog('## [Release] <!-- [1.4.1-mc26.3-fabric] -->\n- Fabric fix.\n'),
                    '1.4.1', targets)
        }
    }

    @Test void rejectsMissingDuplicateAndEmptyEntries() {
        String heading = '## [Release] - 2026-09-27 <!-- [1.4.1-mc26.3-fabric] -->\n'
        for (String content : ['## [1.4.2]\nOther release.\n', heading,
                               heading + 'First.\n' + heading + 'Second.\n']) {
            assertThrows(GradleException) { ChangelogNotes.select(changelog(content), '1.4.1', target) }
        }
    }

    @Test void rejectsASectionForAnotherLoaderOrMinecraftVersion() {
        for (String identifier : ['1.4.1-mc26.3-quilt', '1.4.1-mc26.2-fabric']) {
            assertThrows(GradleException) {
                ChangelogNotes.select(changelog('## [Release] <!-- [' + identifier + '] -->\nNotes.\n'), '1.4.1', target)
            }
        }
    }

    @Test void selectsSharedAndApplicableChangesFromOneRelease() {
        File file = changelog('''## [1.5.0] - 2026-10-01 <!-- [1.5.0-mc26.3-fabric] [1.5.0-mc26.2-neoforge] -->
### Added

- Shared feature.

#### Minecraft 26.3

- Version feature.

#### Fabric

- Loader feature.

#### Minecraft 26.2 — NeoForge

- Other target feature.

### Fixed

#### NeoForge

- Other loader fix.
''')
        String notes = ChangelogNotes.select(file, '1.5.0', target)
        assertTrue(notes.contains('Shared feature.'))
        assertTrue(notes.contains('Version feature.'))
        assertTrue(notes.contains('Loader feature.'))
        assertFalse(notes.contains('Other target'))
        assertFalse(notes.contains('Other loader'))
        assertFalse(notes.contains('### Fixed'))
        String shared = ChangelogNotes.select(file, '1.5.0', [target, [minecraft: '26.2', loader: 'neoforge']])
        assertTrue(shared.contains('Other target feature.'))
        assertTrue(shared.contains('Other loader fix.'))
        assertEquals(1, shared.count('Shared feature.'))
    }

    @Test void doesNotUseASharedEntryForAnUnlistedTarget() {
        assertThrows(GradleException) {
            ChangelogNotes.select(changelog('''## [1.5.0] <!-- [1.5.0-mc26.2-fabric] -->
### Added
- Another target.
'''), '1.5.0', target)
        }
    }

    @Test void groupedVersionsSelectEachApplicableTargetOnce() {
        String body = '''### Fixed
- Shared fix.
#### Minecraft 1.21.1, 1.21.11
- Older-version fix.
#### Minecraft 1.21.11, 26.1, 26.1.1, 26.1.2, 26.2
- Overlapping fix.
#### Minecraft 26.2, 26.3 — Fabric
- Fabric fix.
'''
        List<Map> cases = [
                [minecraft: '1.21.1', loader: 'fabric', expected: ['Older-version fix.']],
                [minecraft: '1.21.11', loader: 'fabric', expected: ['Older-version fix.', 'Overlapping fix.']],
                [minecraft: '26.1', loader: 'fabric', expected: ['Overlapping fix.']],
                [minecraft: '26.1.1', loader: 'fabric', expected: ['Overlapping fix.']],
                [minecraft: '26.1.2', loader: 'fabric', expected: ['Overlapping fix.']],
                [minecraft: '26.2', loader: 'fabric', expected: ['Overlapping fix.', 'Fabric fix.']],
                [minecraft: '26.3', loader: 'fabric', expected: ['Fabric fix.']],
                [minecraft: '26.2', loader: 'neoforge', expected: ['Overlapping fix.']],
                [minecraft: '26.3', loader: 'neoforge', expected: []]
        ]
        cases.each { selected ->
            String notes = ChangelogNotes.forTargets(body, [selected])
            assertTrue(notes.contains('Shared fix.'), selected.toString())
            ['Older-version fix.', 'Overlapping fix.', 'Fabric fix.'].each { change ->
                assertEquals(selected.expected.contains(change), notes.contains(change), selected.toString())
            }
        }
        String shared = ChangelogNotes.forTargets(body, cases)
        ['Shared fix.', 'Older-version fix.', 'Overlapping fix.', 'Fabric fix.'].each { change ->
            assertEquals(1, shared.count(change))
        }
    }

    @Test void rejectsMalformedGroupedScopes() {
        for (String scope : ['Minecraft 26.2,', 'Minecraft 26.2,, 26.3',
                             'Minecraft 26.2, Fabric', 'Minecraft 26.2, 26.3 — Fabrci']) {
            assertThrows(GradleException) { ChangelogNotes.forTargets('#### ' + scope + '\n- Fix.\n', [target]) }
        }
    }

    @Test void requiresMultiTargetSelectionToReferToOneRelease() {
        assertThrows(GradleException) {
            ChangelogNotes.select(changelog('''## [Fabric] <!-- [1.5.0-mc26.3-fabric] -->
- Fabric change.
## [NeoForge] <!-- [1.5.0-mc26.3-neoforge] -->
- NeoForge change.
'''), '1.5.0', [target, [minecraft: '26.3', loader: 'neoforge']])
        }
    }

    @Test void ignoresHeadingsInsideCodeBlocks() {
        String body = '''### Fixed

- Shared fix.

```md
## [Another release]
#### Unknown heading
```

#### Minecraft 26.3 — FABRIC

- Target fix.
'''
        assertEquals(body, ChangelogNotes.select(changelog(
                '## [Release] <!-- [1.5.0-mc26.3-fabric] -->\n' + body), '1.5.0', target))
    }

    @Test void rejectsUnknownScopesAndEmptyApplicableNotes() {
        for (String body : ['#### Fabrci\n- Misspelled loader.\n',
                             '### Fixed\n#### NeoForge\n- Other loader.\n',
                             '### Fixed\n#### Fabric\n']) {
            assertThrows(GradleException) { ChangelogNotes.forTargets(body, [target]) }
        }
    }

    @Test void sharedArtifactNotesIncludeEachSelectedLoader() {
        String body = '''### Fixed
- Shared fix.
#### Fabric
- Fabric fix.
#### Quilt
- Quilt fix.
#### Forge
- Forge fix.
'''
        String result = ChangelogNotes.forTargets(body, [target, [minecraft: '26.3', loader: 'quilt']])
        assertTrue(result.contains('Fabric fix.'))
        assertTrue(result.contains('Quilt fix.'))
        assertFalse(result.contains('Forge fix.'))
    }

    @Test void recognizesLegacyFabricScope() {
        assertEquals('#### Legacy Fabric\n- Legacy fix.\n', ChangelogNotes.forTargets(
                '#### Legacy Fabric\n- Legacy fix.\n', [[minecraft: '1.7.10', loader: 'legacy-fabric']]))
    }
}
