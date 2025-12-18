# Changelog

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres
to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.2.0] - 2025-12-18

### Added
- `rgba32f` main render target option (with automatic fallback to `rgba16f`/`rgba8` if unsupported).
- Dithering strength control.
- STBN controls (size, depth, seed) plus regenerate action (UI + command).
- Chat/toast notifications for STBN generation (configurable).

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