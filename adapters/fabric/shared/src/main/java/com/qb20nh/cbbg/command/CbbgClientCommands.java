package com.qb20nh.cbbg.command;

import com.qb20nh.cbbg.platform.Text;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.qb20nh.cbbg.config.CbbgConfig;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import net.minecraft.network.chat.Component;

public final class CbbgClientCommands<S> {

    private static final String ARG_VALUE = "value";
    private static final String ARG_ENABLED = "enabled";
    private static final String ARG_MODE = "mode";
    private static final String ARG_FORMAT = "format";

    private static final StringArgumentType STRING_ARG =
            Objects.requireNonNull(StringArgumentType.string());
    private static final IntegerArgumentType STBN_SIZE_ARG =
            Objects.requireNonNull(IntegerArgumentType.integer(16, 256));
    private static final IntegerArgumentType STBN_DEPTH_ARG =
            Objects.requireNonNull(IntegerArgumentType.integer(8, 128));
    private static final LongArgumentType STBN_SEED_ARG =
            Objects.requireNonNull(LongArgumentType.longArg());
    private static final BoolArgumentType BOOL_ARG =
            Objects.requireNonNull(BoolArgumentType.bool());

    private final BiConsumer<S, Component> feedback;
    private final BiConsumer<S, Component> error;

    private CbbgClientCommands(BiConsumer<S, Component> feedback, BiConsumer<S, Component> error) {
        this.feedback = feedback;
        this.error = error;
    }

    private void sendFeedback(S source, Component message) { feedback.accept(source, message); }
    private void sendError(S source, Component message) { error.accept(source, message); }

    private LiteralArgumentBuilder<S> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private <T> RequiredArgumentBuilder<S, T> argument(
            String name, ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    public static void register(Runnable resetAfterToggle, Consumer<Boolean> reloadStbn) {
        CommandPlatform.register(resetAfterToggle, reloadStbn);
    }

    public static <S> void registerCommands(CommandDispatcher<S> dispatcher,
            Runnable resetAfterToggle, Consumer<Boolean> reloadStbn,
            BiConsumer<S, Component> feedback, BiConsumer<S, Component> error) {
        new CbbgClientCommands<S>(feedback, error).registerTree(dispatcher, resetAfterToggle, reloadStbn);
    }

    private void registerTree(CommandDispatcher<S> dispatcher,
            Runnable resetAfterToggle, Consumer<Boolean> reloadStbn) {
        dispatcher.register(literal("cbbg").executes(ctx -> {
            sendRootHelp(ctx.getSource());
            return 1;
        }).then(literal("help").executes(ctx -> {
            sendRootHelp(ctx.getSource());
            return 1;
        })).then(modeCommand(resetAfterToggle)).then(formatCommand(resetAfterToggle))
                .then(stbnCommand(reloadStbn))
                .then(notificationCommand()));
    }

    private LiteralArgumentBuilder<S> modeCommand(Runnable resetAfterToggle) {
        return literal(ARG_MODE).executes(ctx -> {
            var mode = CbbgConfig.get().mode();
            sendFeedback(ctx.getSource(), Text.translatable("cbbg.command.mode.current", modeName(mode)));
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
                resetAfterToggle.run();
                sendFeedback(ctx.getSource(), Text.translatable("cbbg.command.mode.set", modeName(mode)));
                return 1;
            } catch (Exception e) {
                sendError(ctx.getSource(),
                        Text.translatable("cbbg.command.mode.invalid", modeName));
                sendModeUsage(ctx.getSource());
                return 0;
            }
        })));
    }

    private LiteralArgumentBuilder<S> formatCommand(Runnable resetAfterToggle) {
        return literal(ARG_FORMAT).executes(ctx -> {
            var format = CbbgConfig.get().pixelFormat();
            sendFeedback(ctx.getSource(), Text.translatable("cbbg.command.format.current",
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
                    sendError(ctx.getSource(),
                            Text.translatable("cbbg.command.format.rgba8_not_selectable"));
                    return 0;
                }

                CbbgConfig.setPixelFormat(fmt);
                resetAfterToggle.run(); // Re-init texture with new format
                sendFeedback(ctx.getSource(),
                        Text.translatable("cbbg.command.format.set", pixelFormatName(fmt)));
                return 1;
            } catch (Exception e) {
                sendError(ctx.getSource(),
                        Text.translatable("cbbg.command.format.invalid", fmtName));
                sendFormatUsage(ctx.getSource());
                return 0;
            }
        })));
    }

    private LiteralArgumentBuilder<S> stbnCommand(Consumer<Boolean> reloadStbn) {
        return literal("stbn").executes(ctx -> {
            sendStbnUsage(ctx.getSource());
            return 1;
        }).then(literal("generate").executes(ctx -> {
            sendFeedback(ctx.getSource(), Text.translatable("cbbg.command.stbn.generating"));
            reloadStbn.accept(true);
            return 1;
        })).then(literal("size").executes(ctx -> {
            sendStbnSizeUsage(ctx.getSource());
            return 1;
        }).then(argument(ARG_VALUE, STBN_SIZE_ARG).executes(ctx -> {
            int size = IntegerArgumentType.getInteger(ctx, ARG_VALUE);
            if (Integer.bitCount(size) != 1) { // Check power of two
                sendError(ctx.getSource(), Text.translatable("cbbg.command.stbn.size.invalid_pow2"));
                sendStbnSizeUsage(ctx.getSource());
                return 0;
            }
            CbbgConfig.setStbnSize(size);
            sendFeedback(ctx.getSource(), Text.translatable("cbbg.command.stbn.size.set", size));
            return 1;
        }))).then(literal("depth").executes(ctx -> {
            sendStbnDepthUsage(ctx.getSource());
            return 1;
        }).then(argument(ARG_VALUE, STBN_DEPTH_ARG).executes(ctx -> {
            int depth = IntegerArgumentType.getInteger(ctx, ARG_VALUE);
            if (Integer.bitCount(depth) != 1) {
                sendError(ctx.getSource(), Text.translatable("cbbg.command.stbn.depth.invalid_pow2"));
                sendStbnDepthUsage(ctx.getSource());
                return 0;
            }
            CbbgConfig.setStbnDepth(depth);
            sendFeedback(ctx.getSource(), Text.translatable("cbbg.command.stbn.depth.set", depth));
            return 1;
        }))).then(literal("seed").executes(ctx -> {
            sendStbnSeedUsage(ctx.getSource());
            return 1;
        }).then(argument(ARG_VALUE, STBN_SEED_ARG).executes(ctx -> {
            long seed = LongArgumentType.getLong(ctx, ARG_VALUE);
            CbbgConfig.setStbnSeed(seed);
            sendFeedback(ctx.getSource(), Text.translatable("cbbg.command.stbn.seed.set", seed));
            return 1;
        }))).then(literal("reset").executes(ctx -> {
            CbbgConfig.setStbnSize(128);
            CbbgConfig.setStbnDepth(64);
            CbbgConfig.setStbnSeed(0);
            reloadStbn.accept(false);
            sendFeedback(ctx.getSource(), Text.translatable("cbbg.command.stbn.reset"));
            return 1;
        }));
    }

    private LiteralArgumentBuilder<S> notificationCommand() {
        return literal("notification").executes(ctx -> {
            sendNotificationUsage(ctx.getSource());
            return 1;
        }).then(literal("chat").executes(ctx -> {
            sendFeedback(ctx.getSource(),
                    Text.translatable("cbbg.command.notification.chat.current",
                            CbbgConfig.get().notifyChat()));
            sendFeedback(ctx.getSource(),
                    Text.translatable("cbbg.command.notification.chat.usage"));
            return 1;
        }).then(argument(ARG_ENABLED, BOOL_ARG).executes(ctx -> {
            boolean val = BoolArgumentType.getBool(ctx, ARG_ENABLED);
            CbbgConfig.setNotifyChat(val);
            sendFeedback(ctx.getSource(),
                    Text.translatable("cbbg.command.notification.chat.current", val));
            return 1;
        }))).then(literal("toast").executes(ctx -> {
            sendFeedback(ctx.getSource(),
                    Text.translatable("cbbg.command.notification.toast.current",
                            CbbgConfig.get().notifyToast()));
            sendFeedback(ctx.getSource(),
                    Text.translatable("cbbg.command.notification.toast.usage"));
            return 1;
        }).then(argument(ARG_ENABLED, BOOL_ARG).executes(ctx -> {
            boolean val = BoolArgumentType.getBool(ctx, ARG_ENABLED);
            CbbgConfig.setNotifyToast(val);
            sendFeedback(ctx.getSource(),
                    Text.translatable("cbbg.command.notification.toast.current", val));
            return 1;
        })));
    }

    private void sendRootHelp(S source) {
        sendFeedback(source, Text.translatable("cbbg.command.root.help.header"));
        sendFeedback(source, Text.translatable("cbbg.command.root.help.mode"));
        sendFeedback(source, Text.translatable("cbbg.command.root.help.mode_set"));
        sendFeedback(source, Text.translatable("cbbg.command.root.help.format"));
        sendFeedback(source, Text.translatable("cbbg.command.root.help.format_set"));
        sendFeedback(source, Text.translatable("cbbg.command.root.help.stbn"));
        sendFeedback(source, Text.translatable("cbbg.command.root.help.notification"));
    }

    private void sendModeUsage(S source) {
        sendFeedback(source, Text.translatable("cbbg.command.mode.usage"));
    }

    private void sendFormatUsage(S source) {
        sendFeedback(source, Text.translatable("cbbg.command.format.usage"));
    }

    private void sendStbnUsage(S source) {
        CbbgConfig cfg = CbbgConfig.get();
        sendFeedback(source, Text.translatable("cbbg.command.stbn.usage.header", cfg.stbnSize(),
                cfg.stbnDepth(), cfg.stbnSeed()));
        sendFeedback(source, Text.translatable("cbbg.command.stbn.usage.generate"));
        sendFeedback(source, Text.translatable("cbbg.command.stbn.usage.size"));
        sendFeedback(source, Text.translatable("cbbg.command.stbn.usage.depth"));
        sendFeedback(source, Text.translatable("cbbg.command.stbn.usage.seed"));
        sendFeedback(source, Text.translatable("cbbg.command.stbn.usage.reset"));
    }

    private void sendStbnSizeUsage(S source) {
        sendFeedback(source, Text.translatable("cbbg.command.stbn.size.current",
                CbbgConfig.get().stbnSize()));
        sendFeedback(source, Text.translatable("cbbg.command.stbn.size.usage"));
    }

    private void sendStbnDepthUsage(S source) {
        sendFeedback(source, Text.translatable("cbbg.command.stbn.depth.current",
                CbbgConfig.get().stbnDepth()));
        sendFeedback(source, Text.translatable("cbbg.command.stbn.depth.usage"));
    }

    private void sendStbnSeedUsage(S source) {
        sendFeedback(source, Text.translatable("cbbg.command.stbn.seed.current",
                CbbgConfig.get().stbnSeed()));
        sendFeedback(source, Text.translatable("cbbg.command.stbn.seed.usage"));
    }

    private void sendNotificationUsage(S source) {
        CbbgConfig cfg = CbbgConfig.get();
        sendFeedback(source, Text.translatable("cbbg.command.notification.usage.header",
                cfg.notifyChat(), cfg.notifyToast()));
        sendFeedback(source, Text.translatable("cbbg.command.notification.usage.chat"));
        sendFeedback(source, Text.translatable("cbbg.command.notification.usage.toast"));
    }

    private static Component modeName(CbbgConfig.Mode mode) {
        return switch (mode) {
            case ENABLED -> Text.translatable("cbbg.mode.enabled");
            case DISABLED -> Text.translatable("cbbg.mode.disabled");
            case DEMO -> Text.translatable("cbbg.mode.demo");
        };
    }

    private static Component pixelFormatName(CbbgConfig.PixelFormat format) {
        return Text.translatable("cbbg.pixel_format." + format.getSerializedName());
    }
}
