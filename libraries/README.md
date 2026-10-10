# CBBG Lib

CBBG Lib generates spatiotemporal blue noise and dithers images on the CPU or
Minecraft's GPU. Your code supplies the images, noise and rendering schedule.
Installation loads shader support; your code decides when to generate noise or
apply dithering.

## Install

Download your Minecraft variant from the
[GitHub releases](https://github.com/qb20nh/cbbg/releases) and put its binary JAR
in `mods/`.

| Minecraft | Library JAR for 1.0.0 | Java |
| --- | --- | --- |
| 1.21.1 | `cbbg-lib-1.0.0+mc1.21.1-fabric.jar` | 21+ |
| 1.21.11 | `cbbg-lib-1.0.0+mc1.21.11-fabric.jar` | 21+ |
| 26.1, 26.1.1, 26.1.2 | `cbbg-lib-1.0.0+mc26.1-fabric.jar` | 25+ |
| 26.2 | `cbbg-lib-1.0.0+mc26.2-fabric.jar` | 25+ |
| 26.3 | `cbbg-lib-1.0.0+mc26.3-fabric.jar` | 25+ |

The library requires Fabric Loader; Fabric API is optional. Its mod ID is
`cbbg_lib`. CBBG bundles the matching library JAR. If you also install that same
version separately, Fabric loads one instance. Put binary mod JARs in `mods/`
and attach `*-sources.jar` files in your IDE.

For Java applications, add `cbbg-utilities-1.0.0.jar` to your classpath. It provides
noise generation, CPU dithering and reusable GLSL on Java 8 and newer, with no
Minecraft dependency or mod entrypoint. The GPU pass requires a Minecraft
library variant.

CBBG's Modrinth and CurseForge releases also offer the matching library and
utilities as additional files.

## Develop a mod

Start with a Fabric Loom project for your Minecraft version and Java runtime.
Download the binary library JAR into your mod project's `libs/` directory.
For Minecraft 26.3, add this to `build.gradle`:

```groovy
def cbbgLib = files('libs/cbbg-lib-1.0.0+mc26.3-fabric.jar')

dependencies {
    implementation cbbgLib
    include cbbgLib
}
```

`implementation` makes the API available during development. Loom's `include`
bundles the library as a nested mod for players. To distribute it as a separate
required download, omit `include` and install the library beside your mod.

For other versions, change the filename. On Minecraft 1.21.1 and 1.21.11, use
`modImplementation` instead of `implementation` so Loom remaps the library to
your development mappings. The mod library JAR includes both CPU and GPU APIs.

Merge these fields into your mod's `fabric.mod.json`, keeping your existing
Minecraft, Fabric Loader and Java requirements:

```json
{
  "environment": "client",
  "depends": {
    "cbbg_lib": ">=1.0.0 <2.0.0"
  }
}
```

Add Fabric API if your own code uses its events or other features.

## Generate noise

`com.qb20nh.cbbg.api.NoiseVolume` generates seeded noise on the thread that calls
it. For large volumes, submit generation to an executor owned by your mod:

```java
import com.qb20nh.cbbg.api.NoiseVolume;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public final class ModNoise {
    public static Future<NoiseVolume> generate(ExecutorService executor) {
        return executor.submit(() -> NoiseVolume.generate(128, 128, 64, 42L));
    }
}
```

Dimensions must be positive powers of two, with at least two pixels in total.
Larger volumes take more time and memory. Poll the future or handle completion
in your task system; wait for its result outside the game thread. Handle failed
or cancelled requests before using their results. `Future.cancel(true)` interrupts
generation. Shut down your executor when your mod stops.

Noise volumes are immutable. Reuse the completed volume and choose the frame
sequence in your code. `pixelABGR(x, y, frame)` wraps all three coordinates;
`frameRGBA(frame)` returns a new RGBA8 byte array for texture upload, wrapping
frame indices at the volume's depth.

## Dither CPU images

`com.qb20nh.cbbg.api.Dithering` converts linear RGBA floats to dithered RGBA8 bytes:

```java
import com.qb20nh.cbbg.api.DitherOptions;
import com.qb20nh.cbbg.api.Dithering;
import com.qb20nh.cbbg.api.NoiseVolume;

public final class CpuDither {
    public static byte[] convert(
            float[] rgba, int width, int height, NoiseVolume noise, int frame) {
        DitherOptions options = new DitherOptions(2, 1, 1, false);
        return Dithering.rgba8(rgba, width, height, noise, frame, options);
    }
}
```

Supply four finite floats per pixel. Conversion preserves row order, uses pixel
centers, dithers RGB and quantizes alpha without noise. The example uses strength
2, unit X/Y noise-coordinate scales and demo mode disabled.

`DitherOptions` controls those four values. Demo mode leaves the left half
undithered and marks the dividing column. For reduced-resolution rendering, set
X/Y scales to rendered width/height divided by the corresponding output dimensions.

## Add a GPU pass

Compile against `com.qb20nh.cbbg.render.DitherPass` from your Minecraft variant.
Integrate it into your renderer or a Minecraft rendering hook. After Minecraft
loads shaders, create one pass on the render thread and reuse it across frames.

| Minecraft | Input image | Noise image |
| --- | --- | --- |
| 1.21.1 | `RenderTarget` | OpenGL texture ID (`int`) |
| 1.21.11, 26.1.x, 26.2, 26.3 | `GpuTextureView` | `GpuTextureView` |

Upload `noise.frameRGBA(frame)` to an RGBA8 texture. Keep that texture and your
input image alive while rendering. On Minecraft 1.21.11 and 26.x:

```java
DitherPass pass = new DitherPass();
// Each frame, using your input and uploaded noise texture views:
TextureTarget output = pass.render(inputView, noiseView, options);
// Present, copy or sample output before the next render, resize or close.
// When finished with the pass, on the render thread:
pass.close();
```

For Minecraft 1.21.1, replace the render call with:

```java
TextureTarget output = pass.render(inputTarget, noiseTextureId, options);
```

The pass samples noise with nearest filtering and repeat addressing. It returns
a library-owned RGBA8 `TextureTarget` at the input dimensions and manages that
output and its uniform storage. You manage the input/noise textures and schedule
rendering, including shader-mod integration. For matching CPU/GPU results, use
the same source pixels, noise frame and options.

Create, render and close on Minecraft's render thread. `close()` releases the
pass's resources; the instance can be reused afterward. The pass works
independently of CBBG's enabled state or shader-mod suspension. The 26.2 and 26.3
variants support Minecraft's OpenGL and native Vulkan backends.

## Check your integration

Run `./gradlew build` and `./gradlew runClient` in your mod project. Check CPU
output and your GPU pass, including window resize and resource reload. Close
the pass and release your textures when the renderer stops using them.

Test your packaged mod in a fresh game directory with:

- Your mod and its nested library, or the separately required library.
- Your mod plus the same library installed separately, if you bundle it.
- CBBG installed alongside your mod, to check shared library resolution.
- OpenGL and native Vulkan on 26.2/26.3, if your mod supports both backends.

Use the packaged JAR for this check: a development launch alone does not test
nested-mod installation.

## Build CBBG Lib

Use Java 25 to run Gradle from the CBBG repository root:

```sh
./gradlew -p libraries/utilities check utilitiesSourcesJar
./gradlew -p libraries/fabric -Ptarget=26.3-fabric check checkPackages
```

Choose `<minecraft>-fabric` for your Minecraft version. The three 26.1 runtimes
share the `26.1-fabric` artifact. Utilities outputs are in
`libraries/utilities/build/libs`; mod outputs are in `build/libraries/<target>/libs`.
The utilities JAR includes its math implementation and the GLSL resource
`com/qb20nh/cbbg/api/shaders/dither.glsl`.

Release binaries use ProGuard while retaining public API names. Development
JARs retain original classes and symbols. Sources JARs include the corresponding
mapping and CycloneDX SBOM under `META-INF/cbbg/`; attach them in your IDE for
source and mapping lookup.

CBBG Lib has its own [changelog](CHANGELOG.md), version and `lib/v<version>` release
tags. Each release includes Minecraft variants and plain Java utilities, with
sources for both. CBBG uses `v<version>` tags and bundles its required library.
Both projects use the MIT license.
