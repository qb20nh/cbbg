package cbbg.gradle

import org.gradle.api.GradleException
import java.util.regex.Pattern

class ChangelogNotes {
    static String select(File changelog, String version, Map target) {
        String identifier = version + '-mc' + target.minecraft + '-' + target.loader
        List<String> lines = changelog.readLines('UTF-8')
        List<Integer> headings = (0..<lines.size()).findAll { lines[it].startsWith('## ') }
        List<Integer> matches = headings.findAll {
            lines[it] ==~ /.*<!--\s*\[${Pattern.quote(identifier)}\]\s*-->\s*/
        }
        if (matches.isEmpty()) {
            matches = headings.findAll {
                lines[it] ==~ /## \[${Pattern.quote(version)}\](?:\s+-\s+\d{4}-\d{2}-\d{2})?\s*/
            }
        }
        if (matches.size() != 1) {
            throw new GradleException('Expected one changelog entry for ' + identifier)
        }
        int start = matches.first() + 1
        int end = headings.find { it >= start } ?: lines.size()
        String notes = lines.subList(start, end).join('\n').trim()
        if (!notes) throw new GradleException('Changelog entry is empty: ' + identifier)
        notes + '\n'
    }
}
