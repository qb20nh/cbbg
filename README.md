# cbbg

color banding be gone - Remove pesky color banding from Minecraft

**cbbg** is a client-side Fabric mod that reduces visible color banding by:

- Using a **higher-precision render target** (RGBA16F)
- Applying **STBN blue-noise dithering** to final image

Helps reduce color banding on following:

* Smooth lighting
* Skybox
* Vignette

## What it does

- **Higher render precision**: upgrades the *main* render target color attachment to **RGBA16F**.
- **Blue-noise dithering**: applies a lightweight full-screen pass using **STBN** noise to break up 8-bit quantization banding.

## Installation

- Install **Fabric Loader** and **Fabric API** for your Minecraft version.
- Put the `cbbg-x.x.x.jar` into your `.minecraft/mods` folder.

This mod is **client-only**. It does not need to be installed on servers.

## Configuration

### In-game (recommended)

If you have **Mod Menu** installed, open cbbg’s config screen and select:

- **Enabled**
- **Disabled**
- **Demo (split)**: left = enabled (dither), right = disabled (no dither)

### Config file

Config file: `.minecraft/config/cbbg.json`

Example:

```json
{
  "mode": "ENABLED"
}
```

Valid values: `ENABLED`, `DISABLED`, `DEMO`.

## Compatibility notes

- **Iris shaderpacks**: when an Iris shaderpack is active, cbbg is **forced OFF** to avoid pipeline conflicts.
- **GPU support**: if RGBA16F allocation fails on your device/driver, cbbg will automatically fall back to RGBA8 for the remainder of the session.

## Credits / Third-party assets

- Includes **STBN** textures. See `src/main/resources/assets/cbbg/licenses/NVIDIA-RTX-STBN-License.txt`.
