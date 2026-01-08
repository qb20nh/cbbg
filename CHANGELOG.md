# Changelog

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.3.1] - 2026-01-08

### Fixed

- Fixed OpenGL errors when Satin (or other mods utilizing initFbo) is present.

## [1.3.0] - 2025-12-25

### Added

- Config screen status message.
- Support multiple languages.

### Fixed

- Color banding in GUI background blurring.
- Pressing esc key in config menu exiting all menus.
- Crash when running alongside the Chat Patches mod.

## [1.2.2] - 2025-12-20

### Fixed

- Fixed crash when opening config screen

## [1.2.1] - 2025-12-19

### Added

- Compatibility with [RenderScale](https://modrinth.com/mod/renderscale).

### Changed

- Improved `/cbbg` command feedback.
- Release artifacts now include the Minecraft version in the filename (e.g. `cbbg-1.2.1+mc1.21.11.jar`).

### Fixed

- Fixed generation completion toast not being shown.

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

- Fixed STBN texture cache not being utilized correctly.

## [1.1.0] - 2025-12-16

### Added

- Added `/cbbg` configuration command.

### Changed

- STBN textures are generated at runtime (packaged textures removed).

## [1.0.0] - 2025-12-16

### Added

- Initial release.
