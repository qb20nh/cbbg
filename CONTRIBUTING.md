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
configuration belongs in `core`; reusable dithering, noise and FFT code belongs
in `libraries/utilities`, and GPU implementations belong in `libraries/fabric`.
CBBG owns its client entrypoint and settings. Adapters handle version-specific
APIs. Discuss new ports before adding a profile.

CBBG and CBBG Lib have independent release versions. Set CBBG
`mod_version=X.Y.Z` or `X.Y.Z-rc.1` in root `gradle.properties` (currently
`1.5.0`). Set `library_version` in `libraries/utilities/gradle.properties`
(currently `1.0.0-beta.1`); `-Plibrary_version=...` overrides it for a build. Both
products use `<version>+mc<minecraft_version>-<loader>` artifact versions.
Tag CBBG releases `vX.Y.Z` and library releases `lib/vX.Y.Z`. Append
`+mc<minecraft_version>-<loader>` for a single-target release.
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
CBBG GitHub releases contain the main mod and sources JARs, verified library
dependency provenance, `provenance.jsonl`, and `SHA256SUMS`. CBBG Lib GitHub
releases contain library mod and sources JARs plus
`cbbg-utilities-<library_version>.jar` and its sources JAR once per release,
with their release provenance and checksums. CBBG packages the matching library
JAR inside its mod; standalone library and utility files are attached to CBBG
Modrinth and CurseForge uploads from the verified candidate references.
The sources JAR includes the ProGuard mapping and CycloneDX SBOM under
`META-INF/cbbg/`; they need no separate release assets.

## Releases

Use root `CHANGELOG.md` for CBBG and `libraries/CHANGELOG.md` for CBBG Lib.
Each changelog entry describes one GitHub release, covering one target or a selected
set of targets. Give it a readable H2 title and list each target's stable identifier
in one HTML comment. For a single target:

```md
## [1.4.1 for Minecraft 26.3 Fabric] - 2026-09-27 <!-- [1.4.1-mc26.3-fabric] -->
```

For a shared release:

```md
## [1.5.0] - YYYY-MM-DD <!-- [1.5.0-mc26.2-fabric] [1.5.0-mc26.3-fabric] [1.5.0-mc26.3-neoforge] -->

### Added

- Shared feature available on every target in this release.

### Fixed

#### Minecraft 26.2, 26.3 — Fabric

- Fix shared by these Fabric targets.

#### Minecraft 26.3

- Fix shared by both 26.3 loaders.
```

Use `<version>-mc<minecraft_version>-<loader>` for each identifier, with that
product's version and no tag prefix. A target must appear in exactly one entry for that version; every target selected for a
shared release must appear in the same entry. Use a new version tag for each
GitHub release.

Keep shared changes directly under the standard `### Added`, `### Changed`,
`### Deprecated`, `### Removed`, `### Fixed` and `### Security` categories. Use
`####` headings to group each change by its applicable targets. List exact versions
with commas, such as `Minecraft 1.21.1, 1.21.11`. An optional loader suffix applies
to the whole list: `Minecraft 26.2, 26.3 — Fabric`. Single-version and loader-only
headings such as `Minecraft 26.3` or `Fabric` also work. Loader labels are Fabric,
Quilt, Forge, NeoForge and Legacy Fabric, regardless of case.
These headings apply through the next H4 or category heading. Reserve H4s for
these scopes. Single-target entries can keep changes directly under categories.

Describe every change users can experience compared with each target's previous
published release. Write each change once per category and group changes with the
same target set under one H4. Groups may overlap; each description must apply to
every target in its group. Combine related items and write briefly without dropping
information. Give readers enough context to understand the change without following
development. Each platform upload must include all shared changes and every group
matching any version and loader supported by its artifact.

Extract the selected entry with Gradle:

```sh
./gradlew releaseNotes -Prelease=v1.4.1 -Ptarget=26.3-fabric -Poutput=build/release-notes.md
./gradlew releaseNotes -Prelease=lib/v1.0.0-beta.1 -Ptarget=26.3-fabric -Poutput=build/library-release-notes.md
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
3. For CBBG, first create the matching library release draft. The workflow
   verifies its exact library JARs, sources and build provenance before attesting
   the CBBG candidate. It builds and checks the candidate, records its inputs,
   creates attestations and uploads one GitHub draft containing the selected artifacts.
   It refuses to replace an existing release.
4. Download the candidate and test its packaged JARs locally. Complete every
   required loader, graphics backend and compatibility configuration using
   fresh game directories and the recorded dependency versions. Keep raw test
   records local. Provide a separate `-Presults.<target-id>=path` result index
   for each target, including loaders that share one JAR.
5. Complete library acceptance independently using its own contracts in
   `runtime-locks/lib/`; CBBG results do not certify a standalone library release.
   Publish the matching library release before finalizing CBBG: `checkRelease`
   requires its immutable published files and provenance. Run `checkRelease`
   against each product's draft, candidate and local results. After
   explicit approval, run `publishRelease` with the final release notes.
   GitHub immutable releases must be enabled.
6. For CBBG releases only, manually dispatch
   [Publish](.github/workflows/publish.yml) with the exact published tag and selected destinations. Start with `dry_run=true` to check
   downloaded files, service labels and retry state. Disable it after approval
   to upload those files to Modrinth and/or CurseForge. Publication checks every
   artifact before uploads begin, then uploads each distinct artifact separately.

CBBG Lib is released through GitHub; its tags cannot trigger Modrinth or
CurseForge uploads. The publisher uses tools from its workflow revision and
source from the release tag. It verifies an immutable release and uploads its existing JARs. Modrinth
retries compare remote metadata and file hashes. CurseForge uses the upload
token; inspect its project files before starting a new dispatch to retry an
upload. If publication returns an uncertain result, check the service before
retrying.

See [the build command reference](build-config/README.md) for task inputs.
Hotfix checks take explicit prior release manifests and expand testing to
affected released targets. Historical branches remain usable for existing
releases; new ports belong in the shared target catalog.
