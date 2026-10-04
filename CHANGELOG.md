# Changelog

This project uses [Keep a Changelog](https://keepachangelog.com/) and [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.4.2 for Minecraft 26.3 Fabric] - 2026-10-05 <!-- [1.4.2-mc26.3-fabric] -->

### Fixed

- Clear generation toasts after cancellation or failure, and after completion if toast notifications were disabled during generation.
- Prevent generation toasts from replacing Minecraft's other periodic notifications.

## [1.4.2 for Minecraft 26.1.x Fabric] - 2026-10-05 <!-- [1.4.2-mc26.1-fabric] [1.4.2-mc26.1.1-fabric] [1.4.2-mc26.1.2-fabric] -->

### Added

- Minecraft 26.1.x Fabric support.

### Fixed

- Clear generation toasts after cancellation or failure, and after completion if toast notifications were disabled during generation.
- Prevent generation toasts from replacing Minecraft's other periodic notifications.

## [1.4.1 for Minecraft 26.3 Fabric] - 2026-09-27 <!-- [1.4.1-mc26.3-fabric] -->

### Added

- Minecraft 26.3 Fabric support for OpenGL and Vulkan.
- Suspend dithering while Sulkan shaderpacks are active.

### Changed

- Smaller jar size by implementing our own early entry and removing the bundled AsmFabricLoader library.
- Even smaller jar size with ProGuard. Source mappings included in sources jar.

### Fixed

- Fixed various bugs and edge case behavior around noise generation and cache saving/loading.
- Use the currently displayed noise frame in screenshots instead of re-dithering it from scratch.

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
