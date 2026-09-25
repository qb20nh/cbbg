# Sodium 0.9.2 reference images

Sodium produces a different world image from vanilla even with CBBG disabled.
The comparison runs and differences are recorded in `docs/26.3-world-pixels.md`.
Separate reference images allow strict pixel comparison for Sodium. Tests select
these images when Sodium is loaded, including when installed as an Iris dependency.

Enabled and demo CPU-reference images were visually inspected. Two fresh runs
per backend produced matching float sources, GPU screenshots and CPU
images. OpenGL and Vulkan reference PNGs are identical for Sodium 0.9.2.

Capture runs: `history-world-sodium-capture-{first,repeat}-sodium-{opengl,vulkan}`.
All four runs failed comparison with the vanilla reference image, while their
CPU/GPU comparisons passed. They record the source of these images and retain
their original failed status.

- Candidate SHA256: `a3ff46cfb547017b71b4d784b18facf55d70dbf5e687b74efe7ee3ec4e5b0973`
- Capture driver SHA256: `a0a8c31de0954d171268a02324e0b58873cad5fffcdc9b316adb96f608e55ff5`
- `enabled.png` SHA256: `eb355689664992601dd65a090eb45c90ea6e78a6721184cc65b840a3777338ba`
- `demo.png` SHA256: `7f76c32d5cff068f4c8c12466edc7e4f853c8f8528c3c1dc5efb1fab2b24edc0`

Follow the parent README's update and capture-record requirements. Changes need
an explanation of the renderer or scene behavior and separate pixel validation.
Investigate a failing comparison before replacing its reference image.
