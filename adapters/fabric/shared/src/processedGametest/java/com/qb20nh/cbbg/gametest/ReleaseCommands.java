package com.qb20nh.cbbg.gametest;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class ReleaseCommands {
  private ReleaseCommands() {}

  @SuppressWarnings("unchecked")
  static @Nullable CommandDispatcher<FabricClientCommandSource> getActiveDispatcher() {
    try {
      Class<?> commands;
      try {
        commands = Class.forName("net.fabricmc.fabric.api.client.command.v2.ClientCommands");
      } catch (ClassNotFoundException olderApi) {
        commands = Class.forName("net.fabricmc.fabric.api.client.command.v2.ClientCommandManager");
      }
      return (CommandDispatcher<FabricClientCommandSource>)
          commands.getMethod("getActiveDispatcher").invoke(null);
    } catch (ReflectiveOperationException failure) {
      throw new LinkageError("Fabric client command dispatcher is unavailable", failure);
    }
  }
}
