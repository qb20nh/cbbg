package cbbg.gradle

class TargetCatalog {
    final Map data

    static TargetCatalog read(File file) {
        Object parsed = CandidateFiles.read(file)
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException('Target catalog must be a JSON object')
        }
        new TargetCatalog((Map) parsed)
    }

    TargetCatalog(Map data) {
        if (!(data.schema instanceof Integer) || data.schema != 1) {
            throw new IllegalArgumentException('Unsupported target catalog schema')
        }
        if (!(data.targets instanceof List) || data.targets.isEmpty()) {
            throw new IllegalArgumentException('Target catalog must not be empty')
        }
        Set ids = [] as Set
        for (Object entry : data.targets) {
            if (!(entry instanceof Map)) {
                throw new IllegalArgumentException('Target must be an object')
            }
            Map target = (Map) entry
            String id = target.id instanceof String ? target.id : null
            if (!id) {
                throw new IllegalArgumentException('Target ID must be nonempty')
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException('Duplicate target: ' + id)
            }
            if (!(target.loader in ['fabric', 'legacy-fabric', 'forge', 'neoforge', 'quilt'])) {
                throw new IllegalArgumentException('Unknown loader: ' + id)
            }
            if (!(target.minecraft instanceof String) || id != "${target.minecraft}-${target.loader}") {
                throw new IllegalArgumentException('Inconsistent target ID: ' + id)
            }
            if (!(target.java instanceof Integer) || !(target.java in [8, 17, 21, 25])) {
                throw new IllegalArgumentException('Unsupported Java version: ' + id)
            }
            if (target.containsKey('buildJava') &&
                    (!(target.buildJava instanceof Integer) || !(target.buildJava in [8, 17, 21, 25]))) {
                throw new IllegalArgumentException('Unsupported build Java version: ' + id)
            }
            if (!(target.implemented instanceof Boolean)) {
                throw new IllegalArgumentException('Missing implementation status: ' + id)
            }
            if (!(target.renderer instanceof String) || !target.renderer) {
                throw new IllegalArgumentException('Missing renderer: ' + id)
            }
            if (target.renderer == 'classic-gl' && !(target.projectionApi in ['joml', 'mojang-math'])) {
                throw new IllegalArgumentException('Missing or unsupported classic projection API: ' + id)
            }
            if (target.buildProfile != null && !safeProfile(target.buildProfile)) {
                throw new IllegalArgumentException('Invalid build profile: ' + id)
            }
            if (target.buildProfile in ['fabric-classic', 'forge-classic'] &&
                    (!(target.sourceGroups instanceof List) || target.sourceGroups.isEmpty())) {
                throw new IllegalArgumentException('Classic build requires source groups: ' + id)
            }
            if (target.containsKey('sourceGroups') &&
                    (!(target.sourceGroups instanceof List) || target.sourceGroups.isEmpty() ||
                    target.sourceGroups.any { !safeSourcePath(it) })) {
                throw new IllegalArgumentException('Invalid source groups: ' + id)
            }
            List backends = target.backends instanceof List ? target.backends : []
            if (backends.isEmpty() || backends.any { !(it in ['opengl', 'vulkan']) } ||
                    backends.size() != backends.toSet().size()) {
                throw new IllegalArgumentException('Invalid backends: ' + id)
            }
            if (backends.contains('vulkan') && !(target.renderer in ['blaze-gpu-format', 'renderpearl'])) {
                throw new IllegalArgumentException('Renderer does not support Vulkan: ' + id)
            }
            if (target.compatibilityProfiles != null) {
                Map profiles = target.compatibilityProfiles instanceof Map ? target.compatibilityProfiles : [:]
                if (!profiles.containsKey('none')) {
                    throw new IllegalArgumentException('Compatibility profiles require a base fixture: ' + id)
                }
                for (Object item : profiles.entrySet()) {
                    Map.Entry profile = (Map.Entry) item
                    Object supported = profile.value
                    if (!(profile.key instanceof String) || !profile.key ||
                            !(supported instanceof List) || supported.isEmpty() ||
                            supported.any { !(it instanceof String) || !backends.contains(it) } ||
                            supported.size() != supported.toSet().size()) {
                        throw new IllegalArgumentException('Invalid compatibility backends: ' + id)
                    }
                }
                if (profiles.none.toSet() != backends.toSet()) {
                    throw new IllegalArgumentException('Base fixture must cover every backend: ' + id)
                }
            }
        }
        Map byId = data.targets.collectEntries { [(it.id): it] }
        for (Map target : data.targets) {
            if (target.artifactOf == null) {
                continue
            }
            Map owner = target.artifactOf instanceof String ? byId[target.artifactOf] : null
            if (owner == null || owner.is(target) || owner.artifactOf != null ||
                    target.loader != 'quilt' || owner.loader != 'fabric' ||
                    owner.minecraft != target.minecraft || owner.java > target.java ||
                    owner.renderer != target.renderer || owner.projectionApi != target.projectionApi ||
                    owner.backends.toSet() != target.backends.toSet()) {
                throw new IllegalArgumentException('Invalid shared artifact reference: ' + target.id)
            }
            if (target.implemented && !owner.implemented) {
                throw new IllegalArgumentException('Shared artifact owner is not implemented: ' + target.id)
            }
        }
        if (data.containsKey('ciTargets')) {
            Object selected = data.ciTargets
            if (!(selected instanceof List) || selected.isEmpty() ||
                    selected.any { !(it instanceof String) || !ids.contains(it) } ||
                    selected.size() != selected.toSet().size()) {
                throw new IllegalArgumentException('CI targets must be known, unique target IDs')
            }
        }
        this.data = data
    }

    private static boolean safeProfile(Object value) {
        value instanceof String && value ==~ /[A-Za-z0-9][A-Za-z0-9-]*/
    }

    private static boolean safeSourcePath(Object value) {
        if (!(value instanceof String) || !value || value.contains('\\')) {
            return false
        }
        String path = (String) value
        if (path.startsWith('/') || path ==~ /^[A-Za-z]:.*/) {
            return false
        }
        List<String> parts = []
        for (String part : path.split('/')) {
            if (part == '..') {
                if (parts.isEmpty()) return false
                parts.remove(parts.size() - 1)
            } else if (part && part != '.') {
                parts.add(part)
            }
        }
        !parts.isEmpty()
    }

    List<Map> select(String selection = null, boolean requireImplemented = false) {
        List<Map> targets = (List<Map>) data.targets
        if (selection != null) {
            List<String> requested = selection.split(',', -1).toList()
            if (requested.any { !it } || requested.size() != requested.toSet().size()) {
                throw new IllegalArgumentException('Selection must contain unique, nonempty target IDs')
            }
            Set unknown = requested.toSet() - targets.collect { it.id }.toSet()
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException('Unknown targets: ' + unknown.sort().join(', '))
            }
            targets = targets.findAll { requested.contains(it.id) }
        }
        if (requireImplemented) {
            List pending = targets.findAll { !it.implemented }.collect { it.id }
            if (!pending.isEmpty()) {
                throw new IllegalArgumentException('Targets not implemented: ' + pending.join(', '))
            }
        }
        targets
    }

    List<Map> selectProfile(String profile, boolean requireImplemented = false) {
        List<String> ids = data.targets.findAll { it.buildProfile == profile }.collect { it.id }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException('Unknown or empty build profile: ' + profile)
        }
        select(ids.join(','), requireImplemented)
    }

    List<Map> artifacts(String selection = null, boolean requireImplemented = false) {
        Map byId = data.targets.collectEntries { [(it.id): it] }
        List<Map> owners = []
        Set seen = [] as Set
        for (Map target : select(selection, requireImplemented)) {
            Map owner = byId[target.artifactOf ?: target.id]
            if (requireImplemented && !owner.implemented) {
                throw new IllegalArgumentException('Artifact owner not implemented: ' + owner.id)
            }
            if (seen.add(owner.id)) owners.add(owner)
        }
        owners
    }

    List<Map> matrix(String selection = null, boolean requireImplemented = false) {
        List<Map> runtimes = select(selection, requireImplemented)
        artifacts(selection, requireImplemented).collect { Map owner ->
            if (!owner.buildProfile) {
                throw new IllegalArgumentException('No build profile configured for ' + owner.id)
            }
            Map row = owner.subMap(['id', 'minecraft', 'loader', 'java', 'renderer', 'buildProfile'])
            row.runtimeTargets = runtimes.findAll { (it.artifactOf ?: it.id) == owner.id }.collect { it.id }
            row
        }
    }

    List<Map> defaults(boolean requireImplemented = false) {
        if (!data.ciTargets) {
            throw new IllegalArgumentException('No CI targets configured')
        }
        select(data.ciTargets.join(','), requireImplemented)
    }

    Map<String, Map> releaseTargets(List<String> selection) {
        if (!selection || selection.any { !(it instanceof String) || !it } ||
                selection.size() != selection.toSet().size()) {
            throw new IllegalArgumentException('Invalid explicit target selection')
        }
        def selected = select(selection.join(','))
        data.targets.findAll { it.artifactOf }.each { target ->
            if ((selection.contains(target.id) && !selection.contains(target.artifactOf)) ||
                    (selection.contains(target.artifactOf) && target.implemented && !selection.contains(target.id))) {
                throw new IllegalArgumentException('Selection omits a shared-artifact runtime: ' + target.id)
            }
        }
        selected.collectEntries { [(it.id): it] }
    }
}
