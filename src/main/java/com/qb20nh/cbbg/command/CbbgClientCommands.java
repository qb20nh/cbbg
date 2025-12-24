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
            ctx.getSource()
                    .sendFeedback(Component.translatable("cbbg.command.mode.current", modeName(mode)));
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
                ctx.getSource()
                        .sendFeedback(Component.translatable("cbbg.command.mode.set", modeName(mode)));
                return 1;
            } catch (Exception e) {
                ctx.getSource().sendError(
                        Component.translatable("cbbg.command.mode.invalid", modeName));
                sendModeUsage(ctx.getSource());
                return 0;
            }
        })));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> formatCommand() {
        return literal(ARG_FORMAT).executes(ctx -> {
            var format = CbbgConfig.get().pixelFormat();
            ctx.getSource().sendFeedback(Component.translatable("cbbg.command.format.current",
                    pixelFormatName(format)));
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
                    ctx.getSource().sendError(
                            Component.translatable("cbbg.command.format.rgba8_not_selectable"));
                    return 0;
                }

                CbbgConfig.setPixelFormat(fmt);
                CbbgDither.resetAfterToggle(); // Re-init texture with new format
                ctx.getSource().sendFeedback(
                        Component.translatable("cbbg.command.format.set", pixelFormatName(fmt)));
                return 1;
            } catch (Exception e) {
                ctx.getSource().sendError(
                        Component.translatable("cbbg.command.format.invalid", fmtName));
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
            ctx.getSource().sendFeedback(Component.translatable("cbbg.command.stbn.generating"));
            CbbgDither.reloadStbn(true);
            return 1;
        })).then(literal("size").executes(ctx -> {
            sendStbnSizeUsage(ctx.getSource());
            return 1;
        }).then(argument(ARG_VALUE, STBN_SIZE_ARG).executes(ctx -> {
            int size = IntegerArgumentType.getInteger(ctx, ARG_VALUE);
            if (Integer.bitCount(size) != 1) { // Check power of two
                ctx.getSource()
                        .sendError(Component.translatable("cbbg.command.stbn.size.invalid_pow2"));
                sendStbnSizeUsage(ctx.getSource());
                return 0;
            }
            CbbgConfig.setStbnSize(size);
            ctx.getSource()
                    .sendFeedback(Component.translatable("cbbg.command.stbn.size.set", size));
            return 1;
        }))).then(literal("depth").executes(ctx -> {
            sendStbnDepthUsage(ctx.getSource());
            return 1;
        }).then(argument(ARG_VALUE, STBN_DEPTH_ARG).executes(ctx -> {
            int depth = IntegerArgumentType.getInteger(ctx, ARG_VALUE);
            if (Integer.bitCount(depth) != 1) {
                ctx.getSource()
                        .sendError(Component.translatable("cbbg.command.stbn.depth.invalid_pow2"));
                sendStbnDepthUsage(ctx.getSource());
                return 0;
            }
            CbbgConfig.setStbnDepth(depth);
            ctx.getSource()
                    .sendFeedback(Component.translatable("cbbg.command.stbn.depth.set", depth));
            return 1;
        }))).then(literal("seed").executes(ctx -> {
            sendStbnSeedUsage(ctx.getSource());
            return 1;
        }).then(argument(ARG_VALUE, STBN_SEED_ARG).executes(ctx -> {
            long seed = LongArgumentType.getLong(ctx, ARG_VALUE);
            CbbgConfig.setStbnSeed(seed);
            ctx.getSource()
                    .sendFeedback(Component.translatable("cbbg.command.stbn.seed.set", seed));
            return 1;
        }))).then(literal("reset").executes(ctx -> {
            CbbgConfig.setStbnSize(128);
            CbbgConfig.setStbnDepth(64);
            CbbgConfig.setStbnSeed(0);
            CbbgDither.reloadStbn(false);
            ctx.getSource().sendFeedback(Component.translatable("cbbg.command.stbn.reset"));
            return 1;
        }));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> notificationCommand() {
        return literal("notification").executes(ctx -> {
            sendNotificationUsage(ctx.getSource());
            return 1;
        }).then(literal("chat").executes(ctx -> {
            ctx.getSource().sendFeedback(
                    Component.translatable("cbbg.command.notification.chat.current",
                            CbbgConfig.get().notifyChat()));
            ctx.getSource().sendFeedback(
                    Component.translatable("cbbg.command.notification.chat.usage"));
            return 1;
        }).then(argument(ARG_ENABLED, BOOL_ARG).executes(ctx -> {
            boolean val = BoolArgumentType.getBool(ctx, ARG_ENABLED);
            CbbgConfig.setNotifyChat(val);
            ctx.getSource().sendFeedback(
                    Component.translatable("cbbg.command.notification.chat.current", val));
            return 1;
        }))).then(literal("toast").executes(ctx -> {
            ctx.getSource().sendFeedback(
                    Component.translatable("cbbg.command.notification.toast.current",
                            CbbgConfig.get().notifyToast()));
            ctx.getSource().sendFeedback(
                    Component.translatable("cbbg.command.notification.toast.usage"));
            return 1;
        }).then(argument(ARG_ENABLED, BOOL_ARG).executes(ctx -> {
            boolean val = BoolArgumentType.getBool(ctx, ARG_ENABLED);
            CbbgConfig.setNotifyToast(val);
            ctx.getSource().sendFeedback(
                    Component.translatable("cbbg.command.notification.toast.current", val));
            return 1;
        })));
    }

    private static void sendRootHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("cbbg.command.root.help.header"));
        source.sendFeedback(Component.translatable("cbbg.command.root.help.mode"));
        source.sendFeedback(Component.translatable("cbbg.command.root.help.mode_set"));
        source.sendFeedback(Component.translatable("cbbg.command.root.help.format"));
        source.sendFeedback(Component.translatable("cbbg.command.root.help.format_set"));
        source.sendFeedback(Component.translatable("cbbg.command.root.help.stbn"));
        source.sendFeedback(Component.translatable("cbbg.command.root.help.notification"));
    }

    private static void sendModeUsage(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("cbbg.command.mode.usage"));
    }

    private static void sendFormatUsage(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("cbbg.command.format.usage"));
    }

    private static void sendStbnUsage(FabricClientCommandSource source) {
        CbbgConfig cfg = CbbgConfig.get();
        source.sendFeedback(Component.translatable("cbbg.command.stbn.usage.header", cfg.stbnSize(),
                cfg.stbnDepth(), cfg.stbnSeed()));
        source.sendFeedback(Component.translatable("cbbg.command.stbn.usage.generate"));
        source.sendFeedback(Component.translatable("cbbg.command.stbn.usage.size"));
        source.sendFeedback(Component.translatable("cbbg.command.stbn.usage.depth"));
        source.sendFeedback(Component.translatable("cbbg.command.stbn.usage.seed"));
        source.sendFeedback(Component.translatable("cbbg.command.stbn.usage.reset"));
    }

    private static void sendStbnSizeUsage(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("cbbg.command.stbn.size.current",
                CbbgConfig.get().stbnSize()));
        source.sendFeedback(Component.translatable("cbbg.command.stbn.size.usage"));
    }

    private static void sendStbnDepthUsage(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("cbbg.command.stbn.depth.current",
                CbbgConfig.get().stbnDepth()));
        source.sendFeedback(Component.translatable("cbbg.command.stbn.depth.usage"));
    }

    private static void sendStbnSeedUsage(FabricClientCommandSource source) {
        source.sendFeedback(Component.translatable("cbbg.command.stbn.seed.current",
                CbbgConfig.get().stbnSeed()));
        source.sendFeedback(Component.translatable("cbbg.command.stbn.seed.usage"));
    }

    private static void sendNotificationUsage(FabricClientCommandSource source) {
        CbbgConfig cfg = CbbgConfig.get();
        source.sendFeedback(Component.translatable("cbbg.command.notification.usage.header",
                cfg.notifyChat(), cfg.notifyToast()));
        source.sendFeedback(Component.translatable("cbbg.command.notification.usage.chat"));
        source.sendFeedback(Component.translatable("cbbg.command.notification.usage.toast"));
    }

    private static Component modeName(CbbgConfig.Mode mode) {
        return switch (mode) {
            case ENABLED -> Component.translatable("cbbg.mode.enabled");
            case DISABLED -> Component.translatable("cbbg.mode.disabled");
            case DEMO -> Component.translatable("cbbg.mode.demo");
        };
    }

    private static Component pixelFormatName(CbbgConfig.PixelFormat format) {
        return Component.translatable("cbbg.pixel_format." + format.getSerializedName());
    }
}
