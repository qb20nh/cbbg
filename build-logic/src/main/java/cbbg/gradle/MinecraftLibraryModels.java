package cbbg.gradle;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.uber.nullaway.LibraryModels;
import org.jspecify.annotations.NullMarked;

/** Nullness contracts confirmed against Minecraft implementations. */
@NullMarked
public final class MinecraftLibraryModels implements LibraryModels {
  @Override
  public ImmutableSetMultimap<MethodRef, Integer> explicitlyNullableParameters() {
    // Clearing the current cache restores RenderSystem's fallback cache.
    return ImmutableSetMultimap.of(
        MethodRef.methodRef(
            "com.mojang.blaze3d.systems.RenderSystem",
            "setCurrentPipelineCache(com.mojang.blaze3d.pipeline.PipelineCache)"),
        0);
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> failIfNullParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> nonNullParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> nullImpliesTrueParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> nullImpliesFalseParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> nullImpliesNullParameters() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSet<MethodRef> nullableReturns() {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<MethodRef> nonNullReturns() {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSetMultimap<MethodRef, Integer> castToNonNullMethods() {
    return ImmutableSetMultimap.of();
  }
}
