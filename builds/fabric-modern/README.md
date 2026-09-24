# Modern Fabric build profile

This isolated Loom 1.17.21 build currently reconstructs the 26.3 Fabric
RenderPearl adapter. It shares the Java 8 core and uses Java 25 for Minecraft.
Other catalog targets remain pending. Distribution packaging rejects targets
whose acceptance is incomplete.

The merged 26.2 port and evoloxi's cleanup remain in
`adapters/fabric/26.2`, built by the root Gradle project. Do not select 26.2
through this profile or replace its implementation with the newer renderer.
Its metadata and resources remain at their upstream root paths for
compatibility with the existing release tooling.

From the repository root:

```
./gradlew -p builds/fabric-modern genSources -Ptarget=26.3-fabric
./gradlew -p builds/fabric-modern prepareParityRuntime -Ptarget=26.3-fabric
python3 scripts/build_targets.py check --targets 26.2-fabric
```

The catalog supplies Minecraft, loader/API versions, renderer family and
optional-mod pins. Outputs are isolated under `build/targets/<target-id>`.
Mod version and artifact group come from the root properties. Mapped Minecraft
sources are development inputs and are never included in mod distributions.

Shared Fabric commands, widgets, compatibility adapters and noise/image helpers
live in `adapters/fabric/shared`; renderer-dependent classes stay in their
renderer families. Resource processing shares the canonical shader mathematics
and translations without modifying the preserved 26.2 Java implementation.

Local graphical tests, using a private display:

```
./gradlew -p builds/fabric-modern runClientGameTest -Ptarget=26.3-fabric -Pbackend=opengl
./gradlew -p builds/fabric-modern runClientGameTest -Ptarget=26.3-fabric -Pbackend=vulkan
```

Use `-Pcompat=<profile>` for a listed catalog combination. Iris profiles are
OpenGL-only. Packaged testing uses separate candidate and test-driver jars from
`prepareParityRuntime`, with exact dependency hashes and fresh game directories.
Run graphics tests locally, never in CI.

Scenarios cover window resizing, allocation/fallback, format transitions,
pixel readback, live presentation, screenshots, commands, settings, notifications
and optional integrations. A passing scenario trace is partial evidence, not
full release acceptance. Required compatibility cells and the complete feature
contract must still pass before enabling distribution.

Use native Wayland for Vulkan validation: an Xwayland swapchain-resize issue
also reproduces without CBBG. Preserve that limitation in evidence; do not
interpret clean process exit as success when Vulkan validation reports errors.
