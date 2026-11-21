package com.qb20nh.cbbg.render;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.qb20nh.cbbg.render.FloatPipelines.Source;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

@NullMarked
class FloatPipelinesTest {
  @Test
  void tracksCompiledObjectsWithoutHashingTheirFields() {
    RenderPipeline source = pipeline("pipeline_cache");
    try (var compiled = new UnhashablePipeline()) {
      assertDoesNotThrow(() -> FloatPipelines.remember(source, compiled));
      assertDoesNotThrow(() -> FloatPipelines.remember(source, compiled));
    }
  }

  @Test
  void keepsFrontendMetadataWhenACompiledPipelineIsRequestedAgain() {
    RenderPipeline first = pipeline("first_source");
    RenderPipeline later = pipeline("later_source");
    try (var compiled = new MetadataPipeline()) {
      FloatPipelines.remember(first, compiled);
      var metadata = Objects.requireNonNull(compiled.cbbg$pipelineSource());
      var format = Map.of(0, GpuFormat.RGBA16_FLOAT);
      metadata.variants().put(format, first);

      FloatPipelines.remember(later, compiled);

      assertSame(metadata, compiled.cbbg$pipelineSource());
      assertSame(first, metadata.pipeline());
      assertSame(first, metadata.variants().get(format));
    }
  }

  private static RenderPipeline pipeline(String name) {
    return RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("cbbg", "test/" + name))
        .withVertexShader("core/screenquad")
        .withFragmentShader("core/blit_screen")
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .build();
  }

  private static final class MetadataPipeline extends UnhashablePipeline
      implements FloatPipelines.SourceHolder {
    private @Nullable Source source;

    @Override
    public @Nullable Source cbbg$pipelineSource() {
      return source;
    }

    @Override
    public void cbbg$pipelineSource(Source value) {
      source = value;
    }
  }

  private static class UnhashablePipeline implements CompiledRenderPipeline {
    private boolean closed;

    @Override
    public boolean isClosed() {
      return closed;
    }

    @Override
    public void close() {
      closed = true;
    }

    @Override
    public int hashCode() {
      throw new AssertionError("Compiled GPU objects must not be hashed by their fields");
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public boolean equals(@Nullable Object other) {
      return this == other;
    }
  }
}
