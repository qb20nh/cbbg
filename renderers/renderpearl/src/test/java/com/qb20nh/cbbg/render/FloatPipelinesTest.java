package com.qb20nh.cbbg.render;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

@NullMarked
class FloatPipelinesTest {
  @Test
  void tracksCompiledObjectsWithoutHashingTheirFields() {
    RenderPipeline source =
        RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("cbbg", "test/pipeline_cache"))
            .withVertexShader("core/screenquad")
            .withFragmentShader("core/blit_screen")
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();
    try (var compiled = new UnhashablePipeline()) {
      assertDoesNotThrow(() -> FloatPipelines.remember(source, compiled));
      assertDoesNotThrow(() -> FloatPipelines.remember(source, compiled));
    }
  }

  private static final class UnhashablePipeline implements CompiledRenderPipeline {
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
