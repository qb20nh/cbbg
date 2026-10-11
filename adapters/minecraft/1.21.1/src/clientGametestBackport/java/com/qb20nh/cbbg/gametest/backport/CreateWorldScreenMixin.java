/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.qb20nh.cbbg.gametest.backport;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.Lifecycle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.impl.client.gametest.ClientGameTestImpl;
import net.fabricmc.fabric.impl.client.gametest.DedicatedServerImplUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Backports Fabric client-gametest 2.0's CreateWorldScreenMixin to Minecraft 1.21.1. That version
 * constructs the level data inside the confirmed createNewWorld callback.
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {
  @WrapOperation(
      method = "onCreate()V",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/screens/worldselection/WorldOpenFlows;confirmWorldCreation(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/worldselection/CreateWorldScreen;Lcom/mojang/serialization/Lifecycle;Ljava/lang/Runnable;Z)V"))
  @SuppressWarnings("UnusedMethod") // MixinExtras invokes this wrapper.
  private void cbbg$confirmDedicatedWorld(
      Minecraft client,
      CreateWorldScreen screen,
      Lifecycle lifecycle,
      Runnable createWorld,
      boolean skipWarning,
      Operation<Void> original) {
    if (DedicatedServerImplUtil.saveLevelDataTo != null) {
      createWorld.run();
    } else {
      original.call(client, screen, lifecycle, createWorld, skipWarning);
    }
  }

  @WrapOperation(
      method =
          "createNewWorld(Lnet/minecraft/world/level/storage/PrimaryLevelData$SpecialWorldProperty;Lnet/minecraft/core/LayeredRegistryAccess;Lcom/mojang/serialization/Lifecycle;)V",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/screens/worldselection/WorldOpenFlows;createLevelFromExistingSettings(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;Lnet/minecraft/server/ReloadableServerResources;Lnet/minecraft/core/LayeredRegistryAccess;Lnet/minecraft/world/level/storage/WorldData;)V"))
  @SuppressWarnings("UnusedMethod") // MixinExtras invokes this wrapper.
  private void cbbg$createDedicatedLevelData(
      WorldOpenFlows flows,
      LevelStorageSource.LevelStorageAccess access,
      ReloadableServerResources resources,
      LayeredRegistryAccess<RegistryLayer> registries,
      WorldData data,
      Operation<Void> original) {
    Path destination = DedicatedServerImplUtil.saveLevelDataTo;
    if (destination == null) {
      original.call(flows, access, resources, registries, data);
      return;
    }

    try {
      CompoundTag levelDat = new CompoundTag();
      levelDat.put("Data", data.createTag(registries.compositeAccess(), null));
      Files.createDirectories(destination);
      NbtIo.writeCompressed(levelDat, destination.resolve("level.dat"));
    } catch (IOException failure) {
      ClientGameTestImpl.LOGGER.error("Failed to save dedicated server level data", failure);
    } finally {
      // The earlier upstream hook never opened this client save's session lock.
      try {
        access.close();
      } catch (IOException failure) {
        ClientGameTestImpl.LOGGER.error(
            "Failed to close dedicated server level-data access", failure);
      }
    }
  }
}
