# CBBG utilities

CBBG exports noise generation, CPU image dithering and a Minecraft GPU pass.
These APIs use supplied inputs and independently owned resources.

## Noise and CPU images

The `com.qb20nh.cbbg.api` package works on Java 8 and newer without Minecraft.
Use it from the CBBG mod or the standalone utilities JAR.

```java
NoiseVolume noise = NoiseVolume.generate(128, 128, 64, 42);
DitherOptions options = new DitherOptions(2, 1, 1, false);
byte[] rgba = Dithering.rgba8(sourceRgbaFloats, width, height, noise, frame, options);
```

Generation runs on your thread. Use your executor for large volumes; interrupting
that thread cancels generation. Dimensions must be positive powers of two with
at least two pixels in total. Memory and runtime grow with the volume size.

Noise volumes are immutable. `pixelABGR(x, y, frame)` wraps all three coordinates.
`frameRGBA(frame)` returns an independent RGBA8 byte array suitable for texture
uploads. CPU dithering preserves row order, uses pixel centers, dithers RGB and
quantizes alpha without noise. Input components must be finite.

`DitherOptions` supplies strength, X/Y noise-coordinate scales and demo mode.
Demo mode leaves the left half undithered and marks the dividing column.
For reduced-resolution rendering, use the actual rendered width and height
divided by the output dimensions as the corresponding scales.

## Minecraft GPU pass

The mod exports `com.qb20nh.cbbg.render.DitherPass`. Compile against the CBBG JAR
for your Minecraft version; Minecraft's texture-view types differ across versions.

```java
DitherPass pass = new DitherPass();
TextureTarget output = pass.render(inputView, noiseView, options);
// Consume output before the next resize or close.
pass.close();
```

Create, render and close on Minecraft's render thread. Upload a noise frame as
RGBA8 and keep both input and noise textures alive during rendering. The pass
samples noise with nearest filtering and repeat addressing, and returns an RGBA8
target with the input dimensions. It owns that output and its uniform storage;
you own the input and noise textures. Close releases its resources. The instance
can be reused afterward. Reuse one instance across frames to avoid reallocations.

This pass is independent of CBBG being enabled or suspended by a shader mod.
Your integration remains responsible for scheduling its pass within that renderer.

## Standalone library

Download `cbbg-utilities-<version>.jar` and `cbbg-utilities-<version>-sources.jar`
from the [GitHub release](https://github.com/qb20nh/cbbg/releases), or from the
additional files on the corresponding Modrinth or CurseForge release. Add the
binary to your Java classpath; attach the sources JAR in your IDE for source and
mapping lookup. These downloads expose the noise and CPU image APIs on Java 8
and newer. Use the CBBG mod for your Minecraft version for the GPU pass.

To build the standalone library locally:

```sh
./gradlew -p core utilitiesSourcesJar
```

This builds `cbbg-utilities-<version>.jar` and its sources JAR in
`core/build/libs`. The binary contains the Java 8 CPU API,
its math implementation and the reusable GLSL include at
`com/qb20nh/cbbg/api/shaders/dither.glsl`. ProGuard keeps public API names and
optimizes the implementation; the sources JAR includes the mapping.

The standalone library has no mod entrypoint or Minecraft dependency. GPU
integration uses the version-specific pass embedded in the CBBG mod.
