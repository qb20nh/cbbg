package com.qb20nh.cbbg.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.qb20nh.cbbg.CbbgClient;
import com.qb20nh.cbbg.config.CbbgConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;

public final class CbbgClientCommands {

    private CbbgClientCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher
                .register(literal("cbbg").then(literal("mode").executes(context -> {
                    CbbgConfig.Mode currentMode = CbbgClient.getUserMode();
                    context.getSource()
                            .sendFeedback(Component.literal("Current cbbg mode: " + currentMode));
                    return Command.SINGLE_SUCCESS;
                }).then(literal("set").then(argument("mode", StringArgumentType.string())
                        .suggests((context, builder) -> {
                            for (CbbgConfig.Mode mode : CbbgConfig.Mode.values()) {
                                builder.suggest(mode.name());
                            }
                            return builder.buildFuture();
                        }).executes(context -> {
                            String modeName = StringArgumentType.getString(context, "mode");
                            try {
                                CbbgConfig.Mode mode =
                                        CbbgConfig.Mode.valueOf(modeName.toUpperCase());
                                CbbgConfig.setMode(mode);
                                context.getSource().sendFeedback(
                                        Component.literal("Set cbbg mode to: " + mode));
                                return Command.SINGLE_SUCCESS;
                            } catch (IllegalArgumentException e) {
                                context.getSource()
                                        .sendError(Component.literal("Invalid mode: " + modeName));
                                return 0;
                            }
                        }))))));
    }
}
