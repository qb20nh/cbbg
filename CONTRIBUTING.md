# Contributing to cbbg

Thanks for contributing!

cbbg is a client-side Fabric mod for Minecraft. It reduces color banding with a higher-precision main render target and spatiotemporal blue-noise dithering.

## Development setup

### Prerequisites

- JDK 25 for the Minecraft 26.2 line (older maintenance branches retain their own Java requirements)
- Git

### Build

Windows:

```
.\gradlew.bat build
```

macOS/Linux:

```
./gradlew build
```

### Run the game (dev environment)

Windows:

```
.\gradlew.bat runClient
```

macOS/Linux:

```
./gradlew runClient
```

## Project conventions

- Keep changes focused and readable.
- Try to minimize compiler/linter warnings.

## Branch model

- `main` is the main development branch.
- Each long-lived Minecraft maintenance line is named exactly:
  - `mc<minecraft_version>` (examples: `mc1.21.1`)

Backports should generally be cherry-picked from `main` into the maintenance branch when applicable.

## Versioning and releases

Set versions in `gradle.properties`:

- `mod_version=X.Y.Z` (or `X.Y.Z-rc.1` for prereleases)
- `minecraft_version=<minecraft_version>`

Gradle computes the published mod/artifact version as:

- `<mod_version>+mc<minecraft_version>`

`fabric.mod.json` is expanded during the build:

- `"version": "${version}"`
- `"minecraft": "~${minecraft_version}"`

Do not hardcode these values in `fabric.mod.json`.

### Tag format

Git tags apply to the whole repository. Include the Minecraft version to distinguish releases for different versions.

Release tags must be:

- Stable: `vX.Y.Z+mc<minecraft_version>`
- Prerelease: `vX.Y.Z-rc.1+mc<minecraft_version>`

### Release branches

CI requires the tagged commit to belong to either:

- `main`, or
- `mc<minecraft_version>` (for the Minecraft line you are releasing)

### Release procedure (maintainers only)

1. Ensure you’re on the branch you intend to release from (`main` or `mc<minecraft_version>`).
2. Update `CHANGELOG.md`:
   - Move entries from `## [Unreleased]` into a new `## [X.Y.Z] - YYYY-MM-DD` section.
   - When reusing a mod version for another Minecraft line, use `## [X.Y.Z+mc<MINECRAFT_VERSION>] - YYYY-MM-DD`. Release notes prefer this exact artifact version before falling back to the legacy version-only section.
3. Bump `gradle.properties`:
   - Update `mod_version=...`
   - Ensure `minecraft_version=...` matches the branch line.
4. Commit the release prep change:

```bash
git add CHANGELOG.md gradle.properties
git commit -m "Prepare release X.Y.Z"
```

5. Create an annotated tag on that commit:

```bash
git tag -a "vX.Y.Z+mc<MINECRAFT_VERSION>" -m "Release vX.Y.Z+mc<MINECRAFT_VERSION>"
```

6. Push the branch and tag:

```bash
git push origin <branch>
git push origin "vX.Y.Z+mc<MINECRAFT_VERSION>"
```

### How CI/CD works

- **GitHub Release creation**: pushing a matching tag triggers [`.github/workflows/release.yml`](.github/workflows/release.yml).
  - Validates the tag matches `gradle.properties` (`mod_version`, `minecraft_version`).
  - Checks that the commit belongs to an allowed release branch.
  - Builds and uploads the JARs to the GitHub Release.
- **Publishing**: [`.github/workflows/publish.yml`](.github/workflows/publish.yml) runs after `Release` and publishes the exact GitHub Release artifacts (Modrinth/CurseForge).
  - The publishing JDK and CurseForge Java label come from the tagged source's `fabric.mod.json`.
  - Python validation lives in `.github/scripts/release.py`. The publisher checks out these tools from its own workflow revision so older release tags remain supported.
  - Downloaded JAR metadata and class-file Java requirements are checked before uploading.
  - Manual publishing requires an explicit existing release tag; it never selects another Minecraft line's latest release implicitly.
  - Manual runs default to `dry_run`: validate the immutable release and packaged metadata, make authenticated CurseForge GET requests, resolve destination labels, and execute Minotaur with `debugMode=true` without uploading. This verifies CurseForge read authentication, not project upload permission. Disable `dry_run` to publish.
  - Client GameTests run with and without RenderScale before a new GitHub Release is created. Sodium checks are still pending.
  - Stable releases and prereleases must be immutable. Publish a new prerelease tag instead of replacing an existing prerelease's assets.

## ~~Adding a new Minecraft maintenance line (future)~~

When adding support for a new Minecraft version while keeping older lines maintained:

1. Create a new maintenance branch named `mc<minecraft_version>` (example: `mc26.1`).
2. Update `gradle.properties` on that branch:
   - `minecraft_version=...`
   - update Fabric/Loader/dependency versions as needed
3. Ensure the release workflows exist on the branch:
   - cherry-pick the relevant workflow commits from `main` into the new branch
4. Release using the tag format `v<mod_version>+mc<minecraft_version>`.

> [!IMPORTANT]
> We’re restructuring the repository to support multiple Minecraft versions and mod loaders on a single branch. Please discuss your plans with the maintainers before starting a backport or a port to a newer version.

## Troubleshooting releases

- **Tag … does not match gradle.properties …**: the tag’s `mod_version` or `minecraft_version` doesn’t match `gradle.properties` on the tagged commit.
- **Branch gating failure**: the tag points to a commit that is not reachable from `main` or `mc<minecraft_version>`. Retag a commit on the correct branch (or fix the branch name/version).
- **Multiple tags on the same commit**: `publish.yml` refuses to guess which tag to publish; remove/avoid ambiguity.
