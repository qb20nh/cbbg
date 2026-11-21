package com.qb20nh.cbbg.gametest;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Observes Minecraft's actual CBBG draw calls without depending on mod internals. */
@NullMarked
public final class ProcessedRenderObservations {
  private static final Set<CompiledRenderPipeline> PIPELINES =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
  private static final Set<CompiledRenderPipeline> WINDOW_PIPELINES =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
  private static final Set<BackendRenderPipeline> WINDOW_BACKENDS =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
  private static final Map<BackendRenderPipeline, Object2IntMap<String>> WINDOW_UNIFORMS =
      Collections.synchronizedMap(new WeakHashMap<>());
  private static final AtomicLong WINDOW_DRAWS = new AtomicLong();
  private static final AtomicLong DRAWS = new AtomicLong();
  private static final AtomicLong PRESENTATIONS = new AtomicLong();
  private static final AtomicLong FIRST_DRAW = new AtomicLong();
  private static @Nullable CompiledRenderPipeline selectedPipeline;
  private static @Nullable GpuTextureView lastDitherOutput;
  private static @Nullable GpuTextureView lastDitherNoise;
  private static long ditherSelections;
  private static @Nullable Consumer<@Nullable GpuTextureView> noiseObserver;
  private static long observedPresentations;

  private ProcessedRenderObservations() {}

  public static void remember(RenderPipeline source, @Nullable CompiledRenderPipeline compiled) {
    String name = source.getLocation().toString();
    if (compiled != null
        && (name.equals("cbbg:pipeline/dither")
            || name.equals("cbbg:pipeline/demo")
            || name.equals("cbbg:pipeline/window_dither")
            || name.equals("cbbg:pipeline/window_demo"))) {
      PIPELINES.add(compiled);
      if (name.equals("cbbg:pipeline/window_dither") || name.equals("cbbg:pipeline/window_demo")) {
        WINDOW_PIPELINES.add(compiled);
        if (compiled instanceof FrontendRenderPipeline frontend) {
          WINDOW_BACKENDS.add(frontend.backendRenderPipeline());
          WINDOW_UNIFORMS.put(frontend.backendRenderPipeline(), frontend.uniformIndices());
        }
      }
    }
  }

  public static boolean isDither(CompiledRenderPipeline pipeline) {
    return PIPELINES.contains(pipeline);
  }

  /** Read and written on the render thread, immediately around setPipeline. */
  public static @Nullable CompiledRenderPipeline selectedPipeline() {
    // skipcq: JAVA-W1060
    return selectedPipeline;
  }

  public static void select(CompiledRenderPipeline pipeline) {
    selectedPipeline = pipeline;
  }

  /** The real color attachment selected for the most recent dither or demo draw. */
  public static void ditherOutput(GpuTextureView output) {
    lastDitherOutput = output;
    ditherSelections++;
  }

  public static @Nullable GpuTextureView lastDitherOutput() {
    return lastDitherOutput;
  }

  /** The texture view successfully bound as NoiseSampler by the most recent dither pass. */
  public static void ditherNoise(GpuTextureView noise) {
    lastDitherNoise = noise;
  }

  public static @Nullable GpuTextureView lastDitherNoise() {
    return lastDitherNoise;
  }

  public static long ditherSelections() {
    return ditherSelections;
  }

  /** Installed and cleared on the render thread; observes completed presentation frames. */
  public static void setNoiseObserver(@Nullable Consumer<@Nullable GpuTextureView> observer) {
    noiseObserver = observer;
    observedPresentations = PRESENTATIONS.get();
  }

  public static void afterFrame() {
    long presentations = PRESENTATIONS.get();
    if (noiseObserver != null && presentations != observedPresentations) {
      observedPresentations = presentations;
      noiseObserver.accept(lastDitherNoise);
    }
  }

  // Count the exact offscreen output, or a dither draw performed inside window presentation.
  @SuppressWarnings("ReferenceEquality")
  public static void recordPresentation(@Nullable GpuTextureView texture, long drawsBefore) {
    if (DRAWS.get() > drawsBefore
        || (texture != null && texture == lastDitherOutput && !texture.texture().isClosed())) {
      PRESENTATIONS.incrementAndGet();
    }
  }

  public static long presentations() {
    return PRESENTATIONS.get();
  }

  public static long windowDraws() {
    return WINDOW_DRAWS.get();
  }

  public static int windowUniformIndex(BackendRenderPipeline pipeline, String name) {
    var indices = WINDOW_UNIFORMS.get(pipeline);
    return indices == null || !indices.containsKey(name) ? -1 : indices.getInt(name);
  }

  public static boolean isWindowBackend(BackendRenderPipeline pipeline) {
    return WINDOW_BACKENDS.contains(pipeline);
  }

  public static void recordWindowDraw() {
    recordDraw(true);
  }

  public static void recordDraw() {
    recordDraw(WINDOW_PIPELINES.contains(selectedPipeline));
  }

  private static void recordDraw(boolean window) {
    if (window) WINDOW_DRAWS.incrementAndGet();
    DRAWS.incrementAndGet();
    FIRST_DRAW.compareAndSet(0, ManagementFactory.getRuntimeMXBean().getUptime());
  }

  public static long draws() {
    return DRAWS.get();
  }

  public static long firstDrawMillis() {
    return FIRST_DRAW.get();
  }
}
