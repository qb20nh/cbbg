package com.qb20nh.cbbg.command;

import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

public final class CommandPlatform {
  private CommandPlatform() {}

  public static void register(Runnable resetAfterToggle, Consumer<Boolean> reloadStbn) {
    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, registryAccess) ->
            CbbgClientCommands.registerCommands(
                dispatcher,
                resetAfterToggle,
                reloadStbn,
                FabricClientCommandSource::sendFeedback,
                FabricClientCommandSource::sendError));
  }
}
