package com.qb20nh.cbbg.mixin;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@org.jspecify.annotations.NullMarked
@Mixin(CommandEncoder.class)
public interface CommandEncoderAccessor {
  @Accessor("backend")
  CommandEncoderBackend cbbg$backend();
}
