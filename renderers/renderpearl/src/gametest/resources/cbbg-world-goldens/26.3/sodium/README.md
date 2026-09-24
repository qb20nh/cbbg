# Sodium 0.9.2 scene baseline

Sodium produces a different world image from vanilla even with CBBG disabled.
The controls and measured differences are recorded in `docs/26.3-world-pixels.md`.
These baselines keep strict pixel comparison instead of widening tolerances to
accommodate that renderer difference. Selection depends on Sodium actually being
loaded, including profiles where Iris brings Sodium as a dependency.

Enabled and demo CPU-reference images were visually inspected. Two fresh runs
per backend produced byte-identical float sources, GPU screenshots and CPU
images. OpenGL and Vulkan reference PNGs are identical for Sodium 0.9.2.

Capture runs: `history-world-sodium-capture-{first,repeat}-sodium-{opengl,vulkan}`.
All four runs deliberately remain failed evidence because their comparisons to
the vanilla golden failed; their independent CPU/GPU comparisons passed. They
are baseline provenance, not retroactively passing acceptance cells.

- Candidate SHA256: `a3ff46cfb547017b71b4d784b18facf55d70dbf5e687b74efe7ee3ec4e5b0973`
- Capture driver SHA256: `a0a8c31de0954d171268a02324e0b58873cad5fffcdc9b316adb96f608e55ff5`
- `enabled.png` SHA256: `eb355689664992601dd65a090eb45c90ea6e78a6721184cc65b840a3777338ba`
- `demo.png` SHA256: `7f76c32d5cff068f4c8c12466edc7e4f853c8f8528c3c1dc5efb1fab2b24edc0`

The parent baseline's no-automatic-update and provenance requirements apply.
Any change must be justified by renderer/scene behavior and independent pixel
validation, not merely by a failing golden test.
