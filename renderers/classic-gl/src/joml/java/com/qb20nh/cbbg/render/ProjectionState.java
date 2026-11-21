package com.qb20nh.cbbg.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;

final class ProjectionState {
  private ProjectionState() {}

  static Runnable capture() {
    Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
    VertexSorting sorting = RenderSystem.getVertexSorting();
    return () -> RenderSystem.setProjectionMatrix(projection, sorting);
  }
}
