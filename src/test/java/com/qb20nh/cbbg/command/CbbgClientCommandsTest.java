package com.qb20nh.cbbg.command;

import com.mojang.brigadier.CommandDispatcher;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CbbgClientCommandsTest {

    @Test
    void commandTree_canBeRegisteredWithoutExecutingHandlers() throws Exception {
        CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();

        Method registerCommands = CbbgClientCommands.class.getDeclaredMethod("registerCommands",
                CommandDispatcher.class);
        registerCommands.setAccessible(true);
        registerCommands.invoke(null, dispatcher);

        Assertions.assertNotNull(dispatcher.getRoot().getChild("cbbg"),
                "Expected /cbbg root command to be registered");
    }
}
