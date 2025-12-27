# Contributing to cbbg

Thanks for contributing!

This repo is a client-side fabric mod for Minecraft that reduces visible color banding by upgrading the main render target precision and applying spatiotemporal blue noise dithering.

## Development setup

### Prerequisites

- JDK 21+
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

- `main` is the canonical development line.
- Each long-lived Minecraft maintenance line is named exactly:
  - `mc<minecraft_version>` (examples: `mc1.21.1`)

Backports should generally be cherry-picked from `main` into the maintenance branch when applicable.

## Versioning and releases

`gradle.properties` is the single source of truth:

- `mod_version=X.Y.Z` (or `X.Y.Z-rc.1` for prereleases)
- `minecraft_version=<minecraft_version>`

Gradle computes the published mod/artifact version as:

- `<mod_version>+mc<minecraft_version>`

`fabric.mod.json` is expanded during the build:

- `"version": "${version}"`
- `"minecraft": "~${minecraft_version}"`

Do not hardcode these values in `fabric.mod.json`.

### Tag format (canonical across all branches)

Git tags are **repo-global** (not branch-local), so tags must include the Minecraft line to avoid collisions.

Release tags must be:

- Stable: `vX.Y.Z+mc<minecraft_version>`
- Prerelease: `vX.Y.Z-rc.1+mc<minecraft_version>`

### Branch-gated releases

Releases are intentionally **branch-gated** by CI:

The tagged commit must be contained in either:

- `main`, or
- `mc<minecraft_version>` (for the Minecraft line you are releasing)

This prevents accidental releases from random branches.

### Release procedure

1. Ensure you’re on the branch you intend to release from (`main` or `mc<minecraft_version>`).
2. Update `CHANGELOG.md`:
   - Move entries from `## [Unreleased]` into a new `## [X.Y.Z] - YYYY-MM-DD` section.
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
  - Enforces branch gating.
  - Builds and uploads the JARs to the GitHub Release.
- **Publishing**: [`.github/workflows/publish.yml`](.github/workflows/publish.yml) runs after `Release` and publishes the exact GitHub Release artifacts (Modrinth/CurseForge).

## Adding a new Minecraft maintenance line (future)

When adding support for a new Minecraft version while keeping older lines maintained:

1. Create a new maintenance branch named `mc<minecraft_version>` (example: `mc26.1`).
2. Update `gradle.properties` on that branch:
   - `minecraft_version=...`
   - update Fabric/Loader/dependency versions as needed
3. Ensure the release workflows exist on the branch:
   - cherry-pick the relevant workflow commits from `main` into the new branch
4. Release using the canonical tag format `v<mod_version>+mc<minecraft_version>`.

## Troubleshooting releases

- **Tag … does not match gradle.properties …**: the tag’s `mod_version` or `minecraft_version` doesn’t match `gradle.properties` on the tagged commit.
- **Branch gating failure**: the tag points to a commit that is not reachable from `main` or `mc<minecraft_version>`. Retag a commit on the correct branch (or fix the branch name/version).
- **Multiple tags on the same commit**: `publish.yml` refuses to guess which tag to publish; remove/avoid ambiguity.
