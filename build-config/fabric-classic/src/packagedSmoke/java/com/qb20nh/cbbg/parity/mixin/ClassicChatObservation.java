package com.qb20nh.cbbg.parity.mixin;

import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChatComponent.class)
public interface ClassicChatObservation {
  @Accessor("allMessages")
  List<GuiMessage> cbbg$messages();
}
