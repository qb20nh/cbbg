# Contributing to cbbg

cbbg is a client-side Minecraft mod that reduces color banding with a
higher-precision render target and spatiotemporal blue-noise dithering.

## Development

Install Git and JDK 25. Use `gradlew.bat` on Windows or `./gradlew` on macOS/Linux:

```sh
./gradlew build
./gradlew check
./gradlew runClient -Ptarget=26.3-fabric
./gradlew build -Ptarget=26.2-fabric
```

Root commands default to the development targets listed in `targets.json`.
Use `-Ptarget=id` or `-Ptargets=id,id` for another selection. Runtime Java
requirements can differ from the JDK used to run Gradle.

Keep changes focused and readable. Run checks for the affected code; run local
game tests when rendering or game behavior changes. CI compiles, runs unit
tests and checks packages. It never launches Minecraft clients.

For incremental packaged acceptance, add `-PacceptanceReuse=<previous acceptance
directory>` to `runCandidateAcceptance`. Add `-PacceptanceReuse.<name>=<directory>`
for other saved campaigns. The task rechecks receipts and reuses passing cases
when the mod JAR, required test classes and resources, runtime, dependencies,
configuration and graphics environment match. Missing or changed inputs require
a new run. Custom Vulkan, JVM or library overrides require fresh runs because
their referenced files are not recorded. Reused results retain the commit that
was tested.

Add `-PacceptanceWorkers=2` to run independent acceptance scenarios in parallel.
Workers share a queue; cache-dependent cases wait for their verified inputs, and
result indexes are written serially. Choose a worker count that fits available
memory, disk and GPU capacity. The default is one worker.

## Targets and versions

Minecraft versions and loaders share one branch. `targets.json` records their
dependencies, source groups, build profiles and implementation status. Shared
code belongs in the core or renderer modules; adapters handle version-specific
APIs. Discuss new ports before adding a profile.

Set `mod_version=X.Y.Z` or `X.Y.Z-rc.1` in `gradle.properties`. Modern artifacts
use `<mod_version>+mc<minecraft_version>-<loader>`. Tag a single-target release
`vX.Y.Z+mc<minecraft_version>-<loader>` and a shared release `vX.Y.Z`.
For a prerelease, put `-rc.1` before `+mc`, for example
`v1.4.1-rc.1+mc26.3-fabric`. The target suffix is SemVer build metadata;
it does not make the release a prerelease. Gradle expands the version and
Minecraft requirement in mod metadata. Keep these values in properties and the catalog.

All current builds include the loader in their artifact versions. Older 26.2
candidates keep the format recorded in their catalogs. The publisher also accepts
historical `vX.Y.Z+mc<minecraft_version>` releases.

Build profiles derive versions with `CandidateManifest.packageVersion` using the
artifact owner. A shared Fabric/Quilt JAR keeps its owner's filename; publication
lists every selected loader and Minecraft version.

GitHub creates the dedicated Release attestation when an immutable release is
published. Drafts show only the uploaded `provenance.jsonl` build attestation.
Upload the mod JAR, sources JAR, `provenance.jsonl`, and `SHA256SUMS`.
Also upload `cbbg-utilities-<version>.jar` and
`cbbg-utilities-<version>-sources.jar` once per release.
The sources JAR includes the ProGuard mapping and CycloneDX SBOM under
`META-INF/cbbg/`; they need no separate release assets.

## Releases

Each changelog entry describes one GitHub release, covering one target or a selected
set of targets. Give it a readable H2 title and list each target's stable identifier
in one HTML comment. For a single target:

```md
## [1.4.1 for Minecraft 26.3 Fabric] - 2026-09-27 <!-- [1.4.1-mc26.3-fabric] -->
```

For a shared release:

```md
## [1.5.0] - YYYY-MM-DD <!-- [1.5.0-mc26.3-fabric] [1.5.0-mc1.20.1-neoforge] -->

### Added

- Shared feature available on every target in this release.

### Fixed

#### Minecraft 26.3 — Fabric

- Fix specific to this target.
```

Use `<mod_version>-mc<minecraft_version>-<loader>` for each identifier. A target
must appear in exactly one entry for that version; every target selected for a
shared release must appear in the same entry. Use a new version tag for each
GitHub release.

Keep shared changes directly under the standard `### Added`, `### Changed`,
`### Deprecated`, `### Removed`, `### Fixed` and `### Security` categories. Use
`####` headings for changes limited to `Minecraft 26.3`, `Fabric`, or
`Minecraft 26.3 — Fabric`; substitute the applicable version and loader. Loader
labels are Fabric, Quilt, Forge, NeoForge and Legacy Fabric, regardless of case.
These headings apply through the next H4 or category heading. Reserve H4s for
these scopes. Single-target entries can keep changes directly under categories.

Describe every change users can experience compared with the relevant previous
published release, and combine related items. Shared descriptions should apply to
every listed target. Write briefly without dropping information and give readers
enough context to understand the change without following development.

Extract the selected entry with Gradle:

```sh
./gradlew releaseNotes -Prelease=v1.4.1 -Ptarget=26.3-fabric -Poutput=build/release-notes.md
# For a shared release, use -Ptargets=id,id instead of -Ptarget=id.
```

The task selects the hidden identifiers and includes shared changes plus scopes
matching any selected target. It rejects missing, duplicate or empty entries and
unknown scope headings. Historical `## [X.Y.Z] - YYYY-MM-DD` headings remain
supported. Candidate CI uses the same selection for the draft body. Pass the
generated file as `-Pnotes=build/release-notes.md` to `checkRelease` and
`publishRelease`. Modrinth and CurseForge receive shared changes plus the scopes
applicable to each uploaded artifact. A shared Fabric/Quilt JAR includes both
loaders' notes.

1. Finish the selected targets and update their version and changelog. Obtain
   approval before release preparation commits, pushes and tags.
2. After approval, create and push the release tag. Manually dispatch
   [Release](.github/workflows/release.yml) from that tag with one target ID or a
   comma-separated target list. Every selected target must be implemented and
   have its required test contracts and dependency/runtime locks.
3. The workflow builds and checks the candidate, records its inputs, creates
   attestations and uploads one GitHub draft containing the selected artifacts.
   It refuses to replace an existing release.
4. Download the candidate and test its packaged JARs locally. Complete every
   required loader, graphics backend and compatibility configuration using
   fresh game directories and the recorded dependency versions. Keep raw test
   records local. Provide a separate `-Presults.<target-id>=path` result index
   for each target, including loaders that share one JAR.
5. Run `checkRelease` against the draft, candidate and local results. After
   explicit approval, run `publishRelease` with the final release notes.
   GitHub immutable releases must be enabled.
6. Manually dispatch [Publish](.github/workflows/publish.yml) with the exact
   published tag and selected destinations. Start with `dry_run=true` to check
   downloaded files, service labels and retry state. Disable it after approval
   to upload those files to Modrinth and/or CurseForge. Publication checks every
   artifact before uploads begin, then uploads each distinct artifact separately.

The publisher uses tools from its workflow revision and source from the release
tag. It verifies an immutable release and uploads its existing JARs. Modrinth
retries compare remote metadata and file hashes. CurseForge uses the upload
token; inspect its project files before starting a new dispatch to retry an
upload. If publication returns an uncertain result, check the service before
retrying.

See [the build command reference](build-config/README.md) for task inputs.
Hotfix checks take explicit prior release manifests and expand testing to
affected released targets. Historical branches remain usable for existing
releases; new ports belong in the shared target catalog.
