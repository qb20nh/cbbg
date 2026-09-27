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

    @Test void supportsHistoricalVersionHeadings() {
        assertEquals('Historical notes.\n', ChangelogNotes.select(changelog(
                '## [1.4.0] - 2026-09-22\nHistorical notes.\n'), '1.4.0', target))
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
}
