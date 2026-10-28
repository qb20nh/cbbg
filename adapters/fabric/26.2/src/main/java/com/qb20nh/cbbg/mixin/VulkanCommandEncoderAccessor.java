package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.checkpoints.CheckpointExtension;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@NullMarked
@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {
  @Accessor("checkpointStorage")
  CheckpointExtension.CheckpointStorage cbbg$checkpoints();
}
