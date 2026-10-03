package cbbg.gradle

import org.gradle.api.GradleException
import java.util.regex.Pattern

class ChangelogNotes {
    static String select(File changelog, String version, Map target) {
        select(changelog, version, [target])
    }

    static String select(File changelog, String version, List<Map> targets) {
        if (!targets) throw new GradleException('Release notes require selected targets')
        List<String> lines = changelog.readLines('UTF-8')
        List<Integer> levels = headingLevels(lines)
        List<Integer> headings = (0..<lines.size()).findAll { levels[it] == 2 }
        Set<Integer> selected = []
        targets.each { target ->
            Map owner = target.artifactOf ? targets.find { it.id == target.artifactOf } : null
            Map identity = owner && (target.loader == 'fabric' ||
                    (target.loader == 'quilt' && target.minecraft != owner.minecraft)) ? owner : target
            String identifier = version + '-mc' + identity.minecraft + '-' + identity.loader
            List<Integer> matches = headings.findAll { identifiers(lines[it]).contains(identifier) }
            if (matches.isEmpty()) {
                matches = headings.findAll {
                    !lines[it].contains('<!--') &&
                            lines[it] ==~ /## \[${Pattern.quote(version)}\](?:\s+-\s+\d{4}-\d{2}-\d{2})?\s*/
                }
            }
            if (matches.size() != 1) throw new GradleException('Expected one changelog entry for ' + identifier)
            selected.add(matches.first())
        }
        if (selected.size() != 1) {
            throw new GradleException('Selected targets must share one changelog entry')
        }
        int start = selected.first() + 1
        int end = headings.find { it >= start } ?: lines.size()
        forTargets(lines.subList(start, end).join('\n'), targets)
    }

    static String forTargets(String notes, List<Map> targets) {
        if (!targets) throw new GradleException('Release notes require selected targets')
        List<String> lines = notes.readLines()
        List<Integer> levels = headingLevels(lines)
        List<String> filtered = []
        boolean include = true
        for (int i = 0; i < lines.size(); i++) {
            if (levels[i] in [1, 2, 3]) include = true
            if (levels[i] == 4) {
                Map scope = scope(lines[i].substring(5).replaceFirst(/\s+#+\s*$/, '').trim())
                include = targets.any { target ->
                    (!scope.minecraft || scope.minecraft == target.minecraft) &&
                            (!scope.loader || scope.loader == target.loader)
                }
            }
            if (include) filtered.add(lines[i])
        }
        List<Integer> filteredLevels = headingLevels(filtered)
        List<String> result = []
        int i = 0
        while (i < filtered.size()) {
            int level = filteredLevels[i]
            if (level in [3, 4]) {
                int end = (i + 1..<filtered.size()).find {
                    filteredLevels[it] > 0 && filteredLevels[it] <= level
                } ?: filtered.size()
                if (!hasContent(filtered, filteredLevels, i + 1, end)) { i = end; continue }
            }
            result.add(filtered[i++])
        }
        if (!hasContent(result, headingLevels(result), 0, result.size())) {
            throw new GradleException('Changelog entry is empty for the selected targets')
        }
        result.join('\n').trim() + '\n'
    }

    private static boolean hasContent(List<String> lines, List<Integer> levels, int start, int end) {
        (start..<end).any { levels[it] == 0 && lines[it].trim() && !(lines[it].trim() ==~ /<!--.*-->/) }
    }

    private static Map scope(String title) {
        Map loaders = ['fabric': 'fabric', 'quilt': 'quilt', 'forge': 'forge',
                       'neoforge': 'neoforge', 'legacy fabric': 'legacy-fabric']
        def version = title =~ /(?i)^Minecraft ([0-9][0-9A-Za-z._-]*)(?:\s+[—–-]\s+(.+))?$/
        if (version.matches()) {
            String label = version[0][2]
            String loader = label == null ? null : loaders[label.toLowerCase(Locale.ROOT)]
            if (label != null && loader == null) throw new GradleException('Unknown changelog loader: ' + label)
            return [minecraft: version[0][1], loader: loader]
        }
        String loader = loaders[title.toLowerCase(Locale.ROOT)]
        if (loader == null) throw new GradleException('Expected a Minecraft version or loader scope: ' + title)
        [loader: loader]
    }

    private static List<String> identifiers(String heading) {
        def comment = heading =~ /<!--(.*?)-->\s*$/
        if (!comment.find()) return []
        (comment.group(1) =~ /\[([^\]]+)\]/).collect { it[1] }
    }

    private static List<Integer> headingLevels(List<String> lines) {
        String fence = null
        List<Integer> levels = []
        lines.each { line ->
            def marker = line =~ /^ {0,3}(`{3,}|~{3,})(.*)$/
            if (fence != null) {
                if (marker.matches() && marker[0][1][0] == fence[0] &&
                        marker[0][1].length() >= fence.length() && !marker[0][2].trim()) fence = null
                levels.add(0)
            } else if (marker.matches()) {
                fence = marker[0][1]
                levels.add(0)
            } else {
                def heading = line =~ /^(#{1,6})\s+.+$/
                levels.add(heading.matches() ? heading[0][1].length() : 0)
            }
        }
        levels
    }
}
