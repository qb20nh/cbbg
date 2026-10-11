package com.qb20nh.cbbg.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.impl.client.gametest.FabricClientGameTestRunner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fabric 1.21.1 records completion after its per-test cleanup assertions succeed. */
@Mixin(value = FabricClientGameTestRunner.class, remap = false)
public abstract class ScenarioProgressMixin {
  @WrapOperation(
      method = "lambda$start$0",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/fabricmc/fabric/api/client/gametest/v1/FabricClientGameTest;runTest(Lnet/fabricmc/fabric/api/client/gametest/v1/ClientGameTestContext;)V"))
  @SuppressWarnings("UnusedMethod") // MixinExtras invokes this wrapper through the injection.
  private static void cbbg$started(
      FabricClientGameTest test, ClientGameTestContext context, Operation<Void> original) {
    cbbg$record("started", test.getClass().getName());
    original.call(test, context);
  }

  @Inject(method = "checkFinalGameTestState", at = @At("RETURN"))
  private static void cbbg$passed(ClientGameTestContext context, String name, CallbackInfo info) {
    cbbg$record("passed", name);
  }

  private static void cbbg$record(String state, String name) {
    Path output =
        Path.of(Objects.requireNonNull(System.getProperty("cbbg.test.evidence")), "scenarios.tsv");
    try {
      Files.createDirectories(Objects.requireNonNull(output.getParent()));
      Files.writeString(
          output, state + "\t" + name + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot record scenario " + name, failure);
    }
  }
}
