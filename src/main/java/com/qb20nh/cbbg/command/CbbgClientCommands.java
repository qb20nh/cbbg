package com.qb20nh.cbbg.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.qb20nh.cbbg.config.CbbgConfig;
import com.qb20nh.cbbg.render.CbbgDither;
import java.util.Objects;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NonNull;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class CbbgClientCommands {

    private static final String ARG_VALUE = "value";
    private static final String ARG_ENABLED = "enabled";
    private static final String ARG_MODE = "mode";
    private static final String ARG_FORMAT = "format";

    private static final @NonNull StringArgumentType STRING_ARG =
            Objects.requireNonNull(StringArgumentType.string());
    private static final @NonNull IntegerArgumentType STBN_SIZE_ARG =
            Objects.requireNonNull(IntegerArgumentType.integer(16, 256));
    private static final @NonNull IntegerArgumentType STBN_DEPTH_ARG =
            Objects.requireNonNull(IntegerArgumentType.integer(8, 128));
    private static final @NonNull LongArgumentType STBN_SEED_ARG =
            Objects.requireNonNull(LongArgumentType.longArg());
    private static final @NonNull BoolArgumentType BOOL_ARG =
            Objects.requireNonNull(BoolArgumentType.bool());

    private CbbgClientCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT
                .register((dispatcher, registryAccess) -> registerCommands(dispatcher));
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("cbbg").executes(ctx -> {
            sendRootHelp(ctx.getSource());
            return 1;
        }).then(literal("help").executes(ctx -> {
            sendRootHelp(ctx.getSource());
            return 1;
        })).then(modeCommand()).then(formatCommand()).then(stbnCommand())
                .then(notificationCommand()));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> modeCommand() {
        return literal(ARG_MODE).executes(ctx -> {
            var mode = CbbgConfig.get().mode();
            ctx.getSource().sendFeedback(Component.literal("Current mode: " + mode));
            return 1;
        }).then(literal("set").executes(ctx -> {
            sendModeUsage(ctx.getSource());
            return 1;
        }).then(argument(ARG_MODE, STRING_ARG).suggests((ctx, builder) -> {
            for (CbbgConfig.Mode mode : CbbgConfig.Mode.values()) {
                builder.suggest(mode.getSerializedName());
            }
            return builder.buildFuture();
        }).executes(ctx -> {
            String modeName = StringArgumentType.getString(ctx, ARG_MODE);
            try {
                CbbgConfig.Mode mode = null;
                for (CbbgConfig.Mode m : CbbgConfig.Mode.values()) {
                    if (m.getSerializedName().equalsIgnoreCase(modeName)) {
                        mode = m;
                        break;
                    }
                }
                if (mode == null) {
                    mode = CbbgConfig.Mode.valueOf(modeName.toUpperCase());
                }

                CbbgConfig.setMode(mode);
                CbbgDither.resetAfterToggle();
                ctx.getSource().sendFeedback(Component.literal("Set mode to: " + mode));
                return 1;
            } catch (Exception e) {
                ctx.getSource().sendError(Component.literal("Invalid mode: " + modeName));
                sendModeUsage(ctx.getSource());
                return 0;
            }
        })));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> formatCommand() {
        return literal(ARG_FORMAT).executes(ctx -> {
            var format = CbbgConfig.get().pixelFormat();
            ctx.getSource().sendFeedback(Component.literal("Current pixel format: " + format));
            return 1;
        }).then(literal("set").then(argument(ARG_FORMAT, STRING_ARG).suggests((ctx, builder) -> {
            builder.suggest(CbbgConfig.PixelFormat.RGBA16F.getSerializedName());
            builder.suggest(CbbgConfig.PixelFormat.RGBA32F.getSerializedName());
            return builder.buildFuture();
        }).executes(ctx -> {
            String fmtName = StringArgumentType.getString(ctx, ARG_FORMAT);
            try {
                CbbgConfig.PixelFormat fmt = null;
                for (CbbgConfig.PixelFormat f : CbbgConfig.PixelFormat.values()) {
                    if (f.getSerializedName().equalsIgnoreCase(fmtName)) {
                        fmt = f;
                        break;
                    }
                }
                if (fmt == null) {
                    fmt = CbbgConfig.PixelFormat.valueOf(fmtName.toUpperCase());
                }
                if (fmt == CbbgConfig.PixelFormat.RGBA8) {
                    ctx.getSource().sendError(Component.literal(
                            "rgba8 is no longer selectable; use rgba16f or rgba32f (RGBA8 is used only as an automatic fallback)."));
                    return 0;
                }

                CbbgConfig.setPixelFormat(fmt);
                CbbgDither.resetAfterToggle(); // Re-init texture with new format
                ctx.getSource().sendFeedback(Component.literal("Set format to: " + fmt));
                return 1;
            } catch (Exception e) {
                ctx.getSource().sendError(Component.literal("Invalid format: " + fmtName));
                sendFormatUsage(ctx.getSource());
                return 0;
            }
        })));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> stbnCommand() {
        return literal("stbn").executes(ctx -> {
            sendStbnUsage(ctx.getSource());
            return 1;
        }).then(literal("generate").executes(ctx -> {
            ctx.getSource().sendFeedback(Component.literal("Starting STBN generation..."));
            CbbgDither.reloadStbn(true);
            return 1;
        })).then(literal("size").executes(ctx -> {
            sendStbnSizeUsage(ctx.getSource());
            return 1;
        }).then(argument(ARG_VALUE, STBN_SIZE_ARG).executes(ctx -> {
            int size = IntegerArgumentType.getInteger(ctx, ARG_VALUE);
            if (Integer.bitCount(size) != 1) { // Check power of two
                ctx.getSource()
                        .sendError(Component.literal("Size must be power of two (16, 32, 64...)"));
                sendStbnSizeUsage(ctx.getSource());
                return 0;
            }
            CbbgConfig.setStbnSize(size);
            ctx.getSource().sendFeedback(Component.literal("Set STBN size to: " + size));
            return 1;
        }))).then(literal("depth").executes(ctx -> {
            sendStbnDepthUsage(ctx.getSource());
            return 1;
        }).then(argument(ARG_VALUE, STBN_DEPTH_ARG).executes(ctx -> {
            int depth = IntegerArgumentType.getInteger(ctx, ARG_VALUE);
            if (Integer.bitCount(depth) != 1) {
                ctx.getSource()
                        .sendError(Component.literal("Depth must be power of two (8, 16, 32...)"));
                sendStbnDepthUsage(ctx.getSource());
                return 0;
            }
            CbbgConfig.setStbnDepth(depth);
            ctx.getSource().sendFeedback(Component.literal("Set STBN depth to: " + depth));
            return 1;
        }))).then(literal("seed").executes(ctx -> {
            sendStbnSeedUsage(ctx.getSource());
            return 1;
        }).then(argument(ARG_VALUE, STBN_SEED_ARG).executes(ctx -> {
            long seed = LongArgumentType.getLong(ctx, ARG_VALUE);
            CbbgConfig.setStbnSeed(seed);
            ctx.getSource().sendFeedback(Component.literal("Set STBN seed to: " + seed));
            return 1;
        }))).then(literal("reset").executes(ctx -> {
            CbbgConfig.setStbnSize(128);
            CbbgConfig.setStbnDepth(64);
            CbbgConfig.setStbnSeed(0);
            CbbgDither.reloadStbn(false);
            ctx.getSource().sendFeedback(Component.literal("Reset STBN parameters to default"));
            return 1;
        }));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> notificationCommand() {
        return literal("notification").executes(ctx -> {
            sendNotificationUsage(ctx.getSource());
            return 1;
        }).then(literal("chat").executes(ctx -> {
            ctx.getSource().sendFeedback(
                    Component.literal("Chat notifications: " + CbbgConfig.get().notifyChat()));
            ctx.getSource().sendFeedback(
                    Component.literal("Usage: /cbbg notification chat <true | false>"));
            return 1;
        }).then(argument(ARG_ENABLED, BOOL_ARG).executes(ctx -> {
            boolean val = BoolArgumentType.getBool(ctx, ARG_ENABLED);
            CbbgConfig.setNotifyChat(val);
            ctx.getSource().sendFeedback(Component.literal("Chat notifications: " + val));
            return 1;
        }))).then(literal("toast").executes(ctx -> {
            ctx.getSource().sendFeedback(
                    Component.literal("Toast notifications: " + CbbgConfig.get().notifyToast()));
            ctx.getSource().sendFeedback(
                    Component.literal("Usage: /cbbg notification toast <true | false>"));
            return 1;
        }).then(argument(ARG_ENABLED, BOOL_ARG).executes(ctx -> {
            boolean val = BoolArgumentType.getBool(ctx, ARG_ENABLED);
            CbbgConfig.setNotifyToast(val);
            ctx.getSource().sendFeedback(Component.literal("Toast notifications: " + val));
            return 1;
        })));
    }

    private static void sendRootHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("cbbg commands:"));
        source.sendFeedback(Component.literal(" - /cbbg mode"));
        source.sendFeedback(Component.literal(" - /cbbg mode set <enabled | disabled | demo>"));
        source.sendFeedback(Component.literal(" - /cbbg format"));
        source.sendFeedback(Component.literal(" - /cbbg format set <rgba16f | rgba32f>"));
        source.sendFeedback(Component.literal(" - /cbbg stbn"));
        source.sendFeedback(Component.literal(" - /cbbg notification"));
    }

    private static void sendModeUsage(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("Usage: /cbbg mode set <enabled | disabled | demo>"));
    }

    private static void sendFormatUsage(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("Usage: /cbbg format set <rgba16f | rgba32f>"));
    }

    private static void sendStbnUsage(FabricClientCommandSource source) {
        CbbgConfig cfg = CbbgConfig.get();
        source.sendFeedback(Component.literal("STBN: size=" + cfg.stbnSize() + " depth="
                + cfg.stbnDepth() + " seed=" + cfg.stbnSeed()));
        source.sendFeedback(Component.literal(" - /cbbg stbn generate"));
        source.sendFeedback(Component.literal(" - /cbbg stbn size <16-256 power-of-two>"));
        source.sendFeedback(Component.literal(" - /cbbg stbn depth <8-128 power-of-two>"));
        source.sendFeedback(Component.literal(" - /cbbg stbn seed <number>"));
        source.sendFeedback(Component.literal(" - /cbbg stbn reset"));
    }

    private static void sendStbnSizeUsage(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("Current STBN size: " + CbbgConfig.get().stbnSize()));
        source.sendFeedback(Component.literal("Usage: /cbbg stbn size <16-256 power-of-two>"));
    }

    private static void sendStbnDepthUsage(FabricClientCommandSource source) {
        source.sendFeedback(
                Component.literal("Current STBN depth: " + CbbgConfig.get().stbnDepth()));
        source.sendFeedback(Component.literal("Usage: /cbbg stbn depth <8-128 power-of-two>"));
    }

    private static void sendStbnSeedUsage(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("Current STBN seed: " + CbbgConfig.get().stbnSeed()));
        source.sendFeedback(Component.literal("Usage: /cbbg stbn seed <number>"));
    }

    private static void sendNotificationUsage(FabricClientCommandSource source) {
        CbbgConfig cfg = CbbgConfig.get();
        source.sendFeedback(Component.literal(
                "Notifications: chat=" + cfg.notifyChat() + " toast=" + cfg.notifyToast()));
        source.sendFeedback(Component.literal(" - /cbbg notification chat <true | false>"));
        source.sendFeedback(Component.literal(" - /cbbg notification toast <true | false>"));
    }
}
