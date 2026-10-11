# Changelog

This project uses [Keep a Changelog](https://keepachangelog.com/) and [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.5.0] - 2026-10-07 <!-- [1.5.0-mc1.21.1-fabric] [1.5.0-mc1.21.11-fabric] [1.5.0-mc26.1-fabric] [1.5.0-mc26.1.1-fabric] [1.5.0-mc26.1.2-fabric] [1.5.0-mc26.2-fabric] [1.5.0-mc26.3-fabric] -->

### Added

- Public noise-generation, CPU image-dithering and Minecraft GPU-dithering APIs for other mods, independent of CBBG's settings ([#90](https://github.com/qb20nh/cbbg/issues/90)).
- A standalone Java 8 noise-generation and CPU-dithering library with a reusable GLSL include.
- CBBG Lib, an independently installable utility mod bundled with CBBG. Library versions and GitHub releases advance separately.
- Open CBBG settings from Sodium's options menu when Sodium provides its configuration API ([#34](https://github.com/qb20nh/cbbg/issues/34)).

### Changed

#### Minecraft 1.21.1, 1.21.11

- Generate noise images and load/save their caches in the background. Cancel replaced calculations and pause dithering until the requested noise is ready.
- Regenerate caches from previous releases on first use.
- Reduce the release jar size; include source mappings and dependency information in the sources jar.

#### Minecraft 1.21.1

- Support Fabric Loader `>=0.16.0 <1.0.0` and require Fabric API `>=0.101.2+1.21.1 <1.0.0`.

#### Minecraft 1.21.11

- Support Fabric Loader `>=0.17.3 <1.0.0` and require Fabric API `>=0.139.4+1.21.11 <1.0.0`.

### Fixed

- Align dithering with RenderScale's rendered pixels, including in screenshots ([#20](https://github.com/qb20nh/cbbg/issues/20)).
- Lock or unlock settings controls immediately when changing Mode.

#### Minecraft 1.21.1, 1.21.11

- Apply the requested noise size, depth and seed consistently, including resets during generation and forced regeneration of cached defaults.
- Restart the noise-frame sequence after reloading, and use the displayed noise frame in screenshots.
- Clear generation toasts after cancellation, failure, or completion with notifications disabled; keep them separate from Minecraft's periodic notifications.
- Release replaced noise images and GPU resources, and cancel generation when Minecraft closes.
- Parse mode and pixel-format commands consistently across system languages.

#### Minecraft 1.21.1, 1.21.11, 26.1, 26.1.1, 26.1.2, 26.2

- Preserve float precision with improved transparency and Fabulous graphics, including when Sodium is installed ([#19](https://github.com/qb20nh/cbbg/issues/19)).

#### Minecraft 1.21.1

- Convert float screenshots correctly when dithering is unavailable.
- Release framebuffer resources after a failed allocation.
- Keep settings cards and labels visible above the menu background, including in generation confirmations.

#### Minecraft 1.21.11

- Avoid crashes when other renderers allocate unnamed framebuffer targets during menu blur.

#### Minecraft 1.21.1, 26.2

- Update RenderScale's target precision when changing CBBG's mode or pixel format.

## [1.4.2 for Minecraft 26.2 Fabric] - 2026-10-05 <!-- [1.4.2-mc26.2-fabric] -->

### Added

- Vulkan renderer support, including float render targets and dithered screenshots.
- Suspend dithering while Sulkan shaderpacks are active.

### Changed

- Smaller jar by replacing AsmFabricLoader with our own early entry and using ProGuard. Source mappings and dependency information are included in the sources jar.
- Require Fabric Loader `>=0.18.4 <1.0.0` and Fabric API `>=0.148.3+26.2 <1.0.0`.
- Generate noise images and load/save their caches in the background. Cancel replaced calculations and pause dithering until the requested noise is ready.
- Regenerate 1.4.0 noise caches on first use.

### Fixed

- Apply the requested noise dimensions and seed throughout generation and cache loading, including resets during generation and forced regeneration of cached defaults.
- Restart the noise-frame sequence after reloading, and use the displayed noise frame in screenshots.
- Convert float screenshots correctly when dithering is disabled or unavailable.
- Clear generation toasts after cancellation or failure, and after completion if toast notifications were disabled during generation.
- Prevent generation toasts from replacing Minecraft's other periodic notifications.
- Fall back to Minecraft's rendering when the dither shader fails, and release noise images and GPU resources when replaced or closed.
- Handle unnamed framebuffer targets used by other renderers.
- Parse mode and pixel-format commands consistently across system languages.

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
