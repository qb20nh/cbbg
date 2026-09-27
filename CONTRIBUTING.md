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

## Targets and versions

Minecraft versions and loaders share one branch. `targets.json` records their
dependencies, source groups, build profiles and implementation status. Shared
code belongs in the core or renderer modules; adapters handle version-specific
APIs. Discuss new ports before adding a profile.

Set `mod_version=X.Y.Z` or `X.Y.Z-rc.1` in `gradle.properties`. Modern artifacts
use `<mod_version>+mc<minecraft_version>-<loader>` and release tags use `vX.Y.Z`
or `vX.Y.Z-rc.1`. Gradle expands the version and Minecraft requirement in mod
metadata. Keep these values in properties and the catalog.

The preserved 26.2 profile retains its `<mod_version>+mc<minecraft_version>`
artifact names and root source resources. The publisher also accepts existing
historical `vX.Y.Z+mc<minecraft_version>` releases.

## Releases

1. Finish the selected target and update its version and changelog. Obtain
   approval before release preparation commits, pushes and tags.
2. After approval, create and push the release tag. Manually dispatch
   [Release](.github/workflows/release.yml) from that tag with the selected target.
3. The workflow builds and checks the candidate, records its inputs, creates
   attestations and uploads a GitHub draft. It refuses to replace an existing
   release.
4. Download the candidate and test its packaged JAR locally. Complete every
   required loader, graphics backend and compatibility configuration using
   fresh game directories and the recorded dependency versions. Keep raw test
   records local.
5. Run `checkRelease` against the draft, candidate and local results. After
   explicit approval, run `publishRelease` with the final release notes.
   GitHub immutable releases must be enabled.
6. Manually dispatch [Publish](.github/workflows/publish.yml) with the exact
   published tag and selected destinations. Start with `dry_run=true` to check
   downloaded files, service labels and retry state. Disable it after approval
   to upload those files to Modrinth and/or CurseForge.

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
