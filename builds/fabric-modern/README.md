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

`scripts/fabric_parity_runtime.py` runs packaged 26.3 candidates against the
checked-in runtime and dependency locks. Supply an isolated display and a new
game directory; it never reuses an existing game directory. For example, using
an isolated Python environment with `minecraft-launcher-lib==8.0`:

```
python scripts/fabric_parity_runtime.py \
  --runtime /path/to/installed-runtime --java /path/to/java-25/bin/java \
  --game-dir /path/to/new-test-run \
  --candidate /path/to/parity-candidate.jar --driver /path/to/parity-driver.jar \
  --gametest-api /path/to/fabric-client-gametest-api.jar \
  --dependency fabricApi=/path/to/fabric-api.jar \
  --runtime-lock runtime-locks/26.3-fabric-linux-x86_64.json \
  --dependency-lock runtime-locks/26.3-fabric-mods.json \
  --xdg-runtime-dir /path/to/private-runtime-dir \
  --wayland-display private-test-display --backend vulkan --compat none
```

For OpenGL on an isolated X display, replace `--wayland-display` with
`--x-display`. For optional profiles, add each selected catalog dependency using
`--dependency NAME=JAR`, including Sodium for Iris and Cloth Config for RenderScale.
Names match catalog dependency keys, such as `modMenu` and `immediatelyFast`.
The receipt binds artifact and lock hashes, source head, requested/observed backend
and the ordered scenario result. Failures and timeouts leave a failure receipt;
none of these receipts claim full release acceptance. Vulkan validation-layer
paths can be supplied through `VK_LAYER_PATH` and `LD_LIBRARY_PATH` when needed.

For the real Iris restart fixture, build `irisRestartDriverJar` and use its
`*-iris-restart-driver.jar` in place of the ordinary parity driver. Launch with
Iris and Sodium and `-Dcbbg.test.restart=prepare`, wait for successful process
exit, then launch a new JVM in the same isolated game directory with
`-Dcbbg.test.restart=verify`. Use separate evidence directories for the phases.
Keep artifacts, dependencies and saved settings unchanged between launches.
The fixture verifies persisted shader selection, actual shader pixels, CBBG
suspension and recovery after disabling shaders. `control` is a separate fresh
directory run with CBBG disabled; it is diagnostic evidence, not a restart pass.

For maximum-size image/cache validation, build `maximumNoiseCacheDriverJar`
and substitute its `*-maximum-noise-cache-driver.jar` for the ordinary driver
in a fresh game directory. Allow a 600-second process timeout and a 2 GiB Java
heap. This separate slow fixture generates 256x256x128 noise, checks every native
image pixel against a fixed CPU checksum, then reloads all PNG frames and checks
the checksum again. It then loads the cached frames into the live controller,
renders one full 128-frame GPU cycle without fallback, and disables the effect.
This checks maximum-size upload/cycling; pixel readback uses the separate small
synthetic fixtures rather than checking every maximum-size GPU texel.
Run it after other clients exit; do not run two graphical clients concurrently.

Scenarios cover window resizing, allocation/fallback, format transitions,
pixel readback, live presentation, screenshots, commands, settings, notifications
and optional integrations. A passing scenario trace is partial evidence, not
full release acceptance. Required compatibility cells and the complete feature
contract must still pass before enabling distribution.

Use native Wayland for Vulkan validation: an Xwayland swapchain-resize issue
also reproduces without CBBG. Preserve that limitation in evidence; do not
interpret clean process exit as success when Vulkan validation reports errors.
