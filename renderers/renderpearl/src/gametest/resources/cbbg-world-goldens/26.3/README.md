# 26.3 world scene baseline

These PNGs are CPU reference outputs from the static `WorldPixelsGameTest` scene,
not screenshots copied without validation. Both images were visually inspected.
Two fresh OpenGL runs and two fresh Vulkan runs produced identical source
buffers and expected images.
The two backends produced identical expected PNG bytes, so one baseline is shared.

Provenance: `history-world-pixels-stable{1,2}-{opengl,vulkan}`, described in
`docs/26.3-world-pixels.md`.

- Candidate SHA256: `a3ff46cfb547017b71b4d784b18facf55d70dbf5e687b74efe7ee3ec4e5b0973`
- Capture driver SHA256: `f5ca7af04b1ef2f94345bd067b1aed13ef41c285ff3a5e836b30350434776542`
- `enabled.png` SHA256: `3046a0eba4bbdc3c43030150e46ae3f1954cb23ce0a924ea413fb0f7cf7f0be9`
- `demo.png` SHA256: `ca4fbf8c555b658f39ff8bb155efc4a4bc08c311db7c7f23d65bade94b226beb`

Verification reads these resources and compares the current world CPU output
exactly; it never rewrites them. GPU screenshot comparison independently allows
one LSB only at explicitly classified shader rounding boundaries. A baseline
change requires inspecting the changed scene, proving repeatability, checking
the CPU/GPU comparison and recording new provenance in a separate reviewed diff.
Do not regenerate baselines simply to make a failure pass. This baseline does
not establish other Minecraft versions, optional renderers or other hardware.
