package cbbg.gradle

import org.gradle.api.GradleException

/** The shared tag grammar and product identity for all release consumers. */
class ReleaseIdentity {
    private static final String NUMBER = '(?:0|[1-9][0-9]*)'
    private static final String SUFFIX = '(?:-[A-Za-z][0-9A-Za-z-]*(?:\\.[A-Za-z][0-9A-Za-z-]*)*\\.[1-9][0-9]*)?'
    private static final String VERSION = NUMBER + '\\.' + NUMBER + '\\.' + NUMBER + SUFFIX
    private static final String MINECRAFT = '[0-9][0-9A-Za-z-]*(?:\\.[0-9A-Za-z-]+)*'
    private static final List<String> LOADERS = ['legacy-fabric', 'neoforge', 'fabric', 'quilt', 'forge']
    private static final String LOADER = '(?:' + LOADERS.join('|') + ')'

    final String tag
    final String product
    final String version
    final String minecraft
    final String loader

    private ReleaseIdentity(String tag, String product, String version, String minecraft, String loader) {
        this.tag = tag
        this.product = product
        this.version = version
        this.minecraft = minecraft
        this.loader = loader
    }

    static ReleaseIdentity parse(String tag) {
        def match = tag == null ? null : (tag =~ ('^(lib/)?v(' + VERSION + ')(?:\\+mc(' + MINECRAFT + ')-(' + LOADER + '))?$'))
        if (match == null || !match.matches()) {
            throw new GradleException("Invalid release tag '${tag}'. Use v<major>.<minor>.<patch> for CBBG or lib/v<major>.<minor>.<patch> for CBBG Lib. Append +mc<minecraft>-<loader> for one artifact owner, e.g. v1.4.2+mc26.2-fabric or lib/v1.0.0+mc26.2-fabric.")
        }
        String minecraft = match.group(3)
        String loader = match.group(4)
        if (minecraft != null) {
            String scope = minecraft + '-' + loader
            // Prefer the longest known loader suffix over a hyphen inside Minecraft metadata.
            loader = LOADERS.find { scope.endsWith('-' + it) }
            minecraft = scope.substring(0, scope.length() - loader.length() - 1)
        }
        new ReleaseIdentity(tag, match.group(1) == null ? 'cbbg' : 'lib', match.group(2), minecraft, loader)
    }

    boolean isTargeted() { minecraft != null }
    boolean isPrerelease() { version.contains('-') }
    String getTagPrefix() { product == 'lib' ? 'lib/v' : 'v' }
    String getDisplayName() { product == 'lib' ? 'CBBG Lib' : 'CBBG' }
    String getChangelogPath() { product == 'lib' ? 'libraries/CHANGELOG.md' : 'CHANGELOG.md' }
    String getReleaseTitle() { (product == 'lib' ? displayName : 'cbbg') + ' ' + version }

    String getLibraryRequirement() {
        requireProduct('lib')
        '>=' + version + ' <2.0.0'
    }

    void requireProduct(String expected) {
        if (product != expected) {
            throw new GradleException("Release tag '${tag}' belongs to product '${product}', but this operation requires '${expected}'. Use ${expected == 'lib' ? 'lib/v' : 'v'}<version> for that product.")
        }
    }

    void requireTargets(Collection<Map> targets) {
        if (!targeted) return
        Set owners = targets.collect { it.artifactOf ?: (it.id ?: "${it.minecraft}-${it.loader}") } as Set
        Map owner = owners.size() == 1 ? targets.find {
            (it.id ?: "${it.minecraft}-${it.loader}") == owners.first()
        } : null
        if (owner == null || tag != tagPrefix + version + '+mc' + owner.minecraft + '-' + owner.loader) {
            throw new GradleException("Release tag '${tag}' must match the selected artifact owner. Selected owners: ${owners.join(', ')}. Use +mc<minecraft>-<loader> for one owner or ${tagPrefix}${version} for multiple owners.")
        }
    }
}
