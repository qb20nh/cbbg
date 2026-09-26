# 26.3 world reference images

These PNGs contain the CPU-calculated output for the static `WorldPixelsGameTest`
scene. Both images were visually inspected. Two OpenGL runs and two Vulkan runs,
each starting a new client, produced the same source buffers and expected images.
Both backends use the same reference PNGs.

Capture runs: `history-world-pixels-stable{1,2}-{opengl,vulkan}`, described in
`docs/26.3-world-pixels.md`.

- Candidate SHA256: `a3ff46cfb547017b71b4d784b18facf55d70dbf5e687b74efe7ee3ec4e5b0973`
- Capture driver SHA256: `f5ca7af04b1ef2f94345bd067b1aed13ef41c285ff3a5e836b30350434776542`
- `enabled.png` SHA256: `3046a0eba4bbdc3c43030150e46ae3f1954cb23ce0a924ea413fb0f7cf7f0be9`
- `demo.png` SHA256: `ca4fbf8c555b658f39ff8bb155efc4a4bc08c311db7c7f23d65bade94b226beb`

Tests compare the CPU output exactly against these files. GPU screenshot checks
allow a one-LSB difference only at identified shader rounding boundaries.
To update the images, inspect the scene, repeat the capture, check CPU/GPU results
and record the new capture details in a change for review. Tests must leave these
files unchanged. Investigate a failure before replacing a reference image.
Other Minecraft versions, optional renderers and hardware need their own tests.
