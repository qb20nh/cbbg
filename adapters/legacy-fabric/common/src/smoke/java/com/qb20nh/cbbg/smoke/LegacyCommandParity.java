package com.qb20nh.cbbg.smoke;

import com.qb20nh.cbbg.LegacyCommands;
import com.qb20nh.cbbg.LegacyToasts;
import com.qb20nh.cbbg.config.CbbgConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

final class LegacyCommandParity {
    private final List<Text> messages = new ArrayList<>();
    private int generated;
    private int reloaded;
    private int checks;

    static int run() {
        LegacyCommandParity test = new LegacyCommandParity();
        CbbgConfig saved = CbbgConfig.get();
        try {
            test.verify();
            return test.checks;
        } finally {
            CbbgConfig.setMode(saved.mode());
            CbbgConfig.setPixelFormat(saved.pixelFormat());
            CbbgConfig.setStbnSize(saved.stbnSize());
            CbbgConfig.setStbnDepth(saved.stbnDepth());
            CbbgConfig.setStbnSeed(saved.stbnSeed());
            CbbgConfig.setNotifyChat(saved.notifyChat());
            CbbgConfig.setNotifyToast(saved.notifyToast());
        }
    }

    private void verify() {
        require(LegacyCommands.suggest("/help") == null && LegacyCommands.suggest("/cbbgother") == null,
                "Completion claimed unrelated commands");
        require(LegacyCommands.suggest("/cbbg mode s").equals(java.util.Arrays.asList("set")), "Missing set completion");
        require(LegacyCommands.suggest("/cbbg format set ").size() == 2
                && !LegacyCommands.suggest("/cbbg format set ").contains("rgba8"), "Invalid precision completion");
        require(LegacyCommands.suggest("/cbbg notification ").contains("toast") == LegacyToasts.supported(),
                "Completion ignored toast capability");
        for (String invalid : new String[] {"/cbbg unknown ", "/cbbg mode bad ", "/cbbg help extra ",
                "/cbbg notification chat true extra", "/cbbg stbn seed "}) {
            require(LegacyCommands.suggest(invalid).isEmpty(), "Unexpected completion: " + invalid);
        }
        require(CommandCompletionParity.complete("/cbbg mo", 1).equals("/cbbg mode"), "Chat completion did not insert locally");
        require(CommandCompletionParity.complete("/cbbg notification chat t", 1).equals("/cbbg notification chat true"),
                "Boolean completion failed");
        require(CommandCompletionParity.complete("/cbbg notification chat ", 2).equals("/cbbg notification chat false"),
                "Completion did not cycle");
        require(CommandCompletionParity.complete("/cbbg notification chat ", 3).equals("/cbbg notification chat true"),
                "Completion did not wrap");
        require(CommandCompletionParity.complete("/cbbg unknown ", 1).equals("/cbbg unknown "),
                "Empty completion changed input");
        require(!execute("/cbbgother") && !execute("hello") && !execute("/help"), "Unrelated chat was claimed");
        for (String command : new String[] {"", "help", "mode", "format", "stbn", "stbn size", "stbn depth",
                "stbn seed", "notification", "notification chat"}) {
            require(execute("/cbbg " + command) && !messages.isEmpty(), "Missing help: " + command);
        }
        execute("/cbbg mode set DEMO");
        require(CbbgConfig.get().mode() == CbbgConfig.Mode.DEMO, "Mode did not change");
        require(execute("/cbbg format set RGBA16F")
                && CbbgConfig.get().pixelFormat() == CbbgConfig.PixelFormat.RGBA16F,
                "RGBA16F format command failed");
        for (int size : new int[] {16, 32, 64, 128, 256}) {
            execute("/cbbg stbn size " + size);
            require(CbbgConfig.get().stbnSize() == size, "Size did not change");
        }
        for (int depth : new int[] {8, 16, 32, 64, 128}) {
            execute("/cbbg stbn depth " + depth);
            require(CbbgConfig.get().stbnDepth() == depth, "Depth did not change");
        }
        for (long seed : new long[] {Long.MIN_VALUE, 0, Long.MAX_VALUE}) {
            execute("/cbbg stbn seed " + seed);
            require(CbbgConfig.get().stbnSeed() == seed, "Seed did not change");
        }
        for (String command : new String[] {"mode set unknown", "mode set enabled extra", "format set rgba8",
                "format set unknown", "stbn size 15", "stbn size 257", "stbn size 17", "stbn depth 7",
                "stbn depth 129", "stbn depth 9", "stbn seed 9223372036854775808", "stbn seed bad",
                "stbn size 32 extra", "notification chat yes", "notification chat false extra"}) {
            CbbgConfig before = CbbgConfig.get();
            execute("/cbbg " + command);
            require(before.equals(CbbgConfig.get()), "Invalid command mutated config: " + command);
            require(!messages.isEmpty() && messages.get(0).getStyle().getColor() == Formatting.RED,
                    "Invalid command did not report an error: " + command);
        }
        execute("/cbbg notification chat false");
        require(!CbbgConfig.get().notifyChat(), "Chat setting did not change");
        boolean toast = CbbgConfig.get().notifyToast();
        execute("/cbbg notification toast " + !toast);
        require(CbbgConfig.get().notifyToast() == (LegacyToasts.supported() ? !toast : toast),
                "Toast capability was not respected");
        require(generated == 0 && reloaded == 0, "Editing settings applied noise before confirmation");
        execute("/cbbg stbn generate");
        require(generated == 1 && reloaded == 0, "Generate dispatched the wrong action");
        execute("/cbbg stbn reset");
        require(CbbgConfig.get().stbnSize() == 128 && CbbgConfig.get().stbnDepth() == 64
                && CbbgConfig.get().stbnSeed() == 0 && reloaded == 1 && generated == 1,
                "Reset did not restore defaults and request cache reload");
        // There is no player/connection in this fixture: forwarding to the server would fail.
        CommandRoutingParity.send("/cbbg mode set disabled");
        require(CbbgConfig.get().mode() == CbbgConfig.Mode.DISABLED, "Chat entry point did not execute locally");
    }

    private boolean execute(String command) {
        messages.clear();
        return LegacyCommands.execute(command, messages::add, () -> generated++, () -> reloaded++);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
