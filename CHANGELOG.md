# Changelog

This project uses [Keep a Changelog](https://keepachangelog.com/) and [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Maintenance lines

- The Minecraft 1.21.1 backport is maintained on the `mc1.21.1` branch.
  - Backport changes from `main` when applicable.
  - Release tags use `v<mod_version>(-prerelease)?+mc<minecraft_version>` (SemVer build metadata; `-` is reserved for prereleases).
  - Release tags are branch-gated: the tagged commit must be contained in `main` or `mc<minecraft_version>`.

## [1.4.0] - 2026-09-22

### Changed

- Ported to Minecraft 26.2 by [Evoloxi](https://github.com/Evoloxi). Requires Java 25.
- Updated release packaging and Java metadata for the 26.2 build.

## [1.3.0] - 2025-12-25

### Added

- Config screen status message.
- Support for multiple languages.

### Fixed

- Color banding in GUI background blurring.
- Pressing Esc in the config menu closed all menus.
- Crash when running alongside the Chat Patches mod.

## [1.2.2] - 2025-12-20

### Fixed

- Crash when opening the config screen.

## [1.2.1] - 2025-12-19

### Added

- Compatibility with [RenderScale](https://modrinth.com/mod/renderscale).

### Changed

- Improved `/cbbg` command feedback.
- Release artifacts now include the Minecraft version in the filename (e.g. `cbbg-1.2.1+mc1.21.11.jar`).

### Fixed

- Missing toast when generation completed.

## [1.2.0] - 2025-12-18

### Added

- `rgba32f` main render target option (with automatic fallback to `rgba16f`/`rgba8` if unsupported).
- Dithering strength control.
- STBN texture regeneration.
- Chat/toast notifications for STBN generation.

## [1.1.2] - 2025-12-16

### Changed

- Matched the mod JAR name/description to the Modrinth listing.
- Updated the in-game config screen layout.

## [1.1.1] - 2025-12-16

### Added

- Started STBN texture generation earlier during init.

### Fixed

- Incorrect STBN texture cache use.

## [1.1.0] - 2025-12-16

### Added

- Added `/cbbg` configuration command.

### Changed

- STBN textures are generated at runtime (packaged textures removed).

## [1.0.0] - 2025-12-16

### Added

- Initial release.
