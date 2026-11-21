package com.qb20nh.cbbg.mixin;

import com.qb20nh.cbbg.platform.LoaderPlatform;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class ClassicBackendPlugin implements IMixinConfigPlugin {
  private static final String VULKAN_MIXINS = "com.qb20nh.cbbg.mixin.vulkanmod.";

  public static boolean usesVulkanMod() {
    return ClassicBackendPlugin.class.getResource("vulkanmod/RendererMixin.class") != null
        && LoaderPlatform.isModLoaded("vulkanmod");
  }

  @Override
  public boolean shouldApplyMixin(String target, String mixin) {
    return usesVulkanMod() == mixin.startsWith(VULKAN_MIXINS);
  }

  @Override
  public List<String> getMixins() {
    return usesVulkanMod()
        ? List.of(
            "vulkanmod.FloatImageMixin",
            "vulkanmod.DefaultMainPassAccess",
            "vulkanmod.DescriptorAllocationMixin",
            "vulkanmod.FramebufferAllocationMixin",
            "vulkanmod.ImageAllocationMixin",
            "vulkanmod.MemoryManagerMixin",
            "vulkanmod.PipelineAllocationMixin",
            "vulkanmod.RenderPassAllocationMixin",
            "vulkanmod.RendererMixin",
            "vulkanmod.SamplerAllocationMixin",
            "vulkanmod.ScreenshotMixin",
            "vulkanmod.ShaderResultMixin",
            "vulkanmod.VertexDescriptionAllocationMixin")
        : null;
  }

  @Override
  public void onLoad(String mixinPackage) {}

  @Override
  public String getRefMapperConfig() {
    return null;
  }

  @Override
  public void acceptTargets(Set<String> own, Set<String> other) {}

  @Override
  public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}

  @Override
  public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
