package com.qb20nh.cbbg.gametest.mixin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.fabricmc.fabric.impl.client.gametest.FabricClientGameTestRunner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records completion only after Fabric's per-test cleanup assertions succeed. */
@Mixin(value = FabricClientGameTestRunner.class, remap = false)
public abstract class ScenarioProgressMixin {
    @Inject(method = "setupInitialGameTestState", at = @At("HEAD"))
    private static void cbbg$started(CallbackInfo ci) {
        cbbg$record("started");
    }

    @Inject(method = "setupAndCheckFinalGameTestState", at = @At("RETURN"))
    private static void cbbg$passed(CallbackInfo ci) {
        cbbg$record("passed");
    }

    private static void cbbg$record(String state) {
        String directory = System.getProperty("cbbg.test.evidence");
        if (directory == null) {
            throw new IllegalStateException("Missing scenario evidence directory");
        }
        String name = FabricClientGameTestRunner.currentlyRunningGameTest
                .getEntrypoint().getClass().getName();
        Path output = Path.of(directory, "scenarios.tsv");
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, state + "\t" + name + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot record scenario " + name, error);
        }
    }
}
